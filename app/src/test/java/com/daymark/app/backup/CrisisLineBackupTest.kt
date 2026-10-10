package com.daymark.app.backup

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The crisis line in a backup (#370). A restore that dropped it left a person outside the US, on a
 * new phone, looking at a US number at the moment they needed theirs. The wiring that cannot be run
 * here — the store is SharedPreferences — is asserted against the source, as
 * [ConstellationBackupTest] does.
 */
class CrisisLineBackupTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `a crisis line survives a backup and restore unchanged`() {
        val before = BackupData(
            version = BackupManager.CURRENT_VERSION,
            exportedAt = 1L,
            entries = emptyList(),
            activities = emptyList(),
            refs = emptyList(),
            crisisLine = BackupCrisisLine("Samaritans (UK & Ireland)", "Call 116 123, \"free\", any time"),
        )
        val after = json.decodeFromString(BackupData.serializer(), json.encodeToString(before))
        assertEquals(before.crisisLine, after.crisisLine)
    }

    @Test
    fun `a v19 backup with no crisis line still reads, and carries none`() {
        val v19 = """{"version": 19, "exportedAt": 1, "entries": [], "activities": [], "refs": []}"""
        val data = json.decodeFromString(BackupData.serializer(), v19)
        assertNull(data.crisisLine)
        assertTrue("CURRENT_VERSION was not bumped past the crisis-line-less format", BackupManager.CURRENT_VERSION >= 20)
    }

    private val source = repoFile("app/src/main/java/com/daymark/app/backup/BackupManager.kt").readText()
    private val store = repoFile("app/src/main/java/com/daymark/app/data/CrisisStore.kt").readText()

    private fun exportBody(src: String) =
        src.substringAfter("suspend fun exportToJson").substringBefore("suspend fun exportEntriesCsv")

    /** The mode-independent tail of importFromJson, where the line is restored. */
    private fun restoreTail(src: String) =
        src.substringAfter("suspend fun importFromJson").substringBefore("private suspend fun importReplace")

    @Test
    fun `the slices are real`() {
        assertTrue(exportBody(source).length in 1000..9000)
        assertTrue(restoreTail(source).contains("ImportMode.MERGE -> importMerge(data)"))
        assertFalse("the tail ran into importReplace", restoreTail(source).contains("deleteAllEntries"))
    }

    @Test
    fun `only a line the person set is exported, never the default`() {
        assertTrue(exportBody(source).contains("crisisLine = crisisStore.own()"))
        assertFalse(exportBody(source).contains("crisisStore.get()"))
        // own() is what keeps the default out: it is null until the person has saved a line.
        assertTrue(store.contains("fun own(): Resource? = if (prefs.contains(K_LABEL)"))
    }

    @Test
    fun `replace takes the file's line, merge never overwrites a line the person changed`() {
        val tail = restoreTail(source)
        val guard = "if (mode == ImportMode.REPLACE || crisisStore.own() == null) crisisStore.save(line.label, line.contact)"
        assertTrue("the restore's guard has changed", tail.contains(guard))
        // A file without a line leaves this phone's alone: the save sits inside the null check.
        assertTrue(tail.contains("data.crisisLine?.let"))
        assertEquals("the line is saved somewhere other than under its guard", 1, Regex("""crisisStore\.save\(""").findAll(source).count())
        // The detector sees a planted unguarded save.
        val planted = source.replace(guard, "crisisStore.save(line.label, line.contact)")
        assertFalse(restoreTail(planted).contains(guard))
    }
}
