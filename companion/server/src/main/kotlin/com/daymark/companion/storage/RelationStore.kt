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
    enum class Kind { BAD_NAME, CONFLICT, TOO_OLD, TOO_LARGE, QUOTA, DISK_FULL, NOT_FOUND, SETTING_KEY_NOT_ALLOWED }
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
            st.execute("PRAGMA synchronous=NORMAL")
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
        }
    }

    fun put(
        relRef: String,
        channel: Channel,
        lineage: String,
        version: Long,
        bytes: ByteArray,
        settingKey: String?,
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
        try {
            val dir = relDir.resolve(relRef).resolve(channel.wire).resolve(lineage)
            Files.createDirectories(dir)
            val target = dir.resolve("$version.blob")
            val tmp = Files.createTempFile(tmpDir, "put", ".tmp")
            Files.write(tmp, bytes)
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            throw RelationStoreException("disk write failed: ${e.message}", RelationStoreException.Kind.DISK_FULL)
        }

        conn.prepareStatement(
            "INSERT INTO rel_blobs(rel_ref, channel, lineage, version, size, content_hash, setting_key, created_at) VALUES (?,?,?,?,?,?,?,?)",
        ).use { ps ->
            ps.setString(1, relRef)
            ps.setString(2, channel.wire)
            ps.setString(3, lineage)
            ps.setLong(4, version)
            ps.setLong(5, bytes.size.toLong())
            ps.setString(6, hash)
            if (settingKey != null) ps.setString(7, settingKey) else ps.setNull(7, java.sql.Types.VARCHAR)
            ps.setLong(8, now)
            ps.executeUpdate()
        }

        pruneLocked(relRef, channel, lineage)
        RelMeta(version, bytes.size.toLong(), hash, settingKey, now)
    }

    fun fetch(relRef: String, channel: Channel, lineage: String, version: Long): ByteArray = synchronized(lock) {
        requireName(relRef)
        requireName(lineage)
        if (!exists(relRef, channel, lineage, version)) {
            throw RelationStoreException("not found", RelationStoreException.Kind.NOT_FOUND)
        }
        val file = relDir.resolve(relRef).resolve(channel.wire).resolve(lineage).resolve("$version.blob")
        if (!file.exists()) throw RelationStoreException("not found", RelationStoreException.Kind.NOT_FOUND)
        Files.readAllBytes(file)
    }

    fun fetchCurrent(relRef: String, channel: Channel, lineage: String): Pair<Long, ByteArray> = synchronized(lock) {
        requireName(relRef)
        requireName(lineage)
        val v = highestVersion(relRef, channel, lineage)
            ?: throw RelationStoreException("not found", RelationStoreException.Kind.NOT_FOUND)
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
