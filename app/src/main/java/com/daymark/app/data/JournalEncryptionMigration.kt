package com.daymark.app.data

/*
 * TURNING A PLAINTEXT JOURNAL INTO AN ENCRYPTED ONE WITHOUT EVER LOSING A ROW.
 *
 * This runs once, on the phone of somebody who already has a year of entries, before Room opens
 * anything. It is the single most dangerous piece of code in this app: it deletes a file that
 * contains the only copy of somebody's journal. Everything below is arranged around that one
 * sentence.
 *
 * ─── COPY, VERIFY, SWAP — AND NOTHING IS DELETED UNTIL THE VERIFY HAS PASSED ───────────────────
 *
 *   1. Is there a plaintext database? (Its first sixteen bytes say so; an encrypted one has no
 *      recognisable header at all.) If not, there is nothing to do.
 *   2. Export every row into a NEW file beside it, encrypted with the data key. The original is
 *      open read-only and untouched.
 *   3. Verify the new file: every table's row count equal to the original's, the schema version
 *      carried across, and SQLite's own integrity check returning ok.
 *   4. ONLY NOW delete the original and every sidecar.
 *   5. Rename the new file into the original's place.
 *   6. Verify that nothing plaintext survives — by LISTING THE DIRECTORY, not by re-reading the
 *      list of names step 4 used.
 *
 * Any failure in steps 1-3 deletes the half-built new file and leaves the original exactly where it
 * was; the next start tries again. That is the whole reason the order is copy-verify-swap and not
 * encrypt-in-place: at every moment before step 4, a complete copy of the journal exists.
 *
 * ─── THE ONE WINDOW WHERE A CRASH COULD LOSE EVERYTHING, AND WHY IT DOES NOT ───────────────────
 *
 * Between step 4 and step 5 the original is gone and the new file is not yet in its place. A crash
 * there — the phone running out of battery, the process killed — would leave a database directory
 * with no database in it and a stray encrypted file beside it.
 *
 * That state is DETECTABLE and is recovered rather than guarded against: it is the only way for the
 * main file to be absent while the sibling is present, because the sibling only ever exists while
 * the original is still there. So step 1 checks for it first and, finding it, simply finishes the
 * rename. The window is not made smaller; it is made survivable, which is better, because a smaller
 * window is still a window and this code will run on millions of unreliable moments.
 *
 * A rename that FAILS is left in exactly that state on purpose — the sibling is NOT cleaned up —
 * so the next start recovers it. This is the one failure path that must not tidy up after itself.
 *
 * ─── WHY THE FINAL CHECK LISTS THE DIRECTORY ───────────────────────────────────────────────────
 *
 * `-wal`, `-shm` and `-journal` hold recently written rows in the clear. A migration that encrypted
 * the database and left a write-ahead log beside it would make the settings copy false on the day
 * it shipped, and would look completely successful from every angle.
 *
 * The obvious check — "delete these three suffixes, then confirm those three suffixes are gone" —
 * is worth nothing, because it asks the same list twice. If SQLite ever leaves a fourth kind of
 * file, or a future Room option changes the journal's name, the delete misses it and the check
 * agrees. So the last step ASKS THE DIRECTORY what is actually there and refuses to report success
 * while anything but the database itself remains. The list of suffixes below is used only to make
 * the deletion deterministic; it is never the thing that decides whether the migration worked.
 *
 * ─── WHAT IS PURE HERE AND WHAT IS NOT ─────────────────────────────────────────────────────────
 *
 * Everything in this file is ordinary Kotlin over two interfaces, so the whole sequence — including
 * the crash recovery and every refusal — runs in JVM unit tests against fakes. The implementations
 * of those interfaces touch real files and real SQLCipher, and nothing in this repository can
 * execute those: there is no Android SDK on the development machine and no device in CI. The
 * instrumented test in `app/src/androidTest` runs the real thing on a seeded plaintext database and
 * CI COMPILES IT WITHOUT EVER RUNNING IT. Until somebody runs it on a phone, with a backup taken
 * first, the real migration is unexercised.
 */

/** The files a SQLite database actually is, from the point of view of something removing them. */
interface DatabaseFiles {

    /** The database file's own name, e.g. `daymark.db`. */
    val mainName: String

    fun mainExists(): Boolean

    /** The first [length] bytes of the database file, or null if it cannot be read. */
    fun mainHeader(length: Int): ByteArray?

