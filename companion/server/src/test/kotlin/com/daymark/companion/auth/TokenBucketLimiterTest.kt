package com.daymark.companion.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The cadence budget behind the therapist's status poll.
 *
 * What it has to get right is not a ceiling but a promise: keep to the cadence and you are never
 * refused, however long you wait. A pairing run can sit unapproved for a day, so any shape that
 * counts DOWN — a window, a quota — eventually refuses an honest poller mid-ceremony, and the
 * person waiting is shown a failure that is entirely the server's own bookkeeping.
 */
class TokenBucketLimiterTest {

    private var now = 1_000_000L
    private fun limiter(burst: Int = 6, refill: Long = 10_000L) =
        TokenBucketLimiter(burst, refill) { now }

    @Test
    fun `the burst is there at once, and then it waits`() {
        val l = limiter()
        repeat(6) { assertTrue(l.allow("run-a"), "the burst is available immediately") }
        assertFalse(l.allow("run-a"))
    }

    @Test
    fun `keys are independent — one run cannot spend another's`() {
        // The whole reason this budget is keyed on the run rather than the address: two clinicians
        // behind one clinic connection are two runs, and neither pays for the other.
        val l = limiter()
        repeat(6) { l.allow("run-a") }
        assertFalse(l.allow("run-a"))
        assertTrue(l.allow("run-b"))
    }

    @Test
    fun `a poller at the cadence is never refused, however long it goes on`() {
        val l = limiter()
        repeat(6) { l.allow("run-a") }
        assertFalse(l.allow("run-a"), "spent")
        // Four hours of polling at the refill interval. A budget that counted down would have
        // stopped answering long before this.
        repeat(1_440) {
            now += 10_000
            assertTrue(l.allow("run-a"), "an honest poll is never refused")
        }
    }

    @Test
    fun `waiting banks tokens up to the burst and no further`() {
        val l = limiter()
        repeat(6) { l.allow("run-a") }
        // An hour away from the keyboard does not buy an hour's worth of polls.
        now += 3_600_000
        repeat(6) { assertTrue(l.allow("run-a")) }
        assertFalse(l.allow("run-a"), "the burst is a ceiling, not a savings account")
    }

    @Test
    fun `a refusal can say when it heals`() {
        val l = limiter(burst = 2, refill = 10_000L)
        repeat(2) { l.allow("run-a") }
        assertFalse(l.allow("run-a"))
        assertEquals(10_000L, l.retryAfterMs("run-a"), "a whole interval, because nothing has elapsed")
        now += 6_000
        assertEquals(4_000L, l.retryAfterMs("run-a"), "and it counts down with the clock")
        now += 4_000
        assertTrue(l.allow("run-a"))
    }

    @Test
    fun `a key nobody has touched is told to come straight back`() {
        // retryAfterMs is a fair question at any time, and the honest answer for a key with no
        // bucket is "now" — never an exception, and never a number that would strand a caller.
        assertEquals(1L, limiter().retryAfterMs("never-seen"))
    }

    @Test
    fun `a clock that steps backwards neither mints tokens nor strands a run`() {
        // NTP in production, an injected clock in a test. Either way the bucket must not be
        // refilled by negative time, and the key must not be frozen out until the clock catches up.
        val l = limiter(burst = 2, refill = 10_000L)
        repeat(2) { l.allow("run-a") }
        now -= 60_000
        assertFalse(l.allow("run-a"), "going back in time does not hand out tokens")
        now += 60_000 + 10_000
        assertTrue(l.allow("run-a"), "and the bucket picks up from where the clock now is")
    }

    @Test
    fun `idle buckets are forgotten, so a flood of keys cannot grow the map for ever`() {
        // A key is an exchange id out of a path parameter, so the map's size is the caller's to
        // choose unless something forgets. This is the eviction AuthGuard's first version got
        // wrong: buckets refill lazily, so a predicate on the STORED level is false for every key
        // that has ever been used and nothing is ever swept.
        val l = limiter(burst = 2, refill = 10_000L)
        repeat(600) { l.allow("old-$it") }
        assertEquals(600, l.trackedKeys(), "the control: every key really is being kept")

        now += 3_600_000
        l.allow("fresh") // the next touch is what sweeps
        assertEquals(1, l.trackedKeys(), "an hour idle is a full bucket, and a full bucket is worth nothing to keep")

        // Forgetting costs the forgotten key nothing it had not already earned.
        assertTrue(l.allow("old-1"))
    }
}
