package com.daymark.app.data

import com.daymark.app.backup.repoFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The four tables people and communities live in — `people`, `person_notes`, `entry_people` and
 * `person_group_shares` — checked the three ways a plain-JVM test can check them: the migration's
 * SQL against the entities it has to produce, the wiring (entities, DAO accessors, Hilt bindings,
 * the migration list) against the files that have to carry it, and the tables' *blindness to mood*
 * against the sources that must not mention it.
 *
 * ## Why this is a source test
 *
 * The honest version of the first part is `MigrationTestHelper.runMigrationsAndValidate`, which
 * builds the database at v17, runs the migration and compares the result against Room's own
 * exported schema. It is instrumented: it needs a device, and `build.yml` runs `test` +
 * `assembleDebug` and never `connectedAndroidTest`. So nothing executes it here.
 * [LifeEventSchemaTest] says the same thing at greater length and this file follows its shape.
 *
 * **The v18 schema JSON is deliberately absent from this branch.** It has to be Room's own export,
 * `identityHash` included, and there is no Android SDK on the machine this was written on. CI's
 * drift check regenerates it and prints it in full; it is committed from there. A hand-written one
 * is precisely the failure that step was rebuilt to catch, so this test asserts the migration
 * against the entities and makes no claim about the JSON.
 *
 * ## The third part is the one worth reading
 *
 * `docs/PLAN_2026-09-SKY-PEOPLE-TIMING.md` §2: *"**Never in any rule that reads mood.**
 * Correlations, patterns and the cards they produce cannot receive a person or a community, groups
 * included, enforced by signature the way the Sky's field is kept blind to data."*
 *
 * The part of that this layer can hold is that **no query in `data/` returns a person and a mood
 * together**. `entry_people` is read through `EntryPersonDao`, every method of which returns ids or
 * counts, and `EntryDao` — the one that returns `moodLevel` — has no method that touches the table.
 * [thePeopleLayerNeverNamesMood] asserts that as an absence, with a planted positive control on
 * each side so it cannot pass by being blind.
 *
 * What this test does **not** prove is the other half: `MoodCorrelations.factorDeltas` takes
 * `List<Long>`, so a person id still fits through its signature. That fix belongs in `stats/`, and
 * `PeopleRepository`'s header says exactly what it is.
 */
class PeopleSchemaTest {

    private companion object {
        const val PERSON = "app/src/main/java/com/daymark/app/data/entity/Person.kt"
        const val NOTE = "app/src/main/java/com/daymark/app/data/entity/PersonNote.kt"
        const val CROSS_REF = "app/src/main/java/com/daymark/app/data/entity/EntryPersonCrossRef.kt"
        const val GROUP_SHARE = "app/src/main/java/com/daymark/app/data/entity/PersonGroupShare.kt"
        const val GROUP = "app/src/main/java/com/daymark/app/data/entity/PersonGroup.kt"

        const val PERSON_DAO = "app/src/main/java/com/daymark/app/data/dao/PersonDao.kt"
        const val NOTE_DAO = "app/src/main/java/com/daymark/app/data/dao/PersonNoteDao.kt"
        const val CROSS_REF_DAO = "app/src/main/java/com/daymark/app/data/dao/EntryPersonDao.kt"
        const val REPOSITORY = "app/src/main/java/com/daymark/app/data/PeopleRepository.kt"

        const val DATABASE = "app/src/main/java/com/daymark/app/data/AppDatabase.kt"
        const val MODULE = "app/src/main/java/com/daymark/app/di/AppModule.kt"

        /** The two sources that must still see mood, or the detector below is blind. */
        const val ENTRY_DAO = "app/src/main/java/com/daymark/app/data/dao/EntryDao.kt"
        const val ENTRY_WITH_ACTIVITIES = "app/src/main/java/com/daymark/app/data/entity/EntryWithActivities.kt"
    }

    private val database = strip(read(DATABASE))
    private val module = strip(read(MODULE))

    private fun read(rel: String) = repoFile(rel).readText()

    // ----------------------------------------------------------------------------------------
    // Guards. Every assertion below reads one of these files; an empty or mis-sliced read would
    // make the comparisons pass against nothing.
    // ----------------------------------------------------------------------------------------

