package com.daylie.app.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class MoodStatsTest {

    @Test
    fun averageMood_emptyIsNull() {
        assertNull(MoodStats.averageMood(emptyList()))
    }

    @Test
    fun averageMood_computesMean() {
        assertEquals(3.0, MoodStats.averageMood(listOf(1, 3, 5))!!, 0.0001)
    }

    @Test
    fun moodCounts_alwaysHasFiveKeys() {
        val counts = MoodStats.moodCounts(listOf(5, 5, 3))
        assertEquals(setOf(1, 2, 3, 4, 5), counts.keys)
        assertEquals(2, counts[5])
        assertEquals(1, counts[3])
        assertEquals(0, counts[1])
    }

    @Test
    fun currentStreak_countsConsecutiveEndingToday() {
        val today = LocalDate.of(2026, 1, 10)
        val days = setOf(today, today.minusDays(1), today.minusDays(2))
        assertEquals(3, MoodStats.currentStreak(days, today))
    }

    @Test
    fun currentStreak_aliveIfLoggedYesterday() {
        val today = LocalDate.of(2026, 1, 10)
        val days = setOf(today.minusDays(1), today.minusDays(2))
        assertEquals(2, MoodStats.currentStreak(days, today))
    }

    @Test
    fun currentStreak_brokenWhenGap() {
        val today = LocalDate.of(2026, 1, 10)
        val days = setOf(today.minusDays(3), today.minusDays(4))
        assertEquals(0, MoodStats.currentStreak(days, today))
    }

    @Test
    fun longestStreak_findsBestRun() {
        val base = LocalDate.of(2026, 1, 1)
        val days = setOf(
            base, base.plusDays(1), base.plusDays(2), // run of 3
            base.plusDays(5),                          // isolated
            base.plusDays(7), base.plusDays(8),        // run of 2
        )
        assertEquals(3, MoodStats.longestStreak(days))
    }

    @Test
    fun activityAverages_perActivityMean() {
        val entries = listOf(
            5 to listOf(1L, 2L),
            3 to listOf(1L),
            1 to listOf(2L),
        )
        val avgs = MoodStats.activityAverages(entries)
        assertEquals(4.0, avgs[1L]!!, 0.0001) // (5 + 3) / 2
        assertEquals(3.0, avgs[2L]!!, 0.0001) // (5 + 1) / 2
    }
}
