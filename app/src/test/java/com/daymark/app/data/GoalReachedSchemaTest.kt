package com.daymark.app.data

import com.daymark.app.backup.repoFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `goals.reachedAt` — the column, the migration that adds it, the list that has to register that
 * migration, the backup that has to carry it, and the rule that nothing may derive it.
 *
 * ## Why this is a source test
 *
 * Same reason as `LifeEventSchemaTest`, which this follows: the honest check is
 * `MigrationTestHelper.runMigrationsAndValidate`, it is instrumented, and `build.yml` runs `test` +
 * `assembleDebug` and never `connectedAndroidTest`. Nothing on this branch executes it. The import
 * paths are equally unreachable from here — `BackupManager` has eighteen collaborators, four of them
 * final classes, and this module has no mocking framework and no Robolectric
 * (`BackupReplaceSourceTest` sets out the whole argument). A source assertion that runs on every
 * push is worth more than an instrumented one that runs nowhere.
 *
 * ## The three failures this is aimed at
 *
 * 1. **A migration that does not match its entity.** Room compares the migrated table against the
 *    SQL it would have written itself; a `NOT NULL DEFAULT 0` here would also hand every goal on
 *    every phone a claim that it was reached at the epoch.
 * 2. **A migration written and never registered.** `AppModule.addMigrations(...)` is a second,
 *    hand-maintained list. Forget an entry and Room finds no path from the installed version to the
 *    new one and throws on first access — only for people who already had data, which is why it
 *    survives every fresh-install test.
 * 3. **`archived` read as "reached".** The one this column exists to prevent. Archiving is giving up
 *    on a goal or setting it aside (`docs/DECISIONS_2026-08.md` §D5); wiring it to `reachedAt` would
 *    draw a "goal reached" star for the act of letting go. It cannot be caught by running anything,
 *    because the wrong version works — so it is asserted as an absence, with a detector for each.
 */
class GoalReachedSchemaTest {

    private companion object {
        const val ENTITY = "app/src/main/java/com/daymark/app/data/entity/Goal.kt"
        const val DATABASE = "app/src/main/java/com/daymark/app/data/AppDatabase.kt"
        const val MODULE = "app/src/main/java/com/daymark/app/di/AppModule.kt"
        const val DAO = "app/src/main/java/com/daymark/app/data/dao/GoalDao.kt"
        const val BACKUP = "app/src/main/java/com/daymark/app/backup/BackupManager.kt"
        const val LOGIC = "app/src/main/java/com/daymark/app/goals/GoalReached.kt"
        const val GOALS_VM = "app/src/main/java/com/daymark/app/ui/goals/GoalsViewModel.kt"
        const val EDITOR_VM = "app/src/main/java/com/daymark/app/ui/goals/GoalEditorViewModel.kt"
    }

    private val entity = strip(read(ENTITY))
    private val database = strip(read(DATABASE))
    private val module = strip(read(MODULE))
    private val dao = strip(read(DAO))
    private val backup = strip(read(BACKUP))

    private fun read(rel: String) = repoFile(rel).readText()

    // ---------------------------------------------------------------------------------------------
    // Guards. Every assertion below is a read of one of these slices; an empty or mis-sliced read
    // would make the absence checks pass against nothing.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `every source was found and stripped to something that is still code`() {
        assertTrue(entity.contains("data class Goal("))
        assertTrue(database.contains("val MIGRATION_16_17"))
        assertTrue(module.contains("addMigrations("))
        assertTrue(dao.contains("interface GoalDao"))
        assertTrue(backup.contains("data class BackupGoal("))

