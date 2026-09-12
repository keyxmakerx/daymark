package com.daymark.app.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The migration that deletes somebody's journal, exercised against fakes until every branch of it
 * has been walked.
 *
 * ## What these tests are for
 *
 * One user, one phone, one file, and no second copy anywhere — `android:allowBackup="false"` means
 * the OS has not kept one either. If this code deletes the plaintext database at the wrong moment,
 * a year of somebody's entries is gone and nothing can bring them back. So the sequencing is not
 * asserted structurally or by reading the code: both fakes write every call into one shared log,
 * and the tests read that log.
 *
 * ## What they cannot be for
 *
 * The fakes are a set of strings standing in for a directory, and a map standing in for SQLCipher.
 * Everything about the ORDER of operations and every refusal is proved here for real. Nothing about
 * whether `sqlcipher_export` actually copies a Room database is — that lives in the instrumented
 * test, which CI compiles and never runs because there is no device. Do not read a green run here
 * as evidence that the migration works on a phone.
 */
class JournalEncryptionMigrationTest {

    private companion object {
        const val DB = "daymark.db"
        const val SIBLING = "daymark.db-encrypting"
        val COUNTS = mapOf("entries" to 412L, "journal_entries" to 88L, "assessments" to 9L)

        /** Everything either fake does that destroys something. */
        val DESTRUCTIVE = listOf("deleteMain", "deleteSidecar", "deleteBeside")

        /** Everything the orchestrator must have asked before it is allowed to destroy anything. */
        val VERIFICATIONS = listOf("siblingTableCounts", "siblingUserVersion", "siblingIntegrityOk")

        /**
         * True when no destructive call appears before the last verification.
         *
         * Shared by the real assertion and by its positive control below, so that the control is
         * exercising the same function and not a second implementation that happens to agree.
         */
        fun destructionComesAfterVerification(log: List<String>): Boolean {
            val firstDestructive = log.indexOfFirst { entry -> DESTRUCTIVE.any { entry.startsWith(it) } }
            val lastVerification = log.indexOfLast { it in VERIFICATIONS }
            if (firstDestructive < 0) return true
            return lastVerification in 0 until firstDestructive
        }
    }

    // ─── Fakes ─────────────────────────────────────────────────────────────────────────────────

    private val log = mutableListOf<String>()

    private inner class FakeFiles(initial: Set<String>) : DatabaseFiles {
        override val mainName = DB
        val present = initial.toMutableSet()
        var mainIsPlaintext = true
        var renameFails = false
        var deleteMainFails = false
        var deleteSiblingFails = false
        val undeletable = mutableSetOf<String>()

        override fun mainExists() = mainName in present

        override fun mainHeader(length: Int): ByteArray? {
            if (!mainExists()) return null
            return if (mainIsPlaintext) {
                JournalEncryptionMigration.SQLITE_HEADER.copyOf(length)
            } else {
                // An encrypted database begins with bytes indistinguishable from random.
                ByteArray(length) { (it * 37 + 11).toByte() }
            }
        }

        override fun siblingExists() = SIBLING in present

        override fun deleteMain(): Boolean {
            log += "deleteMain"
            if (deleteMainFails) return false
            return present.remove(mainName)
        }

        override fun deleteSidecar(suffix: String): Boolean {
            log += "deleteSidecar$suffix"
            val name = mainName + suffix
            if (name in undeletable) return false
            return present.remove(name)
        }

        override fun deleteSibling(): Boolean {
            log += "deleteSibling"
            if (deleteSiblingFails) return false
            return present.remove(SIBLING)
        }

        override fun renameSiblingOverMain(): Boolean {
            log += "rename"
            if (renameFails || SIBLING !in present) return false
            present.remove(SIBLING)
            present.add(mainName)
            return true
        }

        override fun namesBesideTheDatabase(): List<String> =
            present.filter { it.startsWith(mainName) }.sorted()

        override fun deleteBeside(name: String): Boolean {
            log += "deleteBeside:$name"
            if (name in undeletable) return false
            return present.remove(name)
        }
    }

    private inner class FakeExporter(private val files: FakeFiles) : Exporter {
        var plaintextCounts: Map<String, Long>? = COUNTS
        var plaintextVersion: Int? = 17
        var exportSucceeds = true
        var siblingCounts: Map<String, Long>? = COUNTS
        var siblingVersion: Int? = 17
        var integrityOk = true

        override fun plaintextTableCounts(): Map<String, Long>? {
            log += "plaintextTableCounts"
            return plaintextCounts
        }

        override fun plaintextUserVersion(): Int? {
            log += "plaintextUserVersion"
            return plaintextVersion
        }

        override fun exportToSibling(): Boolean {
            log += "exportToSibling"
            if (!exportSucceeds) return false
            files.present.add(SIBLING)
            return true
        }

        override fun siblingTableCounts(): Map<String, Long>? {
            log += "siblingTableCounts"
            return siblingCounts
        }

        override fun siblingUserVersion(): Int? {
            log += "siblingUserVersion"
            return siblingVersion
        }

        override fun siblingIntegrityOk(): Boolean {
            log += "siblingIntegrityOk"
            return integrityOk
        }
    }

