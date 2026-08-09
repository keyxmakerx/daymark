package com.daymark.companion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The trust rule for resolving a client address behind a reverse proxy.
 *
 * Two failure modes are being guarded against, and they pull in opposite directions:
 *
 * - **Trust nothing** and every request behind a proxy looks like the proxy, so all clients share
 *   one lockout bucket and any single attacker can lock out everybody.
 * - **Trust `X-Forwarded-For` blindly** and any client can invent an address per request, evading
 *   lockouts entirely — a denial of service traded for an unlimited-attempts bypass.
 *
 * So the tests that matter most here are the spoofing ones.
 */
class ClientAddressTest {

    private val proxy = ClientAddress.parseTrusted("10.89.0.2/32")

    @Test
    fun `with no trusted proxies configured the peer is always used`() {
        // The default, and the no-regression guarantee: behaviour identical to before this existed.
        val none = ClientAddress.parseTrusted(null)
        assertEquals("10.89.0.2", ClientAddress.resolve("10.89.0.2", listOf("203.0.113.9"), none))
        assertEquals("198.51.100.7", ClientAddress.resolve("198.51.100.7", listOf("1.2.3.4"), none))
        assertEquals("10.89.0.2", ClientAddress.resolve("10.89.0.2", emptyList(), ClientAddress.parseTrusted("")))
    }

    @Test
    fun `a direct connection cannot describe itself`() {
        // Attacker bypasses the proxy, hits the app directly, and sends a forged header hoping to
        // land in someone else's lockout bucket — or a fresh one per request.
        assertEquals(
            "198.51.100.7",
            ClientAddress.resolve("198.51.100.7", listOf("203.0.113.9"), proxy),
        )
    }

    @Test
    fun `a forged prefix is ignored because the chain is read right-to-left`() {
        // The client sent "X-Forwarded-For: 6.6.6.6"; the trusted proxy then APPENDED the address it
        // actually observed. Rightmost is therefore the truth and the attacker's prefix is noise.
        // Reading left-to-right here would return 6.6.6.6 and hand the attacker a free bucket.
        assertEquals(
            "203.0.113.9",
            ClientAddress.resolve("10.89.0.2", listOf("6.6.6.6, 203.0.113.9"), proxy),
        )
    }

    @Test
    fun `the real client is found through a chain of trusted hops`() {
        val chain = ClientAddress.parseTrusted("10.89.0.2/32, 10.89.0.3/32")
        assertEquals(
            "203.0.113.9",
            ClientAddress.resolve("10.89.0.2", listOf("203.0.113.9, 10.89.0.3"), chain),
        )
    }

    @Test
    fun `a chain of nothing but trusted proxies falls back to the peer`() {
        val chain = ClientAddress.parseTrusted("10.89.0.2/32, 10.89.0.3/32")
        assertEquals("10.89.0.2", ClientAddress.resolve("10.89.0.2", listOf("10.89.0.3"), chain))
    }

    @Test
    fun `a malformed chain falls back to the peer rather than guessing`() {
        assertEquals("10.89.0.2", ClientAddress.resolve("10.89.0.2", listOf("not-an-ip"), proxy))
        assertEquals("10.89.0.2", ClientAddress.resolve("10.89.0.2", listOf("1.2.3.4, ../../etc"), proxy))
    }

    @Test
    fun `an empty or absent header falls back to the peer`() {
        assertEquals("10.89.0.2", ClientAddress.resolve("10.89.0.2", emptyList(), proxy))
        assertEquals("10.89.0.2", ClientAddress.resolve("10.89.0.2", listOf("", "  "), proxy))
    }

    @Test
    fun `header values split across repeated headers are read in order`() {
        assertEquals(
            "203.0.113.9",
            ClientAddress.resolve("10.89.0.2", listOf("6.6.6.6", "203.0.113.9"), proxy),
        )
    }

    @Test
    fun `CIDR blocks match the whole range and nothing outside it`() {
        val block = ClientAddress.parseTrusted("10.89.0.0/24")
        assertTrue(ClientAddress.isTrusted("10.89.0.1", block))
        assertTrue(ClientAddress.isTrusted("10.89.0.255", block))
        assertFalse(ClientAddress.isTrusted("10.89.1.1", block))
        assertFalse(ClientAddress.isTrusted("10.88.0.1", block))
    }

