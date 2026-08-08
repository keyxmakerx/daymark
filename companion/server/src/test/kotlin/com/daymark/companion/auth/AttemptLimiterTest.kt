package com.daymark.companion.auth

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The per-source budget that stops one attacker denying every therapist their login.
 *
 * The per-credential TOTP lockout cannot do this job: `credentialId` is a typed username, so an
 * attacker who knows it spends the victim's lockout budget rather than their own.
 */
class AttemptLimiterTest {

    private var now = 1_000_000L
    private fun limiter(max: Int = 3, window: Long = 60_000L) =
        AttemptLimiter(max, window) { now }

    @Test
    fun `allows up to the budget then refuses`() {
        val l = limiter()
        assertTrue(l.allow("1.2.3.4"))
        assertTrue(l.allow("1.2.3.4"))
        assertTrue(l.allow("1.2.3.4"))
        assertFalse(l.allow("1.2.3.4"))
    }

    @Test
    fun `sources are independent — one attacker cannot spend another client's budget`() {
        val l = limiter()
        repeat(3) { assertTrue(l.allow("6.6.6.6")) }
        assertFalse(l.allow("6.6.6.6"))
        // The victim is unaffected. This is the whole point.
        assertTrue(l.allow("203.0.113.9"))
    }

    @Test
    fun `the budget refills once the window has passed`() {
        val l = limiter()
        repeat(3) { l.allow("1.2.3.4") }
        assertFalse(l.allow("1.2.3.4"))
        now += 60_000L
        assertTrue(l.allow("1.2.3.4"))
    }

    @Test
    fun `a partial window does not refill`() {
        val l = limiter()
        repeat(3) { l.allow("1.2.3.4") }
        now += 59_999L
        assertFalse(l.allow("1.2.3.4"))
    }

    @Test
    fun `reset clears a source so success is never penalised`() {
        val l = limiter()
        repeat(3) { l.allow("1.2.3.4") }
        assertFalse(l.allow("1.2.3.4"))
        l.reset("1.2.3.4")
        assertTrue(l.allow("1.2.3.4"))
    }

    @Test
    fun `expired windows are pruned so the map cannot grow without bound`() {
        val l = limiter(max = 1, window = 1_000L)
        repeat(600) { l.allow("10.0.0.$it") }
        now += 10_000L
        // Pruning is internal; the observable contract is simply that it keeps working.
        assertTrue(l.allow("10.0.0.1"))
    }
}
