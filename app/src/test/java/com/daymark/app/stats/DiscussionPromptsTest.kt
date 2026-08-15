package com.daymark.app.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Side 4's prompts, and the line they are not allowed to cross.
 *
 * The regulatory point (`docs/PLAN_2026-08-NEXT.md` §1) is not a style preference: a report that
 * tells a clinician what to do is clinical decision support. So the load-bearing tests here are not
 * the ones checking that a rule fires — they are `the guard catches …` and `no generated prompt …`,
 * which run the forbidden-phrasing regex over everything these rules can emit. The guard is proved
 * against deliberately bad samples first, because a regex that matches nothing would pass the
 * second test silently.
 */
class DiscussionPromptsTest {

    // --- fixtures -------------------------------------------------------------------------------

    /** A range with nothing worth remarking on: enough data, no gaps, one instrument with a shape. */
    private fun base() = DiscussionPrompts.Inputs(
        daysInRange = 84,
        daysLogged = 60,
        totalEntries = 90,
        weeks = List(12) { DiscussionPrompts.Week(entryCount = 5, activityCount = 3, averageMood = 3.4) },
        gapStretches = 1,
        instruments = listOf(
            DiscussionPrompts.Instrument(
                title = "PHQ-9",
                rangeMin = 0,
                rangeMax = 27,
                points = listOf(
                    DiscussionPrompts.ScorePoint(9, "Mild"),
                    DiscussionPrompts.ScorePoint(11, "Moderate"),
                    DiscussionPrompts.ScorePoint(8, "Mild"),
                    DiscussionPrompts.ScorePoint(10, "Mild"),
                    DiscussionPrompts.ScorePoint(12, "Moderate"),
                    DiscussionPrompts.ScorePoint(9, "Mild"),
                ),
            ),
        ),
        offers = emptyList(),
    )

    /** Every rule that can co-exist, fired at once — the input the guard tests sweep. */
    private fun everything() = DiscussionPrompts.Inputs(
        daysInRange = 84,
        daysLogged = 40,
        totalEntries = 60,
        weeks = List(4) { DiscussionPrompts.Week(entryCount = 3, activityCount = 0, averageMood = 2.3) } +
            List(8) { DiscussionPrompts.Week(entryCount = 5, activityCount = 4, averageMood = 3.5) },
        gapStretches = 5,
        instruments = listOf(
            DiscussionPrompts.Instrument(
                title = "WHO-5",
                rangeMin = 0,
                rangeMax = 25,
                points = listOf(
                    DiscussionPrompts.ScorePoint(8, "Lower"),
                    DiscussionPrompts.ScorePoint(9, "Lower"),
                    DiscussionPrompts.ScorePoint(10, "Lower"),
                    DiscussionPrompts.ScorePoint(17, "Higher"),
                    DiscussionPrompts.ScorePoint(18, "Higher"),
                    DiscussionPrompts.ScorePoint(19, "Higher"),
                ),
            ),
            DiscussionPrompts.Instrument(
                title = "PHQ-9",
                rangeMin = 0,
                rangeMax = 27,
                // A real PHQ-9 band label carries the word "severe". It is the instrument's copy,
                // quoted verbatim, and must not be mistaken for this file making a severity claim.
                points = List(6) { DiscussionPrompts.ScorePoint(16, "Moderately severe") },
            ),
            DiscussionPrompts.Instrument(
                title = "GAD-7",
                rangeMin = 0,
                rangeMax = 21,
                points = listOf(
                    DiscussionPrompts.ScorePoint(6, "Mild"),
                    DiscussionPrompts.ScorePoint(7, "Mild"),
                ),
            ),
        ),
        offers = listOf(
            DiscussionPrompts.Offer("Compassion module", opened = false, completedCount = 0, declined = true),
            DiscussionPrompts.Offer("Sleep diary", opened = true, completedCount = 0, declined = false),
            DiscussionPrompts.Offer("Values writing", opened = false, completedCount = 0, declined = false),
            DiscussionPrompts.Offer("Weekly check-in", opened = true, completedCount = 5, declined = false),
        ),
    )

