package com.daymark.companion.storage

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

/**
 * Why the key-document store refused. The message is the kind's name and nothing else, so an error
 * body or a log line built from it carries no content, no path and no identifier.
 */
class KeyDocumentException(val kind: Kind, cause: Throwable? = null) : Exception(kind.name, cause) {
    enum class Kind {
        /** The body is not one JSON object in UTF-8. */
        NOT_A_JSON_OBJECT,

        /** The body nests deeper than [KeyDocumentStore.WRAPPED_KEY_MAX_DEPTH]. */
        TOO_DEEP,

        /** The body is over its document's limit. */
        TOO_LARGE,

        /** A new version numbered below 2: version 1 is written only by [KeyDocumentStore.create]. */
        BAD_VERSION,

        /** A create, when a wrapped key already exists. */
        EXISTS,

        /** A new version that is not the one after the newest: another device wrote first, or there is no wrapped key yet. */
        NOT_NEXT,

        /** The key parameters, read or written once a wrapped key exists. */
        SUPERSEDED,

        /** The wrapped key has as many versions as the store keeps. */
        FULL,

        /** The volume refused a read or a write. */
        IO,
    }
}

/**
 * The owner's key documents (#258): the published key parameters, and the wrapped key that
 * supersedes them. docs/SYNC_PROTOCOL.md §1.2 describes both documents and §2 their routes.
 *
 * THE WRAPPED KEY is the owner's master key locked twice, once under the passphrase and once under
 * the recovery code (companion/web/src/lib/recovery/dataKey.ts), so that either secret opens it from
 * any device. The server cannot open it and vouches for nothing in it: it keeps the bytes exactly as
 * they were sent, and checks only that they are one JSON object of at most [WRAPPED_KEY_MAX_BYTES],
 * nested at most [WRAPPED_KEY_MAX_DEPTH] deep.
 *
 *  - VERSIONS ARE INSERT-ONLY. Version 1 is written once, by [create]; each change after it, a new
 *    passphrase or a new recovery code, is the next version, by [append]. No version is ever updated
 *    or deleted. A write names the version it makes and only the one after the newest is taken, so
 *    of two devices that read the same newest version, one writes and the other is refused: two
 *    devices cannot both mint a master, and no change lands on a version its writer never saw. The
 *    refusal is SQLite's. The insert carries the rule in its WHERE clause and the primary key refuses
 *    a version that exists, so nothing can slip between a check and a write.
 *  - ONLY THE NEWEST VERSION IS READ. The older ones stay in the file and are never served, so a
 *    changed passphrase or recovery code opens nothing this server hands out. It still opens the
 *    older versions in a copy of the volume or a backup: changing a secret retires it against the
 *    live server, not against copies (#297 is the real retirement).
 *  - VERSIONS ARE CAPPED at [maxVersions]. Each is a free insert to whoever holds the owner's token,
 *    and a bounded human act, changing a passphrase, must not become unbounded growth on the volume.
 *
 * THE KEY PARAMETERS are superseded by presence. While no wrapped key exists they are read and
 * written as they always were; from the moment one exists, [getKeyparams] and [putKeyparams] refuse
 * whoever asks, and `keyparams.json` stays as it was, never deleted and never overwritten. Without
 * this a passphrase change would retire nothing, because the old passphrase and the still-published
 * salt reproduce the master. Which document is current is decided here, under the one lock every
 * read and write takes, and never by a client remembering to delete one.
 *
 * Both documents live at the root of the data directory: [DB_FILE] and [KEYPARAMS_FILE].
 */