    @Test
    fun `the sources were found and stripped to something that is still code`() {
        assertTrue(strip(read(PERSON)).contains("data class Person("))
        assertTrue(strip(read(NOTE)).contains("data class PersonNote("))
        assertTrue(strip(read(CROSS_REF)).contains("data class EntryPersonCrossRef("))
        assertTrue(strip(read(GROUP_SHARE)).contains("data class PersonGroupShare("))
        assertTrue(database.contains("val MIGRATION_17_18"))
        assertTrue(module.contains("addMigrations("))

        // Stripping removed the prose and kept the code, or every parser below is reading KDoc —
        // these headers are far longer than the declarations under them.
        assertTrue("KDoc survived stripping", read(PERSON).contains("/**"))
        assertFalse("KDoc survived stripping", strip(read(PERSON)).contains("/**"))
        assertTrue("the declaration was stripped away", strip(read(PERSON)).contains("val sharedOverride: Boolean?"))

        // The migration slicer must actually slice: six statements, not the whole file and not none.
        assertEquals(6, statements().size)
    }

    // ----------------------------------------------------------------------------------------
    // The migration against the entities.
    // ----------------------------------------------------------------------------------------

    /**
     * Statement for statement, in Room's own wording.
     *
     * Whole-string comparison rather than "the column names are all there": a looser check passes a
     * `TEXT` column that should be `INTEGER`, a nullable column that should be `NOT NULL`, and a
     * reordering — all three of which fail Room's own comparison on the device, on somebody else's
     * change. The odd-looking space before `)` in the `person_notes` clause is Room's; it is copied
     * from `goal_steps` in [AppDatabase.MIGRATION_14_15] rather than tidied.
     */
    @Test
    fun `the migration creates exactly the tables and indices the entities declare`() {
        assertEquals(
            listOf(
                "CREATE TABLE IF NOT EXISTS `people` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`name` TEXT NOT NULL, `groupKey` TEXT NOT NULL, `whoTheyAre` TEXT NOT NULL, " +
                    "`archived` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `sharedOverride` INTEGER)",
                "CREATE TABLE IF NOT EXISTS `person_notes` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `personId` INTEGER NOT NULL, " +
                    "`dateTime` INTEGER NOT NULL, `body` TEXT NOT NULL, " +
                    "FOREIGN KEY(`personId`) REFERENCES `people`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE INDEX IF NOT EXISTS `index_person_notes_personId` ON `person_notes` (`personId`)",
                "CREATE TABLE IF NOT EXISTS `entry_people` (" +
                    "`entryId` INTEGER NOT NULL, `personId` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`entryId`, `personId`))",
                "CREATE INDEX IF NOT EXISTS `index_entry_people_personId` ON `entry_people` (`personId`)",
                "CREATE TABLE IF NOT EXISTS `person_group_shares` (" +
                    "`groupKey` TEXT NOT NULL, `shared` INTEGER NOT NULL, PRIMARY KEY(`groupKey`))",
            ),
            statements(),
        )
    }

    /** Each table's columns are the entity's columns, rendered as Room would render them. */
    @Test
    fun `every column in the migration comes from a field on the entity, and every field is there`() {
        assertEquals(renderedFromEntity(strip(read(PERSON)), "Person"), columnsOf(statements()[0]))
        assertEquals(renderedFromEntity(strip(read(NOTE)), "PersonNote"), columnsOf(statements()[1]))
        assertEquals(renderedFromEntity(strip(read(CROSS_REF)), "EntryPersonCrossRef"), columnsOf(statements()[3]))
        assertEquals(renderedFromEntity(strip(read(GROUP_SHARE)), "PersonGroupShare"), columnsOf(statements()[5]))

        // Positive control: the renderer read real constructors, not four empty lists.
        assertEquals(7, renderedFromEntity(strip(read(PERSON)), "Person").size)
        assertEquals(4, renderedFromEntity(strip(read(NOTE)), "PersonNote").size)
        assertEquals(2, renderedFromEntity(strip(read(CROSS_REF)), "EntryPersonCrossRef").size)
        assertEquals(2, renderedFromEntity(strip(read(GROUP_SHARE)), "PersonGroupShare").size)
    }