    /** The thin range from the brief: four entries across five weeks. */
    private fun thin() = DiscussionPrompts.Inputs(
        daysInRange = 35,
        daysLogged = 4,
        totalEntries = 4,
        weeks = List(5) { DiscussionPrompts.Week(entryCount = 1, activityCount = 0, averageMood = 2.0) },
        gapStretches = 4,
    )

    /** Daily entries and no self-checks at all. */
    private fun noCheckins() = base().copy(instruments = emptyList())

    private fun List<DiscussionPrompts.Prompt>.kinds() = map { it.kind }.toSet()

    private fun allPrompts(): List<DiscussionPrompts.Prompt> =
        listOf(base(), everything(), thin(), noCheckins())
            .flatMap { DiscussionPrompts.build(it, Locale.US) }

    // --- the bright line ------------------------------------------------------------------------

    @Test
    fun `the guard catches deliberately bad prompts`() {
        // Proved before it is trusted: a regex that matched nothing would pass the sweep below
        // without noticing a thing. Every line here is a prompt side 4 must never print.
        val forbidden = listOf(
            "Based on study X, consider increasing session frequency. Worth asking about?",
            "We recommend a weekly review.",
            "The client should try the breathing exercise more often.",
            "Scores indicate a moderate depressive disorder.",
            "Symptoms suggest deterioration; risk of relapse is elevated.",
            "Evidence from a recent trial supports reducing the dose.",
            "Advise them to increase activity.",
            "This may warrant a diagnostic assessment.",
            "The research literature recommends titrating upward.",
            "Clinically significant worsening across the range.",
            "Lower activity caused the drop in wellbeing.",
            "Activity correlates with mood and predicts next month's scores.",
        )
        forbidden.forEach { bad ->
            assertTrue("guard missed: $bad", DiscussionPrompts.violatesBrightLine(bad))
        }
    }

    @Test
    fun `no generated prompt matches recommendation phrasing`() {
        val prompts = allPrompts()
        assertTrue(prompts.isNotEmpty())
        prompts.forEach { prompt ->
            assertFalse(
                "prompt crosses the line (${prompt.kind}): ${prompt.text}",
                DiscussionPrompts.violatesBrightLine(prompt.text),
            )
        }
    }

    @Test
    fun `the sweep actually exercises every rule`() {
        // Otherwise the guard test above could pass by never generating anything interesting.
        val kinds = allPrompts().kinds()
        listOf(
            DiscussionPrompts.KIND_THIN_DATA,
            DiscussionPrompts.KIND_QUIET_WEEKS,
            DiscussionPrompts.KIND_COVERAGE_GAPS,
            DiscussionPrompts.KIND_NO_CHECKINS,
            DiscussionPrompts.KIND_THIN_INSTRUMENT,
            DiscussionPrompts.KIND_BAND_HELD,
            DiscussionPrompts.KIND_ONE_MOVED,
            DiscussionPrompts.KIND_OFFER_DECLINED,
            DiscussionPrompts.KIND_OFFER_UNFINISHED,
            DiscussionPrompts.KIND_OFFER_UNOPENED,
        ).forEach { kind ->
            assertTrue("rule never fired in the sweep: $kind", kind in kinds)
        }
    }

    @Test
    fun `an instrument's own band label may carry a word the guard forbids`() {
        // "Moderately severe" is PHQ-9's wording, printed verbatim inside quotes. The guard is
        // about the sentence we wrote around the quotes, which is the only part we author.
        val banded = DiscussionPrompts.build(everything(), Locale.US)
            .first { it.kind == DiscussionPrompts.KIND_BAND_HELD }
        assertTrue("Moderately severe" in banded.text)
        assertFalse(DiscussionPrompts.violatesBrightLine(banded.text))
        // The same words outside quotes are still caught.
        assertTrue(DiscussionPrompts.violatesBrightLine("Results were moderately severe this month."))
    }

    @Test
    fun `every prompt is phrased as a question`() {
        allPrompts().forEach { prompt ->
            assertTrue("not a question (${prompt.kind}): ${prompt.text}", prompt.text.trim().endsWith("?"))
        }
    }

