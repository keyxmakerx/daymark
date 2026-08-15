package com.daymark.companion.storage

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.sql.Connection
import java.sql.DriverManager
import kotlin.io.path.exists

/** The four zero-knowledge per-relationship blob channels. */
enum class Channel(val wire: String) {
    GRANTS("grants"),
    ASSIGNMENTS("assignments"),
    SHARES("shares"),
    GAMEPLANS("gameplans");

    companion object {
        fun fromWire(s: String): Channel? = entries.firstOrNull { it.wire == s }
    }
}

data class RelMeta(
    val version: Long,
    val size: Long,
    val contentHash: String,
    val settingKey: String?,
    val createdAt: Long,
)

class RelationStoreException(message: String, val kind: Kind) : Exception(message) {
    enum class Kind {
        BAD_NAME, CONFLICT, TOO_OLD, TOO_LARGE, QUOTA, DISK_FULL, NOT_FOUND, SETTING_KEY_NOT_ALLOWED,

        /**
         * The blob exists but must not be served: its expiry has passed, or the owner withdrew it.
         *
         * **One kind for both on purpose.** Whether the owner *actively withdrew* rather than simply
         * let a deadline lapse is a social fact about the owner's intent, and the server should not
         * announce it to the party being restricted. Collapsing them here rather than at the HTTP
         * layer means the route physically cannot leak the difference — there is no second value to
         * accidentally map to a second status code. The owner can tell them apart from their own
         * audit log, which is the correct place for it.
         */
        GONE,
    }
}

/**
 * Per-relationship, append-only, zero-knowledge blob store for the GRANT / ASSIGNMENT / SHARE
 * / GAME-PLAN channels. Mirrors [BlobStore] exactly (SQLite index of non-secret metadata only,
 * strict-charset path segments, keep-last-N prune, per-relationship quota, atomic write,
 * server-side SHA-256) — the server never decrypts and never inspects blob contents.
 *
 * STRUCTURAL SETTING-ALLOWLIST: a `setting`-type assignment carries a NON-SECRET routing tag
 * (X-Setting-Key). The store rejects any tag outside the fixed [SETTING_ALLOWLIST] constant
 * WITHOUT reading the (sealed) value. This is a redundant structural gate on top of the
 * client-side authoritative check; it guarantees no PIN/lock/encryption/network/backup key
 * can ever transit the setting channel. See docs/COMPANION_ASSIGNMENTS.md.
 */
