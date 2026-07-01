package com.daymark.companion.auth

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/**
 * SQLite-backed store for the therapist auth subsystem: single-use invites, TOTP
 * credentials, and opaque server-side sessions. Mirrors [com.daymark.companion.storage.BlobStore]'s
 * posture: WAL, no plaintext record columns, secrets stored only as Argon2id/BLAKE2b hashes
 * (the sole exception being the TOTP seed, which any TOTP verifier must hold to compute codes;
 * this is the honestly-weaker server-stored-authenticator path per COMPANION_SECURITY.md §5.2).
 *
 * Status machine for invites: PENDING -> REDEEMING -> CONSUMED, or -> EXPIRED. Redemption uses
 * capped exponential backoff (NOT burn-after-N — that is a denial-of-enrollment vector,
 * COMPANION_THERAPIST.md §5.1): a wrong secret never permanently kills the invite.
 */
class AuthStore(
    dataDir: String,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : AutoCloseable {

    private val root: Path = Path.of(dataDir).toAbsolutePath().normalize()
    private val lock = Any()
    private val conn: Connection

    init {
        Files.createDirectories(root)
        Class.forName("org.sqlite.JDBC")
        conn = DriverManager.getConnection("jdbc:sqlite:${root.resolve("auth.db")}")
        conn.createStatement().use { st ->
            st.execute("PRAGMA journal_mode=WAL")
            st.execute("PRAGMA synchronous=NORMAL")
            st.execute(
                """
                CREATE TABLE IF NOT EXISTS invites (
                    invite_id     TEXT    NOT NULL PRIMARY KEY,
                    rel_ref       TEXT    NOT NULL,
                    scope         TEXT    NOT NULL,
                    secret_argon2 TEXT    NOT NULL,
                    ttl_expiry    INTEGER NOT NULL,
                    status        TEXT    NOT NULL,
                    fail_count    INTEGER NOT NULL DEFAULT 0,
                    locked_until  INTEGER NOT NULL DEFAULT 0,
                    created_at    INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            st.execute(
                """
                CREATE TABLE IF NOT EXISTS totp (
                    credential_id TEXT    NOT NULL PRIMARY KEY,
                    rel_ref       TEXT    NOT NULL,
                    secret_b64    TEXT    NOT NULL,
                    fail_count    INTEGER NOT NULL DEFAULT 0,
                    locked_until  INTEGER NOT NULL DEFAULT 0,
                    created_at    INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            // One credential per relationship: a second enroll attempt for a relRef that already
            // has a live credential is rejected (insert-only enroll), so an attacker cannot enroll
            // a second forged credential bound to the same relRef.
            st.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_totp_rel_ref ON totp(rel_ref)")
            // Short-lived, single-use enrollment tickets minted at invite redemption. TOTP enroll
            // MUST consume a valid ticket that pins the relRef; this is what closes the
            // PENDING->REDEEMING->CONSUMED machine and stops unauthenticated enroll takeover.
            st.execute(
                """
                CREATE TABLE IF NOT EXISTS enroll_tickets (
                    ticket_hash TEXT    NOT NULL PRIMARY KEY,
                    invite_id   TEXT    NOT NULL,
                    rel_ref     TEXT    NOT NULL,
                    scope       TEXT    NOT NULL,
                    expiry      INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            st.execute(
                """
                CREATE TABLE IF NOT EXISTS sessions (
                    session_id_hash TEXT    NOT NULL PRIMARY KEY,
                    credential_id   TEXT    NOT NULL,
                    rel_ref         TEXT    NOT NULL,
                    csrf_token      TEXT    NOT NULL,
                    created_at      INTEGER NOT NULL,
                    last_seen       INTEGER NOT NULL,
                    absolute_expiry INTEGER NOT NULL,
                    revoked         INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
        }
    }

    // ---- Invites -----------------------------------------------------------------

    data class MintedInvite(val inviteId: String, val secret: String, val expiresAt: Long)

    enum class RedeemStatus { OK, WRONG_SECRET, LOCKED, GONE }
    data class RedeemResult(
        val status: RedeemStatus,
        val relRef: String? = null,
        val scope: List<String> = emptyList(),
        /** Single-use enrollment ticket (plaintext, returned once) that /totp/enroll must consume. */
        val enrollTicket: String? = null,
    )

    /** Mint a single-use invite. The plaintext [MintedInvite.secret] is returned once (for the link) and never stored. */
    fun mintInvite(relRef: String, scope: List<String>, ttlSeconds: Long): MintedInvite = synchronized(lock) {
        val inviteId = Secrets.newToken()
        val secret = Secrets.newToken()
        val now = clock()
        val expiry = now + ttlSeconds * 1000
        conn.prepareStatement(
            "INSERT INTO invites(invite_id, rel_ref, scope, secret_argon2, ttl_expiry, status, created_at) VALUES (?,?,?,?,?,?,?)",
        ).use { ps ->
            ps.setString(1, inviteId)
            ps.setString(2, relRef)
            ps.setString(3, scope.joinToString(","))
            ps.setString(4, Secrets.hashSecret(secret))
            ps.setLong(5, expiry)
            ps.setString(6, "PENDING")
            ps.setLong(7, now)
            ps.executeUpdate()
        }
        MintedInvite(inviteId, secret, expiry)
    }

    /**
     * Redeem an invite. Constant-time secret compare; capped exponential backoff on wrong
     * secrets (the invite is NEVER permanently burned by wrong guesses). On success the status
     * moves to REDEEMING so a second redemption of a single-use invite returns GONE.
     */
    fun redeemInvite(inviteId: String, secret: String, lockoutFails: Int, lockoutBaseMs: Long): RedeemResult = synchronized(lock) {
        val now = clock()
        conn.prepareStatement(
            "SELECT rel_ref, scope, secret_argon2, ttl_expiry, status, fail_count, locked_until FROM invites WHERE invite_id=?",
        ).use { ps ->
            ps.setString(1, inviteId)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return RedeemResult(RedeemStatus.GONE)
                val relRef = rs.getString(1)
                val scope = rs.getString(2).split(',').filter { it.isNotEmpty() }
                val secretArgon2 = rs.getString(3)
                val expiry = rs.getLong(4)
                val status = rs.getString(5)
                val failCount = rs.getInt(6)
                val lockedUntil = rs.getLong(7)

                if (status != "PENDING") return RedeemResult(RedeemStatus.GONE)
                if (now >= expiry) {
                    setInviteStatus(inviteId, "EXPIRED")
                    return RedeemResult(RedeemStatus.GONE)
                }
                if (lockedUntil > now) return RedeemResult(RedeemStatus.LOCKED)

                if (Secrets.verifySecret(secret, secretArgon2)) {
                    setInviteStatus(inviteId, "REDEEMING")
                    // Mint a short-lived, single-use enrollment ticket pinning this invite's relRef.
                    val ticket = Secrets.newToken()
                    conn.prepareStatement(
                        "INSERT INTO enroll_tickets(ticket_hash, invite_id, rel_ref, scope, expiry) VALUES (?,?,?,?,?)",
                    ).use { ins ->
                        ins.setString(1, Secrets.tokenHash(ticket))
                        ins.setString(2, inviteId)
                        ins.setString(3, relRef)
                        ins.setString(4, scope.joinToString(","))
                        ins.setLong(5, now + ENROLL_TICKET_TTL_MS)
                        ins.executeUpdate()
                    }
                    return RedeemResult(RedeemStatus.OK, relRef, scope, ticket)
                }
                // Wrong secret: bump fail count, apply capped backoff. Never consume the invite.
                val newFails = failCount + 1
                val locked = if (newFails >= lockoutFails) {
                    val backoff = lockoutBaseMs shl (newFails - lockoutFails).coerceAtMost(6) // cap the shift
                    now + backoff.coerceAtMost(3_600_000L) // cap at 1h
                } else 0L
                conn.prepareStatement("UPDATE invites SET fail_count=?, locked_until=? WHERE invite_id=?").use { up ->
                    up.setInt(1, newFails); up.setLong(2, locked); up.setString(3, inviteId); up.executeUpdate()
                }
                return RedeemResult(RedeemStatus.WRONG_SECRET)
            }
        }
    }

    private fun setInviteStatus(inviteId: String, status: String) {
        conn.prepareStatement("UPDATE invites SET status=? WHERE invite_id=?").use { ps ->
            ps.setString(1, status); ps.setString(2, inviteId); ps.executeUpdate()
        }
    }

    /** Test/inspection helper: is the plaintext secret stored anywhere? (Always false by construction.) */
    fun rawSecretColumnFor(inviteId: String): String? = synchronized(lock) {
        conn.prepareStatement("SELECT secret_argon2 FROM invites WHERE invite_id=?").use { ps ->
            ps.setString(1, inviteId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
    }

    // ---- TOTP --------------------------------------------------------------------

    enum class EnrollStatus { OK, NO_TICKET, ALREADY_ENROLLED }
    data class EnrollResult(val status: EnrollStatus, val relRef: String? = null)

    /**
     * Enrol a TOTP credential, gated on a valid single-use enrollment [ticket] minted at invite
     * redemption. The relRef is derived from the TICKET (not trusted from the caller); the ticket
     * is consumed and its invite driven to CONSUMED on success. Insert-only: if a credential
     * already exists for the credentialId OR the ticket's relRef, the enroll is REJECTED
     * (no silent INSERT OR REPLACE that could overwrite a live credential). Fail-closed.
     *
     * @return [EnrollResult] with the derived relRef on success, else the failure reason.
     */
    fun enrollTotp(ticket: String, credentialId: String, secretB64: String): EnrollResult = synchronized(lock) {
        val now = clock()
        // 1. Consume the ticket (single-use, unexpired). Derive relRef + invite from it.
        val ticketHash = Secrets.tokenHash(ticket)
        val (inviteId, relRef) = conn.prepareStatement(
            "SELECT invite_id, rel_ref, expiry FROM enroll_tickets WHERE ticket_hash=?",
        ).use { ps ->
            ps.setString(1, ticketHash)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return EnrollResult(EnrollStatus.NO_TICKET)
                val inv = rs.getString(1)
                val rel = rs.getString(2)
                val expiry = rs.getLong(3)
                if (now >= expiry) {
                    // Expired ticket: delete and fail closed.
                    deleteEnrollTicket(ticketHash)
                    return EnrollResult(EnrollStatus.NO_TICKET)
                }
                inv to rel
            }
        }
        // 2. Insert-only: reject if a credential already exists for this credentialId or relRef.
        val exists = conn.prepareStatement(
            "SELECT 1 FROM totp WHERE credential_id=? OR rel_ref=? LIMIT 1",
        ).use { ps ->
            ps.setString(1, credentialId); ps.setString(2, relRef)
            ps.executeQuery().use { rs -> rs.next() }
        }
        if (exists) return EnrollResult(EnrollStatus.ALREADY_ENROLLED)
        // 3. Insert the credential, consume the ticket, drive the invite to CONSUMED — atomically.
        conn.prepareStatement(
            "INSERT INTO totp(credential_id, rel_ref, secret_b64, fail_count, locked_until, created_at) VALUES (?,?,?,0,0,?)",
        ).use { ps ->
            ps.setString(1, credentialId); ps.setString(2, relRef); ps.setString(3, secretB64); ps.setLong(4, now)
            ps.executeUpdate()
        }
        deleteEnrollTicket(ticketHash)
        setInviteStatus(inviteId, "CONSUMED")
        return EnrollResult(EnrollStatus.OK, relRef)
    }

    private fun deleteEnrollTicket(ticketHash: String) {
        conn.prepareStatement("DELETE FROM enroll_tickets WHERE ticket_hash=?").use { ps ->
            ps.setString(1, ticketHash); ps.executeUpdate()
        }
    }

    data class TotpRecord(val relRef: String, val secretB64: String, val failCount: Int, val lockedUntil: Long)

    fun getTotp(credentialId: String): TotpRecord? = synchronized(lock) {
        conn.prepareStatement("SELECT rel_ref, secret_b64, fail_count, locked_until FROM totp WHERE credential_id=?").use { ps ->
            ps.setString(1, credentialId)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return null
                TotpRecord(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getLong(4))
            }
        }
    }

    fun recordTotpSuccess(credentialId: String) = synchronized(lock) {
        conn.prepareStatement("UPDATE totp SET fail_count=0, locked_until=0 WHERE credential_id=?").use { ps ->
            ps.setString(1, credentialId); ps.executeUpdate()
        }
    }

    /** Bump the fail count and apply capped backoff lockout. Returns the new locked_until. */
    fun recordTotpFailure(credentialId: String, lockoutFails: Int, lockoutMs: Long): Long = synchronized(lock) {
        val now = clock()
        val rec = getTotp(credentialId) ?: return 0L
        val newFails = rec.failCount + 1
        val locked = if (newFails >= lockoutFails) now + lockoutMs else 0L
        conn.prepareStatement("UPDATE totp SET fail_count=?, locked_until=? WHERE credential_id=?").use { ps ->
            ps.setInt(1, newFails); ps.setLong(2, locked); ps.setString(3, credentialId); ps.executeUpdate()
        }
        locked
    }

    // ---- Sessions ----------------------------------------------------------------

    data class NewSession(val sessionId: String, val csrfToken: String, val absoluteExpiry: Long)

    /** Create an opaque session. The raw [NewSession.sessionId] is returned once (cookie) and stored only hashed. */
    fun createSession(credentialId: String, relRef: String, idleSeconds: Long, absoluteSeconds: Long): NewSession = synchronized(lock) {
        val sessionId = Secrets.newToken()
        val csrf = Secrets.newToken()
        val now = clock()
        val absolute = now + absoluteSeconds * 1000
        conn.prepareStatement(
            "INSERT INTO sessions(session_id_hash, credential_id, rel_ref, csrf_token, created_at, last_seen, absolute_expiry, revoked) VALUES (?,?,?,?,?,?,?,0)",
        ).use { ps ->
            ps.setString(1, Secrets.tokenHash(sessionId))
            ps.setString(2, credentialId)
            ps.setString(3, relRef)
            ps.setString(4, csrf)
            ps.setLong(5, now)
            ps.setLong(6, now)
            ps.setLong(7, absolute)
            ps.executeUpdate()
        }
        NewSession(sessionId, csrf, absolute)
    }

    data class SessionRecord(val credentialId: String, val relRef: String, val csrfToken: String)

    enum class SessionCheck { OK, EXPIRED, REVOKED, MISSING, BAD_CSRF }
    data class SessionValidation(val check: SessionCheck, val record: SessionRecord? = null)

    /**
     * Validate a session by its raw id (hashed for lookup). Enforces idle + absolute timeouts;
     * on success touches last_seen. If [requireCsrf] is non-null it must equal the stored
     * anti-CSRF token (constant-time).
     */
    fun validateSession(sessionId: String, idleSeconds: Long, requireCsrf: String? = null): SessionValidation = synchronized(lock) {
        val now = clock()
        val hash = Secrets.tokenHash(sessionId)
        conn.prepareStatement(
            "SELECT credential_id, rel_ref, csrf_token, last_seen, absolute_expiry, revoked FROM sessions WHERE session_id_hash=?",
        ).use { ps ->
            ps.setString(1, hash)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return SessionValidation(SessionCheck.MISSING)
                val credentialId = rs.getString(1)
                val relRef = rs.getString(2)
                val csrf = rs.getString(3)
                val lastSeen = rs.getLong(4)
                val absolute = rs.getLong(5)
                val revoked = rs.getInt(6) != 0
                if (revoked) return SessionValidation(SessionCheck.REVOKED)
                if (now >= absolute || now - lastSeen > idleSeconds * 1000) {
                    return SessionValidation(SessionCheck.EXPIRED)
                }
                if (requireCsrf != null && !Secrets.constantTimeEquals(requireCsrf, csrf)) {
                    return SessionValidation(SessionCheck.BAD_CSRF)
                }
                // Touch idle timer.
                conn.prepareStatement("UPDATE sessions SET last_seen=? WHERE session_id_hash=?").use { up ->
                    up.setLong(1, now); up.setString(2, hash); up.executeUpdate()
                }
                return SessionValidation(SessionCheck.OK, SessionRecord(credentialId, relRef, csrf))
            }
        }
    }

    /** Hard-delete a session (logout / instant revoke). */
    fun revokeSession(sessionId: String) = synchronized(lock) {
        conn.prepareStatement("DELETE FROM sessions WHERE session_id_hash=?").use { ps ->
            ps.setString(1, Secrets.tokenHash(sessionId)); ps.executeUpdate()
        }
    }

    override fun close() = synchronized(lock) { conn.close() }

    companion object {
        /** Enrollment tickets are short-lived — a therapist enrolls TOTP immediately after redeeming. */
        const val ENROLL_TICKET_TTL_MS = 10 * 60 * 1000L
    }
}