    /**
     * The comparison above has to be able to fail. These are the four ways this migration would
     * really have been written wrong, and every one of them is silent until Room compares schemas.
     */
    @Test
    fun `the comparison rejects a dropped column, a wrong type, a lost nullability and a reorder`() {
        val right = renderedFromEntity(strip(read(PERSON)), "Person")

        val dropped = right - "`createdAt` INTEGER NOT NULL"
        val wrongType = right.map { it.replace("`name` TEXT", "`name` INTEGER") }
        // The one that matters most here: sharedOverride is three-state, and NOT NULL collapses it
        // to two — a group default nobody could ever apply. See Person.sharedOverride.
        val notNull = right.map { it.replace("`sharedOverride` INTEGER", "`sharedOverride` INTEGER NOT NULL") }
        val reordered = listOf(right[0], right[2], right[1]) + right.drop(3)

        assertNotEquals("a dropped column would pass", dropped, right)
        assertNotEquals("a wrong affinity would pass", wrongType, right)
        assertNotEquals("a lost nullability would pass", notNull, right)
        assertNotEquals("a reordering would pass", reordered, right)
        // And the mutations must be mutations of something real.
        assertEquals(7, right.size)
        assertEquals(6, dropped.size)
        assertTrue("the nullable column was not found to mutate", right.contains("`sharedOverride` INTEGER"))
    }

    /**
     * `person_notes` cascades off `people`; `entry_people` has no foreign key at all.
     *
     * The second half is the one that looks like an omission and is not — `entry_activity` has none
     * either, and [com.daymark.app.data.entity.EntryPersonCrossRef] says why: the restore path
     * writes these rows from an untrusted file, and one cross-ref naming a row the file does not
     * carry would abort the import and give the person back nothing.
     */
    @Test
    fun `the notes cascade off their person and the entry link deliberately has no foreign key`() {
        val notes = statements()[1]
        assertTrue(
            "person_notes does not cascade, so deleting somebody leaves writing about them behind",
            notes.contains("FOREIGN KEY(`personId`) REFERENCES `people`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE"),
        )
        assertFalse("entry_people grew a foreign key", statements()[3].contains("FOREIGN KEY"))

        // The detector: this reads a real foreign-key clause when one is there, so its absence
        // above is a finding rather than a regex that matches nothing.
        assertTrue(
            "detector is broken — it cannot see the foreign key goal_steps has",
            database.contains("FOREIGN KEY(`goalId`) REFERENCES `goals`(`id`)"),
        )
        // ...and it fires on this very statement once a clause is put into it, so the absence above
        // is a fact about the SQL and not about the read.
        assertTrue(
            "detector is broken",
            statements()[3].replace("PRIMARY KEY(", "FOREIGN KEY(").contains("FOREIGN KEY("),
        )
    }

    /**
     * Both indices the entities declare, by the names Room derives, and neither is unique.
     *
     * An index missing from a migration is the quietest form of this drift: every query still
     * works. A UNIQUE one would be worse than missing — on `entry_people` it would refuse a second
     * person on an entry, and on `person_notes` a second note about somebody.
     */
    @Test
    fun `the migration creates both indices, with the names Room derives, and neither is unique`() {
        assertTrue(strip(read(NOTE)).contains("""indices = [Index("personId")]"""))
        assertTrue(strip(read(CROSS_REF)).contains("""indices = [Index("personId")]"""))

        val indices = statements().filter { it.startsWith("CREATE INDEX") }
        assertEquals(2, indices.size)
        assertTrue(indices[0].contains("`index_person_notes_personId`") && indices[0].contains("ON `person_notes` (`personId`)"))
        assertTrue(indices[1].contains("`index_entry_people_personId`") && indices[1].contains("ON `entry_people` (`personId`)"))
        for (index in indices) assertFalse("an index is UNIQUE: $index", index.contains("UNIQUE"))

        // `people` declares none and gets none, matching `activities`. Asserted so that adding one
        // to the entity without adding it here fails on this line rather than on a device.
        assertFalse("Person declares an index the migration does not create", strip(read(PERSON)).contains("indices ="))
        assertFalse("an index was created on people", indices.any { it.contains("ON `people`") })

        // The detector: the same read does fail on a migration whose index was forgotten.
        val without = statements().filterNot { it.contains("`index_entry_people_personId`") }
        assertEquals(5, without.size)
    }

