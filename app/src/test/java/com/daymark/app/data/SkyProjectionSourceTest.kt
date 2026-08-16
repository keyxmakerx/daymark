package com.daymark.app.data

import com.daymark.app.backup.repoFile
import com.daymark.app.sky.SkyKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Sky's queries select no text, and this is the thing that checks it rather than the comment
 * above each one.
 *
 * ## The promise
 *
 * `docs/SKY.md` §4.1: *the Sky never renders journal prose — it shows that you wrote, not what you
 * wrote.* §8.2 rule 7 puts that in the **DAO signature** rather than in discipline at the call site,
 * because the Sky is a surface people show to other people and screenshot, and a shoulder-surfer
 * must learn nothing but that something was written on a date.
 *
 * ## Why a source test
 *
 * The property is about a query string, and a query string is only executed against a real database
 * — instrumented, on a device, which `build.yml` compiles and never runs. A behavioural test would
 * therefore prove this on nobody's machine. Reading the source proves it on every push, which is
 * where the change that breaks it will arrive.
 *
 * And it will arrive as a *widening*, not a rewrite. Someone will want a title in the star's detail
 * or a first line in a tooltip, and the cheapest way to get one is to add a column to a `SELECT`
 * that already exists. That edit passes review easily; it does not pass this.
 *
 * ## Every absence assertion here is paired with its own detector
 *
 * An assertion that a column is missing is worthless from a search that could not find it if it
 * were there, so each forbidden token is first searched for in a string that does contain it. This
 * repo has shipped an inert green check before (see `build.yml`'s note on the schema-drift step),
 * and the failure mode is always the same: the assertion was never able to fail.
 */
class SkyProjectionSourceTest {

    private companion object {
        const val ENTRY_DAO = "app/src/main/java/com/daymark/app/data/dao/EntryDao.kt"
        const val JOURNAL_DAO = "app/src/main/java/com/daymark/app/data/dao/JournalDao.kt"
        const val THOUGHT_DAO = "app/src/main/java/com/daymark/app/data/dao/ThoughtRecordDao.kt"
        const val STEP_DAO = "app/src/main/java/com/daymark/app/data/dao/GoalStepDao.kt"
        const val GOAL_DAO = "app/src/main/java/com/daymark/app/data/dao/GoalDao.kt"
        const val LIFE_EVENT_DAO = "app/src/main/java/com/daymark/app/data/dao/LifeEventDao.kt"
        const val REPOSITORY = "app/src/main/java/com/daymark/app/data/SkyRepository.kt"
        const val JOURNAL_REPOSITORY = "app/src/main/java/com/daymark/app/data/JournalRepository.kt"

        const val SKY_QUERY = "fun observeSkyPoints("
    }

    private fun source(path: String): String = repoFile(path).readText()

    /**
     * The `@Query` immediately above `observeSkyPoints`, flattened to one line.
     *
     * Concatenation and line breaks are removed so a query split across two string literals — which
     * `GoalStepDao`'s is — reads the same as one written on a single line. Nothing else is
     * normalised: the column names have to survive verbatim or the assertions are inspecting a
     * string this test invented.
     */
    private fun skyQuery(path: String): String {
        val text = source(path)
        val function = text.indexOf(SKY_QUERY)
        assertTrue("no $SKY_QUERY in $path", function > 0)
        val annotation = text.lastIndexOf("@Query(", function)
        assertTrue("no @Query above $SKY_QUERY in $path", annotation > 0)
        return flatten(text.substring(annotation, function))
    }

    private fun flatten(annotation: String): String = annotation
        .removePrefix("@Query(")
        .replace("\"", "")
        .replace("+", "")
        .replace("\n", " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .removeSuffix(",)")
        .removeSuffix(")")
        .trim()

    /** The expressions between SELECT and FROM, which is what "narrow" is actually counted in. */
    private fun selectedExpressions(query: String): List<String> {
        val select = query.indexOf("SELECT ")
        val from = query.indexOf(" FROM ")
        assertTrue("not a SELECT: $query", select >= 0 && from > select)
        return query.substring(select + "SELECT ".length, from).split(",").map { it.trim() }
    }

    private fun mentions(query: String, column: String): Boolean =
        query.contains(column, ignoreCase = true)

    /**
     * [forbidden] must appear in none of [query] — checked only after the same search is shown to
     * work on a query that does select the column.
     */
    private fun assertSelectsNoText(label: String, query: String, forbidden: List<String>) {
        for (column in forbidden) {
            assertTrue(
                "the detector for '$column' cannot see it even when it is there",
                mentions("SELECT id, $column FROM t", column),
            )
            assertFalse("$label selects '$column'", mentions(query, column))
        }
    }

    // -------------------------------------------------------------------------------------------
    // Guards. Every assertion below reads a file; a missing or mis-sliced read would make them all
    // pass against nothing.
    // -------------------------------------------------------------------------------------------

    @Test
    fun `every source was found and every projection was located in it`() {
        assertTrue(source(ENTRY_DAO).contains("interface EntryDao"))
        assertTrue(source(JOURNAL_DAO).contains("interface JournalDao"))
        assertTrue(source(THOUGHT_DAO).contains("interface ThoughtRecordDao"))
        assertTrue(source(STEP_DAO).contains("interface GoalStepDao"))
        assertTrue(source(GOAL_DAO).contains("interface GoalDao"))
        assertTrue(source(LIFE_EVENT_DAO).contains("interface LifeEventDao"))
        assertTrue(source(REPOSITORY).contains("class SkyRepository"))

        for (path in listOf(ENTRY_DAO, JOURNAL_DAO, THOUGHT_DAO, STEP_DAO, GOAL_DAO, LIFE_EVENT_DAO)) {
            val query = skyQuery(path)
            assertTrue("$path: extracted no SELECT", query.startsWith("SELECT "))
            assertTrue("$path: extracted no FROM", query.contains(" FROM "))
        }
    }

    @Test
    fun `the expression counter counts expressions`() {
        assertEquals(3, selectedExpressions("SELECT a, b, c FROM t").size)
        assertEquals(1, selectedExpressions("SELECT a FROM t").size)
    }

    // -------------------------------------------------------------------------------------------
    // The promise, one table at a time.
    // -------------------------------------------------------------------------------------------

    @Test
    fun `the journal projection is a date and an id, and nothing a person wrote`() {
        val query = skyQuery(JOURNAL_DAO)
        assertEquals(
            "the journal projection selects more than (id, dateTime): $query",
            2,
            selectedExpressions(query).size,
        )
        assertTrue(query, mentions(query, "journal_entries"))
        assertSelectsNoText("the journal projection", query, listOf("title", "body"))

        // The detector, on this file specifically: `search` selects the body, and the same scan
        // finds it there. So the absence above is a fact about the projection, not about the scan.
        assertTrue(mentions(source(JOURNAL_DAO), "body LIKE"))
    }

    @Test
    fun `the check-in projection carries a mood and no note`() {
        val query = skyQuery(ENTRY_DAO)
        assertEquals(
            "the check-in projection selects more than (id, dateTime, moodLevel): $query",
            3,
            selectedExpressions(query).size,
        )
        assertTrue(query, mentions(query, "moodLevel"))
        assertSelectsNoText("the check-in projection", query, listOf("note", "photoPath"))
    }

    @Test
    fun `the practice projection carries none of the seven things a thought record asks for`() {
        val query = skyQuery(THOUGHT_DAO)
        assertEquals(2, selectedExpressions(query).size)
        assertTrue(query, mentions(query, "thought_records"))
        assertSelectsNoText(
            "the practice projection",
            query,
            listOf(
                "situation",
                "automaticThought",
                "evidenceFor",
                "evidenceAgainst",
                "balancedThought",
                "distortions",
            ),
        )
    }

    @Test
    fun `the project-step projection carries no step title, and only completed steps`() {
        val query = skyQuery(STEP_DAO)
        assertEquals(2, selectedExpressions(query).size)
        assertTrue(query, mentions(query, "completedAt"))
        // Both halves of the filter, because either one alone lets a wrong star through: without
        // the state a step that was moved back out of Done keeps its star, and without the null
        // check a step in Done with no stamp lands in January 1970.
        assertTrue(query, mentions(query, "state = 'done'"))
        assertTrue(query, mentions(query, "completedAt IS NOT NULL"))
        assertSelectsNoText("the project-step projection", query, listOf("title"))
    }

    @Test
    fun `the reached-goal projection carries no goal title, and no archived filter`() {
        val query = skyQuery(GOAL_DAO)
        assertEquals(2, selectedExpressions(query).size)
        assertTrue(query, mentions(query, "reachedAt IS NOT NULL"))
        assertSelectsNoText("the reached-goal projection", query, listOf("title", "notes"))

        // Not `archived = 0` (archiving a goal you reached must not delete the star) and not
        // `archived = 1` (the misreading the column exists to prevent). The detector first proves
        // it can see the word in a clause that has it, so the absence is about this query.
        assertTrue(mentions("SELECT id FROM goals WHERE archived = 0", "archived"))
        assertFalse("the reached-goal projection filters on archived: $query", mentions(query, "archived"))
    }

    @Test
    fun `the life-event projection carries no label`() {
        val query = skyQuery(LIFE_EVENT_DAO)
        assertEquals(2, selectedExpressions(query).size)
        assertTrue(query, mentions(query, "life_events"))
        assertTrue(query, mentions(query, "epochDay"))
        assertSelectsNoText("the life-event projection", query, listOf("label"))
    }

    // -------------------------------------------------------------------------------------------
    // And the repository reads the projections rather than the wide queries beside them.
    // -------------------------------------------------------------------------------------------

    @Test
    fun `the Sky reads its own projections and not the whole-row queries`() {
        val repository = source(REPOSITORY)
        // The detector: an ordinary repository does call observeAll, and this search finds it.
        assertTrue(mentions(source(JOURNAL_REPOSITORY), "observeAll("))
        assertFalse(
            "SkyRepository reaches for a whole-row query — the projections exist so it cannot",
            mentions(repository, "observeAll("),
        )
        // Six projections, one per kind, with no exception. The count is asserted rather than the
        // presence of each call because a seventh kind added by reaching for an existing SELECT *
        // would leave all six of these intact and still be the thing this file exists to stop.
        // Tied to the enum rather than written as a literal 6, so adding a seventh kind fails here
        // until that kind has a projection of its own.
        assertEquals(SkyKind.entries.size, Regex("observeSkyPoints\\(\\)").findAll(repository).count())
        // The wide read the reached-goal star used to come from is gone, not merely unused.
        assertFalse(mentions(repository, "observeReached("))
        assertFalse(
            "GoalDao still exposes the SELECT * the Sky was weaned off",
            mentions(source(GOAL_DAO), "fun observeReached("),
        )
    }
}
