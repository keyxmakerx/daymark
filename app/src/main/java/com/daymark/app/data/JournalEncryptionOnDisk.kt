package com.daymark.app.data

import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/*
 * THE HALVES OF THE MIGRATION THAT TOUCH REAL FILES AND REAL SQLCIPHER.
 *
 * `JournalEncryptionMigration` is the sequence and runs in a JVM test. This file is the two
 * implementations it sits on, and NOTHING HERE HAS EVER RUN. There is no Android SDK on the
 * development machine and no device in CI, so the furthest this gets is compiled. The instrumented
 * test in app/src/androidTest exercises it against a seeded plaintext database — and CI compiles
 * that test without ever running it, because running it needs a phone.
 *
 * So: before any release carrying this, somebody has to install it on a device that has a journal
 * on it, having exported a backup first, and confirm that the entries are all still there.
 */

/** The native library sqlcipher-android does not load for you. */
internal object SqlCipherLibrary {
    private var loaded = false

    @Synchronized
    fun ensureLoaded() {
        if (loaded) return
        // sqlcipher-android ships libsqlcipher.so for four ABIs and binds its native methods
        // itself, but — unlike the older android-database-sqlcipher — it never calls loadLibrary.
        // Forgetting this is an UnsatisfiedLinkError on the first query, not at startup.
        System.loadLibrary("sqlcipher")
        loaded = true
    }
}

/** [DatabaseFiles] over an actual directory. */
class JournalFilesOnDisk(private val main: File) : DatabaseFiles {

    private val directory: File? = main.parentFile
    private val sibling = File(directory, main.name + JournalEncryptionMigration.SIBLING_SUFFIX)

    /** Where the encrypted copy is built. Handed to the exporter so the two agree on one path. */
    fun siblingFile(): File = sibling

    override val mainName: String = main.name

    override fun mainExists(): Boolean = main.isFile

    override fun mainHeader(length: Int): ByteArray? = try {
        RandomAccessFile(main, "r").use { file ->
            if (file.length() < length) {
                // A file too short to hold a header is not a plaintext database, and it is not a
                // partial one either — returning a zero-padded buffer would let it be compared
                // against the header and, in principle, match.
                null
            } else {
                val buffer = ByteArray(length)
                file.readFully(buffer)
                buffer
            }
        }
    } catch (_: IOException) {
        null
    }

    override fun siblingExists(): Boolean = sibling.isFile

    // Each of these answers "is it gone now", not "did I personally remove it". A file that was
    // already absent is a success for every caller here.
    override fun deleteMain(): Boolean = main.delete() || !main.exists()

    override fun deleteSidecar(suffix: String): Boolean {
        val file = File(directory, main.name + suffix)
        return file.delete() || !file.exists()
    }

    override fun deleteSibling(): Boolean = sibling.delete() || !sibling.exists()

    override fun renameSiblingOverMain(): Boolean = sibling.renameTo(main)

    override fun namesBesideTheDatabase(): List<String>? =
        // NOT `?: emptyList()`. File.list() returns null for a directory it cannot read, and
        // turning that into "nothing is there" would make the migration's final check report
        // success having looked at nothing.
        directory?.list()?.filter { it.startsWith(main.name) }

    override fun deleteBeside(name: String): Boolean {
        // Scoped to names beginning with the database's own, because that is what the caller was
        // given and this is the only place that turns one into a path.
        if (!name.startsWith(main.name)) return false
        val file = File(directory, name)
        return file.delete() || !file.exists()
    }
}

/**
 * [Exporter] over SQLCipher's `sqlcipher_export`.
 *
 * ## Why the plaintext database is opened by SQLCipher too
 *
 * `sqlcipher_export` copies between two databases attached to ONE connection, so the plaintext
 * original and the encrypted copy have to be reachable from the same handle. SQLCipher opens a
 * plaintext file when the key is the empty string — that is the documented migration path — so the
 * original is opened with `""` and the new one is attached with the data key.
 *
 * ## The key is spelled, not passed
 *
 * [rawKey] is the ASCII of `x'<64 hex>'`. Handed anything else, SQLCipher treats the bytes as a
 * PASSPHRASE and runs its own key derivation on every single open. See `DataKeyWraps.rawKeySpelling`.
 *
 * ## What is checked, and the one that is easy to forget
 *
 * Row counts per table and `PRAGMA integrity_check` are the obvious two. `user_version` is the
 * third and the one that would be missed: `sqlcipher_export` copies rows and not the schema
 * version, so without setting it by hand the new database claims version 0 and Room tries to run
 * every migration this app has ever had over data that already has them.
 */
