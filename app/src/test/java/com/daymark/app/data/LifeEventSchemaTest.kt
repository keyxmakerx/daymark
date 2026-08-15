package com.daymark.app.data

import com.daymark.app.backup.repoFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `life_events`, checked three ways a plain-JVM test can actually check it: the migration's SQL
 * against the entity it has to produce, the migration list in `di/AppModule.kt` against the
 * migrations `AppDatabase` declares, and the entity against the fields it is allowed to have.
 *
 * ## Why this is a source test
 *
 * The honest version is `MigrationTestHelper.runMigrationsAndValidate`, which builds the database at
 * v15, runs the migration and compares the result against Room's own exported schema. It is
 * instrumented: it needs a device, and `build.yml` runs `test` + `assembleDebug` and never
 * `connectedAndroidTest` (`MigrationTest.kt` says so in its own header). So on this branch nothing
 * executes it. A source assertion that runs on every push is worth more than an instrumented one
 * that runs nowhere.
 *
 * ## The two failures this is aimed at
 *
 * 1. **A migration that does not match its entity.** Room compares the migrated table against the
 *    SQL it would have written itself, wording included, so a column in a different order or an
 *    index left out fails — not at runtime, but on the next contributor's change, attributed to
 *    them. This repo has shipped that drift before.
 * 2. **A migration written and never registered.** `AppModule.addMigrations(...)` is a second,
 *    hand-maintained list of the same migrations. Forget an entry and Room finds no path from the
 *    installed version to the new one and throws on the first database access — only for people who
 *    already had data, which is why it survives every fresh-install test.
 */
class LifeEventSchemaTest {

    private companion object {
        const val ENTITY = "app/src/main/java/com/daymark/app/data/entity/LifeEvent.kt"
        const val DATABASE = "app/src/main/java/com/daymark/app/data/AppDatabase.kt"
        const val MODULE = "app/src/main/java/com/daymark/app/di/AppModule.kt"

        const val TABLE = "life_events"
        const val INDEX = "index_life_events_epochDay"
    }

    private val entitySource = strip(repoFile(ENTITY).readText())
    private val databaseSource = strip(repoFile(DATABASE).readText())
    private val moduleSource = strip(repoFile(MODULE).readText())

    // ------------------------------------------------------------------------------------------
    // Guards. Every assertion below reads one of these three files; an empty or mis-sliced read
    // would make the comparisons pass against nothing.
    // ------------------------------------------------------------------------------------------

    @Test
    fun `all three sources were found and stripped to something that is still code`() {
        assertTrue(entitySource.contains("data class LifeEvent"))
        assertTrue(databaseSource.contains("val MIGRATION_15_16"))
        assertTrue(moduleSource.contains("addMigrations("))
        // Stripping must have removed the prose and kept the code, or the parsers below are reading
        // KDoc — this file's entity KDoc is longer than its declaration.
        assertTrue("KDoc survived stripping", repoFile(ENTITY).readText().contains("/**"))
        assertFalse("KDoc survived stripping", entitySource.contains("/**"))
        assertTrue("the entity declaration was stripped away", entitySource.contains("val epochDay: Long"))

        assertEquals(4, entityFields(entitySource).size)
        assertEquals(4, sqlColumns(createTable(databaseSource)).size)
    }

    // ------------------------------------------------------------------------------------------
    // The migration against the entity.
    // ------------------------------------------------------------------------------------------

    /**
     * Column for column, in declaration order, in Room's own wording.
     *
     * Rendering the entity into the SQL Room would generate and comparing whole strings is
     * deliberate: a looser check (the column names are all present) passes a `TEXT` column that
     * should be `INTEGER`, a nullable column that should be `NOT NULL`, and a reordering — all
     * three of which fail Room's own comparison on the device.
     */
    @Test
    fun `the migration creates exactly the table the entity declares`() {
        assertEquals(
            listOf(
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL",
                "`epochDay` INTEGER NOT NULL",
                "`label` TEXT NOT NULL",
                "`createdAt` INTEGER NOT NULL",
            ),
            sqlColumns(createTable(databaseSource)),
        )
        assertEquals(renderedFromEntity(entitySource), sqlColumns(createTable(databaseSource)))
        assertTrue(createTable(databaseSource).contains("CREATE TABLE IF NOT EXISTS `$TABLE` ("))
    }

    /**
     * The comparison above has to be able to fail. These are the four ways this migration would
     * really have been written wrong, and each one is silent until Room compares the schemas.
     */
    @Test
    fun `the comparison rejects a dropped column, a wrong type, a lost NOT NULL and a reorder`() {
        val right = renderedFromEntity(entitySource)

        val dropped = right - "`createdAt` INTEGER NOT NULL"
        val wrongType = right.map { it.replace("`label` TEXT", "`label` INTEGER") }
        val nullable = right.map { it.replace("`epochDay` INTEGER NOT NULL", "`epochDay` INTEGER") }
        val reordered = listOf(right[0], right[2], right[1], right[3])

        assertNotEquals("a dropped column would pass", dropped, right)
        assertNotEquals("a wrong affinity would pass", wrongType, right)
        assertNotEquals("a lost NOT NULL would pass", nullable, right)
        assertNotEquals("a reordering would pass", reordered, right)
        // And the mutations must be mutations of something real, not of an empty list.
        assertEquals(4, right.size)
        assertEquals(3, dropped.size)
    }

