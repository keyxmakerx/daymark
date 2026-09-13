package com.daymark.app

import android.database.sqlite.SQLiteDatabase as AndroidSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.daymark.app.data.JournalEncryptionMigration
import com.daymark.app.data.JournalFilesOnDisk
import com.daymark.app.data.MigrationOutcome
import com.daymark.app.data.SqlCipherExporter
import com.daymark.app.security.DataKeyWraps
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The real migration, on a real plaintext SQLite file, with real SQLCipher.
 *
 * ## READ THIS BEFORE TRUSTING ANYTHING ELSE IN THE AT-REST WORK
 *
 * **Nothing in CI runs this.** `build.yml` runs `assembleFossDebugAndroidTest`, which COMPILES the
 * instrumented sources and stops; running them needs a device or an emulator and no workflow starts
 * one. So a green CI badge proves that this file still compiles against the current code and proves
 * nothing whatsoever about whether the migration works.
 *
 * The JVM test beside it (`JournalEncryptionMigrationTest`) proves the SEQUENCE — what is verified
 * before anything is deleted, what survives every refusal, how a crash between the delete and the
 * rename is recovered — against fakes, and that does run everywhere. What it cannot prove is that
 * `sqlcipher_export` copies a real database, that `PRAGMA user_version` survives the trip, or that
 * the raw-key spelling is the one SQLCipher recognises. THIS file is where those three live, and
 * until somebody runs it on a phone they are unverified.
 *
 * Run it with `./gradlew connectedFossDebugAndroidTest` on a machine with a device attached.
 */
@RunWith(AndroidJUnit4::class)
class JournalEncryptionMigrationInstrumentedTest {

    private lateinit var directory: File
    private lateinit var main: File
    private val key = ByteArray(32) { (it * 5 + 1).toByte() }
    private val rawKey get() = DataKeyWraps.rawKeySpelling(key)

    @Before
    fun setUp() {
        // sqlcipher-android binds its native methods itself but never calls loadLibrary. The
        // production code does it in SqlCipherLibrary, which is `internal` to the main source set
        // and therefore not visible from here — androidTest is a separate compilation.
        System.loadLibrary("sqlcipher")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        directory = File(context.cacheDir, "at-rest-test-${System.nanoTime()}")
        directory.mkdirs()
        main = File(directory, "seeded.db")
    }

    /**
     * A plaintext database written by Android's OWN SQLite, not by SQLCipher.
     *
     * That distinction is the point of the test: what people have on their phones today was written
     * by the framework, and the migration has to be able to read it.
     */
    private fun seedPlaintext(entries: Int, userVersion: Int) {
        val database = AndroidSQLiteDatabase.openOrCreateDatabase(main, null)
        database.execSQL("CREATE TABLE entries (id INTEGER PRIMARY KEY, note TEXT, mood INTEGER)")
        database.execSQL("CREATE TABLE journal_entries (id INTEGER PRIMARY KEY, body TEXT)")
        database.beginTransaction()
        try {
            for (i in 1..entries) {
                database.execSQL(
                    "INSERT INTO entries (id, note, mood) VALUES (?, ?, ?)",
                    arrayOf<Any>(i, "a note with an apostrophe ' and a \" quote, #$i", i % 5 + 1),
                )
                database.execSQL(
                    "INSERT INTO journal_entries (id, body) VALUES (?, ?)",
                    arrayOf<Any>(i, "line one\nline two, entry $i"),
                )
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        database.version = userVersion
        database.close()
    }

    private fun migrate(): MigrationOutcome {
        val files = JournalFilesOnDisk(main)
        return JournalEncryptionMigration(files, SqlCipherExporter(main, files.siblingFile(), rawKey)).run()
    }

    @Test
    fun a_plaintext_journal_becomes_an_encrypted_one_with_every_row_intact() {
        seedPlaintext(entries = 250, userVersion = 17)
        val before = main.length()
        assertTrue("the seeded file is implausibly small", before > 4096)

        assertEquals(MigrationOutcome.Migrated, migrate())

        // The file is no longer a plaintext SQLite database: its first sixteen bytes are not the
        // header any more, because SQLCipher encrypts the header along with everything else.
        val header = main.readBytes().copyOf(JournalEncryptionMigration.SQLITE_HEADER.size)
        assertFalse(
            "the file still begins with the SQLite header, so it is not encrypted",
            header.contentEquals(JournalEncryptionMigration.SQLITE_HEADER),
        )

        // And it opens with the key, with every row and the schema version still there.
        val opened = SQLiteDatabase.openOrCreateDatabase(
            main, String(rawKey, Charsets.US_ASCII), null, null,
        )
        try {
            assertEquals(17, opened.version)
            opened.query("SELECT COUNT(*) FROM entries").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(250L, cursor.getLong(0))
            }
            opened.query("SELECT note FROM entries WHERE id = 42").use { cursor ->
                assertTrue(cursor.moveToFirst())
                // The awkward characters are here on purpose: they are what a SQL-building export
                // would mangle, and a person's journal is full of apostrophes.
                assertEquals("a note with an apostrophe ' and a \" quote, #42", cursor.getString(0))
            }
            opened.query("PRAGMA integrity_check").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("ok", cursor.getString(0))
            }
        } finally {
            opened.close()
        }
    }

    @Test
    fun the_plaintext_file_and_every_sidecar_are_gone_afterwards() {
        seedPlaintext(entries = 20, userVersion = 17)
        // Plant the sidecars a journal-mode database leaves. They hold recently written rows in the
        // clear, and a migration that left one would make the settings copy false on the day it
        // shipped while looking completely successful.
        for (suffix in listOf("-wal", "-shm", "-journal")) {
            File(directory, main.name + suffix).writeText("recent rows, in the clear")
        }

        assertEquals(MigrationOutcome.Migrated, migrate())

        assertEquals(
            "something plaintext survived beside the database",
            listOf(main.name),
            directory.list()!!.filter { it.startsWith(main.name) }.sorted(),
        )
    }

    @Test
    fun a_second_run_over_an_already_encrypted_file_does_nothing() {
        seedPlaintext(entries = 10, userVersion = 17)
        assertEquals(MigrationOutcome.Migrated, migrate())
        val after = main.readBytes()

        assertEquals(MigrationOutcome.NotNeeded, migrate())
        assertTrue("the second run rewrote the file", after.contentEquals(main.readBytes()))
    }

    @Test
    fun a_fresh_install_has_nothing_to_migrate() {
        assertEquals(MigrationOutcome.NotNeeded, migrate())
        assertFalse(main.exists())
    }

    @Test
    fun the_wrong_key_does_not_open_the_migrated_file() {
        // The one assertion that says the encryption is real rather than a rename.
        seedPlaintext(entries = 5, userVersion = 17)
        assertEquals(MigrationOutcome.Migrated, migrate())

        val wrong = DataKeyWraps.rawKeySpelling(ByteArray(32) { (it * 5 + 2).toByte() })
        var opened = false
        try {
            SQLiteDatabase.openOrCreateDatabase(main, String(wrong, Charsets.US_ASCII), null, null)
                .use { database ->
                    database.query("SELECT COUNT(*) FROM entries").use { it.moveToFirst() }
                    opened = true
                }
        } catch (_: RuntimeException) {
            // Expected: the file is not a database as far as this key is concerned.
        }
        assertFalse("the migrated file opened with the wrong key", opened)
    }
}
