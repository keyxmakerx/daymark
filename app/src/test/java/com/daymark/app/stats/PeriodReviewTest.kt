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
        assertEquals("Log a few entries to see your summary.", text)
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
                currentStreak = 4,
            ),
            Locale.US,
        )
        assertTrue(text.contains("12 entries"))
        assertTrue(text.contains("3.8"))
        assertTrue(text.contains("Saturday"))
        assertTrue(text.contains("Monday"))
        assertTrue(text.contains("Exercise"))
        assertTrue(text.contains("4-day"))
    }

    @Test
    fun omitsStreakLineBelowTwo() {
        val text = PeriodReview.build(
            PeriodReview.Inputs(3, 3.0, null, null, null, 1),
            Locale.US,
        )
        assertTrue(!text.contains("streak"))
    }
}
