package com.daymark.companion.mail

import com.daymark.companion.auth.DeviceKeyStore
import com.daymark.companion.auth.OwnerSignIn
import com.daymark.companion.auth.Secrets
import com.daymark.companion.auth.inTransaction
import com.daymark.companion.storage.Schema
import com.daymark.companion.storage.SchemaChange
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection

/** The owner's registered notification address + which [MailMessage.ReviewKind] events it wants. */
data class NotificationSettings(val email: String?, val events: Set<MailMessage.ReviewKind>)

/**
 * Server-side state for the owner (COMPANION_SECURITY.md §6, "Owner notifications"): the owner's
 * registered notification email + per-event preferences, the digest of the accepted owner/bearer
 * token (durable across restarts, changed only by the operator's `DAYMARK_AUTH_TOKEN`), the owner's
 * own account ([signIn]) and the phones paired to the owner. Nothing here sends a token by email:
 * the emailed re-issue is gone, and getting back in proves the owner's own key (#325). The
 * `reissue_confirm` table it used stays in version 1 of the schema, unread. One SQLite file per data
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

    /**
     * The owner's id and the phones registered to it (#186, #189), on this database's connection and
     * under its lock, so a re-issued token and the revocation of every device are one transaction.
     */
    val devices: DeviceKeyStore

    /**
     * The owner's own account and the CLI's credentials (#324), on this database's connection and under
     * its lock, so the account's creation and the token's retirement are one transaction.
     */
    val signIn: OwnerSignIn

    /**
     * The device keys this start revoked because `DAYMARK_AUTH_TOKEN` changed since the last one — the
     * operator's way of re-issuing the token. Each is owed one audit row, written once the owner's log
     * is open.
     */
    val revokedAtStart: List<String>

    init {
        Files.createDirectories(root)
        conn = SCHEMA.open(root)
        // FULL, not NORMAL: a signed request's nonce is written here before the request is served, and
        // the memory of seen nonces has to outlast a power cut as well as a restart (#186).
        conn.createStatement().use { st -> st.execute("PRAGMA synchronous=FULL") }
        devices = DeviceKeyStore(conn, lock, clock)
        signIn = OwnerSignIn(conn, lock, clock, devices.ownerId)
        revokedAtStart = synchronized(lock) { bootstrapToken(envToken) }
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
    private fun bootstrapToken(envToken: String): List<String> {
        val envHash = Secrets.tokenHash(envToken)
        val existing = conn.prepareStatement("SELECT token, bootstrap_token FROM owner_token WHERE id=1").use { ps ->
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) to rs.getString(2) else null }
        }
        if (existing != null && existing.second == envHash) return emptyList()
        // Once the owner's account exists the token signs nobody in (#324), so changing it re-issues
        // nothing and disconnects no phone. The watermark is still moved, so a later start reads it as
        // unchanged.
        if (existing != null && signIn.exists()) {
            conn.prepareStatement("UPDATE owner_token SET token=?, bootstrap_token=?, updated_at=? WHERE id=1").use { ps ->
                ps.setString(1, envHash); ps.setString(2, envHash); ps.setLong(3, clock()); ps.executeUpdate()
            }
            return emptyList()
        }
        // A token the operator sets is a re-issue like the emailed one, and disconnects every paired
        // phone in the same transaction (#186). A first start has none to disconnect.
        return conn.inTransaction {
            val revoked = devices.revokeAllLocked(devices.ownerId)
            conn.prepareStatement(
                "INSERT INTO owner_token(id, token, bootstrap_token, updated_at) VALUES (1,?,?,?) " +
                    "ON CONFLICT(id) DO UPDATE SET token=excluded.token, bootstrap_token=excluded.bootstrap_token, updated_at=excluded.updated_at",
            ).use { ps ->
                ps.setString(1, envHash); ps.setString(2, envHash); ps.setLong(3, clock()); ps.executeUpdate()
            }
            revoked
        }
    }

    /**
     * The digest of the currently accepted owner/bearer token — what
     * [com.daymark.companion.auth.AuthGuard] is constructed and [rotated][com.daymark.companion.auth.AuthGuard.rotate]
     * with at boot. Never the plaintext token: only [Secrets.tokenHash] of it is
     * ever persisted, so there is no plaintext here to return.
     */
    fun currentTokenHash(): String = synchronized(lock) {
        conn.prepareStatement("SELECT token FROM owner_token WHERE id=1").use { ps ->
            ps.executeQuery().use { rs -> rs.next(); rs.getString(1) }
        }
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
     * A null [email] disables every notice, the security receipt of a recovery included.
     *
     * The email is normalized to lowercase before storage: addresses are near-universally treated
     * case-insensitively, and one spelling keeps the stored value comparable.
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

    override fun close() = synchronized(lock) { conn.close() }

    companion object {
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
                // Version 2: the owner's id, the phones registered to it, the codes that pair them, and
                // the nonces signed requests have used (#186, #189). DeviceKeyStore says what each holds.
                DeviceKeyStore.TABLES,
                // Version 3: the owner's own account, its sessions and the CLI's credentials (#324).
                OwnerSignIn.TABLES,
                // Version 4: the owner's recovery key and the challenges a recovery signs (#325).
                OwnerSignIn.RECOVERY_TABLES,
            ),
        )
    }
}