    /**
     * Six statements, every one of them a `CREATE`.
     *
     * Nothing here seeds a row, and that is the whole reason sharing is off by default: a group
     * with no `person_group_shares` row is not shared, so the safe state has to be the one an empty
     * table produces. Nothing reads a row either — this migration must not touch `mood_entries`,
     * derive anybody from an activity called "Mum", or invent a person from existing data.
     *
     * Asserted as "every statement is a CREATE" rather than as four absent keywords, because one of
     * those keywords is not absent: `ON UPDATE NO ACTION` is part of the foreign-key clause Room
     * writes, and a check for `UPDATE` would fail on correct SQL. The positive form says more and
     * cannot be fooled that way.
     */
    @Test
    fun `the migration only creates, and seeds nothing`() {
        for (statement in statements()) {
            assertTrue(
                "a statement is neither a CREATE TABLE nor a CREATE INDEX: $statement",
                statement.startsWith("CREATE TABLE IF NOT EXISTS ") || statement.startsWith("CREATE INDEX IF NOT EXISTS "),
            )
        }
        assertEquals("the migration runs a different number of statements", 6, migrationBody().split("db.execSQL").size - 1)

        // The detector: a seeded row and a backfill are both statements this would reject.
        for (bad in listOf(
            "INSERT INTO person_group_shares VALUES ('friends', 1)",
            "UPDATE people SET sharedOverride = 1",
        )) {
            assertFalse(
                "detector is broken",
                bad.startsWith("CREATE TABLE IF NOT EXISTS ") || bad.startsWith("CREATE INDEX IF NOT EXISTS "),
            )
        }
        // ...and it does accept the statements that are really there, so it is not rejecting
        // everything.
        assertTrue("detector is too greedy", statements().first().startsWith("CREATE TABLE IF NOT EXISTS "))
    }

    // ----------------------------------------------------------------------------------------
    // The wiring.
    // ----------------------------------------------------------------------------------------

    @Test
    fun `the entities, the DAO accessors and the Hilt bindings all exist`() {
        for (entity in listOf("Person", "PersonNote", "EntryPersonCrossRef", "PersonGroupShare")) {
            assertTrue(
                "$entity is not in @Database(entities = ...), so Room never creates its table on a " +
                    "fresh install and only upgraders would have it",
                database.contains("com.daymark.app.data.entity.$entity::class"),
            )
        }
        for (accessor in listOf("personDao", "personNoteDao", "entryPersonDao")) {
            assertTrue("no DAO accessor $accessor() on AppDatabase", database.contains("fun $accessor()"))
        }
        // Without a binding this is a Dagger MissingBinding three minutes into the build, naming a
        // generated Java file. `InjectedDaoBindingTest` asserts the general rule; these three are
        // named because they are the ones this change adds.
        for (provider in listOf("providePersonDao", "providePersonNoteDao", "provideEntryPersonDao")) {
            assertTrue("no Hilt binding $provider(", module.contains("fun $provider("))
        }
    }

    /**
     * Every migration `AppDatabase` declares is registered in `AppModule.addMigrations(...)`.
     *
     * A set comparison rather than "MIGRATION_17_18 is in the list", because the check that names
     * one migration goes green the day a nineteenth is added and forgotten, which is exactly how
     * this is missed. A migration written and not registered leaves Room with no path from the
     * version already on the phone to the new one: it throws on the first database access, for
     * everybody who had data and for nobody who installed fresh.
     */
    @Test
    fun `every migration AppDatabase declares is registered, and the chain reaches the version`() {
        val declared = declaredMigrations()
        val registered = registeredMigrations(module)

        assertTrue("no migrations were parsed out of AppDatabase", declared.size >= 17)
        assertTrue("no migrations were parsed out of AppModule", registered.size >= 17)
        assertEquals(declared, registered)
        assertTrue("MIGRATION_17_18 was not declared at all", declared.contains("MIGRATION_17_18"))

        val hops = Regex("""val MIGRATION_(\d+)_(\d+) = object : Migration\((\d+), (\d+)\)""")
            .findAll(database).map { it.groupValues[3].toInt() to it.groupValues[4].toInt() }.toList()
        val version = Regex("""version = (\d+),""").find(database)!!.groupValues[1].toInt()
        assertEquals("the schema version does not match the newest migration", hops.maxOf { it.second }, version)
        assertEquals("the chain does not run 1 → $version", (1 until version).map { it to it + 1 }, hops.sortedBy { it.first })
    }

