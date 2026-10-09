package com.daymark.app.backup

import com.daymark.app.data.entity.Constellation
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sky's half of a backup (`DECISIONS.md` §D11): its seed and the constellations drawn in it.
 * A restore without the seed hands the person a different sky; one without the constellations
 * loses the only thing in the sky they made by hand. The wiring that cannot be run here is asserted
 * against the source, as [LifeEventBackupTest] does.
 */
class ConstellationBackupTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `the seed and every constellation survive a round trip`() {
        val before = BackupData(
            version = BackupManager.CURRENT_VERSION,
            exportedAt = 1L,
            entries = emptyList(),
            activities = emptyList(),
            refs = emptyList(),
            skySeed = -0x3A5F_0C11_7E2D_9B41L,
            constellations = listOf(
                BackupConstellation(4, "The long \"summer\", again", 19_000L, "check_in,3,0.25,1.5;journal,9,0.3,1.4", 1_700_000_000_000L),
            ),
        )
        val after = json.decodeFromString(BackupData.serializer(), json.encodeToString(before))
        assertEquals(before.skySeed, after.skySeed)
        assertEquals(before.constellations, after.constellations)
    }

    @Test
    fun `a v17 backup with no sky still reads, and its sky is derived again`() {
        val v17 = """{"version": 17, "exportedAt": 1, "entries": [], "activities": [], "refs": []}"""
        val data = json.decodeFromString(BackupData.serializer(), v17)
        assertEquals(0L, data.skySeed)
        assertEquals(emptyList<BackupConstellation>(), data.constellations)
        assertTrue(BackupManager.CURRENT_VERSION >= 18)
    }

    @Test
    fun `the backup carries every column of a constellation`() {
        val entityFields = fieldNames(Constellation::class.java)
        val backupFields = fieldNames(BackupConstellation::class.java)
        assertTrue("reflection read nothing", entityFields.containsAll(setOf("name", "madeEpochDay", "points")))
        assertEquals(entityFields, backupFields)
        assertNotEquals(entityFields, backupFields - "createdAt")
    }

    private val source = repoFile("app/src/main/java/com/daymark/app/backup/BackupManager.kt").readText()

    private fun exportBody(src: String) =
        src.substringAfter("suspend fun exportToJson").substringBefore("suspend fun exportEntriesCsv")

    private fun replacePath(src: String) =
        src.substringAfter("suspend fun importFromJson").substringBefore("private suspend fun importMerge")

    private fun mergeBody(src: String) = src.substringAfter("private suspend fun importMerge")

    @Test
    fun `the export reads the seed and the table, and a replace restores both`() {
        assertTrue(exportBody(source).contains("skyRepository.storedSeed()"))
        assertTrue(exportBody(source).contains("constellationDao.getAll()"))
        assertTrue(replacePath(source).contains("skyRepository.restoreSeed(data.skySeed)"))
        assertTrue("REPLACE does not keep the file's ids", replacePath(source).contains("Constellation(it.id,"))
        // The detector: the slices are real and do not run into each other.
        assertTrue(exportBody(source).length in 1000..9000)
        assertFalse(replacePath(source).contains("activityIdMap"))
    }

    @Test
    fun `a merge keeps this phone's sky and adds no constellation from another`() {
        val merge = mergeBody(source)
        assertTrue("importMerge not found", merge.contains("lifeEventDao.insert(LifeEvent(0,"))
        assertFalse(merge.contains("constellationDao.insert"))
        assertFalse(merge.contains("restoreSeed"))
    }

    private fun fieldNames(type: Class<*>): Set<String> =
        type.declaredFields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }.map { it.name }.toSet()
}
