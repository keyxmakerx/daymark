package com.daymark.companion.storage

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/** Who performed the logged action. */
enum class AuditActor(val wire: String) { OWNER("owner"), THERAPIST("therapist") }

/**
 * The audit action taxonomy (COMPANION_SECURITY.md §9 / COMPANION_THERAPIST.md §10),
 * extend as needed. Every value names an EVENT, never content.
 */
enum class AuditAction(val wire: String) {
    AUTH_SUCCESS("auth.success"),
    AUTH_FAIL("auth.fail"),
    LOCKOUT("lockout"),
    ENROL_OK("enrol.ok"),
    SHARE_OPEN("share.open"),
    GAMEPLAN_OPEN("gameplan.open"),
    ASSIGNMENT_PUBLISH("assignment.publish"),
    GAMEPLAN_PUBLISH("gameplan.publish"),
    SESSION_EXPIRED("session.expired"),
}

data class AuditEvent(
    val seq: Long,
    val ts: Long,
    val relRef: String,
    val actor: String,
    val action: String,
    val objectRef: String?,
    val meta: Map<String, String>?,
    val entryHash: String,
)

/**
 * Owner-readable, append-only, metadata-only audit log of relationship access
 * (COMPANION_SECURITY.md §9 / COMPANION_THERAPIST.md §10). Callers must NEVER pass
 * plaintext, decrypted content, keys, TOTP codes, or which individual record was viewed —
 * only the event type, an opaque [objectRef] (channel-scoped lineage/version), and small
 * fixed non-content [meta] annotations (e.g. an acting credential id, or the source IP when
 * the operator opted in).
 *
 * Each entry is hash-chained: `entryHash = SHA-256(prevHash ‖ seq ‖ ts ‖ relRef ‖ actor ‖
 * action ‖ objectRef ‖ meta)`. This makes a stored entry's tampering/reordering detectable
 * (every later entry's hash would no longer verify), but it is **server-computed, not
 * therapist-signed** — it does not add non-repudiation, and it does NOT stop a hostile
 * server from simply never appending an event, or truncating the chain and serving a
 * shorter-but-internally-consistent history. See the honest retraction in
 * docs/COMPANION_SECURITY.md §9 (R12) — completeness is not provable, only internal
 * consistency of whatever is returned.
 */
