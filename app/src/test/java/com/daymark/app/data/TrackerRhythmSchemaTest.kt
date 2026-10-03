package com.daymark.app.data

import com.daymark.app.backup.repoFile
import com.daymark.app.stats.TrackerRhythm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The v20 migration, read as source: every tracker still asks nothing after it, and nothing is
 * back-filled. Room compares each column's `DEFAULT` with its entity's `@ColumnInfo(defaultValue)`,
 * but only on a device; this compares them in seconds, and also holds the rhythm's default to the
 * one rhythm that asks nothing. Run by `tools/jvm-source-tests.sh`.
 */
class TrackerRhythmSchemaTest {

    private val database = repoFile("app/src/main/java/com/daymark/app/data/AppDatabase.kt").readText()
    private val tracker = repoFile("app/src/main/java/com/daymark/app/data/entity/Tracker.kt").readText()
    private val offerRecord = repoFile("app/src/main/java/com/daymark/app/data/entity/OfferRecord.kt").readText()

    private val body: String = database.substringAfter("val MIGRATION_19_20 = object").substringBefore("\n        }\n")

    /** `(table, column) -> default` for every column the migration adds. */
    private fun added(sql: String): Map<Pair<String, String>, String> =
        Regex("""ALTER TABLE `(\w+)` ADD COLUMN `(\w+)` \w+ NOT NULL DEFAULT ('?[\w-]+'?)""").findAll(sql)
            .associate { (it.groupValues[1] to it.groupValues[2]) to it.groupValues[3].trim('\'') }

    /** `column -> default` for every `@ColumnInfo(defaultValue = ...)` in an entity's source. */
    private fun declared(entity: String): Map<String, String> =
        Regex("""@ColumnInfo\(defaultValue = "([^"]*)"\) val (\w+)""").findAll(entity)
            .associate { it.groupValues[2] to it.groupValues[1] }

    @Test
    fun `the migration was read, and adds the seven columns`() {
        assertTrue("MIGRATION_19_20 was not found", database.contains("val MIGRATION_19_20 = object : Migration(19, 20)"))
        assertEquals(
            setOf("rhythm", "onceAtMinute", "fewCount", "windowStart", "windowEnd", "quickLog").map { "trackers" to it }.toSet() +
                setOf("offer_records" to "subject"),
            added(body).keys,
        )
    }

    @Test
    fun `every SQL default is spelled as its entity declares it`() {
        val entities = mapOf("trackers" to declared(tracker), "offer_records" to declared(offerRecord))
        for ((key, default) in added(body)) {
            assertEquals("${key.first}.${key.second}", entities.getValue(key.first)[key.second], default)
        }
        // The comparison sees a disagreement when there is one.
        val planted = body.replace("DEFAULT 1260", "DEFAULT 1261")
        assertTrue("mutation did not land", planted != body)
        assertEquals("1261", added(planted)["trackers" to "windowEnd"])
        assertFalse(declared(tracker)["windowEnd"] == "1261")
    }

    @Test
    fun `every tracker, old or new, starts at the rhythm that asks nothing, with no notification`() {
        val rhythm = added(body).getValue("trackers" to "rhythm")
        assertEquals(TrackerRhythm.Rhythm.DEFAULT.key, rhythm)
        assertFalse(TrackerRhythm.Rhythm.fromKey(rhythm).asks)
        assertEquals("0", added(body).getValue("trackers" to "quickLog"))
        assertTrue(tracker.contains("val rhythm: String = TrackerRhythm.Rhythm.DEFAULT.key"))
        assertTrue(tracker.contains("val quickLog: Boolean = false"))
        // Positive control: a rhythm that asks is told apart.
        assertTrue(TrackerRhythm.Rhythm.fromKey("daily").asks)
    }

    @Test
    fun `nothing is back-filled, the migration only adds columns`() {
        val writes = Regex("""\b(UPDATE|INSERT|DELETE)\b""")
        assertFalse("the migration writes rows", writes.containsMatchIn(body))
        assertEquals(7, Regex("""db\.execSQL\(""").findAll(body).count())
        // The detector sees a planted back-fill.
        assertTrue(writes.containsMatchIn("$body\nUPDATE `trackers` SET `rhythm` = 'daily'"))
    }
}
