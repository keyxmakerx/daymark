package com.daymark.companion.auth

import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Bearer-token auth with per-source brute-force defense: a token-bucket rate limit and
 * an exponential-ish lockout after repeated bad tokens. Keyed on the **trusted source
 * identity** — by default the socket peer (we trust no forwarded headers; see
 * COMPANION_SECURITY.md §7). Token comparison is constant-time.
 *
 * The bearer token is separate from the E2EE passphrase: it only gates who may PUT/GET
 * opaque blobs; it never decrypts anything.
 */
class AuthGuard(
    private val token: String,
    private val lockoutThreshold: Int,
    private val lockoutMillis: Long,
    private val ratePerSecond: Int,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    enum class Result { OK, BAD_TOKEN, LOCKED, RATE_LIMITED }

    private val tokenBytes = token.toByteArray(Charsets.UTF_8)
    private val failures = ConcurrentHashMap<String, FailState>()
    private val buckets = ConcurrentHashMap<String, Bucket>()

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
     * large, drop entries that carry no live state (no active lockout; a refilled bucket).
     */
    private fun evictIfLarge() {
        if (failures.size <= MAX_ENTRIES && buckets.size <= MAX_ENTRIES) return
        val now = clock()
        failures.entries.removeIf { it.value.lockedUntil <= now && it.value.count < lockoutThreshold }
        val cap = ratePerSecond.toDouble().coerceAtLeast(1.0)
        buckets.entries.removeIf { e -> synchronized(e.value) { e.value.tokens >= cap } }
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
        private const val MAX_ENTRIES = 50_000

        /** Length-independent constant-time compare (MessageDigest.isEqual is constant-time). */
        fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)
    }
}
