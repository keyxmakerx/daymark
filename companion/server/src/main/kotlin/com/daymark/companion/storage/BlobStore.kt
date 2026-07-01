package com.daymark.companion.storage

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import kotlin.io.path.exists

/** Non-secret metadata the server keeps about one stored ciphertext blob. */
data class SnapshotMeta(
    val lineage: String,
    val version: Long,
    val size: Long,
    val contentHash: String, // server-computed SHA-256 (hex) over the opaque blob
    val createdAt: Long,
)

class BlobStoreException(message: String, val kind: Kind) : Exception(message) {
    enum class Kind { BAD_NAME, CONFLICT, TOO_OLD, TOO_LARGE, QUOTA, DISK_FULL, NOT_FOUND }
}

/**
 * Zero-knowledge blob store: one opaque ciphertext file per snapshot (lineage, version),
 * plus a SQLite index of **non-secret metadata only** (no plaintext columns, ever). The
 * server never decrypts and never inspects blob contents beyond hashing the bytes.
 *
 * Append-only: a (lineage, version) that already exists is never overwritten. Retention
 * is keep-last-N per lineage. Blob paths are server-derived from a strict charset, so a
 * client-supplied name can never escape the data dir.
 */
class BlobStore(
    dataDir: String,
    private val maxBlobBytes: Long,
    private val maxVersions: Int,
    private val perTokenQuotaBytes: Long,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : AutoCloseable {

    private val root: Path = Path.of(dataDir).toAbsolutePath().normalize()
    private val blobsDir: Path = root.resolve("blobs")
    private val tmpDir: Path = root.resolve("tmp")
    private val lock = Any()
    private val conn: Connection

    init {
        Files.createDirectories(blobsDir)
        Files.createDirectories(tmpDir)
        Class.forName("org.sqlite.JDBC")
        conn = DriverManager.getConnection("jdbc:sqlite:${root.resolve("index.db")}")
        conn.createStatement().use { st ->
            st.execute("PRAGMA journal_mode=WAL")
            st.execute("PRAGMA synchronous=NORMAL")
            st.execute(
                """
                CREATE TABLE IF NOT EXISTS snapshots (
                    lineage      TEXT    NOT NULL,
                    version      INTEGER NOT NULL,
                    size         INTEGER NOT NULL,
                    content_hash TEXT    NOT NULL,
                    created_at   INTEGER NOT NULL,
                    PRIMARY KEY (lineage, version)
                )
                """.trimIndent(),
            )
        }
    }

    fun put(lineage: String, version: Long, bytes: ByteArray): SnapshotMeta = synchronized(lock) {
        requireValidName(lineage)
        if (version < 0) throw BlobStoreException("version must be >= 0", BlobStoreException.Kind.BAD_NAME)
        if (bytes.size.toLong() > maxBlobBytes) {
            throw BlobStoreException("blob exceeds MAX_BLOB_BYTES", BlobStoreException.Kind.TOO_LARGE)
        }
        if (exists(lineage, version)) {
            throw BlobStoreException("version already exists (append-only)", BlobStoreException.Kind.CONFLICT)
        }
        // Don't accept a version that prune would immediately delete (it would be a lie to
        // return 201 for it). Reject if maxVersions newer versions already exist.
        if (countVersionsAbove(lineage, version) >= maxVersions) {
            throw BlobStoreException("version below retention window", BlobStoreException.Kind.TOO_OLD)
        }
        val newTotal = usedBytesLocked() + bytes.size
        if (newTotal > perTokenQuotaBytes) {
            throw BlobStoreException("storage quota exceeded", BlobStoreException.Kind.QUOTA)
        }

        val hash = sha256Hex(bytes)
        val now = clock()
        try {
            val dir = blobsDir.resolve(lineage)
            Files.createDirectories(dir)
            val target = dir.resolve("$version.blob")
            val tmp = Files.createTempFile(tmpDir, "put", ".tmp")
            Files.write(tmp, bytes)
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            throw BlobStoreException("disk write failed: ${e.message}", BlobStoreException.Kind.DISK_FULL)
        }

        conn.prepareStatement(
            "INSERT INTO snapshots(lineage, version, size, content_hash, created_at) VALUES (?,?,?,?,?)",
        ).use { ps ->
            ps.setString(1, lineage)
            ps.setLong(2, version)
            ps.setLong(3, bytes.size.toLong())
            ps.setString(4, hash)
            ps.setLong(5, now)
            ps.executeUpdate()
        }

        pruneLocked(lineage)
        SnapshotMeta(lineage, version, bytes.size.toLong(), hash, now)
    }

    fun fetch(lineage: String, version: Long): ByteArray = synchronized(lock) {
        requireValidName(lineage)
        if (!exists(lineage, version)) {
            throw BlobStoreException("not found", BlobStoreException.Kind.NOT_FOUND)
        }
        val file = blobsDir.resolve(lineage).resolve("$version.blob")
        if (!file.exists()) throw BlobStoreException("not found", BlobStoreException.Kind.NOT_FOUND)
        Files.readAllBytes(file)
    }

    fun listVersions(lineage: String): List<SnapshotMeta> = synchronized(lock) {
        requireValidName(lineage)
        val out = mutableListOf<SnapshotMeta>()
        conn.prepareStatement(
            "SELECT version, size, content_hash, created_at FROM snapshots WHERE lineage=? ORDER BY version ASC",
        ).use { ps ->
            ps.setString(1, lineage)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    out += SnapshotMeta(lineage, rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getLong(4))
                }
            }
        }
        out
    }

    fun listLineages(): List<String> = synchronized(lock) {
        val out = mutableListOf<String>()
        conn.createStatement().use { st ->
            st.executeQuery("SELECT DISTINCT lineage FROM snapshots ORDER BY lineage ASC").use { rs ->
                while (rs.next()) out += rs.getString(1)
            }
        }
        out
    }

    /**
     * Non-secret per-owner key parameters (KDF salt + params) so any reader with the
     * passphrase can derive the same key. Small, overwrite-allowed, opaque to the server.
     */
    fun putKeyparams(bytes: ByteArray) = synchronized(lock) {
        if (bytes.size > 4096) throw BlobStoreException("keyparams too large", BlobStoreException.Kind.TOO_LARGE)
        val tmp = Files.createTempFile(tmpDir, "kp", ".tmp")
        Files.write(tmp, bytes)
        Files.move(tmp, root.resolve("keyparams.json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    fun getKeyparams(): ByteArray? = synchronized(lock) {
        val f = root.resolve("keyparams.json")
        if (f.exists()) Files.readAllBytes(f) else null
    }

    fun usedBytes(): Long = synchronized(lock) { usedBytesLocked() }

    private fun usedBytesLocked(): Long {
        conn.createStatement().use { st ->
            st.executeQuery("SELECT COALESCE(SUM(size), 0) FROM snapshots").use { rs ->
                return if (rs.next()) rs.getLong(1) else 0L
            }
        }
    }

    private fun countVersionsAbove(lineage: String, version: Long): Int {
        conn.prepareStatement("SELECT COUNT(*) FROM snapshots WHERE lineage=? AND version>?").use { ps ->
            ps.setString(1, lineage)
            ps.setLong(2, version)
            ps.executeQuery().use { rs -> return if (rs.next()) rs.getInt(1) else 0 }
        }
    }

    private fun exists(lineage: String, version: Long): Boolean {
        conn.prepareStatement("SELECT 1 FROM snapshots WHERE lineage=? AND version=?").use { ps ->
            ps.setString(1, lineage)
            ps.setLong(2, version)
            ps.executeQuery().use { rs -> return rs.next() }
        }
    }

    /** Keep only the newest [maxVersions] versions of [lineage]; hard-delete older blob bytes. */
    private fun pruneLocked(lineage: String) {
        val versions = mutableListOf<Long>()
        conn.prepareStatement("SELECT version FROM snapshots WHERE lineage=? ORDER BY version DESC").use { ps ->
            ps.setString(1, lineage)
            ps.executeQuery().use { rs -> while (rs.next()) versions += rs.getLong(1) }
        }
        if (versions.size <= maxVersions) return
        val toDelete = versions.drop(maxVersions)
        for (v in toDelete) {
            try {
                Files.deleteIfExists(blobsDir.resolve(lineage).resolve("$v.blob"))
            } catch (_: IOException) { /* best-effort; index row removal below is the source of truth */ }
            conn.prepareStatement("DELETE FROM snapshots WHERE lineage=? AND version=?").use { ps ->
                ps.setString(1, lineage)
                ps.setLong(2, v)
                ps.executeUpdate()
            }
        }
    }

    override fun close() {
        synchronized(lock) { conn.close() }
    }

    companion object {
        private val NAME = Regex("^[A-Za-z0-9_-]{1,64}$")

        /** Server-side validation: lineage ids must be a strict charset so a derived path can never escape. */
        fun requireValidName(name: String) {
            if (!NAME.matches(name)) {
                throw BlobStoreException("invalid lineage id", BlobStoreException.Kind.BAD_NAME)
            }
        }

        fun sha256Hex(bytes: ByteArray): String {
            val md = MessageDigest.getInstance("SHA-256")
            return md.digest(bytes).joinToString("") { "%02x".format(it) }
        }
    }
}
