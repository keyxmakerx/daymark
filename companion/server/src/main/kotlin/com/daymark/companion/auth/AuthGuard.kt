package com.daymark.companion.auth

import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Bearer-token auth with per-source brute-force defense: a token-bucket rate limit and
 * an exponential-ish lockout after repeated bad tokens. Keyed on the **trusted source
 * identity** — by default the socket peer (we trust no forwarded headers; see
 * COMPANION_SECURITY.md §7). Token comparison is constant-time.
 *
 * The bearer token is separate from the E2EE passphrase: it only gates who may PUT/GET
 * opaque blobs; it never decrypts anything.
 *
 * The accepted token is swappable at runtime via [rotate] — the email-triggered access-token
 * recovery flow (Track T2, email Option A) calls this after persisting a newly issued token, so a
 * live server never needs a restart to accept it. `@Volatile` is sufficient (not a lock): the
 * whole array reference is swapped atomically, so a concurrent reader always sees a complete
 * old or new token, never a partial one.
 */
class AuthGuard(
    token: String,
    private val lockoutThreshold: Int,
    private val lockoutMillis: Long,
    private val ratePerSecond: Int,
    private val clock: () -> Long = { System.currentTimeMillis() },
    /** Sweep threshold. Injectable so tests can exercise eviction without minting 50 000 sources. */
    private val maxEntries: Int = MAX_ENTRIES,
) {
    enum class Result { OK, BAD_TOKEN, LOCKED, RATE_LIMITED }

    @Volatile
    private var tokenBytes = token.toByteArray(Charsets.UTF_8)
    private val failures = ConcurrentHashMap<String, FailState>()
    private val buckets = ConcurrentHashMap<String, Bucket>()

    /** Wall-clock of the last completed sweep; see [evictIfLarge] for why the sweep is throttled. */
    private val lastSweep = AtomicLong(Long.MIN_VALUE / 2)

    /** Visible for tests: how many per-source buckets are currently retained. */
    internal fun trackedSources(): Int = buckets.size

    private data class FailState(var count: Int, var lockedUntil: Long)
    private class Bucket(var tokens: Double, var last: Long)

    fun authorize(sourceId: String, presented: String?): Result {
        evictIfLarge()
        if (!allowRate(sourceId)) return Result.RATE_LIMITED

        val fs = failures[sourceId]
        if (fs != null && fs.lockedUntil > clock()) return Result.LOCKED

        if (presented != null && constantTimeEquals(tokenBytes, presented.toByteArray(Charsets.UTF_8))) {
            failures.remove(sourceId) // reset on success
            return Result.OK
        }
        recordFailure(sourceId)
        return Result.BAD_TOKEN
    }

    /**
     * Replace the accepted token, effective immediately for every subsequent [authorize] call.
     * The old token stops working the instant this returns — there is no grace overlap. Callers
     * are responsible for persisting the new token durably *before* calling this (so a crash
     * between persist and rotate never strands the server on a token nobody has).
     */
    fun rotate(newToken: String) {
        tokenBytes = newToken.toByteArray(Charsets.UTF_8)
    }

    private fun recordFailure(sourceId: String) {
        val now = clock()
        failures.compute(sourceId) { _, prev ->
            val count = (prev?.count ?: 0) + 1
            val locked = if (count >= lockoutThreshold) now + lockoutMillis else (prev?.lockedUntil ?: 0L)
            FailState(count, locked)
        }
    }

    /**
     * Keep the per-source maps from growing without bound under a many-source flood: when
     * large, drop entries that carry no live state (no active lockout; a bucket that has
     * refilled to capacity).
     *
     * **This was broken and evicted nothing.** The bucket predicate used to read `tokens >= cap`
     * against the *stored* value, but [allowRate] is the only writer and it leaves every bucket at
     * `<= cap - 1.0` — a bucket refills lazily, on its next use, not in the background. So the
     * predicate was false for every source that had ever sent a request, the maps grew without
     * limit, and past [maxEntries] the guard below stopped short-circuiting: every `authorize()`
     * then ran a full scan taking a monitor per bucket, *before* checking any token. Unbounded
     * memory plus O(n) work per unauthenticated request, from the routine whose job was to prevent
     * exactly that.
     *
     * The fix is the refill term, which the sibling implementation at
     * [com.daymark.companion.mail.OwnerAccountStore] had all along — that one computes
     * `tokens + elapsed * rate >= cap`, i.e. "would this bucket be full by now?", which is the
     * question actually being asked. Here the refill rate is `ratePerSecond` per second, so a
     * bucket carries no state once it has been idle for a second.
     */
    private fun evictIfLarge() {
        if (failures.size <= maxEntries && buckets.size <= maxEntries) return
        val now = clock()

        // Above the threshold the guard above no longer short-circuits, so without this the sweep
        // runs on every single request and its cost IS the amplification. One sweep per second is
        // enough: a bucket cannot refill faster than that. The CAS also means exactly one thread
        // sweeps — the losers skip rather than queue.
        val prev = lastSweep.get()
        if (now - prev < SWEEP_INTERVAL_MS) return
        if (!lastSweep.compareAndSet(prev, now)) return

        failures.entries.removeIf { it.value.lockedUntil <= now && it.value.count < lockoutThreshold }
        val cap = ratePerSecond.toDouble().coerceAtLeast(1.0)
        buckets.entries.removeIf { e ->
            synchronized(e.value) {
                val elapsedSec = (now - e.value.last).coerceAtLeast(0) / 1000.0
                e.value.tokens + elapsedSec * cap >= cap
            }
        }

        // Every tracked source is genuinely active, so there was nothing to drop. Not a bug —
        // but it is the shape of a distributed flood, and the operator cannot see it any other
        // way. Once per sweep interval at most, so this cannot itself become the flood.
        if (buckets.size > maxEntries) {
            log.warn(
                "AuthGuard is tracking {} active sources (threshold {}) and a sweep freed none. " +
                    "That is either a wide distributed flood or DAYMARK_TRUSTED_PROXIES pointing at " +
                    "something that varies per request.",
                buckets.size,
                maxEntries,
            )
        }
    }

    private fun allowRate(sourceId: String): Boolean {
        val now = clock()
        val cap = ratePerSecond.toDouble().coerceAtLeast(1.0)
        val b = buckets.computeIfAbsent(sourceId) { Bucket(cap, now) }
        synchronized(b) {
            val elapsedSec = (now - b.last).coerceAtLeast(0) / 1000.0
            b.tokens = (b.tokens + elapsedSec * cap).coerceAtMost(cap)
            b.last = now
            return if (b.tokens >= 1.0) {
                b.tokens -= 1.0
                true
            } else {
                false
            }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger("com.daymark.companion.auth")
        private const val MAX_ENTRIES = 50_000

        /** Minimum gap between sweeps. One second is the full refill window for any bucket. */
        private const val SWEEP_INTERVAL_MS = 1_000L

        /** Length-independent constant-time compare (MessageDigest.isEqual is constant-time). */
        fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)
    }
}
