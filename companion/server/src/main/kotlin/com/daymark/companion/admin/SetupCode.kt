package com.daymark.companion.admin

import com.daymark.companion.auth.Secrets
import java.security.SecureRandom

/**
 * The one-time code that claims a server (#322).
 *
 * Whoever reaches a new server first must not own it, and the log is the one thing only the person
 * who installed the server can read, in a container, on a headless box and behind a proxy alike. So
 * a server with no administrator writes a fresh code to its log on every start, and entering it on
 * the server console is the only way to make the first administrator.
 *
 * What this keeps true:
 *  - **Long enough that guessing is not the risk.** [GROUPS] × [GROUP_LENGTH] symbols from the
 *    30-symbol [ALPHABET]: 30 × log2(30), about 147 bits.
 *  - **Held only as a digest, only in memory.** The plaintext exists in the one log line that prints
 *    it and nowhere else: not on disk, not in a response, not in an audit row. A restart forgets it
 *    and prints a new one.
 *  - **Dies at first use or on expiry.** [spendIfMatches] clears it; one past [expiresAt] never matches, and
 *    [rotateIfExpired] prints its replacement while the server is still unclaimed.
 *  - **Compared in constant time**, digest to digest, after normalising what a person types (case,
 *    spaces and dashes), so a wrong code costs the same as a right one.
 *  - **A wrong code locks nobody out.** There is no failure count here at all; the route meters
 *    attempts per address, which slows a guesser and never stops the operator holding the log.
 */
class SetupCode(
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private data class Live(val digest: String, val expiresAt: Long)

    private val lock = Any()
    private var live: Live? = null

    /** A fresh code, replacing any before it. The caller prints it, once, and keeps no copy. */
    fun mint(): Minted = synchronized(lock) {
        val symbols = CharArray(GROUPS * GROUP_LENGTH) { ALPHABET[rng.nextInt(ALPHABET.length)] }
        val plain = String(symbols)
        val expiresAt = clock() + ttlMs
        live = Live(Secrets.tokenHash(plain), expiresAt)
        Minted(plain.chunked(GROUP_LENGTH).joinToString("-"), expiresAt)
    }

    /** A new code when the live one has expired, or null when none is due. Only while a code is wanted. */
    fun rotateIfExpired(): Minted? = synchronized(lock) {
        val current = live ?: return null
        if (clock() < current.expiresAt) return null
        mint()
    }

    /** Whether a code is live: minted, unspent and unexpired. */
    fun isLive(): Boolean = synchronized(lock) { live?.let { clock() < it.expiresAt } == true }

    /** Whether [presented] is the live code. Constant time over the digests; an expired code never matches. */
    fun matches(presented: String): Boolean = synchronized(lock) {
        val current = live ?: return false
        val ok = Secrets.constantTimeEquals(Secrets.tokenHash(normalise(presented)), current.digest)
        ok && clock() < current.expiresAt
    }

    /**
     * Spend the code if [presented] is it, in one step under the lock: true for exactly one caller,
     * however many arrive at once holding the right code. Spent, it never matches again and no
     * replacement is printed.
     */
    fun spendIfMatches(presented: String): Boolean = synchronized(lock) {
        if (!matches(presented)) return false
        live = null
        true
    }

    data class Minted(val code: String, val expiresAt: Long)

    companion object {
        /** No 0/O, 1/I/L or U: a code read off a terminal and typed by hand must survive the reading. */
        const val ALPHABET = "ABCDEFGHJKMNPQRSTVWXYZ23456789"
        const val GROUP_LENGTH = 5
        const val GROUPS = 6
        const val DEFAULT_TTL_MS = 60L * 60 * 1000

        private val rng = SecureRandom()

        /** What a person typed, as the code was minted: upper case, with spaces and dashes gone. */
        fun normalise(typed: String): String = typed.uppercase().filter { it != '-' && !it.isWhitespace() }
    }
}
