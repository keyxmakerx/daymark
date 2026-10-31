package com.daymark.app.data

import com.daymark.app.backup.repoFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v24, the person's own choices for their stars (#516, #517, #518, #519), read as source: the two
 * tables are the tables their entities declare, a habit's set time is added with no default, nothing
 * is back-filled, and deleting a memory deletes what was chosen for its star, from every table a
 * star is drawn from, on a new install as on an upgrade. Run by `tools/jvm-source-tests.sh`.
 */
class SkyStarSchemaTest {

    private val database = strip(repoFile("app/src/main/java/com/daymark/app/data/AppDatabase.kt").readText())
    private val module = strip(repoFile("app/src/main/java/com/daymark/app/di/AppModule.kt").readText())
    private val entity = strip(repoFile("app/src/main/java/com/daymark/app/data/entity/SkyStar.kt").readText())
    private val goal = strip(repoFile("app/src/main/java/com/daymark/app/data/entity/Goal.kt").readText())
    private val kinds = repoFile("app/src/main/java/com/daymark/app/sky/Sky.kt").readText()

    /** Each statement the migration runs, its literals joined, the statements apart. */
    private val v24: String = database.substringAfter("val MIGRATION_23_24 = object")
        .substringBefore("val DEFAULT_ACTIVITIES")
        .split("db.execSQL(").drop(1)
        .joinToString("; ") { call -> Regex("\"([^\"]*)\"").findAll(call).joinToString("") { it.groupValues[1] } }

    /** The memory tables the delete triggers cover, keyed by the kind they forget. */
    private fun triggered(src: String): Map<String, String> =
        Regex(""""(\w+)" to "(\w+)"""").findAll(src.substringAfter("val SKY_STAR_TRIGGERS").substringBefore(".map"))
            .associate { it.groupValues[1] to it.groupValues[2] }

    @Test
    fun `the migration was read, is registered, and makes two tables and one column`() {
        assertTrue(database.contains("val MIGRATION_23_24 = object : Migration(23, 24)"))
        assertTrue(module.contains("AppDatabase.MIGRATION_23_24"))
        assertTrue(v24, v24.contains("CREATE TABLE IF NOT EXISTS `sky_stars` ("))
        assertTrue(v24, v24.contains("CREATE TABLE IF NOT EXISTS `sky_places` ("))
        assertTrue(v24, v24.contains("ALTER TABLE `goals` ADD COLUMN `atMinute` INTEGER;") || v24.endsWith("ALTER TABLE `goals` ADD COLUMN `atMinute` INTEGER"))
        assertTrue(Regex("""version = (\d+)""").find(database)!!.groupValues[1].toInt() >= 24)
    }

    @Test
    fun `the star table is the entity's, keyed by the memory it belongs to`() {
        val body = v24.substringAfter("CREATE TABLE IF NOT EXISTS `sky_stars` (").substringBefore(";").removeSuffix(")")
        assertEquals(
            listOf("`kind` TEXT NOT NULL", "`recordId` INTEGER NOT NULL", "`name` TEXT", "`shower` INTEGER NOT NULL", "`placeId` INTEGER"),
            body.substringBefore(", PRIMARY KEY").split(", ").map { it.trim() },
        )
        assertTrue(body.endsWith("PRIMARY KEY(`kind`, `recordId`)"))
        val fields = Regex("""val (\w+): """).findAll(entity.substringAfter("data class SkyStar(").substringBefore(")\n"))
            .map { it.groupValues[1] }.toList()
        assertEquals(listOf("kind", "recordId", "name", "shower", "placeId"), fields)
        assertTrue(entity.contains("primaryKeys = [\"kind\", \"recordId\"]"))
    }

    /** A set time no one chose would be a pulsar no one asked for. */
    @Test
    fun `a habit's set time is null on every goal until the person sets one`() {
        assertTrue(goal.contains("val atMinute: Int? = null,"))
        assertFalse(Regex("""atMinute` INTEGER[^;]*DEFAULT""").containsMatchIn(v24))
        assertTrue(Regex("""atMinute` INTEGER[^;]*DEFAULT""").containsMatchIn(v24.replace("`atMinute` INTEGER", "`atMinute` INTEGER DEFAULT 480")))
    }

    @Test
    fun `nothing is back-filled`() {
        val writes = Regex("""\b(UPDATE|INSERT)\b""")
        assertFalse("the migration writes rows", writes.containsMatchIn(v24))
        assertTrue(writes.containsMatchIn("$v24; INSERT INTO `sky_stars` SELECT 'journal', id, NULL, 1, NULL FROM journal_entries"))
    }

    /** Every kind of star has a trigger on its own table, so deleting any memory forgets its star. */
    @Test
    fun `deleting any memory forgets what was chosen for its star`() {
        val keys = Regex("""^\s+[A-Z_]+\("(\w+)", """, RegexOption.MULTILINE).findAll(kinds.substringAfter("enum class SkyKind"))
            .map { it.groupValues[1] }.toSet()
        assertTrue("SkyKind was not read", "check_in" in keys && "life_event" in keys)
        val covered = triggered(database)
        assertEquals(keys, covered.keys)
        assertEquals("mood_entries", covered["check_in"])
        assertEquals("goals", covered["goal_reached"])
        assertEquals("goal_steps", covered["project_step"])
        // Run on a fresh install and on the upgrade alike.
        assertTrue(database.substringAfter("override fun onCreate").substringBefore("val SKY_STAR_TRIGGERS").contains("SKY_STAR_TRIGGERS.forEach(db::execSQL)"))
        assertTrue(database.substringAfter("val MIGRATION_23_24").substringBefore("val DEFAULT_ACTIVITIES").contains("SKY_STAR_TRIGGERS.forEach(db::execSQL)"))
        // The control: a list missing a table is seen.
        val planted = database.replace("\"journal\" to \"journal_entries\",", "")
        assertNotEquals("the mutation did not land", database, planted)
        assertNotEquals(keys, triggered(planted).keys)
    }

    private fun strip(source: String): String = source
        .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
        .lines()
        .filterNot { it.trimStart().startsWith("//") }
        .joinToString("\n")
}
