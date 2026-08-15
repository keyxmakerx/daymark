package com.daymark.companion

import com.daymark.companion.routes.parseShareExpiry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import java.util.Base64

/**
 * Server-side share expiry — the header parser.
 *
 * Until this existed, the owner's client SENT the deadline in `X-Share-Meta` and the server dropped
 * it: expiry and `read.share` revocation were enforced only by an `{#if}` and a `Date.now()`
 * comparison running inside the browser of the party being restricted.
 *
 * Failing closed here is what keeps the grandfather rule in `RelationStore.gateLocked` bounded. A
 * `shares` row with no expiry can only be one an older build wrote — never one written today —
 * because a publish with no usable deadline is now refused outright.
 */
class ShareExpiryTest {

    private val now = 1_000_000L
    private val maxAhead = 366L * 24 * 60 * 60 * 1000

    private fun header(json: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())

    @Test
    fun `accepts what the shipped owner client actually sends`() {
        // Non-vacuity for every rejection below: if this parser said no to everything, publishing
        // would be entirely broken and the rejection tests would still pass.
        val expiry = now + 30L * 24 * 60 * 60 * 1000
        val real = header("""{"shareId":"s-1","version":3,"expiry":$expiry,"ownerSigningFp":"ab12"}""")
        assertEquals(expiry, parseShareExpiry(real, now, maxAhead))
    }

    @Test
    fun `refuses everything that is not a usable future deadline`() {
        assertNull(parseShareExpiry(null, now, maxAhead), "absent")
        assertNull(parseShareExpiry("   ", now, maxAhead), "blank")
        assertNull(parseShareExpiry("!!! not base64 !!!", now, maxAhead), "undecodable")
        assertNull(parseShareExpiry(header("not json at all"), now, maxAhead), "not json")
        assertNull(parseShareExpiry(header("""{"shareId":"s"}"""), now, maxAhead), "no expiry field")
        assertNull(parseShareExpiry(header("""{"expiry":null}"""), now, maxAhead), "null expiry")
        assertNull(parseShareExpiry(header("""{"expiry":"12345"}"""), now, maxAhead), "expiry as a string")
        assertNull(parseShareExpiry(header("""{"expiry":0}"""), now, maxAhead), "zero")
        assertNull(parseShareExpiry(header("""{"expiry":-5}"""), now, maxAhead), "negative")
    }

    @Test
    fun `a deadline already in the past is refused rather than stored`() {
        // Storing it would be silent: the owner sees "published" and the therapist sees 410
        // forever, with nothing anywhere saying why.
        assertNull(parseShareExpiry(header("""{"expiry":${now - 1}}"""), now, maxAhead), "past")
        assertNull(parseShareExpiry(header("""{"expiry":$now}"""), now, maxAhead), "exactly now")
        assertEquals(now + 1, parseShareExpiry(header("""{"expiry":${now + 1}}"""), now, maxAhead), "one ms ahead is fine")
    }

    @Test
    fun `an absurd future deadline is clamped, not honoured`() {
        // Otherwise "effectively never expires" is reachable from a modified client by writing a
        // big number, which would quietly re-open the hole this closes.
        assertEquals(
            now + maxAhead,
            parseShareExpiry(header("""{"expiry":9000000000000000000}"""), now, maxAhead),
        )
    }

    @Test
    fun `an oversized header is refused without being decoded`() {
        assertNull(parseShareExpiry("A".repeat(5000), now, maxAhead))
    }
}
