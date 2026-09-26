package com.daymark.app.data

import com.daymark.app.backup.repoFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The six tables v19 adds for the Companion — `game_plans`, `game_plan_items`, `game_plan_progress`,
 * `assignments`, `instrument_results` and `task_results` (#177) — checked the ways a plain-JVM test
 * can check them.
 *
 * ## Why this is a source test
 *
 * The honest check of a migration is `MigrationTestHelper.runMigrationsAndValidate`, and it needs a
 * device, which CI does not have. [PeopleSchemaTest] says the same at greater length and this file
 * follows its shape: the migration's SQL is compared with the entities it has to produce, rendered
 * the way Room renders them. The renderer is calibrated first, on five tables whose SQL is known to
 * be Room's own, so that it cannot agree with this migration by sharing its mistakes.
 * [MigrationSchemaExportTest] then compares the SQL with the text Room itself exports, once the v19
 * schema is committed.
 *
 * ## What else it holds
 *
 * - **Game plans are not treatments.** Nothing in this layer names `treatments`, and v19 names no
 *   table that already existed (`docs/COMPANION_ARCHITECTURE.md` §5).
 * - **One foreign key**, the items' composite one onto their plan version. Its columns lead the
 *   items' primary key, which is why no separate index exists.
 * - **"Replace all current data" empties all six.** `CompanionDao.deleteAll` names every one of
 *   these tables and nothing else, and the replace path calls it.
 * - **The backup file carries none of them, and `BackupManager` reads none of them.** Whether the
 *   file should carry them is not settled (#177); this holds the format still until it is.
 */
class CompanionSchemaTest {

    private companion object {
        const val ENTITY_DIR = "app/src/main/java/com/daymark/app/data/entity"
        const val DATABASE = "app/src/main/java/com/daymark/app/data/AppDatabase.kt"
        const val MODULE = "app/src/main/java/com/daymark/app/di/AppModule.kt"
        const val DAO = "app/src/main/java/com/daymark/app/data/dao/CompanionDao.kt"
        const val BACKUP = "app/src/main/java/com/daymark/app/backup/BackupManager.kt"
        const val TREATMENT = "app/src/main/java/com/daymark/app/data/entity/Treatment.kt"
        const val TREATMENT_DAO = "app/src/main/java/com/daymark/app/data/dao/TreatmentDao.kt"

        /** The six entities, each with the table it declares, in the order v19 creates them. */
        val COMPANION = linkedMapOf(
            "GamePlan" to "game_plans",
            "GamePlanItem" to "game_plan_items",
            "GamePlanProgress" to "game_plan_progress",
            "AcceptedAssignment" to "assignments",
            "InstrumentResult" to "instrument_results",
            "TaskResult" to "task_results",
        )

        /**
         * Tables whose creating statement is known to be Room's own text, because it equals the
         * `createSql` Room exported for its version, and whose entity has not changed since. Between
         * them they have a foreign key, a composite key, a text key, an autoincrement key and a REAL
         * column — every shape the Companion tables use but one, the composite foreign key.
         */
        val CALIBRATION = linkedMapOf(
            "GoalStep" to "val MIGRATION_14_15",
            "PersonNote" to "val MIGRATION_17_18",
            "EntryPersonCrossRef" to "val MIGRATION_17_18",
            "PersonGroupShare" to "val MIGRATION_17_18",
            "TrackerLog" to "val MIGRATION_6_7",
        )
    }

    private fun read(rel: String) = repoFile(rel).readText()
    private fun entity(className: String) = strip(read("$ENTITY_DIR/$className.kt"))

    private val database = strip(read(DATABASE))
    private val module = strip(read(MODULE))
    private val dao = strip(read(DAO))
    private val backup = strip(read(BACKUP))

    /** Every entity class in `data/entity`, by class name, with the table it declares. */
    private val tableOfClass: Map<String, String> by lazy {
        repoFile("$ENTITY_DIR/GamePlan.kt").parentFile.listFiles().orEmpty()
            .filter { it.name.endsWith(".kt") }
            .mapNotNull { file ->
                val src = strip(file.readText())
                val table = Regex("""@Entity\(\s*tableName = "(\w+)"""").find(src)?.groupValues?.get(1)
                val cls = Regex("""data class (\w+)\(""").find(src)?.groupValues?.get(1)
                if (table != null && cls != null) cls to table else null
            }
            .toMap()
    }

    // ---------------------------------------------------------------------------------------------
    // Guards. Every assertion below reads one of these; an empty or mis-sliced read would make the
    // comparisons pass against nothing.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `the sources were found and stripped to something that is still code`() {
        for (cls in COMPANION.keys) assertTrue("$cls not found", entity(cls).contains("data class $cls("))
        assertTrue(database.contains("val MIGRATION_18_19"))
        assertTrue(dao.contains("interface CompanionDao"))
        assertTrue(backup.contains("class BackupManager"))
        assertTrue(module.contains("addMigrations("))

        // Stripping removed the prose and kept the code; these headers are longer than the classes.
        assertTrue("KDoc survived stripping", read("$ENTITY_DIR/GamePlan.kt").contains("/**"))
        assertFalse("KDoc survived stripping", entity("GamePlan").contains("/**"))
        assertTrue("the declaration was stripped away", entity("GamePlan").contains("val sigB64: String"))

        // The slicer sliced: six statements, not the whole file and not none.
        assertEquals(6, statements().size)
        // The class index read the entity directory, not one file.
        assertTrue("the entity index is implausibly small: $tableOfClass", tableOfClass.size >= 26)
        for ((cls, table) in COMPANION) assertEquals(table, tableOfClass[cls])
    }

    // ---------------------------------------------------------------------------------------------
    // The migration against the entities.
    // ---------------------------------------------------------------------------------------------

    /**
     * The renderer reproduces Room's own text for tables where that text is known.
     *
     * Without this, the comparison below would only show that the migration and the renderer agree —
     * and both were written from the same understanding of Room, so they could agree on a mistake.
     * Each statement here equals the `createSql` in the schema Room exported for its version.
     */
    @Test
    fun `the renderer reproduces Room's text for five tables whose SQL is known to be Room's`() {
        for ((cls, marker) in CALIBRATION) {
            val table = tableOfClass.getValue(cls)
            val known = statementsOf(marker).single { it.startsWith("CREATE TABLE IF NOT EXISTS `$table` (") }
            assertEquals("the renderer does not reproduce $table", known, renderCreateTable(cls))
        }
        // The calibration covers the shapes it claims to.
        val known = CALIBRATION.keys.map { renderCreateTable(it) }
        assertTrue(known.any { it.contains("FOREIGN KEY(") && it.endsWith("ON DELETE CASCADE )") })
        assertTrue(known.any { it.contains("PRIMARY KEY(`entryId`, `personId`)") })
        assertTrue(known.any { it.contains("`groupKey` TEXT NOT NULL") && it.contains("PRIMARY KEY(`groupKey`)") })
        assertTrue(known.any { it.contains("PRIMARY KEY AUTOINCREMENT NOT NULL") })
        assertTrue(known.any { it.contains("`value` REAL NOT NULL") })
    }

    /**
     * Statement for statement, the migration is the entities rendered as Room renders them.
     *
     * Whole-string, because a looser check passes a `TEXT` that should be `INTEGER`, a nullable
     * column that should be `NOT NULL`, a reordering or a stray `DEFAULT` — each silent until Room
     * compares the schema on the first open on somebody's phone.
     */
    @Test
    fun `the migration creates exactly the tables the entities declare`() {
        assertEquals(COMPANION.keys.map { renderCreateTable(it) }, statements())
    }

    /** The comparison above has to be able to fail, in each of the ways this migration could be wrong. */
    @Test
    fun `the comparison rejects a wrong affinity, a lost nullability, a reorder, a DEFAULT and a missing clause`() {
        val right = statements()
        val items = right[1]

        val wrongType = right.map { it.replace("`score` REAL", "`score` INTEGER") }
        val notNull = right.map { it.replace("`supersedes` INTEGER,", "`supersedes` INTEGER NOT NULL,") }
        val reordered = listOf(right[0], right[2], right[1]) + right.drop(3)
        val defaulted = right.map { it.replace("`state` TEXT NOT NULL", "`state` TEXT NOT NULL DEFAULT 'todo'") }
        val noCascade = right.map { it.replace(" ON DELETE CASCADE", " ON DELETE NO ACTION") }
        val shortKey = right.map { it.replace("PRIMARY KEY(`lineageId`, `version`, `itemRef`)", "PRIMARY KEY(`lineageId`, `version`)") }

        // Each mutation landed on something real, so each inequality below is about the mutation.
        for (mutated in listOf(wrongType, notNull, defaulted, noCascade, shortKey)) {
            assertNotEquals("a mutation did not land", right, mutated)
            assertNotEquals("the comparison would pass it", COMPANION.keys.map { renderCreateTable(it) }, mutated)
        }
        assertNotEquals("a reordering would pass", COMPANION.keys.map { renderCreateTable(it) }, reordered)
        assertTrue(items.contains("PRIMARY KEY(`lineageId`, `version`, `itemRef`)"))
    }

    /**
     * Each table's key is the one its entity declares, and the signed tables are keyed by what was
     * signed.
     */
    @Test
    fun `the keys are the signed ones for signed content and the owner's own for progress`() {
        assertEquals(listOf("lineageId", "version"), primaryKeysOf("GamePlan"))
        assertEquals(listOf("lineageId", "version", "itemRef"), primaryKeysOf("GamePlanItem"))
        assertEquals(listOf("lineageId", "itemRef"), primaryKeysOf("GamePlanProgress"))
        assertEquals(listOf("lineageId", "version"), primaryKeysOf("AcceptedAssignment"))
        for (cls in listOf("InstrumentResult", "TaskResult")) {
            assertTrue("$cls lost its autoincrement id", entity(cls).contains("@PrimaryKey(autoGenerate = true) val id: Long = 0"))
            assertEquals(emptyList<String>(), primaryKeysOf(cls))
        }
        // A signed version is a Long, as the server files it; an Int would refuse a version the
        // server holds.
        for (cls in listOf("GamePlan", "GamePlanItem", "AcceptedAssignment")) {
            assertTrue("$cls.version is not a Long", entity(cls).contains("val version: Long,"))
        }
        assertTrue(entity("GamePlan").contains("val supersedes: Long?,"))
    }

    /**
     * The items cascade off their plan version; nothing else here has a foreign key.
     *
     * The progress table's lack of one is the point of it: the owner's marks belong to an item across
     * every version of its lineage, and outlive any one version of the clinician's plan.
     */
    @Test
    fun `the items cascade off their plan version and no other Companion table has a foreign key`() {
        val items = statements()[1]
        assertTrue(
            items.contains(
                "FOREIGN KEY(`lineageId`, `version`) REFERENCES `game_plans`(`lineageId`, `version`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE",
            ),
        )
        for ((index, statement) in statements().withIndex()) {
            if (index != 1) assertFalse("a foreign key appeared: $statement", statement.contains("FOREIGN KEY"))
        }
        for (cls in COMPANION.keys - "GamePlanItem") {
            assertFalse("$cls declares a foreign key", entity(cls).contains("ForeignKey("))
        }
        assertEquals(1, foreignKeysOf("GamePlanItem").size)

        // The foreign key's columns lead the items' primary key, which is what serves the cascade
        // and why the entity declares no index.
        val fkColumns = foreignKeysOf("GamePlanItem").single().childColumns
        assertEquals(fkColumns, primaryKeysOf("GamePlanItem").take(fkColumns.size))

        // The detector: the same reads see the foreign key goal_steps has, so the absences above are
        // findings rather than reads of nothing.
        assertTrue("detector is broken", database.contains("FOREIGN KEY(`goalId`) REFERENCES `goals`(`id`)"))
        assertEquals("detector is broken", 1, foreignKeysOf("GoalStep").size)
    }

    /**
     * Six `CREATE TABLE`s and nothing else: no index, no row, no change to a table that exists.
     *
     * "Every statement is a CREATE TABLE" rather than a list of forbidden words, because one of the
     * words is not absent: `ON UPDATE NO ACTION` is part of the foreign-key clause Room writes.
     */
    @Test
    fun `v19 creates six tables and does nothing else`() {
        for (statement in statements()) {
            assertTrue("not a CREATE TABLE: $statement", statement.startsWith("CREATE TABLE IF NOT EXISTS "))
        }
        assertEquals(COMPANION.values.toList(), statements().map { tableCreatedBy(it) })
        for (cls in COMPANION.keys) assertFalse("$cls declares an index", entity(cls).contains("indices ="))
        for (cls in COMPANION.keys) assertFalse("$cls declares a default", entity(cls).contains("defaultValue"))
        for (statement in statements()) assertFalse("a DEFAULT: $statement", statement.contains(" DEFAULT "))

        // The detector: these reads reject a seeded row, an index and an ALTER, and do see a DEFAULT
        // where one really is.
        for (bad in listOf(
            "INSERT INTO game_plans VALUES ('x', 0)",
            "CREATE INDEX IF NOT EXISTS `index_task_results_taskId` ON `task_results` (`taskId`)",
            "ALTER TABLE treatments ADD COLUMN planId TEXT",
        )) {
            assertFalse("detector is broken", bad.startsWith("CREATE TABLE IF NOT EXISTS "))
        }
        assertTrue("detector is broken", statementsOf("val MIGRATION_17_18").any { it.contains(" DEFAULT ") })
    }

    // ---------------------------------------------------------------------------------------------
    // Game plans are not treatments.
    // ---------------------------------------------------------------------------------------------

    /**
     * v19 names no table that already existed.
     *
     * The narrow claim is "not `treatments`" — `docs/COMPANION_ARCHITECTURE.md` §5: game plans never
     * land in the app's `treatments` table. The wider one costs nothing more and is what an additive
     * migration is: every table it names is one of the six.
     */
    @Test
    fun `v19 names no table that already existed, treatments least of all`() {
        val existing = tableOfClass.values.toSet() - COMPANION.values.toSet()
        assertTrue("the existing tables were not read", existing.contains("treatments") && existing.size >= 20)

        for (statement in statements()) {
            assertEquals("v19 names an existing table: $statement", emptySet<String>(), identifiersIn(statement) intersect existing)
        }
        assertFalse(migrationBody().contains("treatments"))

        // The detector: the same scan does see an existing table where one is named.
        val planted = "CREATE TABLE IF NOT EXISTS `x` (`id` INTEGER NOT NULL, " +
            "FOREIGN KEY(`id`) REFERENCES `treatments`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
        assertEquals(setOf("treatments"), identifiersIn(planted) intersect existing)
    }

    /**
     * Nothing in the Companion's entities or DAO names the treatments table or its entity.
     *
     * `Treatment` is the owner's own sleep-treatment marker. A clinician's guidance written into it
     * could be edited by the owner without trace and would lose its signature on a backup round trip
     * (`docs/COMPANION_SECURITY.md` Appendix R, R10). Asserted as an absence over code with comments
     * stripped, so the headers may keep explaining the rule.
     */
    @Test
    fun `the Companion layer never names treatments`() {
        val files = COMPANION.keys.map { "$ENTITY_DIR/$it.kt" } + DAO
        assertEquals(7, files.size)
        for (rel in files) {
            val code = strip(read(rel))
            assertFalse("$rel names the treatments table", code.contains("treatments"))
            assertFalse("$rel names the Treatment entity", Regex("""\bTreatment\b""").containsMatchIn(code))
        }
        // The detector: the same reads fire on the sources that do name them.
        assertTrue("detector is blind", strip(read(TREATMENT_DAO)).contains("treatments"))
        assertTrue("detector is blind", Regex("""\bTreatment\b""").containsMatchIn(strip(read(TREATMENT))))
        // ...and the header that explains the rule does mention it, so stripping is what hides it.
        assertTrue(read("$ENTITY_DIR/GamePlan.kt").contains("[Treatment]"))
    }

    // ---------------------------------------------------------------------------------------------
    // The wiring.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `the entities, the DAO accessor, the Hilt binding and the migration are all wired`() {
        for (cls in COMPANION.keys) {
            assertTrue(
                "$cls is not in @Database(entities = ...), so a fresh install never creates its table",
                database.contains("com.daymark.app.data.entity.$cls::class"),
            )
        }
        assertTrue("no DAO accessor on AppDatabase", database.contains("fun companionDao(): com.daymark.app.data.dao.CompanionDao"))
        assertTrue("no Hilt binding for CompanionDao", module.contains("fun provideCompanionDao("))

        // Every migration declared is registered, and the chain runs 1 → the declared version.
        val declared = Regex("""val (MIGRATION_\d+_\d+) = object""").findAll(database).map { it.groupValues[1] }.toSet()
        val registered = Regex("""AppDatabase\.(MIGRATION_\d+_\d+)""")
            .findAll(module.substringAfter("addMigrations(").substringBefore(".build()"))
            .map { it.groupValues[1] }.toSet()
        assertTrue("MIGRATION_18_19 was not declared", declared.contains("MIGRATION_18_19"))
        assertEquals(declared, registered)

        val hops = Regex("""val MIGRATION_(\d+)_(\d+) = object : Migration\((\d+), (\d+)\)""").findAll(database)
            .map { it.groupValues[3].toInt() to it.groupValues[4].toInt() }.toList()
        val version = Regex("""version = (\d+),""").find(database)!!.groupValues[1].toInt()
        assertEquals((1 until version).map { it to it + 1 }, hops.sortedBy { it.first })
        assertTrue("the schema version is behind the migration that creates these tables", version >= 19)

        // The detector: the registration check does fail on a module that forgot this migration.
        val forgot = module.replace("                AppDatabase.MIGRATION_18_19,\n", "")
        assertNotEquals("mutation did not land", module, forgot)
        assertFalse(forgot.contains("AppDatabase.MIGRATION_18_19"))
    }

    // ---------------------------------------------------------------------------------------------
    // "Replace all current data", and the backup file.
    // ---------------------------------------------------------------------------------------------

    /**
     * `CompanionDao.deleteAll` empties every Companion table and nothing else.
     *
     * `BackupReplaceSourceTest` counts doors: it sees that the replace path touches `companionDao`.
     * That is satisfied by any one call, so it cannot see a seventh table added and forgotten here;
     * this is the check that can.
     */
    @Test
    fun `deleteAll empties all six Companion tables and nothing else`() {
        assertEquals(COMPANION.values.toSet(), tablesEmptiedBy(deleteAllBody()))
        assertEquals("a delete runs twice, or a call is not a delete", 6, callsIn(deleteAllBody()).size)

        // Items before plans, so the erase does not depend on the cascade being switched on.
        val calls = callsIn(deleteAllBody())
        assertTrue(calls.indexOf("deleteAllGamePlanItems") < calls.indexOf("deleteAllGamePlans"))

        // The detector: dropping one call is seen.
        val forgot = deleteAllBody().replace("deleteAllTaskResults()", "")
        assertNotEquals("mutation did not land", deleteAllBody(), forgot)
        assertEquals(COMPANION.values.toSet() - "task_results", tablesEmptiedBy(forgot))
    }

    /** The replace path runs that erase, and it is the only thing `BackupManager` does with the DAO. */
    @Test
    fun `Replace all empties the Companion tables, and BackupManager never reads one`() {
        val replacePath = backup.substringAfter("suspend fun importFromJson").substringBefore("private suspend fun importMerge")
        assertTrue("replace path not sliced", replacePath.length in 500..8000)
        assertTrue("Replace all leaves the Companion tables standing", replacePath.contains("companionDao.deleteAll()"))

        // Exactly one use of the DAO in the whole class, and it is the erase: a backup path reading
        // these rows is the first step towards exporting them, which is not decided (#177).
        assertEquals(listOf("companionDao.deleteAll()"), Regex("""companionDao\.\w+\([^)]*\)""").findAll(backup).map { it.value }.toList())

        // The detector: a read planted into the export is seen.
        val planted = backup.replace("suspend fun exportToJson(nowMillis: Long): String {", "suspend fun exportToJson(nowMillis: Long): String {\n companionDao.highestAcceptedGamePlanVersion(\"x\")")
        assertNotEquals("mutation did not land", backup, planted)
        assertEquals(2, Regex("""companionDao\.\w+\([^)]*\)""").findAll(planted).count())
    }

    /**
     * The backup file carries no field for these tables.
     *
     * Not a ruling that it never may. Whether game plans, assignments and results travel in the
     * backup — and so in the synced snapshot — is not settled (#177). This holds the format still
     * until it is, so that nobody completes the file in passing.
     */
    @Test
    fun `the backup file carries no Companion field until that is decided`() {
        val fields = backupDataFields()
        assertTrue("BackupData's fields were not read: $fields", fields.containsAll(listOf("entries", "people", "personGroupShares")))
        assertEquals(
            "the backup format gained a Companion field — whether it should is not settled (#177)",
            emptyList<String>(),
            fields.filter { looksCompanion(it) },
        )
        // The detector fires on the names such a field would have, and not on the ones there.
        for (name in listOf("gamePlans", "gamePlanItems", "gamePlanProgress", "assignments", "instrumentResults", "taskResults")) {
            assertTrue("detector is broken for $name", looksCompanion(name))
        }
        assertFalse("detector is too greedy", looksCompanion("assessments"))
        assertFalse("detector is too greedy", looksCompanion("entries"))
    }

    private fun looksCompanion(field: String): Boolean =
        listOf("gamePlan", "assignment", "instrumentResult", "taskResult", "companion")
            .any { field.contains(it, ignoreCase = true) }

    // ---------------------------------------------------------------------------------------------
    // Parsing.
    // ---------------------------------------------------------------------------------------------

    /** Source with block comments and line comments removed — claims hold against code, not prose. */
    private fun strip(source: String): String = source
        .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
        .lines()
        .filterNot { it.trimStart().startsWith("//") }
        .joinToString("\n")

    /** One migration's body, from its declaration to the next migration or the end of the list. */
    private fun sliceMigration(marker: String): String {
        val after = database.substringAfter(marker)
        val ends = listOf("val MIGRATION_", "val DEFAULT_ACTIVITIES").map { after.indexOf(it) }.filter { it >= 0 }
        return if (ends.isEmpty()) after else after.substring(0, ends.min())
    }

    private fun migrationBody(): String = sliceMigration("val MIGRATION_18_19")

    /** One entry per `db.execSQL(...)`: that call's string literals concatenated, the SQL it runs. */
    private fun statementsOf(marker: String): List<String> =
        sliceMigration(marker).split("db.execSQL(").drop(1).map { chunk ->
            Regex("\"([^\"]*)\"").findAll(chunk).joinToString("") { it.groupValues[1] }
        }

    private fun statements(): List<String> = statementsOf("val MIGRATION_18_19")

    private fun tableCreatedBy(statement: String): String =
        Regex("""^CREATE TABLE IF NOT EXISTS `(\w+)`""").find(statement)!!.groupValues[1]

    /** Every backticked name in a statement: its tables and its columns. */
    private fun identifiersIn(statement: String): Set<String> =
        Regex("`(\\w+)`").findAll(statement).map { it.groupValues[1] }.toSet()

    private fun entityAnnotation(className: String): String =
        entity(className).substringAfter("@Entity(").substringBefore("\ndata class $className(")

    private fun primaryKeysOf(className: String): List<String> =
        Regex("""primaryKeys = \[([^\]]*)\]""").find(entityAnnotation(className))?.groupValues?.get(1)
            ?.split(",")?.map { it.trim().removeSurrounding("\"") }?.filter { it.isNotEmpty() }
            ?: emptyList()

    private data class Fk(val parent: String, val parentColumns: List<String>, val childColumns: List<String>, val onDelete: String)

    private fun names(list: String) = list.split(",").map { it.trim().removeSurrounding("\"") }.filter { it.isNotEmpty() }

    /** The `ForeignKey(...)`s an entity declares. One that this cannot read throws, never vanishes. */
    private fun foreignKeysOf(className: String): List<Fk> {
        val annotation = entityAnnotation(className)
        val pattern = Regex(
            """ForeignKey\(\s*entity = (\w+)::class,\s*parentColumns = \[([^\]]*)\],\s*""" +
                """childColumns = \[([^\]]*)\],\s*onDelete = ForeignKey\.(\w+),?\s*\)""",
        )
        val found = pattern.findAll(annotation).map {
            Fk(it.groupValues[1], names(it.groupValues[2]), names(it.groupValues[3]), it.groupValues[4])
        }.toList()
        val declared = Regex("""\bForeignKey\(""").findAll(annotation).count()
        if (declared != found.size) throw AssertionError("$className has a foreign key this parser cannot read")
        return found
    }

    /**
     * An entity rendered as the `CREATE TABLE` Room would generate for it.
     *
     * Columns in declaration order: Kotlin type to affinity, non-null to `NOT NULL`, and
     * `@PrimaryKey(autoGenerate = true)` to `INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL`. Then the
     * `PRIMARY KEY(...)` clause for any other key — a composite `primaryKeys` or a bare `@PrimaryKey`
     * — and then each foreign key, ending in Room's `ON DELETE … ` with the space its empty
     * deferral clause leaves before the closing paren. An unrecognised type throws rather than
     * guessing, so a new column cannot slip through as a silent match.
     */
    private fun renderCreateTable(className: String): String {
        val source = entity(className)
        val table = tableOfClass.getValue(className)
        val params = source.substringAfter("data class $className(").substringBefore("\n)")
        var barePrimaryKey: String? = null
        val columns = Regex("""(@PrimaryKey(?:\(autoGenerate = true\))? )?val (\w+): (\w+)(\??)""")
            .findAll(params)
            .map { m ->
                val pk = m.groupValues[1]
                val name = m.groupValues[2]
                val affinity = when (m.groupValues[3]) {
                    "Long", "Int", "Boolean" -> "INTEGER"
                    "String" -> "TEXT"
                    "Double", "Float" -> "REAL"
                    else -> throw AssertionError("unhandled column type ${m.groupValues[3]} on $className.$name")
                }
                val notNull = if (m.groupValues[4].isEmpty()) " NOT NULL" else ""
                when {
                    pk.contains("autoGenerate") -> "`$name` $affinity PRIMARY KEY AUTOINCREMENT$notNull"
                    pk.isNotEmpty() -> { barePrimaryKey = name; "`$name` $affinity$notNull" }
                    else -> "`$name` $affinity$notNull"
                }
            }
            .toList()
        val keyColumns = primaryKeysOf(className).ifEmpty { listOfNotNull(barePrimaryKey) }
        val keyClause = if (keyColumns.isEmpty()) emptyList() else listOf("PRIMARY KEY(${keyColumns.joinToString(", ") { "`$it`" }})")
        val fkClauses = foreignKeysOf(className).map { fk ->
            val onDelete = when (fk.onDelete) {
                "CASCADE" -> "CASCADE"
                "NO_ACTION" -> "NO ACTION"
                "SET_NULL" -> "SET NULL"
                "RESTRICT" -> "RESTRICT"
                else -> throw AssertionError("unhandled onDelete ${fk.onDelete} on $className")
            }
            "FOREIGN KEY(${fk.childColumns.joinToString(", ") { "`$it`" }}) " +
                "REFERENCES `${tableOfClass.getValue(fk.parent)}`(${fk.parentColumns.joinToString(", ") { "`$it`" }}) " +
                "ON UPDATE NO ACTION ON DELETE $onDelete "
        }
        return "CREATE TABLE IF NOT EXISTS `$table` (" + (columns + keyClause + fkClauses).joinToString(", ") + ")"
    }

    private fun deleteAllBody(): String =
        dao.substringAfter("suspend fun deleteAll() {").substringBefore("\n    }")

    /** The DAO methods a body calls with no arguments, in order. */
    private fun callsIn(body: String): List<String> =
        Regex("""(\w+)\(\)""").findAll(body).map { it.groupValues[1] }.toList()

    /** The tables the DAO's no-argument `DELETE FROM` methods called in [body] empty. */
    private fun tablesEmptiedBy(body: String): Set<String> =
        callsIn(body).map { method ->
            Regex("""@Query\("DELETE FROM (\w+)"\)\s*suspend fun $method\(\)""").find(dao)?.groupValues?.get(1)
                ?: throw AssertionError("$method is not a whole-table DELETE on CompanionDao")
        }.toSet()

    /** `BackupData`'s constructor parameter names, in declaration order. */
    private fun backupDataFields(): List<String> =
        Regex("""val (\w+):""").findAll(backup.substringAfter("data class BackupData(").substringBefore("\n)"))
            .map { it.groupValues[1] }.toList()
}