class KeyDocumentStore(
    dataDir: String,
    private val maxVersions: Long = MAX_WRAPPED_KEY_VERSIONS,
) : AutoCloseable {

    private val root: Path = Path.of(dataDir).toAbsolutePath().normalize()
    private val tmpDir: Path = root.resolve("tmp")
    private val keyparamsFile: Path = root.resolve(KEYPARAMS_FILE)
    private val lock = Any()
    private val conn: Connection

    init {
        Files.createDirectories(tmpDir)
        Class.forName("org.sqlite.JDBC")
        conn = DriverManager.getConnection("jdbc:sqlite:${root.resolve(DB_FILE)}")
        conn.createStatement().use { st ->
            st.execute("PRAGMA journal_mode=WAL")
            // FULL, as in BlobStore: a device told 201 goes on to derive an identity from the master
            // it just wrapped, and a first-run master exists nowhere else. A commit that a power cut
            // can take back is a master nobody can open again.
            st.execute("PRAGMA synchronous=FULL")
            st.execute(
                """
                CREATE TABLE IF NOT EXISTS wrapped_key (
                    version  INTEGER NOT NULL PRIMARY KEY CHECK (version >= 1),
                    document BLOB    NOT NULL
                )
                """.trimIndent(),
            )
        }
    }

    /** The document a reader opens with: the newest wrapped key, else the key parameters, else none. */
    sealed interface Current {
        class Wrapped(val version: Long, val document: ByteArray) : Current
        class Keyparams(val document: ByteArray) : Current
        data object None : Current
    }

    fun current(): Current = synchronized(lock) {
        guarded {
            val newest = newestLocked()
            when {
                newest != null -> Current.Wrapped(newest.first, newest.second)
                else -> readKeyparamsLocked()?.let { Current.Keyparams(it) } ?: Current.None
            }
        }
    }

    /** Write version 1. Refused with [KeyDocumentException.Kind.EXISTS] once any version exists. */
    fun create(document: ByteArray): Long = synchronized(lock) {
        requireDocument(document)
        guarded {
            if (!insertIfNextLocked(1, document)) throw KeyDocumentException(KeyDocumentException.Kind.EXISTS)
        }
        1L
    }

    /**
     * Write [version], a change to the wrapped key. Taken only when it is the one after the newest,
     * which is how a writer says which version it changed; refused with
     * [KeyDocumentException.Kind.NOT_NEXT] otherwise, including when no wrapped key exists.
     */
    fun append(version: Long, document: ByteArray): Long = synchronized(lock) {
        if (version < 2) throw KeyDocumentException(KeyDocumentException.Kind.BAD_VERSION)
        requireDocument(document)
        guarded {
            if (version > maxVersions) {
                val full = version == newestVersionLocked() + 1
                throw KeyDocumentException(if (full) KeyDocumentException.Kind.FULL else KeyDocumentException.Kind.NOT_NEXT)
            }
            if (!insertIfNextLocked(version, document)) throw KeyDocumentException(KeyDocumentException.Kind.NOT_NEXT)
        }
        version
    }

    /**
     * The key parameters, or null when none were published. Refused with
     * [KeyDocumentException.Kind.SUPERSEDED] once a wrapped key exists.
     */
    fun getKeyparams(): ByteArray? = synchronized(lock) {
        guarded {
            if (newestVersionLocked() > 0) throw KeyDocumentException(KeyDocumentException.Kind.SUPERSEDED)
            readKeyparamsLocked()
        }
    }

    /**
     * Publish the key parameters, replacing any published before, until a wrapped key exists; refused
     * with [KeyDocumentException.Kind.SUPERSEDED] from then on, leaving the file as it was.
     */
    fun putKeyparams(bytes: ByteArray): Unit = synchronized(lock) {
        guarded {
            if (newestVersionLocked() > 0) throw KeyDocumentException(KeyDocumentException.Kind.SUPERSEDED)
            if (bytes.size > KEYPARAMS_MAX_BYTES) throw KeyDocumentException(KeyDocumentException.Kind.TOO_LARGE)
            var tmp: Path? = null
            try {
                tmp = Files.createTempFile(tmpDir, "kp", ".tmp")
                // fsync before the move, so a power cut leaves the old file or the new one, never a torn one.
                FileOutputStream(tmp.toFile()).use { out ->
                    out.write(bytes)
                    out.flush()
                    out.fd.sync()
                }
                Files.move(tmp, keyparamsFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally {
                tmp?.let { runCatching { Files.deleteIfExists(it) } }
            }
        }
    }

    override fun close() {
        synchronized(lock) { conn.close() }
    }

    /**
     * Insert [version] only when it is the one after the newest (1 when there is none). One
     * statement, so the rule and the write are one step for SQLite; OR IGNORE turns the primary key's
     * refusal of an existing version into the same "not inserted".
     */
    private fun insertIfNextLocked(version: Long, document: ByteArray): Boolean =
        conn.prepareStatement(
            "INSERT OR IGNORE INTO wrapped_key(version, document) " +
                "SELECT ?, ? WHERE ? = (SELECT COALESCE(MAX(version), 0) FROM wrapped_key) + 1",
        ).use { ps ->
            ps.setLong(1, version)
            ps.setBytes(2, document)
            ps.setLong(3, version)
            ps.executeUpdate() == 1
        }

    private fun newestLocked(): Pair<Long, ByteArray>? =
        conn.prepareStatement("SELECT version, document FROM wrapped_key ORDER BY version DESC LIMIT 1").use { ps ->
            ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) to rs.getBytes(2) else null }
        }

    /** The newest version, or 0 when there is no wrapped key. */
    private fun newestVersionLocked(): Long =
        conn.createStatement().use { st ->
            st.executeQuery("SELECT COALESCE(MAX(version), 0) FROM wrapped_key").use { rs -> if (rs.next()) rs.getLong(1) else 0L }
        }

    private fun readKeyparamsLocked(): ByteArray? =
        if (Files.exists(keyparamsFile)) Files.readAllBytes(keyparamsFile) else null

    /** The volume's failures, as this store's own refusal: a route answers it without a stack trace. */
    private inline fun <T> guarded(block: () -> T): T =
        try {
            block()
        } catch (e: IOException) {
            throw KeyDocumentException(KeyDocumentException.Kind.IO, e)
        } catch (e: SQLException) {
            throw KeyDocumentException(KeyDocumentException.Kind.IO, e)
        }

    companion object {
        /** The wrapped key's versions, in the data directory. */
        const val DB_FILE = "wrapped-key.db"

        /** The key parameters, in the data directory. The path they have always had. */
        const val KEYPARAMS_FILE = "keyparams.json"

        /**
         * The largest wrapped-key document taken. The web's two-slot document is 463 bytes, and each
         * further slot about 222 (companion/web/src/lib/recovery/dataKey.ts): this holds some seventy
         * slots, room for every kind the format anticipates, and at the version cap it bounds the
         * file at 16 MiB.
         */
        const val WRAPPED_KEY_MAX_BYTES = 16 * 1024

        /** The largest key-parameters document taken, as it always was. */
        const val KEYPARAMS_MAX_BYTES = 4096

        /** Versions kept before a change is refused: one change a week for nineteen years. */
        const val MAX_WRAPPED_KEY_VERSIONS = 1_000L

        /**
         * The deepest nesting taken. The web's document nests four deep; the parser recurses once per
         * level, and 16 KiB of brackets is deep enough to overflow a thread's stack, so the depth is
         * bounded before anything is parsed.
         */
        const val WRAPPED_KEY_MAX_DEPTH = 32

        /**
         * Whether [document] is one JSON object in strict UTF-8, within [WRAPPED_KEY_MAX_BYTES] and
         * [WRAPPED_KEY_MAX_DEPTH]; the only check the server makes of a wrapped key. A decoder that
         * replaced malformed bytes would pass a body that is not UTF-8, so this one refuses them.
         */
        fun requireDocument(document: ByteArray) {
            if (document.size > WRAPPED_KEY_MAX_BYTES) throw KeyDocumentException(KeyDocumentException.Kind.TOO_LARGE)
            val text = try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(document))
                    .toString()
            } catch (_: CharacterCodingException) {
                throw KeyDocumentException(KeyDocumentException.Kind.NOT_A_JSON_OBJECT)
            }
            if (nestsDeeperThan(text, WRAPPED_KEY_MAX_DEPTH)) throw KeyDocumentException(KeyDocumentException.Kind.TOO_DEEP)
            // SerializationException is an IllegalArgumentException, so this catches every refusal the
            // parser makes, and turns none of them into a message that could echo the body.
            val parsed = try {
                Json.parseToJsonElement(text)
            } catch (_: IllegalArgumentException) {
                null
            }
            if (parsed !is JsonObject) throw KeyDocumentException(KeyDocumentException.Kind.NOT_A_JSON_OBJECT)
        }

        /**
         * Whether [text] opens more than [limit] arrays and objects inside one another, counting only
         * brackets outside strings, as JSON delimits them. Every level the parser recurses into is one
         * this counts, so a text this passes cannot take the parser deeper than [limit].
         */
        private fun nestsDeeperThan(text: String, limit: Int): Boolean {
            var depth = 0
            var inString = false
            var escaped = false
            for (c in text) {
                if (inString) {
                    when {
                        escaped -> escaped = false
                        c == '\\' -> escaped = true
                        c == '"' -> inString = false
                    }
                } else {
                    when (c) {
                        '"' -> inString = true
                        '{', '[' -> if (++depth > limit) return true
                        '}', ']' -> depth--
                    }
                }
            }
            return false
        }
    }
}