class AuditStore(
    dataDir: String,
    private val retentionSeconds: Long = DEFAULT_RETENTION_SECONDS,
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
) : AutoCloseable {

    private val root: Path = Path.of(dataDir).toAbsolutePath().normalize()
    private val lock = Any()
    private val conn: Connection

    init {
        Files.createDirectories(root)
        Class.forName("org.sqlite.JDBC")
        conn = DriverManager.getConnection("jdbc:sqlite:${root.resolve("audit.db")}")
        conn.createStatement().use { st ->
            st.execute("PRAGMA journal_mode=WAL")
            st.execute("PRAGMA synchronous=NORMAL")
            st.execute(
                """
                CREATE TABLE IF NOT EXISTS audit_events (
                    rel_ref    TEXT    NOT NULL,
                    seq        INTEGER NOT NULL,
                    ts         INTEGER NOT NULL,
                    actor      TEXT    NOT NULL,
                    action     TEXT    NOT NULL,
                    object_ref TEXT,
                    meta       TEXT,
                    entry_hash TEXT    NOT NULL,
                    PRIMARY KEY (rel_ref, seq)
                )
                """.trimIndent(),
            )
        }
    }

    /** Append one entry, chained off this relationship's latest entry. Insert-only. */
    fun append(
        relRef: String,
        actor: AuditActor,
        action: AuditAction,
        objectRef: String? = null,
        meta: Map<String, String>? = null,
    ): AuditEvent = synchronized(lock) {
        requireName(relRef)
        val now = clock()
        val prevHash = lastHashLocked(relRef)
        val seq = lastSeqLocked(relRef) + 1
        val metaEncoded = meta?.let { canonicalMeta(it) }
        val entryHash = chainHash(prevHash, seq, now, relRef, actor.wire, action.wire, objectRef, metaEncoded)
        conn.prepareStatement(
            "INSERT INTO audit_events(rel_ref, seq, ts, actor, action, object_ref, meta, entry_hash) VALUES (?,?,?,?,?,?,?,?)",
        ).use { ps ->
            ps.setString(1, relRef)
            ps.setLong(2, seq)
            ps.setLong(3, now)
            ps.setString(4, actor.wire)
            ps.setString(5, action.wire)
            if (objectRef != null) ps.setString(6, objectRef) else ps.setNull(6, java.sql.Types.VARCHAR)
            if (metaEncoded != null) ps.setString(7, metaEncoded) else ps.setNull(7, java.sql.Types.VARCHAR)
            ps.setString(8, entryHash)
            ps.executeUpdate()
        }
        pruneExpiredLocked(relRef, now)
        AuditEvent(seq, now, relRef, actor.wire, action.wire, objectRef, meta, entryHash)
    }

    /** List newest-first. When [beforeSeq] is set, only entries strictly older than it. */
    fun list(relRef: String, beforeSeq: Long? = null, limit: Int = 50): List<AuditEvent> = synchronized(lock) {
        requireName(relRef)
        val cap = limit.coerceIn(1, MAX_PAGE_SIZE)
        val sql = if (beforeSeq != null) {
            "SELECT seq, ts, actor, action, object_ref, meta, entry_hash FROM audit_events " +
                "WHERE rel_ref=? AND seq<? ORDER BY seq DESC LIMIT ?"
        } else {
            "SELECT seq, ts, actor, action, object_ref, meta, entry_hash FROM audit_events " +
                "WHERE rel_ref=? ORDER BY seq DESC LIMIT ?"
        }
        conn.prepareStatement(sql).use { ps ->
            var idx = 1
            ps.setString(idx++, relRef)
            if (beforeSeq != null) ps.setLong(idx++, beforeSeq)
            ps.setInt(idx, cap)
            ps.executeQuery().use { rs ->
                val out = mutableListOf<AuditEvent>()
                while (rs.next()) {
                    out += AuditEvent(
                        seq = rs.getLong(1),
                        ts = rs.getLong(2),
                        relRef = relRef,
                        actor = rs.getString(3),
                        action = rs.getString(4),
                        objectRef = rs.getString(5),
                        meta = rs.getString(6)?.let(::parseMeta),
                        entryHash = rs.getString(7),
                    )
                }
                out
            }
        }
    }

    private fun lastSeqLocked(relRef: String): Long {
        conn.prepareStatement("SELECT MAX(seq) FROM audit_events WHERE rel_ref=?").use { ps ->
            ps.setString(1, relRef)
            ps.executeQuery().use { rs ->
                if (rs.next()) {
                    val v = rs.getLong(1)
                    if (!rs.wasNull()) return v
                }
                return 0L
            }
        }
    }

    private fun lastHashLocked(relRef: String): String {
        conn.prepareStatement("SELECT entry_hash FROM audit_events WHERE rel_ref=? ORDER BY seq DESC LIMIT 1").use { ps ->
            ps.setString(1, relRef)
            ps.executeQuery().use { rs -> return if (rs.next()) rs.getString(1) else GENESIS_HASH }
        }
    }

    /** Best-effort prune of entries past the retention window for this relationship. */
    private fun pruneExpiredLocked(relRef: String, now: Long) {
        if (retentionSeconds <= 0) return
        conn.prepareStatement("DELETE FROM audit_events WHERE rel_ref=? AND ts < ?").use { ps ->
            ps.setString(1, relRef)
            ps.setLong(2, now - retentionSeconds)
            ps.executeUpdate()
        }
    }

    override fun close() = synchronized(lock) { conn.close() }

    companion object {
        const val DEFAULT_RETENTION_SECONDS = 90L * 24 * 3600
        const val MAX_PAGE_SIZE = 200

        /** Fixed genesis hash (64 hex zeros) chained ahead of the first entry for a relationship. */
        private const val GENESIS_HASH = "0000000000000000000000000000000000000000000000000000000000000000"

        private val NAME = Regex("^[A-Za-z0-9_-]{1,64}$")

        private fun requireName(name: String) {
            require(NAME.matches(name)) { "invalid rel_ref" }
        }

        /** Deterministic, order-stable encoding of a small flat meta map (no nested values). */
        private fun canonicalMeta(meta: Map<String, String>): String =
            meta.toSortedMap().entries.joinToString(",") { (k, v) -> "$k=$v" }

        private fun parseMeta(encoded: String): Map<String, String> =
            if (encoded.isEmpty()) {
                emptyMap()
            } else {
                encoded.split(",").associate {
                    val parts = it.split("=", limit = 2)
                    parts[0] to (parts.getOrElse(1) { "" })
                }
            }

        private fun chainHash(
            prevHash: String,
            seq: Long,
            ts: Long,
            relRef: String,
            actor: String,
            action: String,
            objectRef: String?,
            metaEncoded: String?,
        ): String {
            val canonical = listOf(
                prevHash, seq.toString(), ts.toString(), relRef, actor, action,
                objectRef ?: "", metaEncoded ?: "",
            ).joinToString("|")
            return BlobStore.sha256Hex(canonical.toByteArray(Charsets.UTF_8))
        }
    }
}
