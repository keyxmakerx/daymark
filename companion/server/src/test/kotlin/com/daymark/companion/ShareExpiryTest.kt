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
 * Every call here uses the parser's production ceiling rather than restating one, so these tests
 * exercise the number the server actually clamps at.
 */
class ShareExpiryTest {

    private val now = 1_000_000L
    private val day = 24L * 60 * 60 * 1000

    private fun header(json: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())

    @Test
    fun `accepts what the shipped owner client actually sends`() {
        // Non-vacuity for every rejection below: if this parser said no to everything, publishing
        // would be entirely broken and the rejection tests would still pass.
        val expiry = now + 30 * day
        val real = header("""{"shareId":"s-1","version":3,"expiry":$expiry,"ownerSigningFp":"ab12"}""")
        assertEquals(expiry, parseShareExpiry(real, now))
    }

    @Test
    fun `refuses everything that is not a usable future deadline`() {
        assertNull(parseShareExpiry(null, now), "absent")
        assertNull(parseShareExpiry("   ", now), "blank")
        assertNull(parseShareExpiry("!!! not base64 !!!", now), "undecodable")
        assertNull(parseShareExpiry(header("not json at all"), now), "not json")
        assertNull(parseShareExpiry(header("""{"shareId":"s"}"""), now), "no expiry field")
        assertNull(parseShareExpiry(header("""{"expiry":null}"""), now), "null expiry")
        assertNull(parseShareExpiry(header("""{"expiry":"12345"}"""), now), "expiry as a string")
        assertNull(parseShareExpiry(header("""{"expiry":0}"""), now), "zero")
        assertNull(parseShareExpiry(header("""{"expiry":-5}"""), now), "negative")
    }

    @Test
    fun `a deadline already in the past is refused rather than stored`() {
        // Storing it would be silent: the owner sees "published" and the therapist sees 410
        // forever, with nothing anywhere saying why.
        assertNull(parseShareExpiry(header("""{"expiry":${now - 1}}"""), now), "past")
        assertNull(parseShareExpiry(header("""{"expiry":$now}"""), now), "exactly now")
        assertEquals(now + 1, parseShareExpiry(header("""{"expiry":${now + 1}}"""), now), "one ms ahead is fine")
    }

    @Test
    fun `an end more than 90 days out is clamped to 90 days after publishing (#332)`() {
        /*
         * The number is pinned here, not read from the constant: the owner console offers no end
         * later than 90 days (#339) and its tests pin the same number, so a change to either side
         * must fail a test on both.
         */
        assertEquals(now + 90 * day, parseShareExpiry(header("""{"expiry":${now + 200 * day}}"""), now), "200 days")
        assertEquals(now + 90 * day, parseShareExpiry(header("""{"expiry":${now + 91 * day}}"""), now), "91 days")
        // Control: an end inside the ceiling is kept exactly as the owner chose it, so the clamp
        // above is a ceiling and not a rewrite of every end.
        assertEquals(now + 89 * day, parseShareExpiry(header("""{"expiry":${now + 89 * day}}"""), now), "89 days")
        assertEquals(now + 90 * day, parseShareExpiry(header("""{"expiry":${now + 90 * day}}"""), now), "exactly 90")
    }

    @Test
    fun `an absurd future deadline is clamped, not honoured`() {
        // Otherwise "effectively never expires" is reachable from a modified client by writing a
        // big number, which would quietly re-open the hole this closes.
        assertEquals(
            now + 90 * day,
            parseShareExpiry(header("""{"expiry":9000000000000000000}"""), now),
        )
    }

    @Test
    fun `an oversized header is refused without being decoded`() {
        assertNull(parseShareExpiry("A".repeat(5000), now))
    }
}