        // Stripping must remove the prose and keep the code, or the absence checks below are
        // reading KDoc — every one of these files explains `archived` at length in its comments.
        assertTrue("KDoc survived stripping", read(ENTITY).contains("/**"))
        assertFalse("KDoc survived stripping", entity.contains("/**"))
        assertTrue("the entity declaration was stripped away", entity.contains("val reachedAt: Long?"))
        // A phrase that exists only inside the v17 migration's KDoc. Present in the file, absent
        // from the stripped copy — otherwise the absence checks below are reading an explanation of
        // the rule instead of the code that keeps it.
        assertTrue("the migration lost its KDoc", read(DATABASE).contains("It does not backfill"))
        assertFalse("KDoc survived stripping", database.contains("It does not backfill"))
    }

    // ---------------------------------------------------------------------------------------------
    // The column and its migration.
    // ---------------------------------------------------------------------------------------------

    /**
     * A nullable `INTEGER`, added by one `ALTER TABLE`, matching the entity field for field.
     *
     * `NOT NULL DEFAULT 0` is the shape v11 used for `cue`/`routine` and is exactly wrong here: NULL
     * means nobody has been asked, and 0 is a date. Getting this wrong would not crash — it would
     * hand every goal on every existing phone a reached-at of 1 January 1970 and put a star there.
     */
    @Test
    fun `the migration adds the nullable column the entity declares, and nothing else`() {
        val sql = migrationSql()

        assertEquals(listOf("ALTER TABLE goals ADD COLUMN reachedAt INTEGER"), sql)
        assertFalse("the column is NOT NULL, so existing rows cannot mean 'not asked'", sql.single().contains("NOT NULL"))
        assertFalse("the column has a DEFAULT, so every existing goal gets a date", sql.single().contains("DEFAULT"))

        // The entity's half of the match: nullable Long, and no @ColumnInfo default to disagree with
        // the SQL above.
        assertTrue(entity.contains("val reachedAt: Long? = null"))
        assertFalse(
            "the entity gives reachedAt a @ColumnInfo default the migration does not write",
            entity.contains("@ColumnInfo") && goalParams().last().contains("@ColumnInfo"),
        )
    }

    /**
     * The migration reads nothing and backfills nothing.
     *
     * The wrong version of this change is one line long — `UPDATE goals SET reachedAt = createdAt
     * WHERE archived = 1` — and it runs once, silently, on the phone of every person who already has
     * goals. It would turn a list of things they let go of into a sky full of stars.
     */
    @Test
    fun `the migration does not backfill reachedAt from archived or from anything else`() {
        val body = migrationBody()

        assertFalse("the migration reads `archived`", body.contains("archived"))
        assertFalse("the migration writes rows", body.contains("UPDATE"))
        assertFalse("the migration reads rows", body.contains("SELECT"))
        assertEquals("the migration runs more than one statement", 1, body.split("execSQL").size - 1)

        // The detector: the same reads do fire on the backfill this forbids.
        val backfilled = body + """db.execSQL("UPDATE goals SET reachedAt = createdAt WHERE archived = 1")"""
        assertTrue("detector is broken", backfilled.contains("archived"))
        assertTrue("detector is broken", backfilled.contains("UPDATE"))
        assertEquals("detector is broken", 2, backfilled.split("execSQL").size - 1)
    }

    // ---------------------------------------------------------------------------------------------
    // The migration list.
    // ---------------------------------------------------------------------------------------------

    /**
     * Every migration `AppDatabase` declares is registered with the database builder.
     *
     * A set comparison rather than "MIGRATION_16_17 is in the list", because the check that names one
     * migration goes green the day an eighteenth is added and forgotten — which is exactly how this
     * is missed.
     */
    @Test
    fun `every migration AppDatabase declares is registered with the database builder`() {
        val declared = declaredMigrations(database)
        val registered = registeredMigrations(module)

        assertTrue("no migrations were parsed out of AppDatabase", declared.size >= 16)
        assertTrue("no migrations were parsed out of AppModule", registered.size >= 16)
        assertEquals(
            "a migration exists and is not registered: Room will find no path from the version " +
                "already on the phone to the new one and throw on the first database access, for " +
                "everyone who had data and for nobody who installed fresh",
            declared,
            registered,
        )
        assertTrue("MIGRATION_16_17 was not declared at all", declared.contains("MIGRATION_16_17"))
    }

    @Test
    fun `the registration check fails against a module that forgot the newest migration`() {
        val preFix = module.replace("                AppDatabase.MIGRATION_16_17,\n", "")

        assertFalse("mutation did not land", registeredMigrations(preFix).contains("MIGRATION_16_17"))
        assertEquals(
            listOf("MIGRATION_16_17"),
            declaredMigrations(database) - registeredMigrations(preFix).toSet(),
        )
    }

    /**
     * The chain runs 1 → the declared version with no gap, and each migration's name agrees with its
     * `Migration(from, to)` arguments. A gap is the same crash as a missing registration, reached
     * differently: someone sitting on the version before it has no path forward.
     */
    @Test
    fun `the migration chain reaches the declared database version without a gap`() {
        val pattern = Regex("""val MIGRATION_(\d+)_(\d+) = object : Migration\((\d+), (\d+)\)""")
        val matches = pattern.findAll(database).toList()

        assertTrue("no migrations parsed", matches.size >= 16)
        for (m in matches) {
            assertEquals("MIGRATION_x_y disagrees with Migration(x, y)", m.groupValues[1], m.groupValues[3])
            assertEquals("MIGRATION_x_y disagrees with Migration(x, y)", m.groupValues[2], m.groupValues[4])
        }

        val version = Regex("""version = (\d+),""").find(database)!!.groupValues[1].toInt()

        // The migration that actually adds the column, found by its body rather than by name, and
        // the version checked against *it* rather than against a literal 17. A literal here would
        // go red on the next unrelated column anyone adds, and a guard that fails for reasons the
        // person reading it did not cause is a guard that gets its number bumped without thought.
        val adds = matches.singleOrNull { m ->
            val body = database.substring(m.range.first, endOfMigration(database, m.range.first))
            body.contains("ADD COLUMN reachedAt")
        }
        assertTrue("no migration adds the reachedAt column", adds != null)
        val addedAt = adds!!.groupValues[4].toInt()
        assertTrue(
            "the schema version ($version) is behind the migration that adds reachedAt ($addedAt)",
            version >= addedAt,
        )

        assertEquals(
            "the chain does not run 1 → $version",
            (1 until version).map { it to it + 1 },
            matches.map { it.groupValues[3].toInt() to it.groupValues[4].toInt() }.sortedBy { it.first },
        )
    }

    /** The end of the migration starting at [start]: the next migration declaration, or EOF. */
    private fun endOfMigration(source: String, start: Int): Int {
        val next = Regex("""val MIGRATION_\d+_\d+ = object : Migration\(""").find(source, start + 1)
        return next?.range?.first ?: source.length
    }

    // ---------------------------------------------------------------------------------------------
    // The read the Sky needs.
    // ---------------------------------------------------------------------------------------------

    /**
     * The query that feeds a `goal_reached` star selects on the mark and on nothing else.
     *
     * `archived` must not appear in it in either direction: `archived = 1` is the misreading this
     * column exists to prevent, and `archived = 0` would delete a star the person placed the moment
     * they tidied the goal off their list.
     *
     * There is deliberately no `ORDER BY` to assert. This query feeds the Sky and nothing else, and
     * `Sky.layout` sorts — ordering here as well would be a second place for the ordering rule to
     * live, which is why none of the six projections carries one.
     */
    @Test
    fun `the reached query selects on the mark alone, with archived in neither direction`() {
        val query = daoQueryFor("observeSkyPoints")

        assertTrue("no observeSkyPoints query on GoalDao", query.isNotEmpty())
        assertTrue("the query does not select on the mark", query.contains("reachedAt IS NOT NULL"))
        assertFalse("the query filters on archived", query.contains("archived"))

        // The detector: this reader does find `archived` in a query that has it — `observeActive`,
        // three lines up, is filtered that way and is meant to be.
        assertTrue("detector is broken", daoQueryFor("observeActive").contains("archived"))
    }

    // ---------------------------------------------------------------------------------------------
    // Nothing derives the mark.
    // ---------------------------------------------------------------------------------------------

    /**
     * No statement that touches `reachedAt` also touches a progress term.
     *
     * This is the rule stated as something a machine can check. A habit hitting `targetPerWeek` is
     * not reached and a project with every step in *Done* is not reached — both are thresholds, and a
     * threshold that flips this field is the app deciding something about a person and acting on it.
     * `GoalProgressUi` holds `isMet` and `reached` as neighbours, so the temptation is a line away.
     *
     * Line by line rather than file by file, because these files must be allowed to *mention*
     * progress — they are the goals screens.
     */
    @Test
    fun `no line that writes reachedAt also reads a count, a target or archived`() {
        val files = listOf(LOGIC, GOALS_VM, EDITOR_VM, DAO, ENTITY)

        // Positive control: the files must actually contain the field, or this scans nothing.
        assertTrue(
            "no source scanned mentions reachedAt at all",
            files.count { strip(read(it)).contains("reachedAt") } >= 4,
        )
        for (rel in files) assertEquals("$rel derives the mark", emptyList<String>(), derivations(strip(read(rel))))

        // The detector, in the two forms this would really arrive in.
        assertEquals(
            listOf("            reachedAt = if (isMet) now else null,"),
            derivations("            reachedAt = if (isMet) now else null,"),
        )
        assertEquals(
            listOf("val reachedAt = if (archived) createdAt else null"),
            derivations("val reachedAt = if (archived) createdAt else null"),
        )
        // And it must not fire on the lines this feature actually has.
        assertEquals(emptyList<String>(), derivations("val reached: Boolean get() = GoalReached.isReached(goal.reachedAt)"))
    }

    /**
     * The decision file is not given the inputs in the first place.
     *
     * `GoalReached` takes a timestamp and a boolean. It has no parameter named `archived`, no count
     * and no target, so the wrong version of this feature cannot be written inside it — which is
     * worth asserting, because the line-level check above only catches the mistake once someone
     * writes it down next to the field.
     */
    @Test
    fun `the decision file never sees archived, a count or a target`() {
        val logic = strip(read(LOGIC))

        assertTrue("GoalReached.kt did not read", logic.contains("object GoalReached"))
        for (token in listOf("archived", "targetPerWeek", "completed", "isMet", "GoalProgress", "fraction")) {
            assertFalse("GoalReached takes $token as an input", logic.contains(token))
        }
        // The detector: the same scan does fire once one of them is a parameter.
        val withArchived = logic.replace(
            "fun isReached(reachedAt: Long?): Boolean",
            "fun isReached(reachedAt: Long?, archived: Boolean): Boolean",
        )
        assertNotEquals("mutation did not land", logic, withArchived)
        assertTrue("detector is broken", withArchived.contains("archived"))
    }

    // ---------------------------------------------------------------------------------------------
    // The backup.
    // ---------------------------------------------------------------------------------------------

    /**
     * The mark survives an export and both import paths.
     *
     * A field missing from a backup fails the way `offer_records` and `goal_steps` did: not a wrong
     * call but a missing one, with nothing to fail on. Here the loss is quiet twice over — a restored
     * goal simply comes back unmarked, which looks exactly like a goal the person never marked.
     */
    @Test
    fun `the backup exports the mark and both import paths write it back`() {
        assertTrue("BackupGoal has no field for the mark", backup.contains("val reachedAt: Long? = null,"))
        assertTrue("the export never reads Goal.reachedAt", exportBody().contains("it.reachedAt"))
        assertTrue("REPLACE does not restore the mark", replaceBody().contains("it.kind, it.reachedAt"))
        assertTrue("MERGE does not restore the mark", mergeBody().contains("g.kind, g.reachedAt"))

        // The detectors: each read fails against the path that forgot it.
        assertFalse("detector is broken", exportBody().replace("it.reachedAt", "").contains("it.reachedAt"))
        assertFalse("detector is broken", replaceBody().replace(", it.reachedAt", "").contains("it.reachedAt"))
        assertFalse("detector is broken", mergeBody().replace(", g.reachedAt", "").contains("g.reachedAt"))
    }

    @Test
    fun `the three backup bodies this file asserts against are real bodies`() {
        // Every claim above is a `contains` on one of these slices; an empty slice would make the
        // positive ones fail loudly and the detectors pass vacuously. Bound both ends so a slicer
        // that swallowed the whole file is caught too.
        assertTrue("exportToJson not found", exportBody().length in 1000..8000)
        assertTrue("importReplace not found", replaceBody().length in 1000..12000)
        assertTrue("importMerge not found", mergeBody().length in 1000..12000)
    }

    /**
     * A file written by this build declares a version that admits it carries the mark.
     *
     * Left at 15, a v15 reader would accept the file and drop `reachedAt` — and the date under it —
     * without a word.
     */
    @Test
    fun `the backup format version was bumped for the mark`() {
        val version = Regex("""const val CURRENT_VERSION = (\d+)""").find(backup)!!.groupValues[1].toInt()
        assertTrue("CURRENT_VERSION was not bumped past the markless format", version >= 16)
    }

    /**
     * `reachedAt` is the **last** parameter of both `Goal` and `BackupGoal`.
     *
     * `BackupManager` builds both positionally, in three places between them. `Goal.kind`'s KDoc
     * records what a mid-constructor insert did once: it shifted `archived` into `targetPerWeek`, and
     * because every field involved is defaulted, it still compiled.
     */
    @Test
    fun `the new field is appended, not inserted, on both sides of the backup`() {
        assertEquals("reachedAt", goalParams().last().substringAfter("val ").substringBefore(":"))
        assertEquals("reachedAt", backupGoalParams().last().substringAfter("val ").substringBefore(":"))

        // Positive control: the parsers read whole constructors, not one line each.
        assertTrue("Goal's constructor did not parse", goalParams().size >= 9)
        assertTrue("BackupGoal's constructor did not parse", backupGoalParams().size >= 9)
        assertEquals("id", goalParams().first().substringAfter("val ").substringBefore(":"))
    }

    // ---------------------------------------------------------------------------------------------
    // Parsing.
    // ---------------------------------------------------------------------------------------------

    /** Source with block comments and line comments removed — claims hold against code, not prose. */
    private fun strip(source: String): String = source
        .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
        .lines()
        .filterNot { it.trimStart().startsWith("//") }
        .joinToString("\n")

    private fun migrationBody(): String =
        database.substringAfter("val MIGRATION_16_17").substringBefore("val DEFAULT_ACTIVITIES")

    /** Every string literal the v17 migration executes, in order. */
    private fun migrationSql(): List<String> =
        Regex("\"([^\"]*)\"").findAll(migrationBody()).map { it.groupValues[1] }.toList()

    /** The SQL of the `@Query` immediately above [function] on the DAO. */
    private fun daoQueryFor(function: String): String =
        Regex("""@Query\("([^"]*)"\)\s*(?:suspend\s+)?fun $function""")
            .find(dao)
            ?.groupValues
            ?.get(1)
            .orEmpty()

    /** Constructor parameters of a data class, in declaration order. */
    private fun params(source: String, header: String): List<String> =
        source.substringAfter(header).substringBefore("\n)")
            .lines()
            .map { it.trim().removeSuffix(",") }
            .filter { it.startsWith("val ") || it.contains(") val ") }

    private fun goalParams() = params(entity, "data class Goal(")

    private fun backupGoalParams() = params(backup, "data class BackupGoal(")

    /**
     * Lines that mention `reachedAt` and a progress term at once.
     *
     * Deliberately crude: any co-occurrence on one line is reported, because there is no legitimate
     * expression that needs both, and a check that tried to understand the expression would be the
     * one thing here more likely to be wrong than the code it guards.
     */
    private fun derivations(source: String): List<String> {
        val progress = listOf("archived", "targetPerWeek", "completed", "isMet", "fraction", "GoalProgress")
        return source.lines().filter { line ->
            line.contains("reachedAt") && progress.any { line.contains(it) }
        }
    }

    private fun declaredMigrations(source: String): List<String> =
        Regex("""val (MIGRATION_\d+_\d+) = object""").findAll(source).map { it.groupValues[1] }.toList().sorted()

    private fun registeredMigrations(source: String): List<String> =
        Regex("""AppDatabase\.(MIGRATION_\d+_\d+)""")
            .findAll(source.substringAfter("addMigrations(").substringBefore(".build()"))
            .map { it.groupValues[1] }
            .toList()
            .sorted()

    private fun exportBody(): String =
        backup.substringAfter("suspend fun exportToJson").substringBefore("suspend fun exportEntriesCsv")

    private fun replaceBody(): String =
        backup.substringAfter("private suspend fun importReplace").substringBefore("private suspend fun importMerge")

    private fun mergeBody(): String = backup.substringAfter("private suspend fun importMerge")
}