    private fun world(vararg present: String): Pair<FakeFiles, FakeExporter> {
        val files = FakeFiles(present.toSet())
        return files to FakeExporter(files)
    }

    private fun migrate(files: FakeFiles, exporter: FakeExporter) =
        JournalEncryptionMigration(files, exporter).run()

    // ─── Nothing to do ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `a fresh install has nothing to migrate`() {
        val (files, exporter) = world()
        assertEquals(MigrationOutcome.NotNeeded, migrate(files, exporter))
        assertTrue("a fresh install touched something: $log", log.none { it.startsWith("delete") })
    }

    @Test
    fun `an already-encrypted database is left alone`() {
        val (files, exporter) = world(DB, "$DB-wal")
        files.mainIsPlaintext = false
        assertEquals(MigrationOutcome.NotNeeded, migrate(files, exporter))
        assertEquals(setOf(DB, "$DB-wal"), files.present)
        assertTrue("the export ran over an encrypted database", log.none { it == "exportToSibling" })
    }

    @Test
    fun `an abandoned half-export beside an encrypted database is cleaned up`() {
        val (files, exporter) = world(DB, SIBLING)
        files.mainIsPlaintext = false
        assertEquals(MigrationOutcome.NotNeeded, migrate(files, exporter))
        assertEquals(setOf(DB), files.present)
    }

    // ─── The happy path ────────────────────────────────────────────────────────────────────────

    @Test
    fun `a plaintext journal is copied, verified, and only then replaced`() {
        val (files, exporter) = world(DB, "$DB-wal", "$DB-shm")
        assertEquals(MigrationOutcome.Migrated, migrate(files, exporter))
        assertEquals("only the database itself should remain", setOf(DB), files.present)
        assertTrue("the export never ran", log.contains("exportToSibling"))
        assertTrue("the swap never happened", log.contains("rename"))
    }

    @Test
    fun `nothing is destroyed until every verification has been asked for`() {
        val (files, exporter) = world(DB, "$DB-wal", "$DB-shm", "$DB-journal")
        assertEquals(MigrationOutcome.Migrated, migrate(files, exporter))

        // Guards the check: a log with no destruction or no verification in it would satisfy the
        // helper vacuously, which is the shape of a guard that reports green forever.
        assertTrue("nothing was destroyed at all: $log", log.any { e -> DESTRUCTIVE.any { e.startsWith(it) } })
        assertTrue("nothing was verified at all: $log", log.any { it in VERIFICATIONS })

        assertTrue(
            "a file was deleted before the copy had been verified. The plaintext database is the " +
                "only copy of somebody's journal; nothing may be destroyed until the row counts, " +
                "the schema version and the integrity check have all passed. Log: $log",
            destructionComesAfterVerification(log),
        )
    }

    /**
     * The positive control for the assertion above.
     *
     * `destructionComesAfterVerification` is what stands between a reordered migration and a lost
     * journal, so it is shown a log in which the deletion came too early and must reject it. Without
     * this, an implementation that always returned true would pass the test above for ever.
     */
    @Test
    fun `the ordering check can see a deletion that came too early`() {
        val tooEarly = listOf(
            "plaintextTableCounts", "plaintextUserVersion", "exportToSibling",
            "deleteMain", // here is the bug
            "siblingTableCounts", "siblingUserVersion", "siblingIntegrityOk", "rename",
        )
        assertFalse(
            "the ordering check accepted a log with the delete before the verification, so it is " +
                "not checking anything",
            destructionComesAfterVerification(tooEarly),
        )
        val correct = listOf(
            "plaintextTableCounts", "plaintextUserVersion", "exportToSibling",
            "siblingTableCounts", "siblingUserVersion", "siblingIntegrityOk",
            "deleteMain", "deleteSidecar-wal", "rename",
        )
        assertTrue("the ordering check rejected a correct log", destructionComesAfterVerification(correct))
    }

    @Test
    fun `every sidecar is removed, not only the one that happened to exist`() {
        val (files, exporter) = world(DB, "$DB-wal", "$DB-shm", "$DB-journal")
        assertEquals(MigrationOutcome.Migrated, migrate(files, exporter))
        assertEquals(setOf(DB), files.present)
        for (suffix in JournalEncryptionMigration.SIDECAR_SUFFIXES) {
            assertTrue("the $suffix sidecar was never even asked about", log.contains("deleteSidecar$suffix"))
        }
    }

    // ─── Refusals: the plaintext must survive every one of them ────────────────────────────────

    private fun assertNothingWasDestroyed(files: FakeFiles) {
        assertEquals(
            "the original database or a sidecar was destroyed by a run that failed",
            setOf(DB, "$DB-wal", "$DB-shm"),
            files.present,
        )
        assertTrue(
            "a run that failed still destroyed something: $log",
            destructionComesAfterVerification(log) && log.none { it == "deleteMain" },
        )
    }

    @Test
    fun `a row-count mismatch destroys nothing`() {
        val (files, exporter) = world(DB, "$DB-wal", "$DB-shm")
        // One entry short. This is the failure the whole copy-verify-swap order exists for.
        exporter.siblingCounts = COUNTS + ("entries" to 411L)
        assertEquals(MigrationOutcome.Failed(MigrationStep.ROW_COUNTS), migrate(files, exporter))
        assertNothingWasDestroyed(files)
    }

    @Test
    fun `a table that vanished entirely is a row-count mismatch`() {
        val (files, exporter) = world(DB, "$DB-wal", "$DB-shm")
        exporter.siblingCounts = COUNTS - "assessments"
        assertEquals(MigrationOutcome.Failed(MigrationStep.ROW_COUNTS), migrate(files, exporter))
        assertNothingWasDestroyed(files)
    }

    @Test
    fun `an integrity failure destroys nothing`() {
        val (files, exporter) = world(DB, "$DB-wal", "$DB-shm")
        exporter.integrityOk = false
        assertEquals(MigrationOutcome.Failed(MigrationStep.INTEGRITY), migrate(files, exporter))
        assertNothingWasDestroyed(files)
    }

    @Test
    fun `a schema version that did not come across destroys nothing`() {
        val (files, exporter) = world(DB, "$DB-wal", "$DB-shm")
        // sqlcipher_export copies rows, never user_version. Room reads user_version to decide which
        // migrations to run, so a zero here would re-run every migration this app has ever had.
        exporter.siblingVersion = 0
        assertEquals(MigrationOutcome.Failed(MigrationStep.SCHEMA_VERSION), migrate(files, exporter))
        assertNothingWasDestroyed(files)
    }

    @Test
    fun `an export that fails destroys nothing`() {
        val (files, exporter) = world(DB, "$DB-wal", "$DB-shm")
        exporter.exportSucceeds = false
        assertEquals(MigrationOutcome.Failed(MigrationStep.EXPORT), migrate(files, exporter))
        assertNothingWasDestroyed(files)
    }

    @Test
    fun `a plaintext database that cannot be read destroys nothing`() {
        val (files, exporter) = world(DB, "$DB-wal", "$DB-shm")
        exporter.plaintextCounts = null
        assertEquals(MigrationOutcome.Failed(MigrationStep.READ_PLAINTEXT), migrate(files, exporter))
        assertNothingWasDestroyed(files)
    }

    @Test
    fun `a database reporting no tables at all destroys nothing`() {
        // Without this guard the comparison below would be "empty map equals empty map", which is
        // true, and the original would be deleted on the strength of having verified nothing.
        val (files, exporter) = world(DB, "$DB-wal", "$DB-shm")
        exporter.plaintextCounts = emptyMap()
        exporter.siblingCounts = emptyMap()
        assertEquals(MigrationOutcome.Failed(MigrationStep.READ_PLAINTEXT), migrate(files, exporter))
        assertNothingWasDestroyed(files)
    }

    @Test
    fun `a stale sibling that will not delete stops the run before anything is destroyed`() {
        val (files, exporter) = world(DB, "$DB-wal", "$DB-shm", SIBLING)
        files.deleteSiblingFails = true
        assertEquals(MigrationOutcome.Failed(MigrationStep.EXPORT), migrate(files, exporter))
        assertTrue("the export ran into a file that was already there", log.none { it == "exportToSibling" })
        assertTrue(DB in files.present)
    }

    @Test
    fun `a delete that will not happen stops the run rather than pressing on`() {
        // Pressing on would rename the encrypted file over a plaintext one that is still there —
        // which on most filesystems succeeds, and on some does not, and neither outcome is one
        // anybody should find out about on a stranger's phone.
        val (files, exporter) = world(DB, "$DB-wal", "$DB-shm")
        files.deleteMainFails = true
        assertEquals(MigrationOutcome.Failed(MigrationStep.DELETE), migrate(files, exporter))
        assertNothingWasDestroyed(files)
        assertTrue("the swap went ahead over a database that was still there", log.none { it == "rename" })
    }

    @Test
    fun `a failed run leaves no half-built file behind`() {
        val (files, exporter) = world(DB, "$DB-wal", "$DB-shm")
        exporter.integrityOk = false
        migrate(files, exporter)
        assertFalse("a half-built encrypted file survived a failure", SIBLING in files.present)
    }

    // ─── The crash window ──────────────────────────────────────────────────────────────────────

    @Test
    fun `a crash between the delete and the rename is recovered on the next start`() {
        // The state a crash in that window leaves: no database, one encrypted file beside where it
        // was. This is the only way to reach it, because the sibling only ever exists while the
        // original is still there.
        val (files, exporter) = world(SIBLING)
        assertEquals(MigrationOutcome.Migrated, migrate(files, exporter))
        assertEquals(setOf(DB), files.present)
        assertTrue("the recovery re-ran the export over a journal that was already copied",
            log.none { it == "exportToSibling" })
    }

    @Test
    fun `a rename that fails leaves the encrypted journal where the next start will find it`() {
        val (files, exporter) = world(DB, "$DB-wal")
        files.renameFails = true
        assertEquals(MigrationOutcome.Failed(MigrationStep.RENAME), migrate(files, exporter))
        assertTrue(
            "the sibling was tidied away after a failed rename. The original is already deleted at " +
                "that point, so the sibling IS the journal — this is the one failure path that must " +
                "not clean up after itself.",
            SIBLING in files.present,
        )

        // And the next start finishes the job.
        log.clear()
        files.renameFails = false
        assertEquals(MigrationOutcome.Migrated, migrate(files, FakeExporter(files)))
        assertEquals(setOf(DB), files.present)
    }

    // ─── The final check, and why it is not reading the deletion list ──────────────────────────

    @Test
    fun `a sidecar nobody listed is still swept up`() {
        // Not one of SIDECAR_SUFFIXES. A future Room option, a different SQLite build, or an OEM
        // could put one here, and the deletion list would never know.
        val (files, exporter) = world(DB, "$DB-wal", "$DB-journal2")
        assertEquals(MigrationOutcome.Migrated, migrate(files, exporter))
        assertEquals(setOf(DB), files.present)
        assertTrue(
            "the unlisted sidecar was never even looked at, so the final check is reading the " +
                "deletion list rather than the directory",
            log.contains("deleteBeside:$DB-journal2"),
        )
    }

    /**
     * The positive control for the test above, and the reason [DatabaseFiles.namesBesideTheDatabase]
     * exists at all.
     *
     * If the final check were "delete these three suffixes, then confirm those three suffixes are
     * gone", it would ask the same list twice and agree with itself. So here is a file it cannot
     * delete: success must NOT be reported, and the survivor must be named.
     */
    @Test
    fun `a plaintext file that will not delete is not reported as success`() {
        val (files, exporter) = world(DB, "$DB-journal2")
        files.undeletable += "$DB-journal2"
        val outcome = migrate(files, exporter)
        assertEquals(MigrationOutcome.LeftoverPlaintext(listOf("$DB-journal2")), outcome)
        // The journal itself is safe and encrypted; what failed is the claim that nothing plaintext
        // remains, which is exactly why this is not Migrated.
        assertTrue(DB in files.present)
    }

    // ─── The two constants, which are load-bearing ─────────────────────────────────────────────

    @Test
    fun `the header is the sixteen bytes SQLite actually writes`() {
        val header = JournalEncryptionMigration.SQLITE_HEADER
        assertEquals(16, header.size)
        assertEquals("SQLite format 3", String(header.copyOf(15), Charsets.US_ASCII))
        // The sixteenth byte is a NUL, not a space. One invisible character apart, and a space
        // would match no database anywhere while looking exactly right in a diff.
        assertEquals(0.toByte(), header[15])
        assertArrayEquals(
            byteArrayOf(0x53, 0x51, 0x4c, 0x69, 0x74, 0x65, 0x20, 0x66, 0x6f, 0x72, 0x6d, 0x61, 0x74, 0x20, 0x33, 0x00),
            header,
        )
    }

    @Test
    fun `the deletion list is the three files SQLite keeps beside a database`() {
        assertEquals(
            listOf("-wal", "-shm", "-journal"),
            JournalEncryptionMigration.SIDECAR_SUFFIXES,
        )
        // The sibling must be distinguishable from the database and from every sidecar, or the
        // sweep would delete the encrypted journal it just built.
        assertFalse(
            JournalEncryptionMigration.SIDECAR_SUFFIXES.contains(JournalEncryptionMigration.SIBLING_SUFFIX),
        )
    }
}
