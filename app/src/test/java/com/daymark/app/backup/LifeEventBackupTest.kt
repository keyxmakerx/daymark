package com.daymark.app.backup

import com.daymark.app.data.entity.LifeEvent
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The `life_events` half of a backup.
 *
 * A life event is the one row in this file that cannot be reconstructed from anything else. A
 * check-in, a journal entry or a project step could in principle be written again from memory; a
 * life event is a decision the person made once about what mattered, in their own words, with no
 * second copy anywhere on the device. If the backup drops it, it is gone — and the way a table gets
 * dropped from a backup is not a wrong call but a missing one, which nothing fails on.
 *
 * ## What is checked here and what is checked elsewhere
 *
 * This module has no mocking framework and no Robolectric, so the suspend import functions cannot be
 * run (see [BackupReplaceSourceTest]). What runs here is the file format — the round trip, the
 * defaulting of an older file, and the column-for-column comparison against the entity. The wiring
 * that cannot be executed is asserted against the source, the same way [GoalStepBackupTest] does,
 * and the "every collaborator is touched by REPLACE" count in [BackupReplaceSourceTest] covers the
 * replace path generically now that `lifeEventDao` is a constructor parameter.
 */
class LifeEventBackupTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun data(vararg events: BackupLifeEvent) = BackupData(
        version = BackupManager.CURRENT_VERSION,
        exportedAt = 1L,
        entries = emptyList(),
        activities = emptyList(),
        refs = emptyList(),
        lifeEvents = events.toList(),
    )

    // --- The file format ---

    /**
     * The dates people actually enter: one from before the epoch, and one still to come.
     *
     * A negative `epochDay` is not a curiosity — it is 1962, and someone marking a parent's death
     * is the ordinary use of this feature. A round trip that lost the sign, or that took the value
     * through millis and a timezone on the way, would move the date by a day or by decades.
     */
    @Test
    fun `a life event from 1962 and one still to come both survive a round trip`() {
        val longAgo = LocalDate.of(1962, 3, 11).toEpochDay()
        val ahead = LocalDate.of(2031, 9, 1).toEpochDay()
        assertTrue("1962 should be a negative epoch day, or this test is not testing the sign", longAgo < 0)

        val before = data(
            BackupLifeEvent(1, longAgo, "Dad died", 1_700_000_000_000L),
            BackupLifeEvent(2, ahead, "The operation", 1_700_000_001_000L),
        )

        val after = json.decodeFromString(BackupData.serializer(), json.encodeToString(before))

        assertEquals(before.lifeEvents, after.lifeEvents)
        assertEquals(longAgo, after.lifeEvents[0].epochDay)
        assertEquals("Dad died", after.lifeEvents[0].label)
        assertEquals(ahead, after.lifeEvents[1].epochDay)
        // And the day number must be a day number: a value that had been through millis would be
        // eleven orders of magnitude larger, and would still deserialize without complaint.
        assertEquals(LocalDate.of(1962, 3, 11), LocalDate.ofEpochDay(after.lifeEvents[0].epochDay))
    }

    /** The person's own words, verbatim — commas, quotes, newlines and all. */
    @Test
    fun `a label is not reformatted, escaped or trimmed by the file`() {
        val awkward = "Mum's funeral, and the drive back \"home\"\nnot a good day"
        val after = json.decodeFromString(
            BackupData.serializer(),
            json.encodeToString(data(BackupLifeEvent(1, 0L, awkward, 0L))),
        )
        assertEquals(awkward, after.lifeEvents.single().label)
    }

    /**
     * A v14 file has no `lifeEvents` key at all. It must still read, or the first thing this
     * feature does to someone is make the backup they already have unopenable.
     */
    @Test
    fun `a v14 backup with no life events still deserializes`() {
        val v14 = """
            {
              "version": 14,
              "exportedAt": 1700000000000,
              "entries": [],
              "activities": [],
              "refs": []
            }
        """.trimIndent()

        val data = json.decodeFromString(BackupData.serializer(), v14)

        assertEquals(14, data.version)
        assertEquals(emptyList<BackupLifeEvent>(), data.lifeEvents)
    }

    @Test
    fun `the format version was bumped for the life events`() {
        // A file written by this build carries lifeEvents. Left at 14 it would claim to be a v14
        // file, and a v14 reader would accept it and drop them without a word.
        assertTrue("CURRENT_VERSION was not bumped past the life-event-less format", BackupManager.CURRENT_VERSION >= 15)

        val names = BackupData.serializer().descriptor.let { d ->
            (0 until d.elementsCount).map { d.getElementName(it) }
        }
        assertTrue("descriptor did not read", names.contains("entries"))
        assertTrue("the backup has no field for life events", names.contains("lifeEvents"))
    }

    /**
     * Every column of `life_events` is in the file.
     *
     * Field by field rather than "the backup has a list of them", because the way this table gets
     * half-backed-up is a column added to [LifeEvent] later and not to [BackupLifeEvent] — the same
     * missing-call shape as `offer_records`, one field down instead of one table. `createdAt` is the
     * one at risk: nothing displays it, so nothing would look wrong.
     */
    @Test
    fun `the backup carries every column of a life event`() {
        val entityFields = fieldNames(LifeEvent::class.java)
        val backupFields = fieldNames(BackupLifeEvent::class.java)

        // Positive control: reflection must have read something recognisable, or this is
        // empty-equals-empty.
        assertTrue("reflection read nothing", entityFields.containsAll(setOf("epochDay", "label", "createdAt")))
        assertEquals(entityFields, backupFields)

        // And the comparison must be able to fail, or it says nothing about a forgotten column.
        assertNotEquals(entityFields, backupFields - "createdAt")
    }

    // --- The wiring, against the source ---

    private val source = repoFile("app/src/main/java/com/daymark/app/backup/BackupManager.kt").readText()

    private fun exportBody(src: String) =
        src.substringAfter("suspend fun exportToJson").substringBefore("suspend fun exportEntriesCsv")

    private fun replaceBody(src: String) =
        src.substringAfter("private suspend fun importReplace").substringBefore("private suspend fun importMerge")

    private fun mergeBody(src: String) = src.substringAfter("private suspend fun importMerge")

    @Test
    fun `the three bodies this file asserts against are real bodies`() {
        // Every claim below is a `contains` on one of these slices; an empty slice would make the
        // negative ones pass and the positive ones fail loudly, which is the safer half. Bound both
        // ends so a slicer that swallowed the whole file is caught too.
        assertTrue("exportToJson not found", exportBody(source).length in 1000..8000)
        assertTrue("importReplace not found", replaceBody(source).length in 1000..12000)
        assertTrue("importMerge not found", mergeBody(source).length in 1000..12000)
    }

    /**
     * The export reads the table.
     *
     * This is the half no other test covers: [BackupReplaceSourceTest]'s door count proves every
     * collaborator is *wiped or rewritten by a restore*, which `lifeEventDao.deleteAll()` alone
     * satisfies. A table that is emptied on restore and never exported is worse than one that is
     * merely missing — the backup would delete the person's life events on the way in.
     */
    @Test
    fun `the export reads life events, and not only the import paths`() {
        assertTrue(
            "exportToJson never reads life_events, so a restore would empty the table and put " +
                "nothing back",
            exportBody(source).contains("lifeEventDao.getAll()"),
        )
        // The detector: with the call removed, this check fires rather than finding the same string
        // somewhere else in the file.
        val preFix = source.replace(exportBody(source), exportBody(source).replace("lifeEventDao.getAll()", "unreached()"))
        assertFalse("detector is broken", exportBody(preFix).contains("lifeEventDao.getAll()"))
    }

    /**
     * REPLACE restores the file's own ids; MERGE allocates fresh ones.
     *
     * The failure this guards is silent in both directions. A MERGE that kept the file's ids would
     * collide with the events already on the phone; a REPLACE that allocated new ones would be
     * harmless today and would stop being harmless the moment anything references a life event.
     */
    @Test
    fun `the replace path keeps the file's ids and the merge path allocates fresh ones`() {
        assertTrue("REPLACE does not restore life events at all", replaceBody(source).contains("LifeEvent(it.id,"))
        assertFalse("REPLACE is allocating fresh life-event ids", replaceBody(source).contains("LifeEvent(0,"))
        // The absence above only means something if `LifeEvent(0,` is a form that exists in this
        // file and would have been found: it is what MERGE uses, one function down.
        assertTrue("detector is broken — MERGE does not allocate fresh ids either", mergeBody(source).contains("LifeEvent(0,"))
    }

    /**
     * REPLACE empties the table before writing it back.
     *
     * Without it a restore would leave the events already on the phone standing and add the file's
     * on top, which is the MERGE behaviour under the button that says "replace all current data" —
     * and on this table it would also collide on the primary key.
     */
    @Test
    fun `the replace path empties life events before restoring them`() {
        assertTrue("REPLACE never clears life_events", replaceBody(source).contains("lifeEventDao.deleteAll()"))

        // The detector: the same read fails on a replace path that forgot the wipe.
        val preFix = replaceBody(source).replace("lifeEventDao.deleteAll()", "")
        assertFalse("mutation did not land", preFix.contains("lifeEventDao.deleteAll()"))
        assertTrue("mutation removed the restore too", preFix.contains("LifeEvent(it.id,"))
    }

    /**
     * Declared instance field names of a Kotlin data class, order-independent.
     *
     * Statics and `$`-prefixed fields are dropped: the Compose and serialization plugins add their
     * own (`$stable`, `Companion`) and comparing those would make this fail on a plugin upgrade
     * rather than on a forgotten column.
     */
    private fun fieldNames(cls: Class<*>): Set<String> =
        cls.declaredFields
            .filterNot { it.isSynthetic || java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .map { it.name }
            .filterNot { it.startsWith("$") }
            .toSet()
}