    // --- rule 1: too little data ----------------------------------------------------------------

    @Test
    fun `four entries across five weeks says so, and claims nothing`() {
        val prompts = DiscussionPrompts.build(thin(), Locale.US)
        val text = prompts.first { it.kind == DiscussionPrompts.KIND_THIN_DATA }.text
        assertTrue(text, "4 entries across 5 weeks" in text)
        assertTrue(text, "nothing here supports a pattern" in text.lowercase(Locale.US))
    }

    @Test
    fun `thin data stands the pattern rules down`() {
        // The thin fixture has quiet weeks and four gap stretches; neither may speak on four entries.
        val kinds = DiscussionPrompts.build(thin(), Locale.US).kinds()
        assertFalse(DiscussionPrompts.KIND_QUIET_WEEKS in kinds)
        assertFalse(DiscussionPrompts.KIND_COVERAGE_GAPS in kinds)
    }

    @Test
    fun `logging on a handful of days is thin however many entries it produced`() {
        // 40 entries, all of them on 12 days of 84. Enough entries, nowhere near enough coverage.
        val kinds = DiscussionPrompts.build(base().copy(totalEntries = 40, daysLogged = 12), Locale.US).kinds()
        assertTrue(DiscussionPrompts.KIND_THIN_DATA in kinds)
    }

    @Test
    fun `an empty range produces nothing at all`() {
        val prompts = DiscussionPrompts.build(
            DiscussionPrompts.Inputs(daysInRange = 30, daysLogged = 0, totalEntries = 0),
            Locale.US,
        )
        assertTrue(prompts.isEmpty())
    }

    @Test
    fun `a quiet range with nothing to remark on produces nothing`() {
        assertEquals(emptyList<DiscussionPrompts.Prompt>(), DiscussionPrompts.build(base(), Locale.US))
    }

    // --- rule 2: quiet weeks --------------------------------------------------------------------

    @Test
    fun `wellbeing entries lower on weeks with no logged activity`() {
        val text = DiscussionPrompts.build(everything(), Locale.US)
            .first { it.kind == DiscussionPrompts.KIND_QUIET_WEEKS }.text
        assertTrue(text, "4 weeks with no logged activity" in text)
        assertTrue(text, "2.3" in text && "3.5" in text)
        assertTrue(text, text.endsWith("Worth asking about?"))
    }

    @Test
    fun `quiet weeks needs both sides of the comparison`() {
        // Every week quiet: there is nothing to compare against, so the rule says nothing.
        val allQuiet = everything().copy(
            weeks = List(12) { DiscussionPrompts.Week(entryCount = 3, activityCount = 0, averageMood = 2.3) },
        )
        assertFalse(DiscussionPrompts.KIND_QUIET_WEEKS in DiscussionPrompts.build(allQuiet, Locale.US).kinds())
    }

    @Test
    fun `quiet weeks stays quiet when the difference is small`() {
        val narrow = everything().copy(
            weeks = List(4) { DiscussionPrompts.Week(entryCount = 3, activityCount = 0, averageMood = 3.3) } +
                List(8) { DiscussionPrompts.Week(entryCount = 5, activityCount = 4, averageMood = 3.5) },
        )
        assertFalse(DiscussionPrompts.KIND_QUIET_WEEKS in DiscussionPrompts.build(narrow, Locale.US).kinds())
    }

    // --- rule 3: coverage -----------------------------------------------------------------------

    @Test
    fun `gaps are counted against the range, not carried separately`() {
        val text = DiscussionPrompts.build(everything(), Locale.US)
            .first { it.kind == DiscussionPrompts.KIND_COVERAGE_GAPS }.text
        // 84 days, logged on 40 => 44 with nothing. The prompt may not invent its own totals.
        assertTrue(text, "44 of the 84 days" in text)
        assertTrue(text, "5 separate stretches" in text)
    }

    @Test
    fun `a single gap is not a coverage story`() {
        val oneGap = everything().copy(gapStretches = 1)
        assertFalse(DiscussionPrompts.KIND_COVERAGE_GAPS in DiscussionPrompts.build(oneGap, Locale.US).kinds())
    }

