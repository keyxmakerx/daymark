package com.daymark.app.backup

import com.daymark.app.ui.codeOnly
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A replace-all restore that fails part-way keeps the person's photos and their reminders (#487).
 * The database half already rolls back in one transaction; photos and alarms live outside it, so
 * this pins the order that keeps them. Read as text, because a restore needs Room and a device.
 * Every check is first shown a planted example (CLAUDE.md §5). Runs through
 * `tools/jvm-source-tests.sh`.
 */
class RestoreFailureSourceTest {

    private val code = codeOnly(repoFile("app/src/main/java/com/daymark/app/backup/BackupManager.kt").readText())

    private fun replaceBranch(c: String) =
        c.substringAfter("ImportMode.REPLACE -> {").substringBefore("ImportMode.MERGE ->")

    private fun importReplace(c: String) =
        c.substringAfter("private suspend fun importReplace").substringBefore("private suspend fun importMerge")

    @Test
    fun the_slices_are_real() {
        assertTrue(replaceBranch(code).contains("database.withTransaction { importReplace(data) }"))
        assertTrue(importReplace(code).contains("entryDao.deleteAllEntries()"))
    }

    /** Nothing on the transaction's side of the line empties the photo folder. */
    @Test
    fun photos_are_swapped_in_only_after_the_database_commits() {
        fun safe(c: String): Boolean {
            val b = replaceBranch(c)
            val tx = b.indexOf("database.withTransaction")
            val commit = b.indexOf("photoStore.commitStagedRestore()")
            return !importReplace(c).contains("photoStore.clearAll()") &&
                b.contains("staged = true") && tx >= 0 && commit > tx &&
                b.substringAfter("catch (e: Throwable)").contains("photoStore.discardStagedRestore()")
        }
        assertTrue(safe(code))
        val planted = code.replace(
            "        entryDao.deleteAllEntries()\n",
            "        entryDao.deleteAllEntries()\n        photoStore.clearAll()\n",
        )
        assertTrue("mutation did not land", importReplace(planted).contains("photoStore.clearAll()"))
        assertFalse(safe(planted))
    }

    /** A failed restore re-arms the person's own reminders, which importReplace had cancelled. */
    @Test
    fun a_failed_restore_rearms_what_it_cancelled() {
        fun rearmsOnFailure(c: String) =
            replaceBranch(c).substringAfter("catch (e: Throwable)").contains("rearm()") &&
                c.substringAfter("private suspend fun rearm()").substringBefore("private suspend fun importReplace")
                    .let { it.contains("reminderRepository.rescheduleAll()") && it.contains("trackerCheckIns.refreshAll()") }
        assertTrue(rearmsOnFailure(code))
        assertFalse(rearmsOnFailure(code.replace("                    rearm()\n                    throw e", "                    throw e")))
    }
}
