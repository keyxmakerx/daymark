package com.daymark.app.data

import com.daymark.app.backup.repoFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v23, the sky's second batch (`DECISIONS.md` §D11), read as source: the put-away table is the
 * table its entity declares, each added column's `DEFAULT` is spelled as its entity declares it,
 * and nothing is back-filled — no life event is made hard, no tracker is shown and nothing is put
 * away by a migration, because only the person does any of them. Run by `tools/jvm-source-tests.sh`.
 */
class SkyPutAwaySchemaTest {

    private val database = strip(repoFile("app/src/main/java/com/daymark/app/data/AppDatabase.kt").readText())
    private val module = strip(repoFile("app/src/main/java/com/daymark/app/di/AppModule.kt").readText())
    private val putAway = strip(repoFile("app/src/main/java/com/daymark/app/data/entity/SkyPutAway.kt").readText())
    private val lifeEvent = strip(repoFile("app/src/main/java/com/daymark/app/data/entity/LifeEvent.kt").readText())
    private val tracker = strip(repoFile("app/src/main/java/com/daymark/app/data/entity/Tracker.kt").readText())

    /** Each statement the migration runs, its literals joined, the statements apart. */
    private val v23: String = database.substringAfter("val MIGRATION_22_23 = object")
        .substringBefore("val DEFAULT_ACTIVITIES")
        .split("db.execSQL(").drop(1)
        .joinToString("; ") { call -> Regex("\"([^\"]*)\"").findAll(call).joinToString("") { it.groupValues[1] } }

    /** `(table, column) -> default` for every column a migration adds. */
    private fun added(sql: String): Map<Pair<String, String>, String> =
        Regex("""ALTER TABLE `(\w+)` ADD COLUMN `(\w+)` \w+ NOT NULL DEFAULT ('?[\w-]+'?)""").findAll(sql)
            .associate { (it.groupValues[1] to it.groupValues[2]) to it.groupValues[3].trim('\'') }

    private fun declared(entity: String): Map<String, String> =
        Regex("""@ColumnInfo\(defaultValue = "([^"]*)"\) val (\w+)""").findAll(entity)
            .associate { it.groupValues[2] to it.groupValues[1] }

    @Test
    fun `the migration was read, and adds two columns and one table`() {
        assertTrue(database.contains("val MIGRATION_22_23 = object : Migration(22, 23)"))
        assertEquals(setOf("life_events" to "hard", "trackers" to "showInSky"), added(v23).keys)
        assertTrue(v23, v23.contains("CREATE TABLE IF NOT EXISTS `sky_put_away` ("))
    }

    @Test
    fun `every added default is the entity's, so no event is hard and no tracker shows`() {
        assertEquals("0", added(v23).getValue("life_events" to "hard"))
        assertEquals(declared(lifeEvent)["hard"], added(v23).getValue("life_events" to "hard"))
        assertEquals("0", added(v23).getValue("trackers" to "showInSky"))
        assertEquals(declared(tracker)["showInSky"], added(v23).getValue("trackers" to "showInSky"))
        // The control: a migration that marked every event hard is seen.
        val planted = v23.replace("`hard` INTEGER NOT NULL DEFAULT 0", "`hard` INTEGER NOT NULL DEFAULT 1")
        assertNotEquals("the mutation did not land", v23, planted)
        assertEquals("1", added(planted).getValue("life_events" to "hard"))
    }

    @Test
    fun `the put-away table is the entity's, keyed by the record it hides`() {
        val body = v23.substringAfter("CREATE TABLE IF NOT EXISTS `sky_put_away` (").substringBefore(";")
            .removeSuffix(")")
        val columns = body.substringBefore(", PRIMARY KEY").split(", ").map { it.trim() }
        assertEquals(
            listOf("`kind` TEXT NOT NULL", "`recordId` INTEGER NOT NULL", "`putAwayEpochDay` INTEGER NOT NULL"),
            columns,
        )
        assertEquals("PRIMARY KEY(`kind`, `recordId`)", "PRIMARY KEY" + body.substringAfter(", PRIMARY KEY"))
        val fields = Regex("""val (\w+): (\w+)""").findAll(putAway.substringAfter("data class SkyPutAway("))
            .map { it.groupValues[1] }.toList()
        assertEquals(listOf("kind", "recordId", "putAwayEpochDay"), fields)
        assertTrue(putAway.contains("primaryKeys = [\"kind\", \"recordId\"]"))
    }

    @Test
    fun `nothing is back-filled`() {
        val writes = Regex("""\b(UPDATE|INSERT|DELETE)\b""")
        assertFalse("the migration writes rows", writes.containsMatchIn(v23))
        assertTrue(writes.containsMatchIn("$v23 INSERT INTO `sky_put_away` SELECT 'journal', id, 0 FROM journal"))
    }

    @Test
    fun `the table is registered, reachable and migrated to`() {
        assertTrue(database.contains("com.daymark.app.data.entity.SkyPutAway::class"))
        assertTrue(database.contains("fun skyPutAwayDao()"))
        assertTrue(module.contains("fun provideSkyPutAwayDao("))
        assertTrue(module.contains("AppDatabase.MIGRATION_22_23,"))
        assertTrue(database.contains("version = 23,"))
    }

    private fun strip(source: String): String = source
        .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
        .lines()
        .filterNot { it.trimStart().startsWith("//") }
        .joinToString("\n")
}
