package com.daymark.companion.auth

import com.daymark.companion.storage.SchemaChange
import java.sql.Connection

/**
 * The owner's own account (#324): the sign-in that replaces the shared `DAYMARK_AUTH_TOKEN`, its
 * sessions, and the revocable credentials of the command-line writer.
 *
 * WHAT MUST STAY TRUE.
 * - THE ACCOUNT TAKES THE OWNER'S ONE ID. Device keys were tied to [ownerId] from their first insert
 *   (DECISIONS.md D10), so making the account moves no device row and every phone keeps working.
 * - ONE ACCOUNT, MADE ONCE. The row is insert-only by primary key, and [create] refuses a second
 *   one, so no route ever answers for two owners before per-owner scoping (#318, #331) lands.
 * - THE TOKEN IS RETIRED BY THE ACCOUNT'S EXISTENCE. [exists] is what OwnerAuth reads before it takes
 *   a bearer token, so the transaction that inserts the account is the moment the token stops
 *   working everywhere. There is no path back, and none is needed: the row is never deleted.
 * - THE ACCOUNT DECRYPTS NOTHING (O6). It holds a TOTP seed, the same honestly weaker server-stored
 *   path as the administrator's and the clinician's (COMPANION_SECURITY.md §5.2), session digests and
 *   CLI credential digests. Approving a pairing still needs the owner's keys.
 * - NOTHING HERE IS STORED IN THE CLEAR THAT SIGNS ANYONE IN, except the seed any TOTP verifier must
 *   hold. A session id and a CLI token are returned once and kept only as [Secrets.tokenHash].
 *
 * On [conn] and under [lock], OwnerAccountStore's, so the account, the token's retirement and the
 * phones registered to the owner are one database and one lock.
 */