    /**
     * The index the entity declares is the index the migration creates, by name and by column.
     *
     * An index missing from a migration is the quietest form of this drift: every query still
     * works, so nothing looks wrong until Room compares the two schemas.
     */
    @Test
    fun `the migration creates the entity's index, with the name Room derives`() {
        assertTrue("the entity does not index epochDay", entitySource.contains("""indices = [Index("epochDay")]"""))

        val index = createIndex(databaseSource)
        assertTrue("no CREATE INDEX in the migration", index.isNotEmpty())
        assertTrue("the index is not the name Room derives from the entity", index.contains("`$INDEX`"))
        assertTrue("the index is on the wrong table", index.contains("ON `$TABLE`"))
        assertTrue("the index is on the wrong column", index.contains("(`epochDay`)"))
        // Room writes a UNIQUE index only for Index(unique = true); this one is not unique, because
        // two things can happen to a person on one day.
        assertFalse("the index is unique, so a second event on one day would be refused", index.contains("UNIQUE"))

        // The detector: the same reads do fail against a migration whose index was forgotten.
        val without = databaseSource.replace("CREATE INDEX IF NOT EXISTS `$INDEX`", "")
        assertFalse("detector is broken", createIndex(without).contains("`$INDEX`"))
    }

    // ------------------------------------------------------------------------------------------
    // The migration list.
    // ------------------------------------------------------------------------------------------

    /**
     * Every migration `AppDatabase` declares is registered in `AppModule.addMigrations(...)`.
     *
     * Stated as a set comparison rather than "MIGRATION_15_16 is in the list", because the check
     * that names one migration goes green the day a seventeenth is added and forgotten — which is
     * exactly how this is missed.
     */
    @Test
    fun `every migration AppDatabase declares is registered with the database builder`() {
        val declared = declaredMigrations(databaseSource)
        val registered = registeredMigrations(moduleSource)

        assertTrue("no migrations were parsed out of AppDatabase", declared.size >= 15)
        assertTrue("no migrations were parsed out of AppModule", registered.size >= 15)
        assertEquals(
            "a migration exists and is not registered: Room will find no path from the version " +
                "already on the phone to the new one and throw on the first database access, for " +
                "everyone who had data and for nobody who installed fresh",
            declared,
            registered,
        )
        assertTrue("MIGRATION_15_16 was not declared at all", declared.contains("MIGRATION_15_16"))
    }

    @Test
    fun `the registration check fails against a module that forgot the newest migration`() {
        val preFix = moduleSource.replace("                AppDatabase.MIGRATION_15_16,\n", "")

        assertFalse("mutation did not land", registeredMigrations(preFix).contains("MIGRATION_15_16"))
        assertEquals(
            listOf("MIGRATION_15_16"),
            declaredMigrations(databaseSource) - registeredMigrations(preFix).toSet(),
        )
    }

    /**
     * The migrations form an unbroken chain from 1 to the database's declared version.
     *
     * A gap is the same crash as a missing registration, arrived at differently: Room needs a path
     * from whatever version is on the phone, and someone on the version before the gap has none.
     */
    @Test
    fun `the migration chain reaches the declared database version without a gap`() {
        val hops = Regex("""val MIGRATION_(\d+)_(\d+) = object : Migration\((\d+), (\d+)\)""")
            .findAll(databaseSource)
            .map { it.groupValues[3].toInt() to it.groupValues[4].toInt() }
            .toList()

        assertTrue("no migrations parsed", hops.size >= 15)
        // The name and the Migration(from, to) arguments must agree, or the list above is fiction.
        for (m in Regex("""val MIGRATION_(\d+)_(\d+) = object : Migration\((\d+), (\d+)\)""").findAll(databaseSource)) {
            assertEquals("MIGRATION_x_y disagrees with Migration(x, y)", m.groupValues[1], m.groupValues[3])
            assertEquals("MIGRATION_x_y disagrees with Migration(x, y)", m.groupValues[2], m.groupValues[4])
        }

        val version = Regex("""version = (\d+),""").find(databaseSource)!!.groupValues[1].toInt()
        assertEquals("the schema version does not match the newest migration", 16, version)
        assertEquals(
            "the chain does not run 1 → $version",
            (1 until version).map { it to it + 1 },
            hops.sortedBy { it.first },
        )
    }

    @Test
    fun `the entity is registered with the database`() {
        assertTrue(
            "LifeEvent is not in @Database(entities = ...), so Room never creates the table on a " +
                "fresh install and only upgraders would have it",
            databaseSource.contains("com.daymark.app.data.entity.LifeEvent::class"),
        )
        assertTrue("no DAO accessor for life events", databaseSource.contains("fun lifeEventDao()"))
        assertTrue("no Hilt binding for the DAO", moduleSource.contains("fun provideLifeEventDao("))
    }