    /** Whether the half-built encrypted file exists. */
    fun siblingExists(): Boolean

    fun deleteMain(): Boolean

    fun deleteSidecar(suffix: String): Boolean

    fun deleteSibling(): Boolean

    /** Move the encrypted file onto the database file's path. */
    fun renameSiblingOverMain(): Boolean

    /**
     * Every file in the database's directory whose name begins with [mainName], AS THE DIRECTORY
     * REPORTS IT — never as any list in this file believes it should be. This is what makes the
     * final check independent of the deletion, and it is the whole point of the interface.
     *
     * NULL when the directory could not be listed at all, which is NOT the same as "nothing is
     * there". `java.io.File.list()` returns null for an unreadable directory, and an implementation
     * that turned that into an empty list would make this check report success having looked at
     * nothing — the exact shape of guard this whole file is arranged against.
     */
    fun namesBesideTheDatabase(): List<String>?

    fun deleteBeside(name: String): Boolean
}

/** Reading the plaintext database and writing the encrypted one. */
interface Exporter {

    /** Row count per table in the plaintext database, or null if it could not be read. */
    fun plaintextTableCounts(): Map<String, Long>?

    /** The plaintext database's `user_version`, which is how Room knows its schema version. */
    fun plaintextUserVersion(): Int?

    /** Copy every row into a fresh encrypted sibling under the data key. False if it failed. */
    fun exportToSibling(): Boolean

    /** Row count per table in the encrypted sibling. */
    fun siblingTableCounts(): Map<String, Long>?

    /** The sibling's `user_version`. `sqlcipher_export` does not carry it, so it is set by hand. */
    fun siblingUserVersion(): Int?

    /** `PRAGMA integrity_check` on the sibling. */
    fun siblingIntegrityOk(): Boolean
}

/** Where a migration gave up. Reported so a failure is attributable, never shown to a person. */
enum class MigrationStep {
    READ_PLAINTEXT,
    EXPORT,
    READ_SIBLING,
    ROW_COUNTS,
    SCHEMA_VERSION,
    INTEGRITY,
    DELETE,
    RENAME,
}

sealed interface MigrationOutcome {

    /** No plaintext database. A fresh install, or one already migrated. */
    data object NotNeeded : MigrationOutcome

    /** Done, and the directory confirms nothing plaintext survives. */
    data object Migrated : MigrationOutcome

    /**
     * The journal is encrypted and in place, but something whose name begins with the database's
     * would not delete. The data is safe; the claim that nothing plaintext remains is not, so this
     * is deliberately NOT [Migrated].
     */
    data class LeftoverPlaintext(val names: List<String>) : MigrationOutcome

    /**
     * Nothing was deleted. The plaintext database is exactly where it was and the next start will
     * try again — with the single exception of [MigrationStep.RENAME], which is recovered on the
     * next start instead.
     */
    data class Failed(val step: MigrationStep) : MigrationOutcome
}

