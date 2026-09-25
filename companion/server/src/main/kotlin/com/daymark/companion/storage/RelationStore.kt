package com.daymark.companion.storage

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.sql.Connection
import java.sql.DriverManager
import kotlin.io.path.exists

/** Who writes a channel. The routes enforce it by role; the store budgets storage by it. */
enum class Writer { OWNER, CLINICIAN }

/** The four zero-knowledge per-relationship blob channels. */
enum class Channel(val wire: String, val writer: Writer) {
    GRANTS("grants", Writer.OWNER),
    ASSIGNMENTS("assignments", Writer.CLINICIAN),
    SHARES("shares", Writer.OWNER),
    GAMEPLANS("gameplans", Writer.CLINICIAN);

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

/**
 * Everything the ending rule ([RelationStore.hasEnded]) reads about one stored item. Every field
 * comes from the index rows themselves, so whatever asks the rule is reading the same facts.
 */
internal data class ItemFacts(
    val channel: Channel,
    val version: Long,
    /** The highest version stored in this item's lineage; a share below it has been replaced. */
    val newestVersion: Long,
    val createdAt: Long,
    /** The end the writer chose, or null when they chose none. */
    val expiry: Long?,
    val revoked: Boolean,
)

class RelationStoreException(message: String, val kind: Kind) : Exception(message) {
    enum class Kind {
        BAD_NAME, CONFLICT, TOO_OLD, TOO_LARGE, QUOTA, DISK_FULL, NOT_FOUND, SETTING_KEY_NOT_ALLOWED,

