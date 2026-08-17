package com.daymark.companion.auth

/**
 * The contract every per-source attempt budget implements: a fixed window keyed by an opaque
 * source string, spent by [allow] and cleared by [reset] on a legitimate success.
 *
 * ## Which budgets must survive a restart, and which are honestly fine in memory
 *
 * This interface exists because that question has two different correct answers, and the answer is
 * what decides which implementation a call site takes. It is written down here, once, rather than
 * at each call site, so that whoever adds the next limiter is made to answer it as well.
 *
 * A counter has to be **durable** when the security claim of the thing it guards is *stated in
 * attempts*. The pairing surface is exactly that shape: a therapist is handed a link and (once the
 * PAKE lands) a short code, and the entire argument for a short code being safe is "one online
 * guess per attempt, and no offline attack". That argument is worth precisely as much as the thing
 * doing the counting. A process-memory map is worth very little:
 *
 *  - A crash, a deploy, an OOM or a `docker compose up -d` resets every window. An attacker who can
 *    provoke a restart — or who is simply patient enough to wait for one — is handed a fresh budget
 *    for free, and nothing anywhere records that it happened.
 *  - Two instances behind a load balancer each keep their own map, so the deployment silently grants
 *    twice the configured budget, with no signal that the limit has been halved in effectiveness.
 *
 * While rate limiting was hardening — a nice-to-have on top of controls that stood on their own —
 * that was an acceptable trade. It stops being acceptable the moment a limiter becomes the whole
 * security argument for a low-entropy secret. Those counters take [PersistentAttemptLimiter], which
 * keeps them in the same SQLite the invite itself lives in.
 *
 * A counter may stay **in memory** when losing it costs bandwidth rather than secrecy. Two of the
 * three budgets in this server are that kind, and both are deliberate rather than unexamined:
 *
 *  - The per-source budget on TOTP verify. What bounds guessing at a TOTP secret is the
 *    *per-credential* counter, and that one has been in SQLite since it was written
 *    (`totp.fail_count` / `totp.locked_until`), so it already survives a restart. This budget's job
 *    is the different one its own comment states: stop one source spending an unlimited number of
 *    attempts, and make sustained abuse cost the attacker rather than the therapist whose username
 *    they are guessing at. A restart hands back at most one window of request volume; it does not
 *    widen the guessing budget against any secret, because the counter that does that is durable.
 *  - The per-source budget on the relationship surface (`RelationRoutes`). Every caller it meters is
 *    *already authenticated* by a session cookie — it exists to stop a signed-in therapist pouring
 *    notification mail into the owner's inbox, not to stop anyone guessing anything. There is no
 *    secret behind it to protect, so there is nothing for durability to buy.
 *
 * The bearer-token lockout in [AuthGuard] is a third in-memory case and stays that way for the same
 * reason as the first: the token it guards is a 256-bit CSPRNG value, so its safety rests on its
 * entropy rather than on a count, and the lockout is there to make a flood expensive.
 */
interface AttemptBudget {
    /** True if this attempt is within budget. Counts the attempt when it returns true. */
    fun allow(source: String): Boolean

    /** Clears a source's budget — called on success, so legitimate use is never penalised. */
    fun reset(source: String)
}

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
 *
 * **In process memory, and therefore lost on restart.** That is the right trade only for the call
 * sites [AttemptBudget] names as in-memory ones; anything guarding a low-entropy secret takes
 * [PersistentAttemptLimiter] instead. [PersistentAttemptLimiter] reproduces the window arithmetic
 * below exactly, so the two are interchangeable at a call site and only the durability differs.
 */
class AttemptLimiter(
    private val maxPerWindow: Int,
    private val windowMs: Long,
    private val clock: () -> Long = System::currentTimeMillis,
) : AttemptBudget {
    private data class Window(var startedAt: Long, var count: Int)

    private val lock = Any()
    private val windows = HashMap<String, Window>()

    override fun allow(source: String): Boolean = synchronized(lock) {
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

    override fun reset(source: String) = synchronized(lock) { windows.remove(source); Unit }

    /**
     * Drops expired windows so the map cannot grow without bound. Only runs once the map is big
     * enough to be worth walking, so the common case stays O(1).
     */
    private fun pruneIfNeeded(now: Long) {
        if (windows.size < 512) return
        windows.entries.removeIf { now - it.value.startedAt >= windowMs }
    }
}
