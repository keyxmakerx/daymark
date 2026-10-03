package com.daymark.companion.admin

import com.daymark.companion.auth.Secrets
import com.daymark.companion.storage.Schema
import com.daymark.companion.storage.SchemaChange
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection

/**
 * The server's administrators and their sessions (#322).
 *
 * An administrator runs the server: membership, health and counts. They hold no key, no grant and
 * no owner token, and nothing this store keeps opens anything a person wrote. Their one
 * authenticator is TOTP, kept as the seed any TOTP verifier must hold (the same honestly weaker,
 * server-stored path as the clinician's, COMPANION_SECURITY.md §5.2); their sessions are opaque ids
 * stored only as BLAKE2b digests.
 *
 * The first administrator is made only by [claim], which the setup code gates ([SetupCode]); a
 * server with an administrator is claimed, and [claim] refuses to make a second one unless the
 * operator asked at start-up for a fresh code. Rows here are insert-only except the TOTP counters,
 * the lockout and a session's last-seen time.
 */
class AdminStore(
    dataDir: String,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : AutoCloseable {

    private val root: Path = Path.of(dataDir).toAbsolutePath().normalize()
    private val lock = Any()
    private val conn: Connection

    init {
        Files.createDirectories(root)
        conn = SCHEMA.open(root)
        conn.createStatement().use { st -> st.execute("PRAGMA synchronous=NORMAL") }
    }

    fun nowMs(): Long = clock()

    /** How many administrators this server has. Zero means it has never been claimed. */
    fun adminCount(): Int = synchronized(lock) {
        conn.createStatement().use { st -> st.executeQuery("SELECT COUNT(*) FROM admins").use { rs -> rs.next(); rs.getInt(1) } }
    }

    enum class ClaimStatus { OK, NAME_TAKEN, ALREADY_CLAIMED }
    data class ClaimResult(val status: ClaimStatus, val adminId: String? = null)

    /**
     * Make an administrator named [name] with the TOTP seed [secretB64], whose step [step] was just
     * proved, in one transaction under the store's lock.
     *
     * [allowWhenClaimed] is false unless the operator started the server asking for a fresh code
     * because every administrator was lost; without it a server that already has an administrator
     * makes none, whatever code is shown. The caller checks the code first and spends it only on OK.
     */
    fun claim(name: String, secretB64: String, step: Long, allowWhenClaimed: Boolean): ClaimResult = synchronized(lock) {
        if (!allowWhenClaimed && adminCount() > 0) return ClaimResult(ClaimStatus.ALREADY_CLAIMED)
        val taken = conn.prepareStatement("SELECT 1 FROM admins WHERE name=?").use { ps ->
            ps.setString(1, name)
            ps.executeQuery().use { it.next() }
        }
        if (taken) return ClaimResult(ClaimStatus.NAME_TAKEN)
        val adminId = "adm_" + Secrets.newToken(16)
        conn.prepareStatement(
            "INSERT INTO admins(admin_id, name, secret_b64, last_used_step, fail_count, locked_until, created_at) VALUES (?,?,?,?,0,0,?)",
        ).use { ps ->
            ps.setString(1, adminId); ps.setString(2, name); ps.setString(3, secretB64); ps.setLong(4, step); ps.setLong(5, clock())
            ps.executeUpdate()
        }
        ClaimResult(ClaimStatus.OK, adminId)
    }

    data class AdminRecord(val adminId: String, val name: String, val secretB64: String, val lockedUntil: Long)

    fun byName(name: String): AdminRecord? = synchronized(lock) {
        conn.prepareStatement("SELECT admin_id, name, secret_b64, locked_until FROM admins WHERE name=?").use { ps ->
            ps.setString(1, name)
            ps.executeQuery().use { rs -> if (rs.next()) AdminRecord(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4)) else null }
        }
    }

    fun byId(adminId: String): AdminRecord? = synchronized(lock) {
        conn.prepareStatement("SELECT admin_id, name, secret_b64, locked_until FROM admins WHERE admin_id=?").use { ps ->
            ps.setString(1, adminId)
            ps.executeQuery().use { rs -> if (rs.next()) AdminRecord(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4)) else null }
        }
    }

    /** Spend a TOTP step, once: false if it, or a later one, was already spent (RFC 6238 §5.2). */
    fun consumeStep(adminId: String, step: Long): Boolean = synchronized(lock) {
        conn.prepareStatement("UPDATE admins SET last_used_step=? WHERE admin_id=? AND last_used_step < ?").use { ps ->
            ps.setLong(1, step); ps.setString(2, adminId); ps.setLong(3, step)
            ps.executeUpdate() > 0
        }
    }

    fun recordSuccess(adminId: String) = synchronized(lock) {
        conn.prepareStatement("UPDATE admins SET fail_count=0, locked_until=0 WHERE admin_id=?").use { ps ->
            ps.setString(1, adminId); ps.executeUpdate()
        }
        Unit
    }

    /**
     * A wrong code for [adminId]: counts toward a lockout of [lockoutMs] after [lockoutFails] in a
     * row. Returns the new locked-until, 0 when none. A served lockout clears the count, so one wrong
     * code per window never re-arms another (the ratchet `AuthStore.recordTotpFailure` describes).
     * The count is in the database, so a restart does not hand a guesser a fresh budget.
     */
    fun recordFailure(adminId: String, lockoutFails: Int, lockoutMs: Long): Long = synchronized(lock) {
        val now = clock()
        val (fails, lockedUntil) = conn.prepareStatement("SELECT fail_count, locked_until FROM admins WHERE admin_id=?").use { ps ->
            ps.setString(1, adminId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) to rs.getLong(2) else return 0L }
        }
        val prior = if (lockedUntil in 1..now) 0 else fails
        val next = prior + 1
        val locked = if (next >= lockoutFails) now + lockoutMs else 0L
        conn.prepareStatement("UPDATE admins SET fail_count=?, locked_until=? WHERE admin_id=?").use { ps ->
            ps.setInt(1, next); ps.setLong(2, locked); ps.setString(3, adminId); ps.executeUpdate()
        }
        locked
    }

    // ---- Sessions ----------------------------------------------------------------

    data class NewSession(val sessionId: String, val csrfToken: String, val absoluteExpiry: Long)

    /** A session for [adminId]. The raw id is returned once, for the cookie, and stored only as a digest. */
    fun createSession(adminId: String, absoluteSeconds: Long): NewSession = synchronized(lock) {
        val sessionId = Secrets.newToken()
        val csrf = Secrets.newToken()
        val now = clock()
        val absolute = now + absoluteSeconds * 1000
        conn.prepareStatement(
            "INSERT INTO admin_sessions(session_id_hash, admin_id, csrf_token, created_at, last_seen, absolute_expiry) VALUES (?,?,?,?,?,?)",
        ).use { ps ->
            ps.setString(1, Secrets.tokenHash(sessionId)); ps.setString(2, adminId); ps.setString(3, csrf)
            ps.setLong(4, now); ps.setLong(5, now); ps.setLong(6, absolute)
            ps.executeUpdate()
        }
        NewSession(sessionId, csrf, absolute)
    }

    /**
     * The administrator a live session belongs to, or null. Idle and absolute timeouts are enforced
     * here; an expired session is deleted on sight. When [requireCsrf] is given it must equal the
     * session's anti-CSRF token, compared in constant time, and a mismatch is a null like any other.
     */
    fun validateSession(sessionId: String, idleSeconds: Long, requireCsrf: String? = null): AdminRecord? = synchronized(lock) {
        val now = clock()
        val hash = Secrets.tokenHash(sessionId)
        val row = conn.prepareStatement(
            "SELECT admin_id, csrf_token, last_seen, absolute_expiry FROM admin_sessions WHERE session_id_hash=?",
        ).use { ps ->
            ps.setString(1, hash)
            ps.executeQuery().use { rs -> if (rs.next()) listOf(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4)) else null }
        } ?: return null
        val adminId = row[0] as String
        val csrf = row[1] as String
        val lastSeen = row[2] as Long
        val absolute = row[3] as Long
        if (now >= absolute || now - lastSeen > idleSeconds * 1000) {
            revokeSession(sessionId)
            return null
        }
        if (requireCsrf != null && !Secrets.constantTimeEquals(requireCsrf, csrf)) return null
        conn.prepareStatement("UPDATE admin_sessions SET last_seen=? WHERE session_id_hash=?").use { ps ->
            ps.setLong(1, now); ps.setString(2, hash); ps.executeUpdate()
        }
        byId(adminId)
    }

    /**
     * The anti-CSRF token of a session already validated, so a reloaded console can carry on
     * without signing in again. The cookie is HttpOnly and SameSite=Strict, and the server sends no
     * CORS headers, so only a page of this origin can read the answer.
     */
    fun csrfOf(sessionId: String): String? = synchronized(lock) {
        conn.prepareStatement("SELECT csrf_token FROM admin_sessions WHERE session_id_hash=?").use { ps ->
            ps.setString(1, Secrets.tokenHash(sessionId))
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
    }

    fun revokeSession(sessionId: String) = synchronized(lock) {
        conn.prepareStatement("DELETE FROM admin_sessions WHERE session_id_hash=?").use { ps ->
            ps.setString(1, Secrets.tokenHash(sessionId)); ps.executeUpdate()
        }
        Unit
    }

    override fun close() = synchronized(lock) { conn.close() }

    internal companion object {
        internal val SCHEMA = Schema(
            "admin.db",
            listOf(
                listOf(
                    SchemaChange.Table(
                        """
                        CREATE TABLE IF NOT EXISTS admins (
                            admin_id       TEXT    NOT NULL PRIMARY KEY,
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
                        CREATE TABLE IF NOT EXISTS admin_sessions (
                            session_id_hash TEXT    NOT NULL PRIMARY KEY,
                            admin_id        TEXT    NOT NULL,
                            csrf_token      TEXT    NOT NULL,
                            created_at      INTEGER NOT NULL,
                            last_seen       INTEGER NOT NULL,
                            absolute_expiry INTEGER NOT NULL
                        )
                        """.trimIndent(),
                    ),
                ),
            ),
        )
    }
}
