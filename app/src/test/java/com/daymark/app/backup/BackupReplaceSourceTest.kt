package com.daymark.app.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Finds a file in this repo by walking up from the working directory, because Gradle runs unit
 * tests with `user.dir` at the module directory while some IDEs use the repo root. Not finding it
 * throws: an assertion suite that cannot locate its subject is exactly the shape of a guard that
 * reports green forever.
 */
internal fun repoFile(rel: String): File {
    var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
    val tail = rel.substringAfter("app/")
    while (dir != null) {
        for (candidate in listOf(File(dir, rel), File(dir, tail))) {
            if (candidate.isFile) return candidate
        }
        dir = dir.parentFile
    }
    throw AssertionError("could not find $rel from ${System.getProperty("user.dir")}")
}

/**
 * "Replace all current data" must not leave a table behind.
 *
 * ## The bug this exists for
 *
 * `importReplace` wiped twelve tables and never touched `offer_records` — [BackupManager] did not
 * hold anything that could reach it. A `kind="support"` row is only written after an entry at mood
 * 1 or 2 (`EntryEditorViewModel.save`), so what survived a full restore was a dated list of the
 * days this person logged their worst moods, on the one operation someone performs *because* they
 * want the old data gone. Restore a backup from a fresh install and the journal was empty while
 * that table still knew the dates.
 *
 * ## Why this is a source test
 *
 * The honest test constructs a [BackupManager] and asserts the ledger is empty afterwards. Its
 * sixteen collaborators include four final classes (`PhotoStore`, `MoodCustomizationStore`,
 * `AchievementsStore`, `ReminderRepository`) and its photo path calls `android.util.Base64`; this
 * module's unit tests have no mocking framework and no Robolectric, so that test cannot be written
 * here — it would need an instrumented run, and nothing in CI runs those. A source assertion that
 * actually executes is worth more than an instrumented one that does not.
 *
 * So this counts doors instead. Not "does `importReplace` call `offerLedger.clear()`" — that check
 * goes green the day a seventeenth table is added and forgotten, which is precisely how
 * `offer_records` was missed. Every collaborator the class is given must be named somewhere on the
 * REPLACE path, so adding one and not wiping it fails here.
 */
class BackupReplaceSourceTest {

    private companion object {
        const val REL = "app/src/main/java/com/daymark/app/backup/BackupManager.kt"
    }

    private val source = repoFile(REL).readText()

    /** The file with comments and KDoc removed — claims must hold against code, not prose. */
    private val code = source
        .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
        .lines()
        .filterNot { it.trimStart().startsWith("//") }
        .joinToString("\n")

    /**
     * The collaborator names: the constructor's `private val`s, plus any DAO taken off the injected
     * database in the class body.
     *
     * That second source is not tidiness — it is the hole this count had. `goalStepDao` has no
     * `@Provides` in `di/AppModule.kt`, so [BackupManager] reaches it through `AppDatabase` the way
     * `GoalRepository` does, which put it outside the constructor and outside a count that only
     * read constructor parameters. The count would have stayed green at seventeen doors while
     * `goal_steps` was emptied by the `goals` cascade on every REPLACE and never written back: the
     * `offer_records` failure again, in a table holding what the person wrote.
     */
    private fun collaborators(src: String): List<String> {
        val body = src.substringAfter("class BackupManager")
        val declared = Regex("""private val (\w+):""").findAll(body.substringBefore(") {"))
        val offDatabase = Regex("""private val (\w+) = \w+\.\w+[Dd]ao\(\)""").findAll(body)
        return (declared + offDatabase).map { it.groupValues[1] }.toList()
    }

    /**
     * Everything the REPLACE path runs: `importFromJson` down to (not including) `importMerge`,
     * which is `importReplace` plus the mode-independent tail that clears mood overrides and
     * achievements. MERGE-only code lives below the cut.
     */
    private fun replacePath(src: String): String =
        src.substringAfter("suspend fun importFromJson").substringBefore("private suspend fun importMerge")

    /** Collaborators the REPLACE path never mentions. */
    private fun untouchedByReplace(src: String): List<String> {
        val path = replacePath(src)
        return collaborators(src).filterNot { path.contains("$it.") }
    }