    // --- rule 4: entries without self-checks ----------------------------------------------------

    @Test
    fun `empty plots are named as untaken, not as flat`() {
        val text = DiscussionPrompts.build(noCheckins(), Locale.US)
            .first { it.kind == DiscussionPrompts.KIND_NO_CHECKINS }.text
        assertTrue(text, "nothing was taken" in text)
        assertTrue(text, "held flat" in text)
    }

    // --- rules 5-7: instruments -----------------------------------------------------------------

    @Test
    fun `a tool with two results says it cannot show a shape`() {
        val text = DiscussionPrompts.build(everything(), Locale.US)
            .first { it.kind == DiscussionPrompts.KIND_THIN_INSTRUMENT }.text
        assertTrue(text, "\"GAD-7\"" in text)
        assertTrue(text, "2 results" in text)
    }

    @Test
    fun `a tool with a full run is not called thin`() {
        val kinds = DiscussionPrompts.build(base(), Locale.US).kinds()
        assertFalse(DiscussionPrompts.KIND_THIN_INSTRUMENT in kinds)
    }

    @Test
    fun `every result in the same band is reported with the instrument's own word`() {
        val text = DiscussionPrompts.build(everything(), Locale.US)
            .first { it.kind == DiscussionPrompts.KIND_BAND_HELD }.text
        assertTrue(text, "All 6 \"PHQ-9\" results" in text)
        assertTrue(text, "(\"Moderately severe\")" in text)
    }

    @Test
    fun `a blank band label never becomes a band claim`() {
        val unbanded = base().copy(
            instruments = listOf(
                DiscussionPrompts.Instrument("Own check", 0, 10, List(5) { DiscussionPrompts.ScorePoint(4, "") }),
            ),
        )
        assertFalse(DiscussionPrompts.KIND_BAND_HELD in DiscussionPrompts.build(unbanded, Locale.US).kinds())
    }

    @Test
    fun `a change confined to one instrument names the ones that held`() {
        val text = DiscussionPrompts.build(everything(), Locale.US)
            .first { it.kind == DiscussionPrompts.KIND_ONE_MOVED }.text
        assertTrue(text, "\"WHO-5\" moved" in text)
        assertTrue(text, "\"PHQ-9\"" in text)
        // Direction is the instrument's business, not ours.
        assertFalse(text.lowercase(Locale.US).contains("improv"))
        assertFalse(text.lowercase(Locale.US).contains("better"))
    }

    @Test
    fun `nothing is said when every instrument moved`() {
        val bothMoved = everything().copy(
            instruments = listOf(
                DiscussionPrompts.Instrument(
                    "WHO-5", 0, 25,
                    listOf(8, 9, 10, 17, 18, 19).map { DiscussionPrompts.ScorePoint(it, "Band") },
                ),
                DiscussionPrompts.Instrument(
                    "PHQ-9", 0, 27,
                    listOf(4, 5, 6, 18, 19, 20).map { DiscussionPrompts.ScorePoint(it, "Band") },
                ),
            ),
        )
        assertFalse(DiscussionPrompts.KIND_ONE_MOVED in DiscussionPrompts.build(bothMoved, Locale.US).kinds())
    }

    @Test
    fun `one instrument alone is never a comparison`() {
        val single = everything().copy(
            instruments = listOf(
                DiscussionPrompts.Instrument(
                    "WHO-5", 0, 25,
                    listOf(8, 9, 10, 17, 18, 19).map { DiscussionPrompts.ScorePoint(it, "Band") },
                ),
            ),
        )
        assertFalse(DiscussionPrompts.KIND_ONE_MOVED in DiscussionPrompts.build(single, Locale.US).kinds())
    }

