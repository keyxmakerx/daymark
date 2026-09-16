package com.daymark.app.data

import com.daymark.app.backup.repoFile
import com.daymark.app.stats.TimingGrid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `offer_records.offeredHour`, `.offeredWeekday` and `.responded` — the columns, the migration that
 * adds them, the list that has to register that migration, the mapper that hands them to
 * `TimingGrid`, and the two things none of it may ever do: guess a slot from a timestamp, or reach
 * an export.
 *
 * ## Why this is a source test
 *
 * The same reason `GoalReachedSchemaTest` is, and this follows its shape: the honest check is
 * `MigrationTestHelper.runMigrationsAndValidate`, it is instrumented, and `build.yml` runs `test` +
 * `assembleDebug` and never `connectedAndroidTest`, so nothing on this branch executes it. Room and
 * Hilt are equally unreachable from a plain JVM test. A source assertion that runs on every push is
 * worth more than an instrumented one that runs nowhere.
 *
 * Where a claim *can* be made by running something it is, rather than by reading source: the
 * sentinel's whole job is to be a value [TimingGrid] refuses to place, and that is asserted by
 * calling [TimingGrid] with it.
 *
 * ## v18 is shared
 *
 * The people and communities tables were written against the same version in parallel and merged
 * into one migration, so the body sliced here runs nine statements and not three.
 * [ledgerStatements] takes this feature's half by what a statement is, and
 * [theRestOfVersionEighteen] accounts for the rest — the pair is still a closed description of v18.
 * One assertion changed shape rather than scope because of it, and says so at its own definition:
 * the back-fill scan reads this feature's statements, because `ON UPDATE NO ACTION` in the people
 * half is a legitimate `UPDATE`.
 *
 * ## The failures this is aimed at
 *
 * 1. **A migration that back-fills a slot from `offeredAt`.** An epoch millisecond only becomes an
 *    hour once a time zone is applied, and the zone somebody was in last March is not knowable in
 *    October — so a back-fill would invent evidence about a person's day and then let placement act
 *    on it. It cannot be caught by running anything, because the wrong version works.
 * 2. **A migration written and never registered.** `AppModule.addMigrations(...)` is a second,
 *    hand-maintained list; forget an entry and Room throws on first access, only for people who
 *    already had data.
 * 3. **The sentinel drifting from what `TimingGrid` drops.** Four spellings of `-1` (two annotations
 *    and two `DEFAULT`s) have to agree with one Kotlin constant, and with the ranges `TimingGrid`
 *    checks.
 * 4. **The mapper learning to read outcome keys.** `TimingGrid` treats any non-null outcome,
 *    recognised or not, as an answer on purpose. A mapper that special-cased particular keys would
 *    put back exactly the coupling that rule exists to remove.
 * 5. **The ledger reaching a backup, a CSV or a PDF.** `docs/PLAN_2026-09-SKY-PEOPLE-TIMING.md` §4:
 *    the reception ledger and the timing grid are never shared with a clinician.
 */
class TimedOfferSchemaTest {

    private companion object {
        const val ENTITY = "app/src/main/java/com/daymark/app/data/entity/OfferRecord.kt"
        const val DATABASE = "app/src/main/java/com/daymark/app/data/AppDatabase.kt"
        const val MODULE = "app/src/main/java/com/daymark/app/di/AppModule.kt"
        const val DAO = "app/src/main/java/com/daymark/app/data/dao/OfferRecordDao.kt"
        const val REPO = "app/src/main/java/com/daymark/app/data/OfferLedgerRepository.kt"
        const val BACKUP = "app/src/main/java/com/daymark/app/backup/BackupManager.kt"

        /** Every name the new columns go by. If one of these is in an export, the ledger is in it. */
        val LEDGER_COLUMNS = listOf("offeredHour", "offeredWeekday", "responded")
    }

    private val entity = strip(read(ENTITY))
    private val database = strip(read(DATABASE))
    private val module = strip(read(MODULE))
    private val dao = strip(read(DAO))
    private val repo = strip(read(REPO))

