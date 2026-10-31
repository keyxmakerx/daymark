package com.daymark.app.backup

import com.daymark.app.data.entity.Goal
import com.daymark.app.data.entity.SkyPlace
import com.daymark.app.data.entity.SkyStar
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The person's own choices for their stars in a backup, format 21 (#516, #517, #518, #519): a
 * star's name, its meteor shower and its place, the places they named, and a habit's set time. A
 * restore that dropped any of them would quietly undo something only the person decided.
 */
class SkyStarBackupTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `names, showers, places and set times survive a round trip`() {
        val before = BackupData(
            version = BackupManager.CURRENT_VERSION,
            exportedAt = 1L,
            entries = emptyList(),
            activities = emptyList(),
            refs = emptyList(),
            goals = listOf(BackupGoal(4, "Walk", null, 3, 1L, false, "", "", "habit", null, atMinute = 450)),
            skyStars = listOf(BackupSkyStar("journal", 9, "Lighthouse", true, 2), BackupSkyStar("check_in", 3, null, false, 2)),
            skyPlaces = listOf(BackupSkyPlace(2, "Home", 5L)),
        )
        val after = json.decodeFromString(BackupData.serializer(), json.encodeToString(before))
        assertEquals(before.skyStars, after.skyStars)
        assertEquals(before.skyPlaces, after.skyPlaces)
        assertEquals(450, after.goals.single().atMinute)
    }

    @Test
    fun `a v20 file still reads, with no star named, no place and no set time`() {
        val v20 = """{"version": 20, "exportedAt": 1, "entries": [], "activities": [], "refs": [],
            "goals": [{"id": 1, "title": "Walk", "targetPerWeek": 3, "createdAt": 1}]}"""
        val data = json.decodeFromString(BackupData.serializer(), v20)
        assertEquals(emptyList<BackupSkyStar>(), data.skyStars)
        assertEquals(emptyList<BackupSkyPlace>(), data.skyPlaces)
        assertNull(data.goals.single().atMinute)
        assertTrue(BackupManager.CURRENT_VERSION >= 21)
    }

    @Test
    fun `the backup carries every column of a star, a place and a goal`() {
        val star = fieldNames(SkyStar::class.java)
        assertTrue("reflection read nothing", star.containsAll(setOf("kind", "recordId", "name", "shower", "placeId")))
        assertEquals(star, fieldNames(BackupSkyStar::class.java))
        assertNotEquals(star, fieldNames(BackupSkyStar::class.java) - "placeId")
        assertEquals(fieldNames(SkyPlace::class.java), fieldNames(BackupSkyPlace::class.java))
        assertTrue("atMinute" in fieldNames(Goal::class.java))
        assertEquals(fieldNames(Goal::class.java), fieldNames(BackupGoal::class.java))
    }

    private val source = repoFile("app/src/main/java/com/daymark/app/backup/BackupManager.kt").readText()

    @Test
    fun `the export reads them, a replace puts them back, and a merge leaves them out`() {
        val export = source.substringAfter("suspend fun exportToJson").substringBefore("suspend fun exportEntriesCsv")
        assertTrue(export.contains("skyStarDao.getAllStars()"))
        assertTrue(export.contains("skyStarDao.getAllPlaces()"))
        val replace = source.substringAfter("suspend fun importFromJson").substringBefore("private suspend fun importMerge")
        assertTrue(replace.contains("skyStarDao.deleteAllStars()"))
        assertTrue(replace.contains("skyStarDao.insertPlaces(data.skyPlaces"))
        assertTrue(replace.contains("skyStarDao.insertStars(data.skyStars"))
        // A merge renumbers every record it adds, so the file's stars would name the wrong memories.
        val merge = source.substringAfter("private suspend fun importMerge")
        assertTrue("importMerge not found", merge.contains("g.reachedAt, g.atMinute)"))
        assertFalse(merge.contains("skyStarDao"))
        assertTrue(replace.contains("skyStarDao"))
    }

    private fun fieldNames(type: Class<*>): Set<String> =
        type.declaredFields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }.map { it.name }.toSet()
}