class SqlCipherExporter(
    private val main: File,
    private val sibling: File,
    private val rawKey: ByteArray,
) : Exporter {

    private val keyText: String get() = String(rawKey, Charsets.US_ASCII)

    override fun plaintextTableCounts(): Map<String, Long>? = withPlaintext { tableCounts(it) }

    override fun plaintextUserVersion(): Int? = withPlaintext { it.version }

    override fun exportToSibling(): Boolean {
        // Never append to whatever an earlier attempt left. The orchestrator clears a stale sibling
        // before calling, and this is the second lock on the same door.
        if (sibling.exists() && !sibling.delete()) return false
        val ok = withPlaintext { database ->
            // Concatenated rather than interpolated, because the statement contains both kinds of
            // quote — a single-quoted path and a double-quoted x'...' key — and a template with
            // escaped quotes inside it is the sort of line that is read wrong by everybody after.
            val attach = "ATTACH DATABASE '" + sqlLiteral(sibling.absolutePath) +
                "' AS encrypted KEY \"" + keyText + "\""
            database.rawExecSQL(attach)
            try {
                database.rawExecSQL("SELECT sqlcipher_export('encrypted')")
                database.rawExecSQL("PRAGMA encrypted.user_version = ${database.version}")
            } finally {
                database.rawExecSQL("DETACH DATABASE encrypted")
            }
            true
        }
        if (ok != true) {
            // A half-written encrypted file is worse than none: the orchestrator would find a
            // sibling next time and could not tell it from a completed one.
            sibling.delete()
            return false
        }
        return true
    }

    override fun siblingTableCounts(): Map<String, Long>? = withSibling { tableCounts(it) }

    override fun siblingUserVersion(): Int? = withSibling { it.version }

    override fun siblingIntegrityOk(): Boolean = withSibling { database ->
        database.query("PRAGMA integrity_check").use { cursor ->
            // Any answer other than exactly one row saying "ok" is a failure, including no rows at
            // all — which is what a query that did not run looks like.
            cursor.moveToFirst() && cursor.count == 1 && cursor.getString(0) == "ok"
        }
    } ?: false

    /**
     * Row count per table, including Room's own `room_master_table` and `android_metadata`.
     *
     * Those two are deliberately not excluded. They are rows like any others, they must survive the
     * copy like any others, and `room_master_table` in particular carries the identity hash Room
     * checks on open — so a copy that lost it would fail at a much less helpful moment.
     */
    private fun tableCounts(database: SQLiteDatabase): Map<String, Long> {
        val names = mutableListOf<String>()
        database.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite\\_%' ESCAPE '\\'",
        ).use { cursor ->
            while (cursor.moveToNext()) names += cursor.getString(0)
        }
        val counts = LinkedHashMap<String, Long>()
        for (name in names) {
            // -1 for a count that could not be read, never 0. Zero is a real answer and would
            // compare equal to another table that also could not be read.
            // A table name from sqlite_master, quoted as an identifier with any embedded quote
            // doubled. These are Room's own table names and none of them needs it — which is
            // exactly why it would be left out and exactly why it is not.
            val quoted = "\"" + name.replace("\"", "\"\"") + "\""
            database.query("SELECT COUNT(*) FROM $quoted").use { cursor ->
                counts[name] = if (cursor.moveToFirst()) cursor.getLong(0) else -1L
            }
        }
        return counts
    }

    private fun <T> withPlaintext(block: (SQLiteDatabase) -> T): T? = open(main, "", block)

    private fun <T> withSibling(block: (SQLiteDatabase) -> T): T? = open(sibling, keyText, block)

    private fun <T> open(file: File, password: String, block: (SQLiteDatabase) -> T): T? {
        SqlCipherLibrary.ensureLoaded()
        return try {
            SQLiteDatabase.openOrCreateDatabase(file, password, null, null).use(block)
        } catch (_: RuntimeException) {
            // A wrong key, a corrupt file and a file that is not a database all arrive as some
            // SQLiteException, which is a RuntimeException. The orchestrator wants one answer —
            // "could not read it" — and turns that into a failure that destroys nothing.
            null
        }
    }

    /** A path inside a single-quoted SQL literal. Doubling the quote is the whole escape. */
    private fun sqlLiteral(path: String): String = path.replace("'", "''")
}