class JournalEncryptionMigration(
    private val files: DatabaseFiles,
    private val exporter: Exporter,
) {

    fun run(): MigrationOutcome {
        val mainExists = files.mainExists()
        val siblingExists = files.siblingExists()

        if (!mainExists) {
            // No database and a sibling beside it: the only way to reach this state is a crash
            // between the delete and the rename, because the sibling is built while the original is
            // still there. Finish the rename rather than treat it as a fresh install.
            return if (siblingExists) finishSwap() else MigrationOutcome.NotNeeded
        }

        val header = files.mainHeader(SQLITE_HEADER.size)
        if (header == null || !header.contentEquals(SQLITE_HEADER)) {
            // Already encrypted, or unreadable — Room will report the second in its own terms. A
            // sibling here is an abandoned export from a run that failed before it deleted anything.
            if (siblingExists) files.deleteSibling()
            return MigrationOutcome.NotNeeded
        }

        // Never export into a file that is already there: whatever is in it is from an earlier
        // attempt, and appending this journal to it would be worse than any failure.
        if (siblingExists && !files.deleteSibling()) return giveUpWithoutDeleting(MigrationStep.EXPORT)

        val before = exporter.plaintextTableCounts()
            ?: return giveUpWithoutDeleting(MigrationStep.READ_PLAINTEXT)
        // A database with no tables is not one this app wrote. Without this, the count comparison
        // below would compare two empty maps, find them equal, and delete the original on the
        // strength of having verified nothing at all.
        if (before.isEmpty()) return giveUpWithoutDeleting(MigrationStep.READ_PLAINTEXT)
        val version = exporter.plaintextUserVersion()
            ?: return giveUpWithoutDeleting(MigrationStep.READ_PLAINTEXT)

        if (!exporter.exportToSibling()) return giveUpWithoutDeleting(MigrationStep.EXPORT)

        val after = exporter.siblingTableCounts()
            ?: return giveUpWithoutDeleting(MigrationStep.READ_SIBLING)
        if (after != before) return giveUpWithoutDeleting(MigrationStep.ROW_COUNTS)
        // sqlcipher_export copies rows and not the schema version. Room reads user_version to
        // decide which migrations to run: carried across wrong, it would either re-run migrations
        // over data that already has them or refuse to open at all.
        if (exporter.siblingUserVersion() != version) {
            return giveUpWithoutDeleting(MigrationStep.SCHEMA_VERSION)
        }
        if (!exporter.siblingIntegrityOk()) return giveUpWithoutDeleting(MigrationStep.INTEGRITY)

        // Every check above has passed. This is the first line in this function that destroys
        // anything, and it must stay the first line in this function that destroys anything.
        if (!files.deleteMain()) return giveUpWithoutDeleting(MigrationStep.DELETE)
        for (suffix in SIDECAR_SUFFIXES) files.deleteSidecar(suffix)

        return finishSwap()
    }

    /**
     * The last two steps, reached either normally or by recovering an interrupted run.
     *
     * A failed rename returns without deleting the sibling, ON PURPOSE. The main file is already
     * gone at that point, so the sibling is the journal; tidying it away would be the one action in
     * this class that could actually lose somebody's data. Leaving it is what makes the next start
     * able to recover.
     */
    private fun finishSwap(): MigrationOutcome {
        if (!files.renameSiblingOverMain()) return MigrationOutcome.Failed(MigrationStep.RENAME)

        // A directory that cannot be listed is not an empty directory. Reporting success on the
        // strength of a listing that failed would be the one thing this check exists to prevent.
        val found = leftovers() ?: return MigrationOutcome.LeftoverPlaintext(UNLISTABLE)
        for (name in found) files.deleteBeside(name)
        val remaining = leftovers() ?: return MigrationOutcome.LeftoverPlaintext(UNLISTABLE)
        return if (remaining.isEmpty()) {
            MigrationOutcome.Migrated
        } else {
            MigrationOutcome.LeftoverPlaintext(remaining)
        }
    }

    /** Whatever the DIRECTORY says is beside the database, minus the database itself. */
    private fun leftovers(): List<String>? =
        files.namesBesideTheDatabase()?.filterNot { it == files.mainName }

    /** Tidy the half-built file away. The plaintext database has not been touched. */
    private fun giveUpWithoutDeleting(step: MigrationStep): MigrationOutcome {
        files.deleteSibling()
        return MigrationOutcome.Failed(step)
    }

    companion object {

        /**
         * The first sixteen bytes of every plaintext SQLite file, and of no encrypted one —
         * SQLCipher encrypts the header along with everything else, so an encrypted database begins
         * with bytes indistinguishable from random.
         */
        // Fifteen ASCII characters and then a NUL byte, written as a separate element rather
        // than as an escape inside the literal: a raw NUL in a source file makes it a binary
        // file to git and an invisible character to every editor, and "SQLite format 3" with a
        // trailing SPACE instead would match nothing while looking identical.
        val SQLITE_HEADER: ByteArray =
            "SQLite format 3".toByteArray(Charsets.US_ASCII) + 0.toByte()

        /**
         * The files SQLite keeps beside a database, each of which can hold recently written rows in
         * the clear.
         *
         * This list makes the DELETION deterministic and is deliberately not what decides whether
         * the migration worked — see the note at the top of this file. Adding a suffix here is
         * safe; relying on this list to prove nothing plaintext survives is not.
         */
        val SIDECAR_SUFFIXES: List<String> = listOf("-wal", "-shm", "-journal")

        /** The name the encrypted file is built under, beside the database. */
        const val SIBLING_SUFFIX = "-encrypting"

        /**
         * What [MigrationOutcome.LeftoverPlaintext] carries when the directory could not be listed
         * at all. Not a file name; a statement that the question went unanswered. Never shown to a
         * person — the outcome is read by the code that decides whether to say anything at all.
         */
        val UNLISTABLE: List<String> = listOf("(the database directory could not be listed)")
    }
}