    @Test
    fun `the registration check fails against a module that forgot the newest migration`() {
        val preFix = module.replace("                AppDatabase.MIGRATION_17_18,\n", "")

        assertFalse("mutation did not land", registeredMigrations(preFix).contains("MIGRATION_17_18"))
        assertEquals(
            listOf("MIGRATION_17_18"),
            declaredMigrations() - registeredMigrations(preFix).toSet(),
        )
    }

    private fun declaredMigrations(): List<String> =
        Regex("""val (MIGRATION_\d+_\d+) = object""")
            .findAll(database).map { it.groupValues[1] }.toList().sorted()

    private fun registeredMigrations(source: String): List<String> =
        Regex("""AppDatabase\.(MIGRATION_\d+_\d+)""")
            .findAll(source.substringAfter("addMigrations(").substringBefore(".build()"))
            .map { it.groupValues[1] }.toList().sorted()

    // ----------------------------------------------------------------------------------------
    // Blindness to mood.
    // ----------------------------------------------------------------------------------------

    /**
     * No file in the people layer names mood, in code, anywhere.
     *
     * Crude on purpose. There is no expression in any of these files that legitimately needs a
     * `moodLevel`, a `MoodEntry`, an `EntryWithActivities` or the `mood_entries` table, so any
     * co-occurrence is worth reporting — and a check that tried to understand the expression would
     * be the thing here most likely to be wrong. KDoc is stripped first, so these files may keep
     * *explaining* the rule, which several of them do at length.
     */
    @Test
    fun thePeopleLayerNeverNamesMood() {
        val files = listOf(PERSON, NOTE, CROSS_REF, GROUP_SHARE, GROUP, PERSON_DAO, NOTE_DAO, CROSS_REF_DAO, REPOSITORY)

        // Positive control on both halves. The detector must fire on the sources that DO read mood,
        // or the emptiness below proves only that it cannot see anything.
        assertTrue("detector is blind — EntryDao reads mood and it was not seen", moodTokensIn(strip(read(ENTRY_DAO))).isNotEmpty())
        assertTrue("detector is blind", moodTokensIn(strip(read(ENTRY_WITH_ACTIVITIES))).contains("MoodEntry"))
        // And it must fire on the exact shape this feature would grow it in: a convenience query on
        // the people side that hands back the moods of the entries naming somebody.
        assertTrue(
            "detector is broken",
            moodTokensIn("""@Query("SELECT moodLevel FROM mood_entries JOIN entry_people ON id = entryId")""").isNotEmpty(),
        )
        // ...and not on the files as they stand, which are scanned for real below.
        assertTrue("nothing was scanned", files.size == 9)

        for (rel in files) {
            assertEquals("$rel names mood", emptyList<String>(), moodTokensIn(strip(read(rel))))
        }
    }

    /**
     * The wall has two sides: `EntryDao` never names the people table either.
     *
     * `EntryWithActivities` shows how short the distance is — a second
     * `@Relation(... Junction(EntryPersonCrossRef::class))` beside the existing one is four lines,
     * reads as symmetry, and would hand every caller of `observeAll` a mood and a person in one
     * object. That is the accident this asserts against, not malice.
     */
    @Test
    fun `the mood side never names the people tables`() {
        val tokens = listOf("entry_people", "EntryPersonCrossRef", "Person", "people")
        for (rel in listOf(ENTRY_DAO, ENTRY_WITH_ACTIVITIES)) {
            val src = strip(read(rel))
            for (token in tokens) assertFalse("$rel names $token", src.contains(token))
        }

        // The detector: the same scan does see the cross-ref that IS there, so the absence above is
        // a finding rather than a scan of nothing.
        assertTrue("detector is broken", strip(read(ENTRY_DAO)).contains("entry_activity"))
        assertTrue("detector is broken", strip(read(ENTRY_WITH_ACTIVITIES)).contains("EntryActivityCrossRef"))
    }

