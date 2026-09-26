package com.daymark.companion.mail

import com.daymark.companion.auth.Secrets
import com.daymark.companion.storage.Schema
import com.daymark.companion.storage.SchemaChange
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.util.concurrent.ConcurrentHashMap

/** The owner's registered notification address + which [MailMessage.ReviewKind] events it wants. */
data class NotificationSettings(val email: String?, val events: Set<MailMessage.ReviewKind>)

/** A minted, single-use recovery-confirmation link (the owner's access-token re-issue). */
data class ReissueMint(val email: String, val confirmToken: String, val expiresAt: Long)

sealed interface ReissueConfirmOutcome {
    data class Rotated(val newToken: String) : ReissueConfirmOutcome
    data object Gone : ReissueConfirmOutcome
}

/**
 * Server-side state for the owner's email (COMPANION_SECURITY.md §6, "Owner notifications and
 * server-access recovery"): the owner's registered notification email + per-event preferences,
 * the currently accepted owner/bearer token (rotatable via the email-triggered recovery flow,
 * durable across restarts), and single-use recovery-confirmation tokens. One SQLite file per data
 * dir, independent of the therapist-portal feature flag — the bearer token also gates the plain
 * sync API, which does not need the portal.
 *
 * **Token storage is a digest, not the token.** `owner_token.token` and `owner_token.bootstrap_token`
 * hold [Secrets.tokenHash] of the bearer token, never the token itself;
 * [com.daymark.companion.auth.AuthGuard] is handed the digest and hashes whatever a caller
 * presents before comparing (see [currentTokenHash]).
 *
 * This used to be deliberately plaintext, on the reasoning that the token was "the same class of
 * secret as `DAYMARK_AUTH_TOKEN` itself" — a network-enumeration/DoS guard, not a confidentiality
 * boundary, so a DB leak here was no worse than a leak of the operator's own secret file. That
 * stopped being true when `POST /v1/relations/{relRef}/pairing/{exchangeId}/approve` became the
 * *only* path that mints a therapist enrolment ticket, authorised by this token alone: holding it
 * now lets someone mint an invite, answer their own pairing exchange, approve it as the owner, and
 * enrol a credential — over the network, no code to guess. (An investigation into issue #113
 * established that a dump-holder still cannot read relationship *content* that way — every content
 * route separately gates on the raw per-relationship inbox token, of which only a digest is stored
 * — so the exposure is "can install a therapist" rather than "can read the journal". But the token
 * being a confidentiality-adjacent, stored-in-the-clear credential was a needless class of risk,
 * and hashing it is cheap.) `DAYMARK_AUTH_TOKEN` the env var is unaffected and out of scope here —
 * it is the operator's own secret, already plaintext in their environment by design; only the
 * *stored copy* changes.
 *
 * The registered notification email is likewise stored in plaintext, but by necessity (the server
 * must read it to address an outbound message) rather than by the stale reasoning above; see
 * COMPANION_SECURITY.md §6, "Owner notifications and server-access recovery".
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
        conn = SCHEMA.open(root)
        conn.createStatement().use { st -> st.execute("PRAGMA synchronous=NORMAL") }
        synchronized(lock) { bootstrapToken(envToken) }
    }

    // ---- Owner bearer token (sync/portal access) ----------------------------------

    /**
     * Reconcile the stored token digest against the current `DAYMARK_AUTH_TOKEN[_FILE]` value at
     * boot. Both columns hold [Secrets.tokenHash] of a token, never a token.
     * - First boot ever (no row): seed both `token` and `bootstrap_token` from the env value's
     *   digest.
     * - Operator changed the env var since last boot (redeploy-with-a-new-secret-file, the
     *   rotation method already documented in COMPANION_DEPLOYMENT.md): the env value WINS,
     *   overwriting any since-rotated token — the operator's explicit action takes precedence.
     * - Env var unchanged since last boot: keep whatever is stored, including a token rotated at
     *   runtime via [rotateToken] (so the email-recovery flow survives a restart).
     *
     * **Migration note (issue #113):** a deployment upgraded from before this change has a
     * *plaintext* value sitting in `bootstrap_token`. A digest can never equal that plaintext
     * value (short of a hash preimage), so the comparison below reads as "the env var changed"
     * on the first post-upgrade boot regardless of whether it actually did, and re-seeds both
     * columns from the current env value's digest — exactly the re-hash-on-next-bootstrap
     * behaviour this migration wants. This is safe, never a lockout: `DAYMARK_AUTH_TOKEN` is the
     * operator's own secret and is unchanged by this deploy, so it still authorises after the
     * reseed. The one side effect is that a token rotated at runtime (via the email-recovery
     * flow) before the upgrade does not survive this specific boot — the server reverts to
     * accepting the operator's env-var token, which the operator can always still present.
     */
    private fun bootstrapToken(envToken: String) {
        val envHash = Secrets.tokenHash(envToken)
        val existing = conn.prepareStatement("SELECT token, bootstrap_token FROM owner_token WHERE id=1").use { ps ->
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) to rs.getString(2) else null }
        }
        if (existing == null || existing.second != envHash) {
            conn.prepareStatement(
                "INSERT INTO owner_token(id, token, bootstrap_token, updated_at) VALUES (1,?,?,?) " +
                    "ON CONFLICT(id) DO UPDATE SET token=excluded.token, bootstrap_token=excluded.bootstrap_token, updated_at=excluded.updated_at",
            ).use { ps ->
                ps.setString(1, envHash); ps.setString(2, envHash); ps.setLong(3, clock()); ps.executeUpdate()
            }
        }
    }

    /**
     * The digest of the currently accepted owner/bearer token — what
     * [com.daymark.companion.auth.AuthGuard] is constructed and [rotated][com.daymark.companion.auth.AuthGuard.rotate]
     * with at boot / on reissue. Never the plaintext token: only [Secrets.tokenHash] of it is
     * ever persisted, so there is no plaintext here to return.
     */
    fun currentTokenHash(): String = synchronized(lock) {
        conn.prepareStatement("SELECT token FROM owner_token WHERE id=1").use { ps ->
            ps.executeQuery().use { rs -> rs.next(); rs.getString(1) }
        }
    }

    /**
     * Generate a fresh token, persist its digest (keeping `bootstrap_token` — the env-var
     * watermark — unchanged), and return the plaintext. The plaintext is deliberately handed back
     * to the caller here and nowhere else: this is the one moment it exists outside the owner's
     * own head, on its way to the recovery-confirm HTTP response / the caller's `onRotated`
     * callback, and it is never itself stored.
     */
    fun rotateToken(): String = synchronized(lock) {
        val newToken = Secrets.newToken()
        conn.prepareStatement("UPDATE owner_token SET token=?, updated_at=? WHERE id=1").use { ps ->
            ps.setString(1, Secrets.tokenHash(newToken)); ps.setLong(2, clock()); ps.executeUpdate()
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
     *
     * The email is normalized to lowercase before storage (matching [requestReissue]'s compare) —
     * email addresses are near-universally treated case-insensitively in practice, and without
     * this an owner who registers `Owner@Example.org` but later requests recovery with the
     * everyday lowercase form would silently never match.
     */
    fun setNotificationSettings(email: String?, events: Set<MailMessage.ReviewKind>) = synchronized(lock) {
        conn.prepareStatement(
            "INSERT INTO owner_notify(id, email, events, updated_at) VALUES (1,?,?,?) " +
                "ON CONFLICT(id) DO UPDATE SET email=excluded.email, events=excluded.events, updated_at=excluded.updated_at",
        ).use { ps ->
            ps.setString(1, email?.lowercase())
            ps.setString(2, events.joinToString(",") { it.name })
            ps.setLong(3, clock())
            ps.executeUpdate()
        }
    }

    /** Lightweight accessor for just the registered email, avoiding the events parse/lookup. */
    fun registeredEmail(): String? = synchronized(lock) {
        conn.prepareStatement("SELECT email FROM owner_notify WHERE id=1").use { ps ->
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
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
        evictBucketsIfLarge(cap, now)
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
     * Keep the per-source bucket map from growing without bound under a many-source flood on this
     * UNAUTHENTICATED endpoint — mirrors [com.daymark.companion.auth.AuthGuard.evictIfLarge]:
     * when large, drop entries whose bucket has already fully refilled (no live rate-limit state).
     */
    private fun evictBucketsIfLarge(cap: Double, now: Long) {
        if (requestBuckets.size <= MAX_RATE_ENTRIES) return
        requestBuckets.entries.removeIf { e ->
            synchronized(e.value) {
                val elapsed = (now - e.value.last).coerceAtLeast(0)
                e.value.tokens + elapsed * (cap / 3_600_000.0) >= cap
            }
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
        val registered = registeredEmail() ?: return null
        if (!Secrets.constantTimeEquals(presentedEmail.lowercase(), registered)) return null
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
     *
     * [onRotated] is invoked with the new token from *inside* the same critical section that
     * persists it (before [lock] is released) — callers use this to apply the rotation to the
     * live [com.daymark.companion.auth.AuthGuard] atomically with the persist. Without this, two
     * concurrent confirms (e.g. two valid pending links) could persist in one order but apply to
     * the live guard in the other order, leaving the in-memory guard on a token that doesn't match
     * what either caller was told or what a restart would load.
     */
    fun confirmReissue(confirmToken: String, onRotated: (String) -> Unit = {}): ReissueConfirmOutcome = synchronized(lock) {
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
        val newToken = rotateToken()
        onRotated(newToken)
        ReissueConfirmOutcome.Rotated(newToken)
    }

    override fun close() = synchronized(lock) { conn.close() }

    companion object {
        private const val MAX_RATE_ENTRIES = 50_000

        /**
         * owner-account.db, version by version (#193). [Schema] says what a version is, and how a
         * database written by an earlier release is brought to [Schema.current] before it is served.
         */
        internal val SCHEMA = Schema(
            "owner-account.db",
            listOf(
                // Version 1: the structure as it stood when versions began to be kept.
                listOf(
                    SchemaChange.Table(
                        """
                        CREATE TABLE IF NOT EXISTS owner_token (
                            id              INTEGER NOT NULL PRIMARY KEY CHECK (id = 1),
                            token           TEXT    NOT NULL,
                            bootstrap_token TEXT    NOT NULL,
                            updated_at      INTEGER NOT NULL
                        )
                        """.trimIndent(),
                    ),
                    SchemaChange.Table(
                        """
                        CREATE TABLE IF NOT EXISTS owner_notify (
                            id         INTEGER NOT NULL PRIMARY KEY CHECK (id = 1),
                            email      TEXT,
                            events     TEXT    NOT NULL,
                            updated_at INTEGER NOT NULL
                        )
                        """.trimIndent(),
                    ),
                    SchemaChange.Table(
                        """
                        CREATE TABLE IF NOT EXISTS reissue_confirm (
                            token_hash TEXT    NOT NULL PRIMARY KEY,
                            expiry     INTEGER NOT NULL,
                            status     TEXT    NOT NULL,
                            created_at INTEGER NOT NULL
                        )
                        """.trimIndent(),
                    ),
                ),
            ),
        )
    }
}