class OwnerSignIn internal constructor(
    private val conn: Connection,
    private val lock: Any,
    private val clock: () -> Long,
    /** The owner every row here names: DeviceKeyStore's, minted once and never changed. */
    val ownerId: String,
) {

    /** The store's clock, in milliseconds: the one a code's step is judged against. */
    fun now(): Long = clock()

    /** Whether the owner's account has been made. Once true, the shared token is refused everywhere. */
    fun exists(): Boolean = synchronized(lock) {
        conn.prepareStatement("SELECT 1 FROM owner_signin WHERE owner_id=?").use { ps ->
            ps.setString(1, ownerId)
            ps.executeQuery().use { it.next() }
        }
    }

    enum class CreateStatus { OK, ALREADY_MADE }

    /**
     * Make the owner's account, named [name], with the TOTP seed [secretB64] whose step [step] was
     * just proved. Refused once the account exists, so whichever of two racing requests inserts first
     * makes it and the other is told it is already made.
     */
    fun create(name: String, secretB64: String, step: Long): CreateStatus = synchronized(lock) {
        if (exists()) return CreateStatus.ALREADY_MADE
        conn.prepareStatement(
            "INSERT INTO owner_signin(owner_id, name, secret_b64, last_used_step, fail_count, locked_until, created_at) " +
                "VALUES (?,?,?,?,0,0,?)",
        ).use { ps ->
            ps.setString(1, ownerId); ps.setString(2, name); ps.setString(3, secretB64); ps.setLong(4, step); ps.setLong(5, clock())
            ps.executeUpdate()
        }
        CreateStatus.OK
    }

    data class Account(val ownerId: String, val name: String, val secretB64: String, val lockedUntil: Long)

    fun byName(name: String): Account? = synchronized(lock) {
        conn.prepareStatement("SELECT owner_id, name, secret_b64, locked_until FROM owner_signin WHERE name=?").use { ps ->
            ps.setString(1, name)
            ps.executeQuery().use { rs -> if (rs.next()) Account(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4)) else null }
        }
    }

    fun account(): Account? = synchronized(lock) {
        conn.prepareStatement("SELECT owner_id, name, secret_b64, locked_until FROM owner_signin WHERE owner_id=?").use { ps ->
            ps.setString(1, ownerId)
            ps.executeQuery().use { rs -> if (rs.next()) Account(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4)) else null }
        }
    }

    /** Spend a TOTP step, once: false if it, or a later one, was already spent (RFC 6238 §5.2). */
    fun consumeStep(step: Long): Boolean = synchronized(lock) {
        conn.prepareStatement("UPDATE owner_signin SET last_used_step=? WHERE owner_id=? AND last_used_step < ?").use { ps ->
            ps.setLong(1, step); ps.setString(2, ownerId); ps.setLong(3, step)
            ps.executeUpdate() > 0
        }
    }

    fun recordSuccess() = synchronized(lock) {
        conn.prepareStatement("UPDATE owner_signin SET fail_count=0, locked_until=0 WHERE owner_id=?").use { ps ->
            ps.setString(1, ownerId); ps.executeUpdate()
        }
        Unit
    }

    /**
     * A wrong code: counts toward a lockout of [lockoutMs] after [lockoutFails] in a row, and returns
     * the new locked-until, 0 when none. A served lockout clears the count, so one wrong code per
     * window never re-arms another; the count is in the database, so a restart hands a guesser no
     * fresh budget (the same ratchet as AdminStore.recordFailure).
     */
    fun recordFailure(lockoutFails: Int, lockoutMs: Long): Long = synchronized(lock) {
        val now = clock()
        val (fails, lockedUntil) = conn.prepareStatement("SELECT fail_count, locked_until FROM owner_signin WHERE owner_id=?").use { ps ->
            ps.setString(1, ownerId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) to rs.getLong(2) else return 0L }
        }
        val prior = if (lockedUntil in 1..now) 0 else fails
        val next = prior + 1
        val locked = if (next >= lockoutFails) now + lockoutMs else 0L
        conn.prepareStatement("UPDATE owner_signin SET fail_count=?, locked_until=? WHERE owner_id=?").use { ps ->
            ps.setInt(1, next); ps.setLong(2, locked); ps.setString(3, ownerId); ps.executeUpdate()
        }
        locked
    }

    // ---- Sessions ----------------------------------------------------------------

    data class NewSession(val sessionId: String, val csrfToken: String, val absoluteExpiry: Long)

    /** A session for the owner. The raw id is returned once, for the cookie, and stored only as a digest. */
    fun createSession(absoluteSeconds: Long): NewSession = synchronized(lock) {
        val sessionId = Secrets.newToken()
        val csrf = Secrets.newToken()
        val now = clock()
        val absolute = now + absoluteSeconds * 1000
        conn.prepareStatement(
            "INSERT INTO owner_sessions(session_id_hash, owner_id, csrf_token, created_at, last_seen, absolute_expiry) VALUES (?,?,?,?,?,?)",
        ).use { ps ->
            ps.setString(1, Secrets.tokenHash(sessionId)); ps.setString(2, ownerId); ps.setString(3, csrf)
            ps.setLong(4, now); ps.setLong(5, now); ps.setLong(6, absolute)
            ps.executeUpdate()
        }
        NewSession(sessionId, csrf, absolute)
    }

    /**
     * Whether [sessionId] is a live session of the owner. Idle and absolute timeouts are enforced
     * here, and an expired session is deleted on sight. When [requireCsrf] is given it must equal the
     * session's anti-CSRF token, compared in constant time; a mismatch is a false like any other.
     */
    fun validateSession(sessionId: String, idleSeconds: Long, requireCsrf: String? = null): Boolean = synchronized(lock) {
        val now = clock()
        val hash = Secrets.tokenHash(sessionId)
        val row = conn.prepareStatement(
            "SELECT owner_id, csrf_token, last_seen, absolute_expiry FROM owner_sessions WHERE session_id_hash=?",
        ).use { ps ->
            ps.setString(1, hash)
            ps.executeQuery().use { rs -> if (rs.next()) listOf(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4)) else null }
        } ?: return false
        val owner = row[0] as String
        val csrf = row[1] as String
        val lastSeen = row[2] as Long
        val absolute = row[3] as Long
        if (owner != ownerId) return false
        if (now >= absolute || now - lastSeen > idleSeconds * 1000) {
            revokeSession(sessionId)
            return false
        }
        if (requireCsrf != null && !Secrets.constantTimeEquals(requireCsrf, csrf)) return false
        conn.prepareStatement("UPDATE owner_sessions SET last_seen=? WHERE session_id_hash=?").use { ps ->
            ps.setLong(1, now); ps.setString(2, hash); ps.executeUpdate()
        }
        true
    }

    /**
     * The anti-CSRF token of a session already validated, so a reloaded console carries on without
     * signing in again. The cookie is HttpOnly and SameSite=Strict, and the server sends no CORS
     * headers, so only a page of this origin can read the answer.
     */
    fun csrfOf(sessionId: String): String? = synchronized(lock) {
        conn.prepareStatement("SELECT csrf_token FROM owner_sessions WHERE session_id_hash=?").use { ps ->
            ps.setString(1, Secrets.tokenHash(sessionId))
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
    }

    fun revokeSession(sessionId: String) = synchronized(lock) {
        conn.prepareStatement("DELETE FROM owner_sessions WHERE session_id_hash=?").use { ps ->
            ps.setString(1, Secrets.tokenHash(sessionId)); ps.executeUpdate()
        }
        Unit
    }

    // ---- The command-line writer's credentials -------------------------------------

    /** One CLI credential as the console lists it: never its token. */
    data class CliCredential(val credentialId: String, val label: String, val createdAt: Long, val revokedAt: Long?)

    /** A CLI credential as minted: the token goes to the console once and is never stored. */
    data class MintedCli(val credentialId: String, val token: String, val createdAt: Long)

    /** Mint a credential for the command-line writer, labelled [label] by the owner. */
    fun mintCli(label: String): MintedCli = synchronized(lock) {
        val credentialId = "cli_" + Secrets.newToken(9)
        val token = CLI_TOKEN_PREFIX + Secrets.newToken()
        val now = clock()
        conn.prepareStatement(
            "INSERT INTO cli_credentials(credential_id, owner_id, token_hash, label, created_at, revoked_at) VALUES (?,?,?,?,?,NULL)",
        ).use { ps ->
            ps.setString(1, credentialId); ps.setString(2, ownerId); ps.setString(3, Secrets.tokenHash(token))
            ps.setString(4, label); ps.setLong(5, now)
            ps.executeUpdate()
        }
        MintedCli(credentialId, token, now)
    }

    /** The live CLI credential [token] belongs to, or null: unknown, revoked, or another owner's. */
    fun cliCredentialFor(token: String): String? = synchronized(lock) {
        conn.prepareStatement(
            "SELECT credential_id FROM cli_credentials WHERE token_hash=? AND owner_id=? AND revoked_at IS NULL",
        ).use { ps ->
            ps.setString(1, Secrets.tokenHash(token)); ps.setString(2, ownerId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
    }

    fun listCli(): List<CliCredential> = synchronized(lock) {
        conn.prepareStatement(
            "SELECT credential_id, label, created_at, revoked_at FROM cli_credentials WHERE owner_id=? ORDER BY created_at, credential_id",
        ).use { ps ->
            ps.setString(1, ownerId)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val revoked = rs.getLong(4).takeUnless { rs.wasNull() }
                        add(CliCredential(rs.getString(1), rs.getString(2), rs.getLong(3), revoked))
                    }
                }
            }
        }
    }

    /** Revoke [credentialId], once: false when there is no such live credential of this owner. */
    fun revokeCli(credentialId: String): Boolean = synchronized(lock) {
        conn.prepareStatement(
            "UPDATE cli_credentials SET revoked_at=? WHERE credential_id=? AND owner_id=? AND revoked_at IS NULL",
        ).use { ps ->
            ps.setLong(1, clock()); ps.setString(2, credentialId); ps.setString(3, ownerId)
            ps.executeUpdate() > 0
        }
    }

    companion object {
        /**
         * What every CLI token begins with, so the gate knows a CLI credential from the retired token
         * without a lookup that would let one be tried as the other.
         */
        const val CLI_TOKEN_PREFIX = "dmcli_"

        /** owner-account.db version 3 (#324): the owner's account, its sessions and the CLI's credentials. */
        internal val TABLES: List<SchemaChange> = listOf(
            SchemaChange.Table(
                """
                CREATE TABLE IF NOT EXISTS owner_signin (
                    owner_id       TEXT    NOT NULL PRIMARY KEY,
                    name           TEXT    NOT NULL UNIQUE,
                    secret_b64     TEXT    NOT NULL,
                    last_used_step INTEGER NOT NULL DEFAULT 0,
                    fail_count     INTEGER NOT NULL DEFAULT 0,
                    locked_until   INTEGER NOT NULL DEFAULT 0,
                    created_at     INTEGER NOT NULL
                )
                """.trimIndent(),
            ),
            SchemaChange.Table(
                """
                CREATE TABLE IF NOT EXISTS owner_sessions (
                    session_id_hash TEXT    NOT NULL PRIMARY KEY,
                    owner_id        TEXT    NOT NULL,
                    csrf_token      TEXT    NOT NULL,
                    created_at      INTEGER NOT NULL,
                    last_seen       INTEGER NOT NULL,
                    absolute_expiry INTEGER NOT NULL
                )
                """.trimIndent(),
            ),
            SchemaChange.Table(
                """
                CREATE TABLE IF NOT EXISTS cli_credentials (
                    credential_id TEXT    NOT NULL PRIMARY KEY,
                    owner_id      TEXT    NOT NULL,
                    token_hash    TEXT    NOT NULL UNIQUE,
                    label         TEXT    NOT NULL,
                    created_at    INTEGER NOT NULL,
                    revoked_at    INTEGER
                )
                """.trimIndent(),
            ),
        )
    }
}