    private fun read(rel: String) = repoFile(rel).readText()

    // ---------------------------------------------------------------------------------------------
    // Guards. Every assertion below reads one of these slices; an empty or mis-sliced read would
    // make the absence checks pass against nothing.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `every source was found and stripped to something that is still code`() {
        assertTrue(entity.contains("data class OfferRecord("))
        assertTrue(database.contains("val MIGRATION_17_18"))
        assertTrue(module.contains("addMigrations("))
        assertTrue(dao.contains("interface OfferRecordDao"))
        assertTrue(repo.contains("class OfferLedgerRepository"))

        // Stripping must remove the prose and keep the code, or every absence check below is
        // reading KDoc. All five files explain these columns at length in their comments, and the
        // entity's own KDoc quotes the annotation this test counts.
        assertTrue("KDoc survived stripping", read(ENTITY).contains("/**"))
        assertFalse("KDoc survived stripping", entity.contains("/**"))
        assertTrue(
            "the entity declaration was stripped away",
            entity.contains("val responded: Boolean?"),
        )
        // A phrase that exists only inside the v18 migration's KDoc. Present in the file, absent
        // from the stripped copy — otherwise the back-fill check below is reading an explanation of
        // the rule instead of the code that keeps it.
        assertTrue("the migration lost its KDoc", read(DATABASE).contains("It does not back-fill"))
        assertFalse("the migration's KDoc survived stripping", database.contains("It does not back-fill"))
    }

    // ---------------------------------------------------------------------------------------------
    // The columns.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `the entity declares the two slots and the response flag, in the shapes the migration adds`() {
        val params = params(entity, "data class OfferRecord(")

        assertTrue("offeredHour is missing", params.any { it.contains("val offeredHour: Int") })
        assertTrue("offeredWeekday is missing", params.any { it.contains("val offeredWeekday: Int") })

        // Nullable, and that is load-bearing: NULL means "this row predates the distinction", which
        // is not the same as "nobody answered". A non-null Boolean here would have forced every old
        // row to claim one or the other.
        assertTrue(
            "responded must stay nullable — NULL is 'not known', which is not 'nobody answered'",
            params.any { it.contains("val responded: Boolean?") },
        )
        assertFalse(
            "responded became non-null, so an old row now claims something nothing recorded",
            params.any { it.contains("val responded: Boolean =") },
        )

        // Still no free-text column. The table holds the app's behaviour and never the person.
        val textColumns = params.filter { it.contains(": String") }
        assertEquals(
            "a new text column appeared on the reception ledger",
            listOf("kind", "outcome"),
            textColumns.map { it.substringAfter("val ").substringBefore(":") },
        )
    }

    /**
     * The sentinel is one number written five ways — a Kotlin constant, two `@ColumnInfo` defaults
     * and two SQL `DEFAULT`s — and they all have to say the same thing.
     *
     * An annotation argument must be a literal, so the duplication cannot be removed; it can only be
     * pinned.
     */
    @Test
    fun `every spelling of the unrecorded sentinel agrees`() {
        val declared = Regex("""const val UNRECORDED: Int = (-?\d+)""").find(entity)
        assertTrue("OfferRecord.UNRECORDED was not declared", declared != null)
        val sentinel = declared!!.groupValues[1]

        val annotations = Regex("""@ColumnInfo\(defaultValue = "(-?\d+)"\)""").findAll(entity)
            .map { it.groupValues[1] }
            .toList()
        assertEquals("both slot columns need a @ColumnInfo default", 2, annotations.size)
        assertTrue("an @ColumnInfo default disagrees with UNRECORDED", annotations.all { it == sentinel })

        val defaults = Regex("""ADD COLUMN offered\w+ INTEGER NOT NULL DEFAULT (-?\d+)""")
            .findAll(migrationBody())
            .map { it.groupValues[1] }
            .toList()
        assertEquals("both slot columns need a SQL default", 2, defaults.size)
        assertTrue("a SQL DEFAULT disagrees with UNRECORDED", defaults.all { it == sentinel })
    }

    /**
     * The sentinel's entire job: [TimingGrid] must refuse to place a row carrying it.
     *
     * Run rather than read, because this is the one claim here that a running program can settle.
     * The positive control is the same grid with a real slot — without it, a `TimingGrid` that had
     * quietly stopped counting anything at all would pass.
     */
    @Test
    fun `TimingGrid drops a row carrying the unrecorded sentinel, and counts one that does not`() {
        val sentinel = Regex("""const val UNRECORDED: Int = (-?\d+)""")
            .find(entity)!!.groupValues[1].toInt()

        val unrecorded = TimingGrid.Ask("support", sentinel, sentinel, "accepted")
        val real = TimingGrid.Ask("support", 9, 3, "accepted")

        val dropped = TimingGrid.grid("support", listOf(unrecorded))
        assertEquals(
            "a row with an unrecorded slot was placed somewhere in the grid",
            0,
            dropped.cells.sumOf { it.asks },
        )

        // Positive control: a real slot does land, so the assertion above is about the sentinel and
        // not about `grid` having stopped counting.
        val counted = TimingGrid.grid("support", listOf(real))
        assertEquals(1, counted.cells.sumOf { it.asks })
        assertEquals(1, counted.cell(3, 9)!!.asks)

        // And the same row is invisible to placement, which reads the hour only.
        val placement = TimingGrid.allocate("support", listOf(unrecorded), hoursWanted = 3)
        assertEquals(0, placement.readings.sumOf { it.asks })
        assertNotEquals(
            "the positive control did not distinguish the two",
            0,
            TimingGrid.allocate("support", listOf(real), hoursWanted = 3).readings.sumOf { it.asks },
        )
    }

    // ---------------------------------------------------------------------------------------------
    // The migration.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `the migration adds three columns and nothing else`() {
        val ledger = ledgerStatements()
        val body = ledger.joinToString("\n")

        assertEquals("the ledger half runs a different number of statements", 3, ledger.size)
        assertTrue(body.contains("ADD COLUMN offeredHour INTEGER NOT NULL DEFAULT -1"))
        assertTrue(body.contains("ADD COLUMN offeredWeekday INTEGER NOT NULL DEFAULT -1"))

        // Nullable, no DEFAULT — the SQL form of "this row predates the distinction".
        assertTrue(body.contains("ADD COLUMN responded INTEGER"))
        assertFalse(
            "`responded` was given a default, so every old row now claims an answer either way",
            body.contains("ADD COLUMN responded INTEGER NOT NULL"),
        )
        assertFalse(body.contains("ADD COLUMN responded INTEGER DEFAULT"))
    }

    /**
     * v18 is shared with the people tables, and this is the rest of it.
     *
     * Two features wrote a version 18 in parallel and neither had shipped, so they were merged into
     * one migration rather than left as v18 and v19: a version is a state a database can actually be
     * in, and no phone anywhere holds a v18 with the ledger columns but not the people tables.
     *
     * The cost is that the tests on either side now slice a body containing the other side's
     * statements, which is how three assertions in this file went red at once. The fix is not to
     * loosen them. [ledgerStatements] takes this feature's half by what a statement is, and this
     * counts the remainder — so the two halves still account for every statement v18 runs, and a
     * third feature joining it turns this red rather than quietly widening what "this migration"
     * means. `PeopleSchemaTest.theRestOfVersionEighteen` makes the mirror-image claim and names the
     * other half statement for statement; it is not repeated here, because two copies of the same
     * list drift and the one that is not read is the one that goes stale.
     */
    @Test
    fun theRestOfVersionEighteen() {
        val others = allStatements() - ledgerStatements().toSet()

        assertEquals("v18 runs a different number of statements", 9, allStatements().size)
        assertEquals(6, others.size)
        for (statement in others) {
            assertTrue(
                "v18 gained a statement that is neither a ledger column nor a people table: $statement",
                statement.startsWith("CREATE TABLE IF NOT EXISTS ") || statement.startsWith("CREATE INDEX IF NOT EXISTS "),
            )
        }

        // The detector: a statement belonging to neither half is not absorbed by either filter, so
        // the account above is a fact about v18 and not about the arithmetic.
        val intruder = "DROP TABLE offer_records"
        assertFalse("detector is broken", intruder.startsWith("ALTER TABLE offer_records "))
        assertFalse(
            "detector is broken",
            intruder.startsWith("CREATE TABLE IF NOT EXISTS ") || intruder.startsWith("CREATE INDEX IF NOT EXISTS "),
        )
        // ...and it does accept the statements really there, so it is not rejecting everything.
        assertTrue("detector is too greedy", ledgerStatements().first().startsWith("ALTER TABLE offer_records "))
    }

    /**
     * The slicer stops at the next migration, shown on a source that has one.
     *
     * The assertion below it cannot demonstrate this today: v18 is the newest migration, so
     * "stop at the next migration" and "stop at the end of the companion object" take the same
     * slice, and a mutation between them changes nothing. The day that stops being true is the day
     * the difference matters, so the case is built here instead of waited for.
     */
    @Test
    fun `the slicer stops at a later migration when there is one`() {
        val source = """
            val MIGRATION_17_18 = object : Migration(17, 18) {
                db.execSQL("ALTER TABLE offer_records ADD COLUMN offeredHour INTEGER NOT NULL DEFAULT -1")
            }
            val MIGRATION_18_19 = object : Migration(18, 19) {
                db.execSQL("UPDATE offer_records SET offeredHour = 9")
            }
            val DEFAULT_ACTIVITIES = listOf("Work")
        """.trimIndent()

        val body = sliceMigration(source, "val MIGRATION_17_18")

        assertTrue("the slice lost the migration it is about", body.contains("ADD COLUMN offeredHour"))
        assertFalse("the slice ran into the next migration", body.contains("UPDATE"))
        assertEquals("the slice took more than this migration's statements", 1, body.split("execSQL").size - 1)

        // The control: the naive slice this replaced really does take both, so the case is a real
        // difference and not a distinction the test invented.
        val naive = source.substringAfter("val MIGRATION_17_18").substringBefore("val DEFAULT_ACTIVITIES")
        assertTrue("the control did not reproduce the old behaviour", naive.contains("UPDATE"))
        assertEquals(2, naive.split("execSQL").size - 1)
    }

    /**
     * The slice reads one migration, not the companion object from here down.
     *
     * Without this, the absence assertions below are claims about however much of the file the
     * slice happened to take, and they get weaker rather than louder as the file grows.
     */
    @Test
    fun `the migration slice stops at the end of this migration`() {
        val body = migrationBody()

        assertTrue("the slice missed the statements it is about", body.contains("ADD COLUMN offeredHour"))
        // Nine, not three: v18 is shared with the people tables, and the slice is bounded by the
        // migration rather than by this feature. [theRestOfVersionEighteen] is what says the other
        // six are the people tables and nothing else; this line only says the slice stopped.
        assertEquals("the slice runs past this migration", 9, body.split("execSQL").size - 1)
        assertFalse("the slice swallowed a later migration", body.contains("val MIGRATION_"))

        // Positive control: the slice does end, and it ends before the rest of the companion object.
        assertFalse("the slice ran to the end of the companion object", body.contains("DEFAULT_ACTIVITIES"))
        assertTrue("the file has more after this migration", database.contains("val DEFAULT_ACTIVITIES"))
    }

    /**
     * The one thing this migration must never do.
     *
     * A slot derived from `offeredAt` under the phone's *current* zone is a fact about the person's
     * day that nothing recorded, and placement would then act on it. Every read below has a
     * detector, because an absence assertion that cannot see a planted example proves only that it
     * is blind.
     *
     * Scanned over [notThePeopleTables] and not over the whole v18 body, for a reason worth stating:
     * the word `UPDATE` is not absent from v18. `ON UPDATE NO ACTION` is part of the foreign-key
     * clause Room writes for `person_notes`, so the whole-body form of this test fails on correct
     * SQL — which is exactly what it did when the two migrations were merged. Narrowing the scan to
     * the statements this feature owns is the honest fix; widening the allowed words would have let
     * a real `UPDATE` through on this side too.
     *
     * And it is [notThePeopleTables] rather than [ledgerStatements] because a back-fill is not an
     * `ALTER`: the narrower filter would drop the forbidden statement before reading it, and this
     * test would pass on a migration doing the one thing it names. See that helper.
     */
    @Test
    fun `the migration does not derive a slot from offeredAt or from anything else`() {
        val body = notThePeopleTables().joinToString("\n")

        assertFalse("the migration reads `offeredAt`", body.contains("offeredAt"))
        assertFalse("the migration writes rows", body.contains("UPDATE"))
        assertFalse("the migration reads rows", body.contains("SELECT"))
        assertFalse("the migration reaches for a clock", body.contains("strftime"))
        assertFalse("the migration reaches for a clock", body.contains("datetime("))

        // The detector: the same reads do fire on the back-fill this forbids.
        val backfilled = body + "\nUPDATE offer_records SET offeredHour = CAST(" +
            "strftime('%H', datetime(offeredAt / 1000, 'unixepoch', 'localtime')) AS INTEGER)"
        assertTrue("detector is broken", backfilled.contains("offeredAt"))
        assertTrue("detector is broken", backfilled.contains("UPDATE"))
        assertTrue("detector is broken", backfilled.contains("strftime"))
        assertTrue("detector is broken", backfilled.contains("datetime("))
        // ...and it scanned the statements really there, not an empty string. Three today, and the
        // assertion is `>=` because this scan's job is to grow when v18 does: a fourth statement
        // outside the people tables must be judged by the reads above, not excluded by a count.
        assertTrue("nothing was scanned", body.lines().size >= 3)
        assertEquals("the ledger statements are not all being scanned", 3, ledgerStatements().size)
    }

    // ---------------------------------------------------------------------------------------------
    // The migration list.
    // ---------------------------------------------------------------------------------------------

    /**
     * Every migration `AppDatabase` declares is registered with the database builder.
     *
     * A set comparison rather than "MIGRATION_17_18 is in the list", because the check that names
     * one migration goes green the day a nineteenth is added and forgotten — which is exactly how
     * this is missed.
     */
    @Test
    fun `every migration AppDatabase declares is registered with the database builder`() {
        val declared = declaredMigrations(database)
        val registered = registeredMigrations(module)

        assertTrue("no migrations were parsed out of AppDatabase", declared.size >= 17)
        assertTrue("no migrations were parsed out of AppModule", registered.size >= 17)
        assertEquals(
            "a migration exists and is not registered: Room will find no path from the version " +
                "already on the phone to the new one and throw on the first database access, for " +
                "everyone who had data and for nobody who installed fresh",
            declared,
            registered,
        )
        assertTrue("MIGRATION_17_18 was not declared at all", declared.contains("MIGRATION_17_18"))
    }

    @Test
    fun `the registration check fails against a module that forgot the newest migration`() {
        val preFix = module.replace("                AppDatabase.MIGRATION_17_18,\n", "")

        assertFalse("mutation did not land", registeredMigrations(preFix).contains("MIGRATION_17_18"))
        assertEquals(
            listOf("MIGRATION_17_18"),
            declaredMigrations(database) - registeredMigrations(preFix).toSet(),
        )
    }

    /**
     * The chain runs 1 → the declared version with no gap, and the migration that adds these columns
     * is the one that arrives at it.
     *
     * The version is read from the migration that adds `offeredHour`, not from a literal 18: a
     * literal goes red on the next unrelated column anyone adds, and a guard that fails for reasons
     * its reader did not cause is a guard that gets its number bumped without thought.
     */
    @Test
    fun `the migration chain reaches the declared database version without a gap`() {
        val pattern = Regex("""val MIGRATION_(\d+)_(\d+) = object : Migration\((\d+), (\d+)\)""")
        val matches = pattern.findAll(database).toList()

        assertTrue("no migrations parsed", matches.size >= 17)
        for (m in matches) {
            assertEquals("MIGRATION_x_y disagrees with Migration(x, y)", m.groupValues[1], m.groupValues[3])
            assertEquals("MIGRATION_x_y disagrees with Migration(x, y)", m.groupValues[2], m.groupValues[4])
        }

        val steps = matches.map { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
            .sortedBy { it.first }
        assertEquals("the chain does not start at 1", 1, steps.first().first)
        for (index in 1 until steps.size) {
            assertEquals(
                "a gap in the migration chain: nothing gets someone from " +
                    steps[index - 1].second + " to " + steps[index].first,
                steps[index - 1].second,
                steps[index].first,
            )
        }

        val version = Regex("""version = (\d+),""").find(database)!!.groupValues[1].toInt()
        assertEquals("the chain stops short of the declared version", version, steps.last().second)

        val adds = matches.singleOrNull { m ->
            database.substring(m.range.first, endOfMigration(database, m.range.first))
                .contains("ADD COLUMN offeredHour")
        }
        assertTrue("no migration adds the offeredHour column", adds != null)
        assertEquals(
            "the migration that adds the timing columns is not the one that reaches the declared version",
            version,
            adds!!.groupValues[2].toInt(),
        )
    }

    // ---------------------------------------------------------------------------------------------
    // The mapper.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `timedOffers reads every row of the kind, through a query that does not window`() {
        val mapper = timedOffersBody()

        assertTrue("timedOffers is missing", repo.contains("suspend fun timedOffers"))
        assertTrue("timedOffers does not go through allForKind", mapper.contains("dao.allForKind"))
        assertFalse(
            "timedOffers took a window — placement reads the whole history, and the retention " +
                "sweep is what bounds it",
            mapper.contains("RECENT_WINDOW"),
        )

        val query = daoQueryFor("allForKind")
        assertTrue("allForKind has no query", query.isNotEmpty())
        assertTrue(query.contains("WHERE kind = :kind"))
        assertFalse("the wide read grew a LIMIT", query.contains("LIMIT"))
    }

    /**
     * The mapper nulls the outcome on a recorded `false` and on nothing else.
     *
     * `TimingGrid.Ask.outcome` means something narrower than "what became of it": null is *no
     * response was ever recorded*, and any other value — recognised or not — is an answer. That is
     * deliberate, so a later schema change cannot make the app abandon an hour, and it survives only
     * as long as the mapper refuses to look at outcome keys.
     */
    @Test
    fun `the mapper nulls an outcome only for a recorded non-response, and never reads a key`() {
        val mapper = timedOffersBody()

        assertTrue(
            "the mapper does not turn a recorded non-response into a null outcome",
            mapper.contains("if (record.responded == false) null else record.outcome"),
        )

        // `responded == false`, never `!= true` and never `?: false`: those read a NULL row as an
        // unanswered ask, which is the direction that gives up an hour on evidence nothing recorded.
        assertFalse("a null `responded` is being read as 'nobody answered'", mapper.contains("!= true"))
        assertFalse("a null `responded` is being read as 'nobody answered'", mapper.contains("?: false"))

        // And it never inspects an outcome key. A denylist of "keys that secretly mean no outcome"
        // is exactly the coupling TimingGrid's any-non-null rule exists to remove.
        for (key in listOf("ACCEPTED", "DISMISSED", "SNOOZED", "STOP", "IGNORED")) {
            assertFalse("the mapper is reading outcome keys", mapper.contains(key))
        }

        // Detectors, so none of the above is a check that has gone blind.
        val loosened = mapper.replace("record.responded == false", "record.responded != true")
        assertNotEquals("detector is broken", mapper, loosened)
        assertTrue("detector is broken", loosened.contains("!= true"))
        assertTrue(
            "detector is broken",
            (mapper + "if (record.outcome == OfferOutcome.IGNORED.key) null").contains("IGNORED"),
        )
    }

    @Test
    fun `record stamps the slot at the moment of the ask, and the retention sweep never does`() {
        val record = slice(repo, "suspend fun record(", "private fun hourIn")

        assertTrue("record no longer stamps the hour", record.contains("offeredHour = hourIn("))
        assertTrue("record no longer stamps the weekday", record.contains("offeredWeekday = weekdayIn("))
        assertTrue("record cannot be given a zone", record.contains("zone: ZoneId"))

        // "Not recorded" is the only default that is true of a caller who has not been told, and it
        // reaches placement exactly as `true` does — as an answered hour, which keeps the app
        // asking. A default of `false` would let one un-updated call site talk the app out of an
        // hour somebody uses; a default of `true` would write a claim nobody checked.
        assertTrue("responded no longer defaults to 'not recorded'", record.contains("responded: Boolean? = null"))
        assertFalse("responded now fails towards giving up an hour", record.contains("responded: Boolean = false"))
        assertFalse("responded now claims an answer nobody reported", record.contains("responded: Boolean = true"))

        // The carry-forward row the sweep writes is a preference, not an ask. Giving it a real slot
        // would inject a phantom ask at whatever hour the app was next opened.
        val sweep = slice(repo, "suspend fun sweepRetention", "suspend fun clear(")
        assertTrue(sweep.contains("offeredHour = OfferRecord.UNRECORDED"))
        assertTrue(sweep.contains("offeredWeekday = OfferRecord.UNRECORDED"))
        assertTrue(sweep.contains("responded = null"))
        assertFalse("the sweep is stamping a live slot onto a row that was not an ask", sweep.contains("hourIn("))
    }

    // ---------------------------------------------------------------------------------------------
    // Nothing here leaves the phone.
    // ---------------------------------------------------------------------------------------------

    /**
     * `docs/PLAN_2026-09-SKY-PEOPLE-TIMING.md` §4: *the reception ledger and the timing grid are
     * never shared with a clinician.*
     *
     * `BackupReplaceSourceTest` already asserts that the backup payload has no ledger field at all;
     * this is the narrower claim for the three columns added here, over every file that produces
     * something a person can send somewhere.
     */
    @Test
    fun `no column of the reception ledger reaches a backup, an export or a report`() {
        val leaving = sourcesUnder("app/src/main/java/com/daymark/app/backup") +
            sourcesUnder("app/src/main/java/com/daymark/app/export")

        assertTrue("no export sources were read at all", leaving.size >= 2)
        assertTrue(
            "the export sources did not read — the absence check below would pass over nothing",
            leaving.any { (_, source) -> source.contains("class ") },
        )

        for ((path, source) in leaving) {
            val code = strip(source)
            for (column in LEDGER_COLUMNS) {
                assertFalse(
                    "$path names `$column` — the reception ledger is not shared, with anyone",
                    code.contains(column),
                )
            }
        }

        // Detector: the same read does fire on a planted leak.
        val planted = strip(read(BACKUP)) + "\n    val offeredHour: Int = 0\n"
        assertTrue("detector is broken", planted.contains("offeredHour"))
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

    /**
     * The v18 migration's body — from its declaration to whatever comes next, which is the migration
     * after it or the end of the companion object.
     *
     * Not `substringBefore("val DEFAULT_ACTIVITIES")`. That is the same slice only while this is the
     * newest migration, and `GoalReachedSchemaTest` had exactly that and broke the day it stopped
     * being true: v18's statements landed inside v17's slice and its "exactly one `execSQL`"
     * assertions started describing two migrations at once. Written this way, a v19 cannot do the
     * same to this file.
     */
    private fun migrationBody(): String = sliceMigration(database, "val MIGRATION_17_18")

    /**
     * Every statement v18 runs, this feature's and the people tables' alike, in source order.
     *
     * One entry per `db.execSQL(...)`, each being that call's string literals concatenated.
     */
    private fun allStatements(): List<String> =
        migrationBody().split("db.execSQL(").drop(1).map { chunk ->
            Regex("\"([^\"]*)\"").findAll(chunk).joinToString("") { it.groupValues[1] }
        }

    /**
     * The half of v18 this file is about: the three columns added to `offer_records`.
     *
     * Selected by what a statement *is* rather than by where it sits, because position is exactly
     * what another feature joining this migration would change — which is what happened. See
     * [theRestOfVersionEighteen] for the other half and for why the two are asserted as a pair.
     */
    private fun ledgerStatements(): List<String> =
        allStatements().filter { it.startsWith("ALTER TABLE offer_records ") }

    /**
     * Everything v18 does that is not one of the people tables' `CREATE`s.
     *
     * Deliberately wider than [ledgerStatements], and the difference is the whole point. A filter
     * that keeps only `ALTER TABLE offer_records ...` cannot see a statement that is neither that
     * nor a `CREATE` — which is exactly the shape of the back-fill this file exists to forbid. Try
     * it: plant `UPDATE offer_records SET offeredHour = ...` into the migration, scan
     * [ledgerStatements], and the test named for the back-fill stays green while the counting tests
     * go red for the wrong reason. Found that way, in the change that merged the two v18s.
     *
     * So the back-fill scan reads this instead. It excludes the people half by what a statement is,
     * which is what keeps `ON UPDATE NO ACTION` — real, legitimate, inside a `CREATE TABLE` — out of
     * a scan that forbids the word `UPDATE`, and it lets everything else through to be judged.
     */
    private fun notThePeopleTables(): List<String> =
        allStatements().filterNot { it.startsWith("CREATE ") }

    /** [migrationBody] with its input passed in, so it can be tested on a source that has a v19. */
    private fun sliceMigration(source: String, marker: String): String {
        val after = source.substringAfter(marker)
        val ends = listOf("val MIGRATION_", "val DEFAULT_ACTIVITIES")
            .map { after.indexOf(it) }
            .filter { it >= 0 }
        return if (ends.isEmpty()) after else after.substring(0, ends.min())
    }

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

    private fun declaredMigrations(source: String): List<String> =
        Regex("""val (MIGRATION_\d+_\d+) = object""").findAll(source)
            .map { it.groupValues[1] }
            .sorted()
            .toList()

    private fun registeredMigrations(source: String): List<String> =
        Regex("""AppDatabase\.(MIGRATION_\d+_\d+)""").findAll(source.substringAfter("addMigrations("))
            .map { it.groupValues[1] }
            .sorted()
            .toList()

    /** Where the migration starting at [from] ends — the next `val MIGRATION_`, or the end. */
    private fun endOfMigration(source: String, from: Int): Int {
        val next = source.indexOf("val MIGRATION_", from + 1)
        return if (next < 0) source.length else next
    }

    /**
     * The body of one declaration: everything between [from] and [to].
     *
     * Both markers are code rather than comment text, because [strip] has already removed the
     * comments — slicing a stripped source on a KDoc opener would silently return the whole rest of
     * the file, and every absence assertion over it would then be reading the wrong thing and
     * passing. (Kotlin block comments nest, so writing that opener out here would have swallowed
     * the rest of this file too.)
     */
    private fun slice(source: String, from: String, to: String): String {
        val start = source.indexOf(from)
        assertTrue("`$from` was not found", start >= 0)
        val end = source.indexOf(to, start + from.length)
        assertTrue("`$to` was not found after `$from`", end >= 0)
        return source.substring(start, end)
    }

    private fun timedOffersBody(): String =
        slice(repo, "suspend fun timedOffers", "suspend fun mayInterrupt")

    /** Every `.kt` file under a repo-relative directory, as (path, source). */
    private fun sourcesUnder(dir: String): List<Pair<String, String>> =
        repoDir(dir).walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.path to it.readText() }
            .toList()

    /** [repoFile], for a directory. Not finding it throws, for the same reason. */
    private fun repoDir(rel: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        val tail = rel.substringAfter("app/")
        while (dir != null) {
            for (candidate in listOf(File(dir, rel), File(dir, tail))) {
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError("could not find $rel from ${System.getProperty("user.dir")}")
    }
}
