package com.daymark.companion.auth

/**
 * A per-key token bucket: [burst] touches available at once, refilling one every
 * [refillIntervalMs]. The other half of [AttemptBudget], for the budgets whose honest shape is a
 * CADENCE rather than a ration.
 *
 * ## Why a bucket and not another fixed window
 *
 * [AttemptLimiter]'s fixed window answers "how many attempts may this source spend before it has
 * to wait?", which is the right question for guessing at a secret. The therapist's status poll is
 * not guessing at anything: it is one question — has the owner decided yet? — asked over and over
 * while a person waits, possibly for a day. There is no number of polls that is suspicious in
 * itself; what would be suspicious is the RATE. A window big enough to hold a long wait is no
 * limit at all, and one small enough to be a limit refuses an honest poller halfway through their
 * own ceremony, which is the failure this shape exists to avoid.
 *
 * A bucket says the thing that is actually true: keep to this cadence and you are never refused,
 * however long you wait; ask faster than this and you wait a moment. [burst] is the allowance for
 * the honest interruptions — a reload, a tab restored, a laptop coming back from sleep and firing
 * the poll it owed — so a person is not punished for their machine's timing.
 *
 * ## What it is NOT for
 *
 * Anything whose security claim is stated in attempts. [AttemptBudget]'s kdoc sets out that
 * argument in full and it is unchanged here: a counter that bounds guessing at a secret must be
 * durable, and this one is in process memory, so a restart returns at most one bucket's worth of
 * volume to every key. That is the right trade for a cadence and the wrong one for a guessing
 * budget.
 *
 * [clock] is injectable, and callers pass the STORE's clock rather than the wall clock, so a test
 * that advances time advances this too — the divergence that made the TOTP lockout untestable
 * (see the route's note on `authStore.nowMs()`) is not worth repeating here.
 */
class TokenBucketLimiter(
    private val burst: Int,
    private val refillIntervalMs: Long,
    private val clock: () -> Long = System::currentTimeMillis,
) : AttemptBudget {

    private class Bucket(var tokens: Double, var last: Long)

    private val lock = Any()
    private val buckets = HashMap<String, Bucket>()

    /** Visible for tests: how many per-key buckets are currently retained. */
    internal fun trackedKeys(): Int = synchronized(lock) { buckets.size }

    override fun allow(source: String): Boolean = synchronized(lock) {
        val now = clock()
        pruneIfNeeded(now)
        val b = buckets.getOrPut(source) { Bucket(burst.toDouble(), now) }
        refill(b, now)
        if (b.tokens >= 1.0) {
            b.tokens -= 1.0
            return true
        }
        return false
    }

    override fun reset(source: String) = synchronized(lock) { buckets.remove(source); Unit }

    /** How long until one whole token exists again. Always at least a moment. */
    override fun retryAfterMs(source: String): Long = synchronized(lock) {
        val now = clock()
        val b = buckets[source] ?: return 1L
        refill(b, now)
        if (b.tokens >= 1.0) return 1L
        return (((1.0 - b.tokens) * refillIntervalMs).toLong()).coerceAtLeast(1L)
    }

    private fun refill(b: Bucket, now: Long) {
        val elapsed = now - b.last
        if (elapsed <= 0) {
            // A clock that went backwards (an injected one in a test, NTP in production) must not
            // mint tokens or strand a key forever. Hold the bucket where it is and re-anchor.
            b.last = now
            return
        }
        b.tokens = (b.tokens + elapsed.toDouble() / refillIntervalMs).coerceAtMost(burst.toDouble())
        b.last = now
    }

    /**
     * Drops buckets that have refilled completely, so the map cannot grow without bound under a
     * flood of distinct keys. A full bucket is indistinguishable from one that was never used, so
     * forgetting it gives its key nothing it did not already have.
     *
     * The predicate is computed against the PROJECTED level rather than the stored one, which is
     * the bug [AuthGuard]'s eviction had and had to be fixed: buckets refill lazily, on use, so a
     * stored level is always below capacity and a test on the stored value evicts nothing at all,
     * for ever.
     */
    private fun pruneIfNeeded(now: Long) {
        if (buckets.size < 512) return
        buckets.entries.removeIf { (_, b) ->
            val elapsed = now - b.last
            elapsed > 0 && b.tokens + elapsed.toDouble() / refillIntervalMs >= burst.toDouble()
        }
    }
}
