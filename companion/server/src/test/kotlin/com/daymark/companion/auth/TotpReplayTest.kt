package com.daymark.companion.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * A TOTP code must be accepted **at most once** (RFC 6238 §5.2).
 *
 * Without that, a code stays valid for the whole ±90 second window, so anyone who observes one —
 * over a shoulder, in a screenshot, through a phished prompt — can replay it. And because each
 * acceptance mints an independent 8-hour session, the attacker's session then survives the victim
 * noticing and logging out. The delta is not access, it is *persistence*, which is the harder
 * problem to notice.
 */
class TotpReplayTest {

    private val secret = ByteArray(20) { it.toByte() }

    @Test
    fun `verifyStep returns the matched step, not just a boolean`() {
        val now = 1_700_000_000L
        val step = now / 30
        val code = Totp.code(secret, now)
        assertEquals(step, Totp.verifyStep(secret, code, now))
    }

    @Test
    fun `a code from one step back still verifies, and reports that step`() {
        val now = 1_700_000_000L
        val previous = Totp.code(secret, now - 30)
        // Drift tolerance is the point of the window; it must still identify WHICH step matched,
        // or the caller cannot tell the two apart when spending them.
        assertEquals((now - 30) / 30, Totp.verifyStep(secret, previous, now))
    }

    @Test
    fun `a wrong code matches no step`() {
        val now = 1_700_000_000L
        assertNull(Totp.verifyStep(secret, "000000", now))
        assertNull(Totp.verifyStep(secret, "", now))
        assertNull(Totp.verifyStep(secret, "abcdef", now))
    }

    @Test
    fun `a code well outside the drift window is rejected`() {
        val now = 1_700_000_000L
        val stale = Totp.code(secret, now - 300)
        assertNull(Totp.verifyStep(secret, stale, now))
    }

    @Test
    fun `steps are distinct across the window so spending one does not spend another`() {
        val now = 1_700_000_000L
        val steps = listOf(now - 30, now, now + 30).map { Totp.verifyStep(secret, Totp.code(secret, it), now) }
        steps.forEach { assertNotNull(it) }
        assertEquals(steps.size, steps.distinct().size, "each step in the window must be distinguishable")
    }
}