class RelationStore(
    dataDir: String,
    private val maxBlobBytes: Long,
    private val maxVersions: Int,
    private val perRelQuotaBytes: Long,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : AutoCloseable {

    private val root: Path = Path.of(dataDir).toAbsolutePath().normalize()
    private val relDir: Path = root.resolve("rel")
    private val tmpDir: Path = root.resolve("tmp")
    private val lock = Any()
    private val conn: Connection

    init {
        Files.createDirectories(relDir)
        Files.createDirectories(tmpDir)
        Class.forName("org.sqlite.JDBC")
        conn = DriverManager.getConnection("jdbc:sqlite:${root.resolve("rel-index.db")}")
        conn.createStatement().use { st ->
            st.execute("PRAGMA journal_mode=WAL")
            st.execute("PRAGMA synchronous=FULL") // see BlobStore.init for why not NORMAL
            st.execute(
                """
                CREATE TABLE IF NOT EXISTS rel_blobs (
                    rel_ref      TEXT    NOT NULL,
                    channel      TEXT    NOT NULL,
                    lineage      TEXT    NOT NULL,
                    version      INTEGER NOT NULL,
                    size         INTEGER NOT NULL,
                    content_hash TEXT    NOT NULL,
                    setting_key  TEXT,
                    created_at   INTEGER NOT NULL,
                    PRIMARY KEY (rel_ref, channel, lineage, version)
                )
                """.trimIndent(),
            )

            /*
             * ACCESS STATE — added after the fact, so it arrives by ALTER for existing databases.
             *
             * `expiry`  epoch ms after which this blob must not be served. NULL means "no deadline
             *           recorded", which is only reachable for non-share channels and for rows
             *           written before this column existed (see the grandfather note on `gateLocked`).
             * `revoked` 1 once the owner withdraws the lineage. NOT NULL DEFAULT 0 because SQLite
             *           requires a default to add a NOT NULL column to a populated table.
             *
             * Both are NON-SECRET routing metadata, like `size` and `content_hash`. The server still
             * never decrypts anything; this is access control over ciphertext, not over keys.
             *
             * The `runCatching` swallows the duplicate-column error on every start after the first
             * (the same idiom as AuthStore's session columns). But swallowing ALL errors here would
             * be its own outage: a genuinely failed ALTER would leave construction succeeding and
             * every later query throwing, i.e. a 500 on every relationship request, misattributed.
             * So the columns are VERIFIED outside the catch, and a server that cannot see them
             * refuses to start. A failed boot is diagnosable; a silent 500 on every request is not.
             */
            runCatching { st.execute("ALTER TABLE rel_blobs ADD COLUMN expiry INTEGER") }
            runCatching { st.execute("ALTER TABLE rel_blobs ADD COLUMN revoked INTEGER NOT NULL DEFAULT 0") }
            try {
                st.executeQuery("SELECT expiry, revoked FROM rel_blobs LIMIT 0").close()
            } catch (e: java.sql.SQLException) {
                throw IllegalStateException(
                    "rel_blobs is missing the expiry/revoked columns and they could not be added: ${e.message}. " +
                        "Refusing to start: without them the server cannot enforce share expiry or revocation.",
                    e,
                )
            }
        }
    }

    fun put(
        relRef: String,
        channel: Channel,
        lineage: String,
        version: Long,
        bytes: ByteArray,
        settingKey: String?,
        /**
         * Epoch ms after which this blob must not be served, or null for "no deadline".
         *
         * Deliberately has NO default. A default would let every existing and future call site
         * compile unchanged and then fail at runtime on the one channel where it matters; the
         * compiler should be the thing that notices a new writer forgot to decide.
         */
        expiry: Long?,
    ): RelMeta = synchronized(lock) {
        requireName(relRef)
        requireName(lineage)
        if (version < 0) throw RelationStoreException("version must be >= 0", RelationStoreException.Kind.BAD_NAME)

        // STRUCTURAL allowlist gate — checked BEFORE touching the body, so a rejected setting
        // key never causes the server to read/store a byte of the (opaque) payload.
        if (settingKey != null && settingKey !in SETTING_ALLOWLIST) {
            throw RelationStoreException("setting key not allowlisted", RelationStoreException.Kind.SETTING_KEY_NOT_ALLOWED)
        }

        if (bytes.size.toLong() > maxBlobBytes) {
            throw RelationStoreException("blob exceeds MAX_BLOB_BYTES", RelationStoreException.Kind.TOO_LARGE)
        }
        if (exists(relRef, channel, lineage, version)) {
            throw RelationStoreException("version already exists (append-only)", RelationStoreException.Kind.CONFLICT)
        }
        if (countVersionsAbove(relRef, channel, lineage, version) >= maxVersions) {
            throw RelationStoreException("version below retention window", RelationStoreException.Kind.TOO_OLD)
        }
        if (usedBytesLocked(relRef) + bytes.size > perRelQuotaBytes) {
            throw RelationStoreException("storage quota exceeded", RelationStoreException.Kind.QUOTA)
        }

        val hash = BlobStore.sha256Hex(bytes)
        val now = clock()
        val dir = relDir.resolve(relRef).resolve(channel.wire).resolve(lineage)
        val target = dir.resolve("$version.blob")
        var tmp: java.nio.file.Path? = null
        try {
            Files.createDirectories(dir)
            tmp = Files.createTempFile(tmpDir, "put", ".tmp")
            java.io.FileOutputStream(tmp.toFile()).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }

            // Same ordering fix as BlobStore.put — see the long comment there. Index row inside a
            // transaction, blob moved into place, then commit; every failure path rolls back and
            // the finally clears the temp file. This path carries therapist-visible blobs, so a
            // half-written pair here is a relationship that reads as empty rather than as broken.
            conn.autoCommit = false
            try {
                conn.prepareStatement(
                    "INSERT INTO rel_blobs(rel_ref, channel, lineage, version, size, content_hash, setting_key, created_at, expiry, revoked) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,0)",
                ).use { ps ->
                    ps.setString(1, relRef)
                    ps.setString(2, channel.wire)
                    ps.setString(3, lineage)
                    ps.setLong(4, version)
                    ps.setLong(5, bytes.size.toLong())
                    ps.setString(6, hash)
                    if (settingKey != null) ps.setString(7, settingKey) else ps.setNull(7, java.sql.Types.VARCHAR)
                    ps.setLong(8, now)
                    // A republished lineage starts servable again: `revoked` is 0 on the new row.
                    // Withdrawing marks the versions that exist at that moment, not the lineage
                    // forever -- otherwise an owner could never share with that person again.
                    if (expiry != null) ps.setLong(9, expiry) else ps.setNull(9, java.sql.Types.INTEGER)
                    ps.executeUpdate()
                }
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                conn.commit()
            } catch (e: Throwable) {
                runCatching { conn.rollback() }
                throw e
            } finally {
                conn.autoCommit = true
            }
        } catch (e: IOException) {
            throw RelationStoreException("disk write failed: ${e.message}", RelationStoreException.Kind.DISK_FULL)
        } catch (e: java.sql.SQLException) {
            throw RelationStoreException("index write failed: ${e.message}", RelationStoreException.Kind.DISK_FULL)
        } finally {
            tmp?.let { runCatching { Files.deleteIfExists(it) } }
        }

        pruneLocked(relRef, channel, lineage)
        RelMeta(version, bytes.size.toLong(), hash, settingKey, now)
    }

    /**
     * Refuse to serve a blob whose deadline has passed or which the owner withdrew.
     *
     * ## Why this lives in the store rather than in the route
     *
     * It runs inside the same `synchronized(lock)` that already guards the row read, so a
     * concurrent [revokeLineage] cannot slip past a check that has already succeeded — there is no
     * window between "this is servable" and "these are the bytes". It is also the same query that
     * establishes existence, so the two answers cannot disagree. And any future route built on
     * [fetch] or [fetchCurrent] inherits it; a gate written in the routes layer would protect only
     * the two handlers that exist today. The original defect was precisely a guard applied to some
     * of the doors.
     *
     * ## The grandfather rule
     *
     * A NULL `expiry` never expires. Rows written before this column existed have one, and locking
     * every already-published share out on upgrade would be an outage delivered as a security fix.
     * That is bounded rather than open-ended because the PUT path now *requires* the expiry header
     * on the shares channel — so after this change, "no expiry recorded" can only mean "written by
     * an older build", never "written today without one".
     *
     * `revoked` has no such carve-out: it defaults to 0, which is exactly right for old rows.
     */
    private fun gateLocked(relRef: String, channel: Channel, lineage: String, version: Long) {
        conn.prepareStatement(
            "SELECT expiry, revoked FROM rel_blobs WHERE rel_ref=? AND channel=? AND lineage=? AND version=?",
        ).use { ps ->
            ps.setString(1, relRef); ps.setString(2, channel.wire); ps.setString(3, lineage); ps.setLong(4, version)
            ps.executeQuery().use { rs ->
                if (!rs.next()) throw RelationStoreException("not found", RelationStoreException.Kind.NOT_FOUND)
                if (rs.getInt(2) != 0) throw RelationStoreException("withdrawn", RelationStoreException.Kind.GONE)
                // getLong returns 0 for SQL NULL, so wasNull() must be consulted BEFORE the value is
                // used — otherwise a NULL expiry reads as epoch 0 and every grandfathered row is
                // instantly expired, which is the outage this rule exists to avoid.
                val expiry = rs.getLong(1)
                if (!rs.wasNull() && clock() >= expiry) {
                    throw RelationStoreException("expired", RelationStoreException.Kind.GONE)
                }
            }
        }
    }

    /**
     * Withdraw every version of a lineage. Returns how many rows were marked.
     *
     * Marks all versions, not just the newest: prior versions stay on disk up to the retention
     * window and are individually fetchable by `GET /{lineage}/{version}`, so withdrawing only the
     * head would leave the previous share readable — the same partial-guard shape as the original
     * bug.
     *
     * The ciphertext is deleted too, best-effort. Keeping withdrawn bytes on the volume is live
     * exposure against a compromised-server threat model for data the owner has explicitly taken
     * back, and nothing needs them: the index row remains, so version numbering and the audit trail
     * are intact, and [gateLocked] refuses before the file would ever be read.
     */
    fun revokeLineage(relRef: String, channel: Channel, lineage: String): Int = synchronized(lock) {
        requireName(relRef)
        requireName(lineage)
        val marked = conn.prepareStatement(
            "UPDATE rel_blobs SET revoked=1 WHERE rel_ref=? AND channel=? AND lineage=? AND revoked=0",
        ).use { ps ->
            ps.setString(1, relRef); ps.setString(2, channel.wire); ps.setString(3, lineage)
            ps.executeUpdate()
        }
        val dir = relDir.resolve(relRef).resolve(channel.wire).resolve(lineage)
        runCatching {
            Files.list(dir).use { paths ->
                paths.filter { it.fileName.toString().endsWith(".blob") }
                    .forEach { runCatching { Files.deleteIfExists(it) } }
            }
        }
        marked
    }

    fun fetch(relRef: String, channel: Channel, lineage: String, version: Long): ByteArray = synchronized(lock) {
        requireName(relRef)
        requireName(lineage)
        if (!exists(relRef, channel, lineage, version)) {
            throw RelationStoreException("not found", RelationStoreException.Kind.NOT_FOUND)
        }
        gateLocked(relRef, channel, lineage, version)
        val file = relDir.resolve(relRef).resolve(channel.wire).resolve(lineage).resolve("$version.blob")
        if (!file.exists()) throw RelationStoreException("not found", RelationStoreException.Kind.NOT_FOUND)
        Files.readAllBytes(file)
    }

    fun fetchCurrent(relRef: String, channel: Channel, lineage: String): Pair<Long, ByteArray> = synchronized(lock) {
        requireName(relRef)
        requireName(lineage)
        val v = highestVersion(relRef, channel, lineage)
            ?: throw RelationStoreException("not found", RelationStoreException.Kind.NOT_FOUND)
        // No fallback to an older version: "current" stays MAX(version). Falling back would serve
        // a previous share the owner also withdrew, which is the opposite of what withdrawing means.
        gateLocked(relRef, channel, lineage, v)
        val file = relDir.resolve(relRef).resolve(channel.wire).resolve(lineage).resolve("$v.blob")
        if (!file.exists()) throw RelationStoreException("not found", RelationStoreException.Kind.NOT_FOUND)
        v to Files.readAllBytes(file)
    }

    fun listLineages(relRef: String, channel: Channel): List<String> = synchronized(lock) {
        requireName(relRef)
        val out = mutableListOf<String>()
        conn.prepareStatement("SELECT DISTINCT lineage FROM rel_blobs WHERE rel_ref=? AND channel=? ORDER BY lineage ASC").use { ps ->
            ps.setString(1, relRef); ps.setString(2, channel.wire)
            ps.executeQuery().use { rs -> while (rs.next()) out += rs.getString(1) }
        }
        out
    }

    fun listVersions(relRef: String, channel: Channel, lineage: String): List<RelMeta> = synchronized(lock) {
        requireName(relRef)
        requireName(lineage)
        val out = mutableListOf<RelMeta>()
        conn.prepareStatement(
            "SELECT version, size, content_hash, setting_key, created_at FROM rel_blobs WHERE rel_ref=? AND channel=? AND lineage=? ORDER BY version ASC",
        ).use { ps ->
            ps.setString(1, relRef); ps.setString(2, channel.wire); ps.setString(3, lineage)
            ps.executeQuery().use { rs ->
                while (rs.next()) out += RelMeta(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getLong(5))
            }
        }
        out
    }

    private fun highestVersion(relRef: String, channel: Channel, lineage: String): Long? {
        conn.prepareStatement("SELECT MAX(version) FROM rel_blobs WHERE rel_ref=? AND channel=? AND lineage=?").use { ps ->
            ps.setString(1, relRef); ps.setString(2, channel.wire); ps.setString(3, lineage)
            ps.executeQuery().use { rs ->
                if (rs.next()) {
                    val v = rs.getLong(1)
                    return if (rs.wasNull()) null else v
                }
                return null
            }
        }
    }

    private fun usedBytesLocked(relRef: String): Long {
        conn.prepareStatement("SELECT COALESCE(SUM(size),0) FROM rel_blobs WHERE rel_ref=?").use { ps ->
            ps.setString(1, relRef)
            ps.executeQuery().use { rs -> return if (rs.next()) rs.getLong(1) else 0L }
        }
    }

    private fun countVersionsAbove(relRef: String, channel: Channel, lineage: String, version: Long): Int {
        conn.prepareStatement("SELECT COUNT(*) FROM rel_blobs WHERE rel_ref=? AND channel=? AND lineage=? AND version>?").use { ps ->
            ps.setString(1, relRef); ps.setString(2, channel.wire); ps.setString(3, lineage); ps.setLong(4, version)
            ps.executeQuery().use { rs -> return if (rs.next()) rs.getInt(1) else 0 }
        }
    }

    private fun exists(relRef: String, channel: Channel, lineage: String, version: Long): Boolean {
        conn.prepareStatement("SELECT 1 FROM rel_blobs WHERE rel_ref=? AND channel=? AND lineage=? AND version=?").use { ps ->
            ps.setString(1, relRef); ps.setString(2, channel.wire); ps.setString(3, lineage); ps.setLong(4, version)
            ps.executeQuery().use { rs -> return rs.next() }
        }
    }

    private fun pruneLocked(relRef: String, channel: Channel, lineage: String) {
        val versions = mutableListOf<Long>()
        conn.prepareStatement("SELECT version FROM rel_blobs WHERE rel_ref=? AND channel=? AND lineage=? ORDER BY version DESC").use { ps ->
            ps.setString(1, relRef); ps.setString(2, channel.wire); ps.setString(3, lineage)
            ps.executeQuery().use { rs -> while (rs.next()) versions += rs.getLong(1) }
        }
        if (versions.size <= maxVersions) return
        for (v in versions.drop(maxVersions)) {
            try {
                Files.deleteIfExists(relDir.resolve(relRef).resolve(channel.wire).resolve(lineage).resolve("$v.blob"))
            } catch (_: IOException) { /* best-effort */ }
            conn.prepareStatement("DELETE FROM rel_blobs WHERE rel_ref=? AND channel=? AND lineage=? AND version=?").use { ps ->
                ps.setString(1, relRef); ps.setString(2, channel.wire); ps.setString(3, lineage); ps.setLong(4, v)
                ps.executeUpdate()
            }
        }
    }

    override fun close() = synchronized(lock) { conn.close() }

    companion object {
        /**
         * The fixed server-side setting-key allowlist. Mirrors
         * companion/web/src/lib/assignments/types.ts SETTING_ALLOWLIST. Nothing
         * security/privacy/crypto (no PIN, lock, biometric, encryption, network, backup key)
         * can ever be here.
         */
        val SETTING_ALLOWLIST = setOf("visibleSelfChecks", "reminderTime", "reminderCadence", "theme")

        /** Public re-export of the shared SHA-256 hex helper, for route GET echo headers. */
        fun sha256HexPublic(bytes: ByteArray): String = BlobStore.sha256Hex(bytes)

        private val NAME = Regex("^[A-Za-z0-9_-]{1,64}$")
    }

    /** Strict charset for path segments, so a derived path can never escape the data dir. */
    private fun requireName(name: String) {
        if (!NAME.matches(name)) {
            throw RelationStoreException("invalid path segment", RelationStoreException.Kind.BAD_NAME)
        }
    }
}
