package com.daymark.companion.mail

import com.daymark.companion.auth.Secrets
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.ConcurrentHashMap

/** The owner's registered notification address + which [MailMessage.ReviewKind] events it wants. */
data class NotificationSettings(val email: String?, val events: Set<MailMessage.ReviewKind>)

/** A minted, single-use recovery-confirmation link (Track T2 access-token re-issue). */
data class ReissueMint(val email: String, val confirmToken: String, val expiresAt: Long)

sealed interface ReissueConfirmOutcome {
    data class Rotated(val newToken: String) : ReissueConfirmOutcome
    data object Gone : ReissueConfirmOutcome
}

/**
 * Server-side state for Track T2 (COMPANION_PLAN.md, email Option A): the owner's registered
 * notification email + per-event preferences, the currently accepted owner/bearer token
 * (rotatable via the email-triggered recovery flow, durable across restarts), and single-use
 * recovery-confirmation tokens. One SQLite file per data dir, independent of the therapist-portal
 * feature flag — the bearer token also gates the plain sync API, which does not need the portal.
 *
 * **Token storage is deliberately PLAINTEXT at rest.** This is the same class of secret as
 * `DAYMARK_AUTH_TOKEN` itself, which is already plaintext in an env var or a mounted file; see
 * COMPANION_SECURITY.md's "server auth token is not the E2EE key" note — it is a
 * network-enumeration/DoS guard, not a confidentiality boundary, and a DB leak here is no worse
 * than a leak of the operator's own secret file. The registered notification email is likewise
 * stored in plaintext by necessity (the server must read it to address an outbound message);
 * see the T2 security note added to COMPANION_SECURITY.md.
 */