    @Test
    fun `the source was found and stripped to something that is still code`() {
        // Guards every assertion below: an empty or unparsed subject would make them all pass.
        assertTrue("source is implausibly short", source.length > 8000)
        assertTrue(code.contains("class BackupManager"))
        assertTrue(code.contains("private suspend fun importReplace"))
        assertTrue(code.contains("private suspend fun importMerge"))
        assertTrue("KDoc survived stripping", source.contains("/**") && !code.contains("/**"))
        // The two slicers must actually slice, or the checks below read the whole file and pass.
        assertTrue("constructor not found", collaborators(code).size >= 16)
        // And the body-level half of the count must actually match something, or extending it was
        // inert and this suite is back to reading constructor parameters alone.
        assertTrue("goalStepDao is not counted as a door", collaborators(code).contains("goalStepDao"))
        assertTrue("replace path not sliced", replacePath(code).length in 500..6000)
        assertFalse("replace path swallowed the merge", replacePath(code).contains("activityIdMap"))
    }

    @Test
    fun `every table BackupManager can reach is wiped or rewritten by a replace`() {
        assertEquals(
            "these are injected into BackupManager and the REPLACE path never touches them. " +
                "That is how offer_records survived 'Replace all current data': it was not a " +
                "wrong call, it was a missing one, and nothing failed when it was absent",
            emptyList<String>(),
            untouchedByReplace(code),
        )
        // Named explicitly as well, because the ledger is the one whose absence was invisible:
        // it holds no user-facing rows, so no screen looked wrong while it survived.
        assertTrue("offerLedger is not a collaborator", collaborators(code).contains("offerLedger"))
        assertTrue("the ledger is never cleared", replacePath(code).contains("offerLedger.clear()"))
    }

    @Test
    fun `the door count fails against the code as it was before this fix`() {
        // The pre-fix state: BackupManager holds the ledger but the replace path never calls it.
        // This test FAILS if the check above is satisfiable without the call.
        val preFix = code.replace("offerLedger.clear()", "")
        assertFalse("mutation did not land", preFix.contains("offerLedger.clear()"))
        assertEquals(listOf("offerLedger"), untouchedByReplace(preFix))

        // And the same detector fires for a table added later and forgotten — the general shape.
        val extraDao = code.replace(
            "    private val offerLedger:",
            "    private val futureDao: com.daymark.app.data.dao.FutureDao,\n    private val offerLedger:",
        )
        assertTrue("mutation did not land", collaborators(extraDao).contains("futureDao"))
        assertEquals(listOf("futureDao"), untouchedByReplace(extraDao))
    }

    /**
     * The same mutation for the door that is not a constructor parameter.
     *
     * `goal_steps` is the case that showed the old count was not general: it cascades off `goals`,
     * so `goalDao.deleteAll()` already emptied it and the boards vanished on every restore with
     * nothing failing. A count that reads only the constructor cannot see a DAO held in the class
     * body, and would have reported green throughout.
     */
    @Test
    fun `the door count fires for a DAO held in the body rather than the constructor`() {
        val path = replacePath(code)
        val preFix = code.replace(path, path.replace("goalStepDao.", "unreached."))

        assertFalse("mutation did not land", replacePath(preFix).contains("goalStepDao."))
        assertTrue("mutation removed the declaration too", collaborators(preFix).contains("goalStepDao"))
        assertEquals(listOf("goalStepDao"), untouchedByReplace(preFix))
    }

    @Test
    fun `the backup file carries no field for the reception ledger`() {
        /*
         * The ledger is not exported, and this is the assertion that keeps it that way. Adding a
         * `offerRecords` list here would look like completeness — the class KDoc does say it
         * round-trips every table — and would write those same mood-1-and-2 dates into a plaintext
         * file that leaves the device. It would also buy nothing back: the rows are working state
         * pruned at sixty days, and everything a person would miss is in `entries`.
         */
        val descriptor = BackupData.serializer().descriptor
        val names = (0 until descriptor.elementsCount).map { descriptor.getElementName(it) }

        // The detector must detect: this is the field name a future "round-trip everything" change
        // would most likely add.
        assertTrue("detector is broken", looksLikeLedger("offerRecords"))
        assertTrue("detector is broken", looksLikeLedger("receptionLedger"))
        assertFalse("detector is too greedy", looksLikeLedger("entries"))

        // Positive control on the descriptor itself, so an empty name list cannot pass.
        assertTrue("descriptor did not read", names.containsAll(listOf("entries", "safetyPlan")))
        assertEquals(emptyList<String>(), names.filter { looksLikeLedger(it) })
    }

    private fun looksLikeLedger(field: String): Boolean =
        listOf("offer", "reception", "ledger", "interrupt").any { field.contains(it, ignoreCase = true) }
}
