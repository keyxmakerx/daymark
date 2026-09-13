package com.daymark.app.data

import android.content.Context
import com.daymark.app.security.DataKeyResult
import com.daymark.app.security.DataKeyStore
import com.daymark.app.security.DataKeyWraps
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** What the database file turned out to be, once this process had looked. */
enum class JournalFileState {

    /** Encrypted and openable. Room is given the SQLCipher factory. */
    ENCRYPTED,

    /**
     * Still a plaintext file, because a migration has not succeeded yet. Room opens it exactly as
     * it always did and the migration is tried again next launch. This is the kind failure: the
     * app works, the journal is intact, and nothing has been destroyed.
     */
    PLAINTEXT,

    /** There is an encrypted journal here and this phone can no longer produce its key. */
    UNOPENABLE,

    /**
     * A migration got as far as replacing the file and could not complete the last step. The
     * journal is safe, in a file beside where it belongs, and the next launch finishes the job —
     * so the only correct thing to do is stop, and say so, without offering to delete anything.
     */
    NEEDS_RESTART,
}

/**
 * Runs the at-rest migration once per process, and decides what Room may open.
 *
 * ## Why this is not just a line inside `provideDatabase`
 *
 * Two callers need the answer and only one of them may run the work. `AppModule` needs the key to
 * hand Room; `MainActivity` needs to know, BEFORE it composes anything that could touch a DAO,
 * whether there is a journal to show at all. Running the migration twice would be a second export
 * over a file the first one was in the middle of.
 *
 * ## Do not call [prepare] from the main thread
 *
 * It copies a database. For most people it is a few megabytes and finishes in well under a second,
 * once, on the launch after this ships — but "usually fast" is how an ANR gets written. MainActivity
 * runs it on IO and shows a waiting state; AppModule reaches it only from whichever background
 * thread first asks for a DAO.
 *
 * ## None of this has ever run
 *
 * There is no device in CI. The migration underneath is exercised against fakes in a JVM test and
 * against a real seeded database in an instrumented test that CI compiles and never executes. The
 * first phone it runs on should have a backup taken first.
 */
@Singleton
class JournalEncryptionGate @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataKeys: DataKeyStore,
) {

    @Volatile
    private var resolved: JournalFileState? = null

    /** The state of the journal file, migrating it if that is what is needed. Cached per process. */
    @Synchronized
    fun prepare(): JournalFileState = resolved ?: resolve().also { resolved = it }

    /**
     * What [prepare] already found, or null if it has not run in this process. Runs nothing.
     *
     * This is what a screen reads when it needs to SAY something about the journal's state rather
     * than open it. Settings uses it, so that the sentence under "Your entries on this device" is
     * the truth about this phone rather than a claim the app would like to make — a migration that
     * has not succeeded means the file is still plaintext, and the setting has to say so.
     */
    val settled: JournalFileState?
        get() = resolved

    /**
     * The key spelling Room's SQLCipher factory takes, or null when Room must open the file the old
     * way — or not at all.
     */
    fun rawKeyForRoom(): ByteArray? {
        if (prepare() != JournalFileState.ENCRYPTED) return null
        val key = (dataKeys.dataKey() as? DataKeyResult.Available)?.key ?: return null
        return DataKeyWraps.rawKeySpelling(key)
    }

    /**
     * Delete this journal and begin an empty one. Only ever reached by a person choosing it.
     *
     * The database file and everything beside it go; the photos do not. They were never encrypted
     * and are not part of what could not be opened, so removing them would be destroying something
     * for no reason at all.
     */
    @Synchronized
    fun startNewJournal(): JournalFileState {
        val main = databaseFile()
        val files = JournalFilesOnDisk(main)
        files.deleteMain()
        for (suffix in JournalEncryptionMigration.SIDECAR_SUFFIXES) files.deleteSidecar(suffix)
        files.deleteSibling()
        // Anything else the directory still holds under this database's name, by the same
        // directory-driven sweep the migration uses — the deletion list is not the authority.
        files.namesBesideTheDatabase()?.forEach { files.deleteBeside(it) }

        dataKeys.startNewJournal()
        resolved = null
        return prepare()
    }

    private fun databaseFile() = context.getDatabasePath(AppDatabase.NAME)

    private fun resolve(): JournalFileState {
        val key = when (val result = dataKeys.dataKey()) {
            is DataKeyResult.Available -> result.key
            // A key existed and this phone cannot produce it any more. Say so; destroy nothing.
            DataKeyResult.Lost -> return JournalFileState.UNOPENABLE
            // No keystore to hold a key, so nothing was ever encrypted and nothing is at risk.
            DataKeyResult.Unsupported -> return JournalFileState.PLAINTEXT
        }

        val main = databaseFile()
        val files = JournalFilesOnDisk(main)
        val exporter = SqlCipherExporter(main, files.siblingFile(), DataKeyWraps.rawKeySpelling(key))
        val migration = JournalEncryptionMigration(files, exporter)

        var outcome = migration.run()
        if (outcome == MigrationOutcome.Failed(MigrationStep.RENAME)) {
            // The database file is gone and the encrypted copy is beside it. That is recoverable and
            // the recovery is the same call, so try it once more before telling anybody anything —
            // a rename onto a path that was just cleared has no ordinary reason to fail twice.
            outcome = migration.run()
        }

        return when (val settled = outcome) {
            // Includes LeftoverPlaintext: the journal itself is encrypted and in place. What failed
            // there is the claim that nothing plaintext survives, which is worth knowing and is not
            // worth stopping somebody's app over.
            is MigrationOutcome.Failed ->
                if (settled.step == MigrationStep.RENAME) {
                    JournalFileState.NEEDS_RESTART
                } else {
                    JournalFileState.PLAINTEXT
                }
            else -> JournalFileState.ENCRYPTED
        }
    }
}