class OwnerAccountStore(
    dataDir: String,
    envToken: String,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : AutoCloseable {

    private val root: Path = Path.of(dataDir).toAbsolutePath().normalize()
    private val lock = Any()
    private val conn: Connection
    private val requestBuckets = ConcurrentHashMap<String, Bucket>()
    private class Bucket(var tokens: Double, var last: Long)

    init {
        Files.createDirectories(root)
        Class.forName("org.sqlite.JDBC")
        conn = DriverManager.getConnection("jdbc:sqlite:${root.resolve("owner-account.db")}")
        conn.createStatement().use { st ->
            st.execute("PRAGMA journal_mode=WAL")
            st.execute("PRAGMA synchronous=NORMAL")
            st.execute(
                """
                CREATE TABLE IF NOT EXISTS owner_token (
                    id              INTEGER NOT NULL PRIMARY KEY CHECK (id = 1),
                    token           TEXT    NOT NULL,
                    bootstrap_token TEXT    NOT NULL,
                    updated_at      INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            st.execute(
                """
                CREATE TABLE IF NOT EXISTS owner_notify (
                    id         INTEGER NOT NULL PRIMARY KEY CHECK (id = 1),
                    email      TEXT,
                    events     TEXT    NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            st.execute(
                """
                CREATE TABLE IF NOT EXISTS reissue_confirm (
                    token_hash TEXT    NOT NULL PRIMARY KEY,
                    expiry     INTEGER NOT NULL,
                    status     TEXT    NOT NULL,
                    created_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
        }
        synchronized(lock) { bootstrapToken(envToken) }
    }

    // ---- Owner bearer token (sync/portal access) ----------------------------------

    /**
     * Reconcile the stored token against the current `DAYMARK_AUTH_TOKEN[_FILE]` value at boot.
     * - First boot ever (no row): seed both `token` and `bootstrap_token` from the env value.
     * - Operator changed the env var since last boot (redeploy-with-a-new-secret-file, the
     *   rotation method already documented in COMPANION_DEPLOYMENT.md): the env value WINS,
     *   overwriting any since-rotated token — the operator's explicit action takes precedence.
     * - Env var unchanged since last boot: keep whatever is stored, including a token rotated at
     *   runtime via [rotateToken] (so the email-recovery flow survives a restart).
     */
    private fun bootstrapToken(envToken: String) {
        val existing = conn.prepareStatement("SELECT token, bootstrap_token FROM owner_token WHERE id=1").use { ps ->
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) to rs.getString(2) else null }
        }
        if (existing == null || existing.second != envToken) {
            conn.prepareStatement(
                "INSERT INTO owner_token(id, token, bootstrap_token, updated_at) VALUES (1,?,?,?) " +
                    "ON CONFLICT(id) DO UPDATE SET token=excluded.token, bootstrap_token=excluded.bootstrap_token, updated_at=excluded.updated_at",
            ).use { ps ->
                ps.setString(1, envToken); ps.setString(2, envToken); ps.setLong(3, clock()); ps.executeUpdate()
            }
        }
    }

    /** The currently accepted owner/bearer token — what [rotate the guard][com.daymark.companion.auth.AuthGuard] with at boot. */
    fun currentToken(): String = synchronized(lock) {
        conn.prepareStatement("SELECT token FROM owner_token WHERE id=1").use { ps ->
            ps.executeQuery().use { rs -> rs.next(); rs.getString(1) }
        }
    }

    /** Generate + persist a fresh token, keeping `bootstrap_token` (the env-var watermark) unchanged. */
    fun rotateToken(): String = synchronized(lock) {
        val newToken = Secrets.newToken()
        conn.prepareStatement("UPDATE owner_token SET token=?, updated_at=? WHERE id=1").use { ps ->
            ps.setString(1, newToken); ps.setLong(2, clock()); ps.executeUpdate()
        }
        newToken
    }

    // ---- Notification email + prefs -----------------------------------------------

    fun getNotificationSettings(): NotificationSettings = synchronized(lock) {
        conn.prepareStatement("SELECT email, events FROM owner_notify WHERE id=1").use { ps ->
            ps.executeQuery().use { rs ->
                if (!rs.next()) return NotificationSettings(null, emptySet())
                val email = rs.getString(1)
                val events = rs.getString(2).split(',').filter { it.isNotEmpty() }
                    .mapNotNull { wire -> MailMessage.ReviewKind.entries.firstOrNull { it.name == wire } }.toSet()
                NotificationSettings(email, events)
            }
        }
    }

    /**
     * Set/change/remove the registered email + which [MailMessage.ReviewKind] events it wants.
     * A null [email] disables all content notifications (the feature no-ops); it does NOT clear
     * the recovery capability retroactively — a null email simply means [requestReissue] never
     * matches, since there is nothing registered to match against.
     */
    fun setNotificationSettings(email: String?, events: Set<MailMessage.ReviewKind>) = synchronized(lock) {
        conn.prepareStatement(
            "INSERT INTO owner_notify(id, email, events, updated_at) VALUES (1,?,?,?) " +
                "ON CONFLICT(id) DO UPDATE SET email=excluded.email, events=excluded.events, updated_at=excluded.updated_at",
        ).use { ps ->
            ps.setString(1, email)
            ps.setString(2, events.joinToString(",") { it.name })
            ps.setLong(3, clock())
            ps.executeUpdate()
        }
    }

    // ---- Access-token recovery (request → single-use confirm) ---------------------

    /**
     * Heavily-rate-limited token-bucket gate for the unauthenticated request endpoint, keyed on
     * the caller's trusted source identity (socket peer, matching [com.daymark.companion.auth.AuthGuard]'s
     * convention). Independent of `AuthGuard`'s own limiter — a different resource, a different
     * knob (`DAYMARK_REISSUE_MAX_PER_HOUR`).
     */
    fun allowReissueAttempt(sourceId: String, maxPerHour: Int): Boolean {
        val now = clock()
        val cap = maxPerHour.toDouble().coerceAtLeast(1.0)
        val refillPerMs = cap / 3_600_000.0
        val b = requestBuckets.computeIfAbsent(sourceId) { Bucket(cap, now) }
        synchronized(b) {
            val elapsed = (now - b.last).coerceAtLeast(0)
            b.tokens = (b.tokens + elapsed * refillPerMs).coerceAtMost(cap)
            b.last = now
            return if (b.tokens >= 1.0) { b.tokens -= 1.0; true } else false
        }
    }

    /**
     * Mint a single-use, time-limited confirmation token IFF [presentedEmail] matches the
     * registered address (constant-time compare). Returns null on any mismatch or when nothing
     * is registered. Callers MUST respond identically whether this returns null or non-null —
     * this method only decides whether to mint/send, never what the HTTP response looks like, so
     * a source cannot distinguish "wrong email" from "right email" from the response alone.
     */
    fun requestReissue(presentedEmail: String, ttlSeconds: Long): ReissueMint? = synchronized(lock) {
        val registered = getNotificationSettings().email ?: return null
        if (!Secrets.constantTimeEquals(presentedEmail, registered)) return null
        val token = Secrets.newToken()
        val now = clock()
        val expiry = now + ttlSeconds * 1000
        conn.prepareStatement(
            "INSERT INTO reissue_confirm(token_hash, expiry, status, created_at) VALUES (?,?,?,?)",
        ).use { ps ->
            ps.setString(1, Secrets.tokenHash(token))
            ps.setLong(2, expiry)
            ps.setString(3, "PENDING")
            ps.setLong(4, now)
            ps.executeUpdate()
        }
        ReissueMint(registered, token, expiry)
    }

    /**
     * Validate + consume a confirm token and rotate the owner token. Single-use (status flips to
     * CONSUMED atomically under [lock]) and capped by TTL; expired-or-already-used tokens are
     * indistinguishable [ReissueConfirmOutcome.Gone] (no oracle on which).
     */
    fun confirmReissue(confirmToken: String): ReissueConfirmOutcome = synchronized(lock) {
        val hash = Secrets.tokenHash(confirmToken)
        val now = clock()
        val row = conn.prepareStatement("SELECT expiry, status FROM reissue_confirm WHERE token_hash=?").use { ps ->
            ps.setString(1, hash)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) to rs.getString(2) else null }
        } ?: return ReissueConfirmOutcome.Gone
        val (expiry, status) = row
        if (status != "PENDING" || now >= expiry) return ReissueConfirmOutcome.Gone
        conn.prepareStatement("UPDATE reissue_confirm SET status='CONSUMED' WHERE token_hash=?").use { ps ->
            ps.setString(1, hash)
            ps.executeUpdate()
        }
        ReissueConfirmOutcome.Rotated(rotateToken())
    }

    override fun close() = synchronized(lock) { conn.close() }
}
