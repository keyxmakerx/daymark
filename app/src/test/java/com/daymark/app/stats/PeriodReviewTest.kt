package com.daymark.app.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.util.Locale

class PeriodReviewTest {

    @Test
    fun emptyReturnsPrompt() {
        val text = PeriodReview.build(
            PeriodReview.Inputs(0, null, null, null, null, 0),
            Locale.US,
        )
        assertEquals("No entries. The summary is drawn from entries.", text)
    }

    @Test
    fun fullSummaryMentionsKeyFacts() {
        val text = PeriodReview.build(
            PeriodReview.Inputs(
                totalEntries = 12,
                avgMood = 3.8,
                bestDay = DayOfWeek.SATURDAY,
                worstDay = DayOfWeek.MONDAY,
                topFactorUp = "Exercise",
                daysWithEntryLast30 = 4,
            ),
            Locale.US,
        )
        assertTrue(text.contains("12 entries"))
        assertTrue(text.contains("3.8"))
        assertTrue(text.contains("Saturday"))
        assertTrue(text.contains("Monday"))
        assertTrue(text.contains("Exercise"))
        assertTrue(text.contains("4 of the last 30 days"))
    }

    /**
     * At zero the sentence is not written at all — not "0 of the last 30 days", which would draw
     * someone's absence as a figure and put it in a summary they may hand to a clinician.
     *
     * The positive control is the same call with the count at one: if the sentence ever stopped
     * being produced for any reason, the absence assertion alone would pass while saying nothing.
     */
    @Test
    fun omitsTheContinuitySentenceEntirelyAtZero() {
        val none = PeriodReview.build(
            PeriodReview.Inputs(3, 3.0, null, null, null, 0),
            Locale.US,
        )
        assertTrue(none, !none.contains("of the last"))
        assertTrue(none, !none.contains("0 of"))

        val one = PeriodReview.build(
            PeriodReview.Inputs(3, 3.0, null, null, null, 1),
            Locale.US,
        )
        assertTrue(one, one.contains("1 of the last 30 days"))
    }

    /** Nothing in the summary reads as a run, a reward, or a compliment on having logged. */
    @Test
    fun theSummaryCarriesNoStreakAndNoCongratulation() {
        val banned = Regex("""streak|in a row|consecutive|nice|well done|keep it up|great""", RegexOption.IGNORE_CASE)
        // The detector must be able to fail — this is the sentence that used to ship.
        assertTrue(banned.containsMatchIn("You're on a 4-day logging streak — nice."))

        val text = PeriodReview.build(
            PeriodReview.Inputs(
                totalEntries = 12,
                avgMood = 3.8,
                bestDay = DayOfWeek.SATURDAY,
                worstDay = DayOfWeek.MONDAY,
                topFactorUp = "Exercise",
                daysWithEntryLast30 = 21,
            ),
            Locale.US,
        )
        assertTrue(text.isNotEmpty())
        assertTrue(text, !banned.containsMatchIn(text))
    }
}
