package com.daymark.companion.auth

/**
 * A small fixed-window, per-source attempt limiter for credential-free or pre-authentication
 * routes.
 *
 * ## Why this is separate from the per-credential lockout
 *
 * The TOTP lockout counts failures against a *credential*, which protects that credential's secret
 * from being brute-forced. It does not protect the credential's **owner** from being denied
 * service: `credentialId` is a therapist-typed username, so anyone who can guess it can spend the
 * lockout budget on their behalf. A per-credential counter is the wrong tool for that, because the
 * attacker is not trying to guess the secret — they are trying to burn the counter.
 *
 * Keying on the source address instead means an attacker pays per-source, and (given a configured
 * trusted-proxy contract — see `ClientAddress`) that address is the real client rather than the
 * proxy.
 *
 * Fixed-window rather than sliding: an attacker can send at most `2 * maxPerWindow` across a window
 * boundary, which is an acceptable overshoot for a control whose job is to make sustained abuse
 * expensive, and it costs one long per source instead of a queue of timestamps.
 */
class AttemptLimiter(
    private val maxPerWindow: Int,
    private val windowMs: Long,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Window(var startedAt: Long, var count: Int)

    private val lock = Any()
    private val windows = HashMap<String, Window>()

    /** True if this attempt is within budget. Counts the attempt when it returns true. */
    fun allow(source: String): Boolean = synchronized(lock) {
        val now = clock()
        pruneIfNeeded(now)
        val w = windows.getOrPut(source) { Window(now, 0) }
        if (now - w.startedAt >= windowMs) {
            w.startedAt = now
            w.count = 1
            return true
        }
        if (w.count < maxPerWindow) {
            w.count++
            return true
        }
        return false
    }

    /** Clears a source's budget — called on success, so legitimate use is never penalised. */
    fun reset(source: String) = synchronized(lock) { windows.remove(source); Unit }

    /**
     * Drops expired windows so the map cannot grow without bound. Only runs once the map is big
     * enough to be worth walking, so the common case stays O(1).
     */
    private fun pruneIfNeeded(now: Long) {
        if (windows.size < 512) return
        windows.entries.removeIf { now - it.value.startedAt >= windowMs }
    }
}