    // ------------------------------------------------------------------------------------------
    // The entity's shape.
    // ------------------------------------------------------------------------------------------

    /**
     * Four columns, and the ones that are absent are the point.
     *
     * A `category` or `type` column turns grief into a dropdown; a `mood` column asks a person to
     * rate a bereavement; a `body` column makes this a second journal with different rules. Each
     * would arrive as an obvious small improvement, which is why the shape is asserted rather than
     * described. `epochDay` and not a timestamp, because a life event is a day.
     */
    @Test
    fun `the entity is a date, a line, and nothing that judges it`() {
        val fields = entityFields(entitySource)
        assertEquals(listOf("id", "epochDay", "label", "createdAt"), fields)

        // The detector: these are the names the fields this table must not have would arrive under,
        // and the check does fire on them.
        val forbidden = listOf("category", "type", "kind", "mood", "valence", "body", "note", "severity")
        assertTrue("detector is broken", forbidden.any { readsAsJudgement(it) })
        assertTrue("detector is broken", readsAsJudgement("eventType"))
        assertFalse("detector is too greedy", readsAsJudgement("epochDay"))
        assertFalse("detector is too greedy", readsAsJudgement("label"))
        for (field in fields) assertFalse("the entity has a $field column", readsAsJudgement(field))

        // A timestamp masquerading as the event's date is the other way this record loses its
        // meaning. createdAt is millis and is allowed; the *event's* date must be a day number.
        assertTrue(entitySource.contains("val epochDay: Long"))
        assertFalse("the event's date is stored as a timestamp", fields.contains("dateTime"))
    }

    private fun readsAsJudgement(field: String): Boolean =
        listOf("category", "type", "kind", "mood", "valence", "severity", "body", "note", "tag")
            .any { field.contains(it, ignoreCase = true) }

    // ------------------------------------------------------------------------------------------
    // Parsing.
    // ------------------------------------------------------------------------------------------

    /** Source with block comments and line comments removed — claims hold against code, not prose. */
    private fun strip(source: String): String = source
        .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
        .lines()
        .filterNot { it.trimStart().startsWith("//") }
        .joinToString("\n")

    /** The entity's constructor parameters, in declaration order. */
    private fun entityFields(source: String): List<String> =
        Regex("""val (\w+): \w+""")
            .findAll(source.substringAfter("data class LifeEvent(").substringBefore("\n)"))
            .map { it.groupValues[1] }
            .toList()

    /**
     * The entity rendered as the column list Room would generate for it.
     *
     * Kotlin type → affinity, non-nullable → `NOT NULL`, `@PrimaryKey(autoGenerate = true)` →
     * `INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL`. Only the forms this entity uses are handled;
     * an unrecognised type throws rather than guessing, so a new column cannot slip through as a
     * silent match.
     */
    private fun renderedFromEntity(source: String): List<String> {
        val params = source.substringAfter("data class LifeEvent(").substringBefore("\n)")
        return Regex("""(@PrimaryKey\(autoGenerate = true\) )?val (\w+): (\w+)(\??)""")
            .findAll(params)
            .map { m ->
                val (pk, name, type, nullable) = m.destructured
                val affinity = when (type) {
                    "Long", "Int", "Boolean" -> "INTEGER"
                    "String" -> "TEXT"
                    "Double", "Float" -> "REAL"
                    else -> throw AssertionError("unhandled column type $type on $name")
                }
                val notNull = if (nullable.isEmpty()) " NOT NULL" else ""
                if (pk.isNotEmpty()) "`$name` $affinity PRIMARY KEY AUTOINCREMENT$notNull" else "`$name` $affinity$notNull"
            }
            .toList()
    }

    /** Every string literal in MIGRATION_15_16, concatenated — the SQL as the migration builds it. */
    private fun migrationSql(source: String): String =
        Regex("\"([^\"]*)\"")
            .findAll(source.substringAfter("val MIGRATION_15_16").substringBefore("val DEFAULT_ACTIVITIES"))
            .joinToString("") { it.groupValues[1] }

    private fun createTable(source: String): String =
        migrationSql(source).substringBefore("CREATE INDEX")

    private fun createIndex(source: String): String =
        migrationSql(source).substringAfter("CREATE INDEX", "")

    /** The column definitions inside `CREATE TABLE ... ( ... )`. */
    private fun sqlColumns(createTable: String): List<String> =
        createTable.substringAfter("(").substringBeforeLast(")").split(", ").map { it.trim() }

    private fun declaredMigrations(source: String): List<String> =
        Regex("""val (MIGRATION_\d+_\d+) = object""").findAll(source).map { it.groupValues[1] }.toList().sorted()

    private fun registeredMigrations(source: String): List<String> =
        Regex("""AppDatabase\.(MIGRATION_\d+_\d+)""")
            .findAll(source.substringAfter("addMigrations(").substringBefore(".build()"))
            .map { it.groupValues[1] }
            .toList()
            .sorted()
}
