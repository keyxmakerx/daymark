package com.daymark.companion

import com.daymark.companion.auth.OwnerAuth
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The owner's log gets at most one lockout row a minute, server-wide (#186): sources without number,
 * each arming a lockout of its own, cannot turn the owner's log into a disk-filler. On a real engine
 * that trusts 127.0.0.1 as its proxy, so each request can come from an address of its own.
 */
class LockoutRowCapTest {

    @Test
    fun `fifty addresses arming their lockouts within a minute write one row, and a minute later one more`() {
        val server = DeviceServer(lockoutFails = 1, trustedProxies = "127.0.0.1/32")
        server.startNetty().use { live ->
            fun from(address: String, token: String) =
                live.send("GET", "/v1/snapshots", mapOf("Authorization" to "Bearer $token", "X-Forwarded-For" to address))
            val locked = RawResponse(429, """{"error":"temporarily locked"}""")
            val start = server.now

            val addresses = (1..50).map { "198.51.100.$it" }
            for (address in addresses) assertEquals(401, from(address, "not-the-token").status, address)
            // Control: each of them armed a lockout of its own, so one row is the cap and not one arming.
            for (address in addresses) assertEquals(locked, from(address, server.authToken), address)
            assertEquals(listOf("lockout"), server.ownerLog(), "fifty lockouts armed within the minute: one row")

            // A millisecond short of a minute after that row, another address's lockout writes none...
            server.now = start + OwnerAuth.LOCKOUT_ROW_GAP_MS - 1
            assertEquals(401, from("203.0.113.1", "not-the-token").status)
            assertEquals(locked, from("203.0.113.1", server.authToken), "control: it armed")
            assertEquals(listOf("lockout"), server.ownerLog())

            // ...and a minute after it, the next one writes one more.
            server.now = start + OwnerAuth.LOCKOUT_ROW_GAP_MS
            assertEquals(401, from("203.0.113.2", "not-the-token").status)
            assertEquals(locked, from("203.0.113.2", server.authToken), "control: it armed")
            assertEquals(listOf("lockout", "lockout"), server.ownerLog())
        }
    }
}