    @Test
    fun `a bare address is treated as an exact match, not a range`() {
        val exact = ClientAddress.parseTrusted("10.89.0.2")
        assertTrue(ClientAddress.isTrusted("10.89.0.2", exact))
        assertFalse(ClientAddress.isTrusted("10.89.0.3", exact))
    }

    @Test
    fun `ports and IPv6 brackets are stripped before matching`() {
        assertEquals(
            "203.0.113.9",
            ClientAddress.resolve("10.89.0.2", listOf("203.0.113.9:44321"), proxy),
        )
        val v6 = ClientAddress.parseTrusted("2001:db8::1")
        assertTrue(ClientAddress.isTrusted("[2001:db8::1]", v6))
    }

    @Test
    fun `an unparseable allowlist entry is dropped without taking the others down`() {
        val mixed = ClientAddress.parseTrusted("nonsense, 10.89.0.2/32, 10.89.0.9/999")
        assertTrue(ClientAddress.isTrusted("10.89.0.2", mixed))
        // The typo'd entries are simply absent — dropping one can only make trust narrower.
        assertFalse(ClientAddress.isTrusted("10.89.0.9", mixed))
    }

    @Test
    fun `a hostname in the chain is never resolved`() {
        // Guards against an attacker-supplied name triggering a DNS lookup from the server.
        assertEquals(
            "10.89.0.2",
            ClientAddress.resolve("10.89.0.2", listOf("evil.example.com"), proxy),
        )
    }

    @Test
    fun `a hostname made only of hex-looking characters is still never resolved`() {
        // This is the case the test above MISSED. The old implementation filtered by charset and
        // then called InetAddress.getByName; the filter allowed a-f so IPv6 literals would pass,
        // which also admitted names like "dead.cafe" — and getByName resolved them over real DNS.
        // "evil.example.com" never exercised that path because it failed the filter first, so the
        // test passed for the wrong reason.
        for (name in listOf("dead.cafe", "cafe", "abc", "deadbeef", "face.b00c")) {
            assertEquals(
                "10.89.0.2",
                ClientAddress.resolve("10.89.0.2", listOf(name), proxy),
                "'$name' must not be treated as an address (and must not be resolved)",
            )
        }
    }

    @Test
    fun `malformed IPv4 octets are rejected rather than coerced`() {
        for (bad in listOf("1.2.3", "1.2.3.4.5", "256.1.1.1", "1.2.3.999", "1.2.3.", ".1.2.3")) {
            assertEquals(
                "10.89.0.2",
                ClientAddress.resolve("10.89.0.2", listOf(bad), proxy),
                "'$bad' is not a valid address",
            )
        }
    }

    @Test
    fun `leading-zero octets are rejected, not read as octal`() {
        // "010.1.1.1" is 8.1.1.1 to an octal-aware parser and 10.1.1.1 to a decimal one.
        // Disagreements of exactly this kind are how allowlist bypasses happen, so reject outright.
        assertEquals("10.89.0.2", ClientAddress.resolve("10.89.0.2", listOf("010.1.1.1"), proxy))
        assertEquals("10.89.0.2", ClientAddress.resolve("10.89.0.2", listOf("01.2.3.4"), proxy))
    }

    @Test
    fun `well-formed IPv4 still parses`() {
        assertEquals("203.0.113.9", ClientAddress.resolve("10.89.0.2", listOf("203.0.113.9"), proxy))
        assertEquals("0.0.0.0", ClientAddress.resolve("10.89.0.2", listOf("0.0.0.0"), proxy))
        assertEquals("255.255.255.255", ClientAddress.resolve("10.89.0.2", listOf("255.255.255.255"), proxy))
    }

    @Test
    fun `IPv4 and IPv6 do not match across families`() {
        val v4 = ClientAddress.parseTrusted("10.89.0.2/32")
        assertFalse(ClientAddress.isTrusted("2001:db8::1", v4))
    }
}