        /**
         * The item exists but has ended ([RelationStore.hasEnded]): the end the owner chose has
         * passed, or 90 days have, or a newer share replaced it, or the owner withdrew it.
         *
         * **One kind for all of them on purpose.** Whether the owner *actively withdrew*, published
         * a newer share, or simply let a deadline lapse is a social fact about the owner's intent,
         * and the server should not announce it to the party being restricted. Collapsing them here
         * rather than at the HTTP layer means the route physically cannot leak the difference —
         * there is no second value to accidentally map to a second status code. The owner can tell
         * a withdrawal apart from their own audit log, which is the correct place for it.
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
 * STRUCTURAL SETTING-ALLOWLIST: a `setting`-type assignment may carry a NON-SECRET routing tag
 * (X-Setting-Key). The store rejects any tag outside the fixed [SETTING_ALLOWLIST] constant
 * WITHOUT reading the (sealed) value. That keeps a stray string out of the index and guarantees
 * nothing about the setting: the tag is a second claim by the same author, the shipped clinician
 * client does not send it, and the setting itself is inside the sealed body. The check that
 * binds is the owner's, on the decrypted item. See docs/COMPANION_ASSIGNMENTS.md §2.2.
 *
 * EVERY ITEM BUT A GRANT ENDS, by one rule ([hasEnded], #332), and both read paths refuse an ended
 * item as GONE.
 */
class RelationStore(
    dataDir: String,
    private val maxBlobBytes: Long,
    private val maxVersions: Int,
    private val perRelQuotaBytes: Long,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : AutoCloseable {

    /*
     * EACH WRITING DIRECTION HAS ITS OWN BUDGET, carved out of the one configured quota: the
     * clinician's channels may hold at most a quarter of it, and the other three quarters are the
     * owner's alone. With a single shared budget, a clinician's own assignments and game plans could
     * fill it, and the owner's next grant or share to that relationship would be refused, including
     * a grant written to narrow what that clinician may do. Nothing one direction writes can now
     * refuse the other's. The total stays what the operator configured (DAYMARK_REL_QUOTA_BYTES).
     */
    private val clinicianQuotaBytes: Long = perRelQuotaBytes / 4
    private val ownerQuotaBytes: Long = perRelQuotaBytes - clinicianQuotaBytes

    private fun quotaFor(writer: Writer): Long = when (writer) {
        Writer.OWNER -> ownerQuotaBytes
        Writer.CLINICIAN -> clinicianQuotaBytes
    }

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
             * `expiry`  epoch ms of the end the writer chose, or NULL when they chose none: the
             *           non-share channels, and shares written before this column existed. A NULL
             *           never means "forever" — see [hasEnded].
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
                        "Refusing to start: without them the server cannot tell when an item has ended.",
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
         * The end the writer chose, in epoch ms, or null when they chose none.
         *
         * One input to [hasEnded], never the whole answer: whatever is recorded here, an item on
         * any channel but grants still ends [ITEM_LIFETIME_MS] after it was written (#332).
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
        if (usedBytesLocked(relRef, channel.writer) + bytes.size > quotaFor(channel.writer)) {
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
     * Refuse to serve an item that has ended ([hasEnded]).
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
     * A row with no recorded expiry, which an older build could write on the shares channel, is
     * not exempt: the rule measures the 90-day ceiling from `created_at`, which every row has.
     */
    private fun gateLocked(relRef: String, channel: Channel, lineage: String, version: Long) {
        val item = itemLocked(relRef, channel, lineage, version)
            ?: throw RelationStoreException("not found", RelationStoreException.Kind.NOT_FOUND)
        if (hasEnded(item, clock())) {
            throw RelationStoreException("no longer available", RelationStoreException.Kind.GONE)
        }
    }

    private fun itemLocked(relRef: String, channel: Channel, lineage: String, version: Long): ItemFacts? {
        conn.prepareStatement(
            "SELECT b.created_at, b.expiry, b.revoked, ($NEWEST_IN_LINEAGE) FROM rel_blobs b " +
                "WHERE b.rel_ref=? AND b.channel=? AND b.lineage=? AND b.version=?",
        ).use { ps ->
            ps.setString(1, relRef); ps.setString(2, channel.wire); ps.setString(3, lineage); ps.setLong(4, version)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return null
                val createdAt = rs.getLong(1)
                // getLong returns 0 for SQL NULL, so wasNull() must be consulted straight after the
                // read — otherwise a NULL expiry reads as epoch 0 and the item as ended in 1970.
                val expiry = rs.getLong(2).takeUnless { rs.wasNull() }
                val revoked = rs.getInt(3) != 0
                val newest = rs.getLong(4)
                return ItemFacts(channel, version, newest, createdAt, expiry, revoked)
            }
        }
    }

    /**
     * What a withdrawal did: rows marked, ciphertext files actually removed, and files that could
     * NOT be removed. The last number is the one that matters to an owner who just took something
     * back — "withdrawn" with a copy still on the volume is not the thing they asked for, and the
     * route reports it rather than rounding it to success.
     */
    data class RevokeOutcome(val marked: Int, val deleted: Int, val undeletable: Int)

    /**
     * Withdraw every version of a lineage.
     *
     * Marks all versions, not just the newest. A share version below the newest is already refused
     * as replaced ([hasEnded]), but its file is still on the volume, and a withdrawal is the owner
     * taking the whole lineage back now: every copy goes at once. Marking only the head would also
     * leave older versions to a rule that could change — the same partial-guard shape as the
     * original bug.
     *
     * The ciphertext is deleted too. Keeping withdrawn bytes on the volume is live exposure against
     * a compromised-server threat model for data the owner has explicitly taken back, and nothing
     * needs them: the index row remains, so version numbering and the audit trail are intact, and
     * [gateLocked] refuses before the file would ever be read. A file that will not delete is
     * COUNTED, not swallowed: the previous version of this wrapped both the listing and every
     * delete in `runCatching` and returned only the marked count, so a full volume, a permissions
     * slip, or a stray directory left "withdrawn" reading as complete while the bytes stayed. If the
     * directory cannot even be listed, every marked version is reported as undeletable, because
     * that is what is known.
     */
    fun revokeLineage(relRef: String, channel: Channel, lineage: String): RevokeOutcome = synchronized(lock) {
        requireName(relRef)
        requireName(lineage)
        val marked = conn.prepareStatement(
            "UPDATE rel_blobs SET revoked=1 WHERE rel_ref=? AND channel=? AND lineage=? AND revoked=0",
        ).use { ps ->
            ps.setString(1, relRef); ps.setString(2, channel.wire); ps.setString(3, lineage)
            ps.executeUpdate()
        }
        val dir = relDir.resolve(relRef).resolve(channel.wire).resolve(lineage)
        if (!Files.isDirectory(dir)) {
            // Nothing was ever written under this lineage (or it was already cleared): no copies to remove.
            return@synchronized RevokeOutcome(marked, deleted = 0, undeletable = 0)
        }
        val blobs = try {
            Files.list(dir).use { paths -> paths.filter { it.fileName.toString().endsWith(".blob") }.toList() }
        } catch (e: IOException) {
            return@synchronized RevokeOutcome(marked, deleted = 0, undeletable = marked)
        }
        var deleted = 0
        var undeletable = 0
        for (path in blobs) {
            try {
                if (Files.deleteIfExists(path)) deleted++
            } catch (e: IOException) {
                undeletable++
            }
        }
        RevokeOutcome(marked, deleted, undeletable)
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
        // No fallback to an older version: "current" stays MAX(version). Falling back would serve a
        // share the newest replaced, or one the owner also withdrew — the opposite of what
        // publishing anew and withdrawing mean.
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

    /** Bytes the channels written by [writer] hold for this relationship. */
    private fun usedBytesLocked(relRef: String, writer: Writer): Long {
        val channels = Channel.entries.filter { it.writer == writer }
        val marks = channels.joinToString(",") { "?" }
        val sql = "SELECT COALESCE(SUM(size),0) FROM rel_blobs WHERE rel_ref=? AND channel IN ($marks)"
        conn.prepareStatement(sql).use { ps ->
            ps.setString(1, relRef)
            channels.forEachIndexed { i, c -> ps.setString(i + 2, c.wire) }
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
         * The longest the server serves anything the owner and the clinician send each other: 90
         * days after it was written (#332, decided in #228). The sealing has no forward secrecy
         * (COMPANION_SECURITY.md §11), so time is the only limit on what a stolen clinician key
         * could open. The owner console must offer no end later than this (#339); its tests and
         * this server's tests each pin 90 days, and change together.
         */
        const val ITEM_LIFETIME_MS: Long = 90L * 24 * 60 * 60 * 1000

        /**
         * THE ONE RULE FOR WHEN A RELATIONSHIP ITEM HAS ENDED (#332). The read gate refuses what it
         * calls ended, and nothing else decides it: anything that must know whether an item has
         * ended asks this function rather than restating the rule.
         *
         * - A withdrawn item has ended, on every channel.
         * - A grant ends only at an end recorded for it, which the routes never record: the
         *   clinician needs the current grant for as long as the relationship lasts, and it is
         *   signed, not sealed (COMPANION_THERAPIST.md §5).
         * - A share version below the newest of its lineage has ended: publishing a new share ends
         *   the ones before it, so an owner who narrows a share has narrowed it. Read from the rows
         *   themselves, so it needs no column of its own, and it is not a withdrawal — it writes no
         *   `share.revoke` entry. Shares only: nothing says a newer assignment ends an older one.
         * - Anything else ends at the end its writer chose or [ITEM_LIFETIME_MS] after it was
         *   written, whichever comes first. A row with no recorded end — an assignment, a game plan,
         *   or a share an older build wrote — ends at the ceiling rather than never.
         *
         * Refusal is at `now >= end`, matching the client's `now < expiry`.
         */
        internal fun hasEnded(item: ItemFacts, now: Long): Boolean {
            if (item.revoked) return true
            if (item.channel == Channel.GRANTS) return item.expiry != null && now >= item.expiry
            if (item.channel == Channel.SHARES && item.version < item.newestVersion) return true
            val ceiling = item.createdAt + ITEM_LIFETIME_MS
            val end = if (item.expiry == null) ceiling else minOf(item.expiry, ceiling)
            return now >= end
        }

        /** The newest version of the lineage of the row aliased `b`, for [hasEnded]'s replacement test. */
        private const val NEWEST_IN_LINEAGE =
            "SELECT MAX(n.version) FROM rel_blobs n WHERE n.rel_ref=b.rel_ref AND n.channel=b.channel AND n.lineage=b.lineage"

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
