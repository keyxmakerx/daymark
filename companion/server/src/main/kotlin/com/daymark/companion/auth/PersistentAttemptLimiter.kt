package com.daymark.companion.auth

/**
 * The durable half of [AttemptBudget]: identical fixed-window semantics to [AttemptLimiter], with
 * the window kept in the same SQLite database the invite it protects already lives in.
 *
 * ## Why this is a thin adapter rather than a store of its own
 *
 * Every byte of state here belongs to [AuthStore]: it owns the connection, the schema, the monitor
 * that every other invite mutation takes, and the injectable clock the lockout tests drive. A
 * second connection to the same file would be a second writer with its own lock — WAL tolerates
 * that, but the window read and its write-back would no longer be one critical section with the
 * redemption they are metering, and the failure would show up only under concurrency. So the
 * arithmetic lives in `AuthStore.allowAttempt` and this class exists for two smaller reasons that
 * are still worth a type:
 *
 *  - it states the scope and the window size **once**, at construction, next to the reasoning for
 *    the numbers, instead of repeating four arguments at every call site; and
 *  - it makes the call site read exactly like the in-memory one, so choosing between "survives a
 *    restart" and "does not" is a one-word change at the point where the choice is actually being
 *    made, rather than a different-shaped call that discourages revisiting it.
 *
 * [scope] namespaces one budget from another inside the shared table. Two surfaces that should not
 * be able to fund each other's guessing get one scope between them, deliberately: the invite-redeem
 * and invite-report routes both verify the same invite secret, so giving them separate scopes would
 * let an attacker alternate routes and spend twice the budget on one secret.
 */
class PersistentAttemptLimiter(
    private val store: AuthStore,
    private val scope: String,
    private val maxPerWindow: Int,
    private val windowMs: Long,
) : AttemptBudget {

    override fun allow(source: String): Boolean = store.allowAttempt(scope, source, maxPerWindow, windowMs)

    override fun reset(source: String) = store.resetAttempts(scope, source)
}
