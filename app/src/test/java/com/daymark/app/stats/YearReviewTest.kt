package com.daymark.app.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

class YearReviewTest {

    private val label: (Int) -> String =
        { level -> listOf("Awful", "Bad", "Meh", "Good", "Rad")[level - 1] }

    @Test
    fun emptyYear_hasNoStarsOrChapters() {
        val r = YearReview.build(2026, emptyMap(), label, Locale.US)
        assertEquals(0, r.totalStars)
        assertNull(r.avgMoodLabel)
        assertTrue(r.chapters.isEmpty())
        assertEquals(0, r.longestStreak)
    }

    @Test
    fun countsStars_andLabelsOverallAverage() {
        val moods = mapOf(
            LocalDate.of(2026, 1, 5) to 4.0,
            LocalDate.of(2026, 1, 6) to 4.0,
            LocalDate.of(2026, 7, 10) to 5.0,
        )
        val r = YearReview.build(2026, moods, label, Locale.US)
        assertEquals(3, r.totalStars)
        assertEquals("Good", r.avgMoodLabel) // mean 4.33 -> 4 -> Good
    }

    @Test
    fun picksBrightestMonth() {
        val moods = mapOf(
            LocalDate.of(2026, 2, 1) to 2.0,
            LocalDate.of(2026, 8, 1) to 5.0,
            LocalDate.of(2026, 8, 2) to 5.0,
        )
        val r = YearReview.build(2026, moods, label, Locale.US)
        assertEquals("August", r.brightestMonthLabel)
    }

    @Test
    fun longestStreak_isAttributedToItsQuarter() {
        // A 4-day run in April (Q2) and isolated days elsewhere.
        val moods = buildMap {
            put(LocalDate.of(2026, 1, 1), 3.0)
            for (d in 10..13) put(LocalDate.of(2026, 4, d), 4.0)
            put(LocalDate.of(2026, 9, 1), 3.0)
        }
        val r = YearReview.build(2026, moods, label, Locale.US)
        assertEquals(4, r.longestStreak)
        val q2 = r.chapters.first { it.label.startsWith("April") }
        assertTrue(q2.highlight!!.contains("Longest streak began here"))
        assertTrue(q2.highlight!!.contains("4 days"))
    }

    @Test
    fun chaptersOnlyForQuartersWithData_andSummarise() {
        val moods = mapOf(
            LocalDate.of(2026, 5, 1) to 4.0,
            LocalDate.of(2026, 5, 2) to 4.0,
        )
        val r = YearReview.build(2026, moods, label, Locale.US)
        assertEquals(1, r.chapters.size)
        val c = r.chapters.first()
        assertEquals(2, c.daysLogged)
        assertEquals("Mostly Good · 2 days", c.summary)
        assertEquals(listOf(4, 4), c.starLevels)
    }

    @Test
    fun brightestMonth_tieResolvesToEarliestMonth() {
        // March and August both average exactly 4.0 — March (earlier) should win, deterministically.
        val moods = mapOf(
            LocalDate.of(2026, 3, 1) to 4.0,
            LocalDate.of(2026, 8, 1) to 4.0,
        )
        val r = YearReview.build(2026, moods, label, Locale.US)
        assertEquals("March", r.brightestMonthLabel)
    }

    @Test
    fun ignoresEntriesOutsideTheYear() {
        val moods = mapOf(
            LocalDate.of(2025, 12, 31) to 5.0,
            LocalDate.of(2026, 1, 1) to 3.0,
        )
        val r = YearReview.build(2026, moods, label, Locale.US)
        assertEquals(1, r.totalStars)
    }
}