    @Test
    fun `a move is measured against the instrument's own scale`() {
        // Three points on a 0..100 scale is a rounding error there and a landslide on 0..10.
        val wideScale = everything().copy(
            instruments = listOf(
                DiscussionPrompts.Instrument(
                    "Wide", 0, 100,
                    listOf(40, 41, 42, 43, 44, 45).map { DiscussionPrompts.ScorePoint(it, "Band") },
                ),
                DiscussionPrompts.Instrument(
                    "Narrow", 0, 10,
                    listOf(2, 2, 2, 2, 2, 2).map { DiscussionPrompts.ScorePoint(it, "Band") },
                ),
            ),
        )
        assertFalse(DiscussionPrompts.KIND_ONE_MOVED in DiscussionPrompts.build(wideScale, Locale.US).kinds())
    }

    // --- rules 8-10: what was offered -----------------------------------------------------------

    @Test
    fun `a declined offer is stated, and not explained`() {
        val text = DiscussionPrompts.build(everything(), Locale.US)
            .first { it.kind == DiscussionPrompts.KIND_OFFER_DECLINED }.text
        assertTrue(text, "\"Compassion module\" was offered in this range and declined" in text)
    }

    @Test
    fun `opened and unopened are different facts, kept apart`() {
        val prompts = DiscussionPrompts.build(everything(), Locale.US)
        val unfinished = prompts.first { it.kind == DiscussionPrompts.KIND_OFFER_UNFINISHED }.text
        val unopened = prompts.first { it.kind == DiscussionPrompts.KIND_OFFER_UNOPENED }.text
        assertTrue(unfinished, "\"Sleep diary\"" in unfinished && "not finished" in unfinished)
        assertTrue(unopened, "\"Values writing\"" in unopened && "not opened" in unopened)
        // A completed item is nobody's discussion prompt.
        assertFalse(prompts.any { "Weekly check-in" in it.text })
    }

    @Test
    fun `nothing here says why is on both offer prompts`() {
        val prompts = DiscussionPrompts.build(everything(), Locale.US)
            .filter {
                it.kind == DiscussionPrompts.KIND_OFFER_UNFINISHED ||
                    it.kind == DiscussionPrompts.KIND_OFFER_UNOPENED
            }
        assertEquals(2, prompts.size)
        prompts.forEach { assertTrue(it.text, "Nothing here says why" in it.text) }
    }

    @Test
    fun `several declines are listed in one prompt`() {
        val many = everything().copy(
            offers = listOf(
                DiscussionPrompts.Offer("Compassion module", declined = true),
                DiscussionPrompts.Offer("Sleep diary", declined = true),
                DiscussionPrompts.Offer("Values writing", declined = true),
            ),
        )
        val declined = DiscussionPrompts.build(many, Locale.US)
            .filter { it.kind == DiscussionPrompts.KIND_OFFER_DECLINED }
        assertEquals(1, declined.size)
        assertTrue(declined.first().text, "3 things offered in this range were declined" in declined.first().text)
        assertTrue(declined.first().text, "\"Sleep diary\" and \"Values writing\"" in declined.first().text)
    }

    // --- shape ----------------------------------------------------------------------------------

    @Test
    fun `the same inputs always produce the same page`() {
        val a = DiscussionPrompts.build(everything(), Locale.US)
        val b = DiscussionPrompts.build(everything(), Locale.US)
        assertEquals(a, b)
    }

    @Test
    fun `thin data leads the page when it fires`() {
        assertEquals(DiscussionPrompts.KIND_THIN_DATA, DiscussionPrompts.build(thin(), Locale.US).first().kind)
    }

    @Test
    fun `texts carries the same strings the prompts do`() {
        assertEquals(
            DiscussionPrompts.build(everything(), Locale.US).map { it.text },
            DiscussionPrompts.texts(everything(), Locale.US),
        )
    }

    @Test
    fun `no prompt labels the person or names a clinical state`() {
        // D1a / "reflect, never label": prompts describe entries, counts and instruments. They do
        // not describe a person. This is a second, blunter net under the guard regex.
        val banned = listOf("depress", "anxious", "anxiety", "unwell", "patient is", "they are struggling")
        allPrompts().forEach { prompt ->
            val lower = prompt.text.lowercase(Locale.US)
            banned.forEach { word ->
                assertFalse("${prompt.kind} labels the person: ${prompt.text}", word in lower)
            }
        }
    }
}
