package com.daymark.app.backup

import com.daymark.app.data.entity.SkyPutAway
import com.daymark.app.data.entity.Tracker
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rest of the sky in a backup (`DECISIONS.md` §D11), format 19: a life event marked as hard,
 * each tracker's "Show in my sky", the memories put away, and the colours with their window. A
 * restore that dropped any of them would quietly undo something only the person decided. The
 * wiring that cannot be run here is asserted against the source, as [ConstellationBackupTest] does.
 */
class SkyBackupTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `hard marks, shown trackers, put-away memories and colours survive a round trip`() {
        val before = BackupData(
            version = BackupManager.CURRENT_VERSION,
            exportedAt = 1L,
            entries = emptyList(),
            activities = emptyList(),
            refs = emptyList(),
            trackers = listOf(BackupTracker(3, "Walks", "BOOLEAN", 0, 1, "", 0, false, showInSky = true)),
            lifeEvents = listOf(BackupLifeEvent(5, 19_500L, "A day", 1L, hard = true)),
            skyPutAway = listOf(BackupSkyPutAway("journal", 9, 20_000L), BackupSkyPutAway("check_in", 2, 20_001L)),
            skyColourChoice = 3,
            skyColourUntil = 20_030L,
        )
        val after = json.decodeFromString(BackupData.serializer(), json.encodeToString(before))
        assertEquals(true, after.trackers.single().showInSky)
        assertEquals(true, after.lifeEvents.single().hard)
        assertEquals(before.skyPutAway, after.skyPutAway)
        assertEquals(3, after.skyColourChoice)
        assertEquals(20_030L, after.skyColourUntil)
    }

    @Test
    fun `a v18 file still reads, with nothing hard, no tracker shown and nothing put away`() {
        val v18 = """{"version": 18, "exportedAt": 1, "entries": [], "activities": [], "refs": [],
            "trackers": [{"id": 1, "name": "Walks", "type": "BOOLEAN", "minValue": 0, "maxValue": 1,
              "unit": "", "sortOrder": 0, "archived": false}],
            "lifeEvents": [{"id": 1, "epochDay": 19000, "label": "A day"}]}"""
        val data = json.decodeFromString(BackupData.serializer(), v18)
        assertFalse(data.lifeEvents.single().hard)
        assertFalse(data.trackers.single().showInSky)
        assertFalse(data.trackers.single().toTracker(1).showInSky)
        assertEquals(emptyList<BackupSkyPutAway>(), data.skyPutAway)
        assertEquals(0, data.skyColourChoice)
        assertEquals(0L, data.skyColourUntil)
        assertTrue(BackupManager.CURRENT_VERSION >= 19)
    }

    @Test
    fun `the backup carries every column of a put-away row and of a tracker`() {
        val putAway = fieldNames(SkyPutAway::class.java)
        assertTrue("reflection read nothing", putAway.containsAll(setOf("kind", "recordId", "putAwayEpochDay")))
        assertEquals(putAway, fieldNames(BackupSkyPutAway::class.java))
        assertNotEquals(putAway, fieldNames(BackupSkyPutAway::class.java) - "putAwayEpochDay")

        val tracker = fieldNames(Tracker::class.java)
        assertTrue("reflection read nothing", "showInSky" in tracker)
        assertEquals(tracker, fieldNames(BackupTracker::class.java))
    }

    private val source = repoFile("app/src/main/java/com/daymark/app/backup/BackupManager.kt").readText()

    private fun exportBody(src: String) =
        src.substringAfter("suspend fun exportToJson").substringBefore("suspend fun exportEntriesCsv")

    private fun replacePath(src: String) =
        src.substringAfter("suspend fun importFromJson").substringBefore("private suspend fun importMerge")

    private fun mergeBody(src: String) = src.substringAfter("private suspend fun importMerge")

    @Test
    fun `the export reads every choice, and a replace puts every one back`() {
        val export = exportBody(source)
        assertTrue(export.contains("skyPutAwayDao.getAll()"))
        assertTrue(export.contains("skyRepository.colourChoice()"))
        assertTrue(export.contains("skyRepository.colourUntil()"))
        assertTrue(export.contains("it.createdAt, it.hard)"))
        assertTrue(export.contains("it.showInSky"))
        val replace = replacePath(source)
        assertTrue(replace.contains("skyRepository.restoreColours(data.skyColourChoice, data.skyColourUntil)"))
        assertTrue(replace.contains("skyPutAwayDao.deleteAll()"))
        assertTrue(replace.contains("skyPutAwayDao.insertAll(data.skyPutAway"))
        assertTrue(replace.contains("LifeEvent(it.id, it.epochDay, it.label, it.createdAt, it.hard)"))
        // The detector: the slices are real and do not run into each other.
        assertTrue(export.length in 1000..9000)
        assertFalse(replace.contains("activityIdMap"))
    }

    /**
     * A merge renumbers every record it adds, so the file's put-away rows would name the wrong
     * memories: it carries none, and none of the file's colours. It does carry a hard mark, which
     * travels with its own life event.
     */
    @Test
    fun `a merge carries hard marks but no put-away row and no colours`() {
        val merge = mergeBody(source)
        assertTrue("importMerge not found", merge.contains("LifeEvent(0, e.epochDay, e.label, e.createdAt, e.hard)"))
        assertFalse(merge.contains("skyPutAwayDao"))
        assertFalse(merge.contains("restoreColours"))
        // The control: the same search finds the call on the path that makes it.
        assertTrue(replacePath(source).contains("restoreColours"))
    }

    private fun fieldNames(type: Class<*>): Set<String> =
        type.declaredFields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }.map { it.name }.toSet()
}
