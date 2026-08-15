package com.daymark.companion.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AuthGuardTest {

    /**
     * Eviction. Every one of these would have failed before the refill term was added — the old
     * predicate compared the *stored* token count against capacity, and `allowRate` leaves every
     * bucket below capacity, so nothing was ever evictable. Two consequences, both tested here:
     * the maps grew without limit, and past the threshold the sweep ran on every request instead
     * of short-circuiting, turning the anti-flood routine into the amplifier.
     *
     * `maxEntries` is injected so these run in milliseconds instead of minting 50 000 sources.
     */
    private fun floodGuard(now: () -> Long, maxEntries: Int = 4) =
        AuthGuard("t", lockoutThreshold = 8, lockoutMillis = 1000, ratePerSecond = 5, clock = now, maxEntries = maxEntries)

    @Test
    fun `idle sources are evicted once the map is over the threshold`() {
        var now = 10_000L
        val guard = floodGuard({ now })
        repeat(6) { guard.authorize("src-$it", "t") }
        assertEquals(6, guard.trackedSources(), "each distinct source should get a bucket")

        // A second of idleness fully refills every bucket at ratePerSecond=5, so none of them
        // carries live rate-limit state any more and all are safe to drop.
        now += 2_000
        guard.authorize("trigger", "t")

        assertTrue(
            guard.trackedSources() <= 2,
            "idle buckets must be evicted; still tracking ${guard.trackedSources()}",
        )
    }

    @Test
    fun `a source still spending its budget is NOT evicted`() {
        // The other half of the contract. Eviction must not hand an active flooder a fresh bucket:
        // that would reset its rate limit on every sweep and make the limiter worse than absent.
        // Timings matter here — at ratePerSecond=5 a drained bucket refills in exactly 1 s, which
        // is also the sweep interval, so the window where "active" is distinguishable from
        // "refilled" is narrow and the test has to sit inside it deliberately.
        var now = 10_000L
        val guard = floodGuard({ now })
        repeat(6) { guard.authorize("filler-$it", "t") } // first call sweeps; nothing evictable yet

        now += 900
        repeat(5) { guard.authorize("busy", "t") } // drains 'busy' from 5 tokens to 0

        now += 100 // sweep interval elapsed, but 'busy' has refilled only 0.5 of 5 tokens
        guard.authorize("trigger", "t")

        // Retained with its spent budget intact, so the limiter still bites.
        assertEquals(AuthGuard.Result.RATE_LIMITED, guard.authorize("busy", "t"))
        // ...while the genuinely idle fillers, a full second stale, were dropped.
        assertTrue(guard.trackedSources() < 8, "idle fillers should still have been evicted")
    }

    @Test
    fun `below the threshold nothing is swept`() {
        var now = 10_000L
        val guard = floodGuard({ now }, maxEntries = 100)
        repeat(6) { guard.authorize("src-$it", "t") }
        now += 60_000
        guard.authorize("src-0", "t")
        assertEquals(6, guard.trackedSources(), "no sweep should run while the map is small")
    }

    @Test
    fun `sweeps are throttled to at most one per second`() {
        // Past the threshold the early return no longer fires, so an unthrottled sweep would run a
        // full scan — taking a monitor per bucket — on every single unauthenticated request.
        var now = 10_000L
        val guard = floodGuard({ now })
        repeat(6) { guard.authorize("src-$it", "t") }

        now += 2_000
        guard.authorize("a", "t") // sweeps: the six idle buckets go
        val afterFirst = guard.trackedSources()

        repeat(6) { guard.authorize("late-$it", "t") } // re-grow the map past the threshold
        val grown = guard.trackedSources()
        assertTrue(grown > maxOf(afterFirst, 4), "precondition: the map is over the threshold again")

        // Half a second on: the late buckets ARE now evictable (4 tokens + 0.5 s × 5 = 6.5 ≥ 5),
        // so if the throttle were missing this call would clear them. It must not — that is the
        // whole point. Without this step the assertion would pass vacuously, because a sweep at
        // the same millisecond would find nothing to drop either way.
        now += 500
        guard.authorize("b", "t")

        assertEquals(
            grown + 1,
            guard.trackedSources(),
            "a second sweep ran inside the throttle window and dropped live buckets",
        )
    }

    @Test
    fun `rotate takes effect immediately and invalidates the old token`() {
        val guard = AuthGuard("old-token", lockoutThreshold = 8, lockoutMillis = 1000, ratePerSecond = 100)
        assertEquals(AuthGuard.Result.OK, guard.authorize("peer-a", "old-token"))

        guard.rotate("new-token")

        assertEquals(AuthGuard.Result.BAD_TOKEN, guard.authorize("peer-b", "old-token"))
        assertEquals(AuthGuard.Result.OK, guard.authorize("peer-b", "new-token"))
    }

    @Test
    fun `rotate does not reset an in-progress lockout for a source`() {
        var now = 0L
        val guard = AuthGuard("old-token", lockoutThreshold = 2, lockoutMillis = 60_000, ratePerSecond = 100, clock = { now })
        assertEquals(AuthGuard.Result.BAD_TOKEN, guard.authorize("peer", "wrong"))
        assertEquals(AuthGuard.Result.BAD_TOKEN, guard.authorize("peer", "wrong"))
        // Now locked out for "peer".
        assertEquals(AuthGuard.Result.LOCKED, guard.authorize("peer", "old-token"))

        guard.rotate("new-token")

        // Rotation does not clear the pre-existing lockout for this source.
        assertEquals(AuthGuard.Result.LOCKED, guard.authorize("peer", "new-token"))

        now += 61_000
        assertEquals(AuthGuard.Result.OK, guard.authorize("peer", "new-token"))
    }

    /*
     * THE RATCHET. `count` was cleared only by a successful auth, so once a source crossed the
     * threshold every later failure re-armed a FULL lockout. One wrong request per window held a
     * source out forever — and where `sourceId` is shared (the misconfigured-proxy case
     * DAYMARK_TRUSTED_PROXIES warns about, where every request keys to the proxy) that is one
     * request per period to lock out every user of the server, indefinitely, for free.
     */

    @Test
    fun `one failure per lockout window cannot hold a source out forever`() {
        var now = 0L
        val guard = AuthGuard("good", lockoutThreshold = 2, lockoutMillis = 1_000, ratePerSecond = 100, clock = { now })

        assertEquals(AuthGuard.Result.BAD_TOKEN, guard.authorize("peer", "wrong"))
        assertEquals(AuthGuard.Result.BAD_TOKEN, guard.authorize("peer", "wrong"))
        assertEquals(AuthGuard.Result.LOCKED, guard.authorize("peer", "good"), "threshold should lock")

        // The attacker's whole budget: one wrong request just after each lockout expires.
        repeat(20) {
            now += 1_001
            assertEquals(AuthGuard.Result.BAD_TOKEN, guard.authorize("peer", "wrong"))
        }

        // Under the old rule the count is now 22, so this single failure re-arms a full lockout and
        // the legitimate holder of the token is never served again.
        now += 1_001 + 4 * 1_000 // past the lockout AND past the forget window
        assertEquals(
            AuthGuard.Result.OK,
            guard.authorize("peer", "good"),
            "a source that went quiet must be forgiven rather than ratcheted",
        )
    }

    @Test
    fun `escalation still holds for a source that keeps hammering`() {
        // The forgiveness must not become an amnesty: without pauses, nothing is forgiven.
        var now = 0L
        val guard = AuthGuard("good", lockoutThreshold = 3, lockoutMillis = 10_000, ratePerSecond = 100, clock = { now })
        repeat(3) {
            now += 10
            guard.authorize("peer", "wrong")
        }
        assertEquals(AuthGuard.Result.LOCKED, guard.authorize("peer", "good"))

        // Waiting out one lockout, but not the forget window, keeps the count.
        now += 10_001
        assertEquals(AuthGuard.Result.BAD_TOKEN, guard.authorize("peer", "wrong"))
        assertEquals(AuthGuard.Result.LOCKED, guard.authorize("peer", "good"), "the 4th failure must re-lock immediately")
    }

    @Test
    fun `locked-out sources do not accumulate forever`() {
        /*
         * The eviction predicate required `count < lockoutThreshold`, which a locked-out source can
         * never satisfy again — so those entries were permanently un-evictable and the map grew
         * without bound, seeded by exactly the traffic this class exists to survive. That is the
         * same unbounded-growth bug already fixed for `buckets`, left in place one line below it.
         */
        var now = 0L
        val guard = AuthGuard("good", lockoutThreshold = 2, lockoutMillis = 1_000, ratePerSecond = 100, clock = { now }, maxEntries = 4)

        repeat(10) { i ->
            guard.authorize("flood-$i", "wrong")
            guard.authorize("flood-$i", "wrong") // crosses the threshold, arming a lockout
        }
        assertTrue(guard.trackedFailures() >= 10, "the flood should be tracked while it is live")

        // Long past both the lockouts and the forget window: none of these carries live state.
        now += 1_000 * 4 + 2_000
        guard.authorize("trigger", "good")

        assertTrue(
            guard.trackedFailures() <= 2,
            "expired failure entries must be evictable; still tracking ${guard.trackedFailures()}",
        )
    }
}