    private fun moodTokensIn(source: String): List<String> =
        listOf("moodLevel", "MoodEntry", "mood_entries", "EntryWithActivities", "SkyMoodPoint")
            .filter { source.contains(it) }

    // ----------------------------------------------------------------------------------------
    // Parsing.
    // ----------------------------------------------------------------------------------------

    /** Source with block comments and line comments removed — claims hold against code, not prose. */
    private fun strip(source: String): String = source
        .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
        .lines()
        .filterNot { it.trimStart().startsWith("//") }
        .joinToString("\n")

    /**
     * MIGRATION_17_18's body and nothing else — bounded by the next `val MIGRATION_` as well as by
     * `val DEFAULT_ACTIVITIES`, so this keeps reading one migration once a nineteenth is written.
     */
    private fun migrationBody(): String =
        database.substringAfter("val MIGRATION_17_18")
            .substringBefore("val DEFAULT_ACTIVITIES")
            .substringBefore("val MIGRATION_")

    /**
     * One entry per `db.execSQL(...)`, each being that call's string literals concatenated — the SQL
     * as the migration builds it.
     *
     * Per statement rather than one blob, because the claims above are about which table gets which
     * clause, and a blob cannot tell a foreign key on `person_notes` from one on `entry_people`.
     */
    private fun statements(): List<String> =
        migrationBody().split("db.execSQL(").drop(1).map { chunk ->
            Regex("\"([^\"]*)\"").findAll(chunk).joinToString("") { it.groupValues[1] }
        }

    /**
     * The definitions inside `CREATE TABLE ... ( ... )`, split on top-level commas only.
     *
     * Depth-aware because `PRIMARY KEY(\`entryId\`, \`personId\`)` and the foreign-key clause both
     * contain commas and parentheses of their own; a plain `split(", ")` tears them in half and then
     * compares the pieces against column definitions, which passes or fails for reasons nobody can
     * read. Trailing clauses are dropped here — they are asserted whole, above.
     */
    private fun columnsOf(createTable: String): List<String> {
        val inner = createTable.substringAfter("(").substringBeforeLast(")")
        val out = ArrayList<String>()
        val current = StringBuilder()
        var depth = 0
        for (ch in inner) {
            when {
                ch == '(' -> { depth++; current.append(ch) }
                ch == ')' -> { depth--; current.append(ch) }
                ch == ',' && depth == 0 -> { out.add(current.toString().trim()); current.setLength(0) }
                else -> current.append(ch)
            }
        }
        if (current.isNotBlank()) out.add(current.toString().trim())
        return out.filterNot { it.startsWith("PRIMARY KEY") || it.startsWith("FOREIGN KEY") }
    }

    /**
     * An entity rendered as the column list Room would generate for it.
     *
     * Kotlin type → affinity, non-nullable → `NOT NULL`, `@PrimaryKey(autoGenerate = true)` →
     * `INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL`. A bare `@PrimaryKey` renders as an ordinary
     * column, because Room puts a non-generated key in a trailing `PRIMARY KEY(...)` clause instead
     * — which is what `person_group_shares` has. An unrecognised type throws rather than guessing,
     * so a new column cannot slip through as a silent match.
     */
    private fun renderedFromEntity(source: String, className: String): List<String> {
        val params = source.substringAfter("data class $className(").substringBefore("\n)")
        return Regex("""(@PrimaryKey(?:\(autoGenerate = true\))? )?val (\w+): (\w+)(\??)""")
            .findAll(params)
            .map { m ->
                val pk = m.groupValues[1]
                val name = m.groupValues[2]
                val type = m.groupValues[3]
                val nullable = m.groupValues[4]
                val affinity = when (type) {
                    "Long", "Int", "Boolean" -> "INTEGER"
                    "String" -> "TEXT"
                    "Double", "Float" -> "REAL"
                    else -> throw AssertionError("unhandled column type $type on $name")
                }
                val notNull = if (nullable.isEmpty()) " NOT NULL" else ""
                if (pk.contains("autoGenerate")) {
                    "`$name` $affinity PRIMARY KEY AUTOINCREMENT$notNull"
                } else {
                    "`$name` $affinity$notNull"
                }
            }
            .toList()
    }
}
