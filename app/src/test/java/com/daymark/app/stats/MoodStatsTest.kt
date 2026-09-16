package com.daymark.app.stats

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
    fun daysWithEntry_countsInsideTheWindowRegardlessOfOrderOrRepeats() {
        val today = LocalDate.of(2026, 1, 30)
        val days = setOf(today, today.minusDays(5), today.minusDays(29))
        assertEquals(3, MoodStats.daysWithEntryInLast30(days, today))
    }

    @Test
    fun daysWithEntry_bothEdgesOfTheWindowAreInside() {
        // The window is thirty days INCLUSIVE of today, so today and today-29 both count and
        // today-30 does not. An off-by-one here would silently change the number on two screens.
        val today = LocalDate.of(2026, 1, 30)
        assertEquals(1, MoodStats.daysWithEntryInLast30(setOf(today), today))
        assertEquals(1, MoodStats.daysWithEntryInLast30(setOf(today.minusDays(29)), today))
        assertEquals(0, MoodStats.daysWithEntryInLast30(setOf(today.minusDays(30)), today))
    }

    /**
     * The property the whole change is for: a gap costs the days it covers and nothing more.
     *
     * The old `currentStreak` returned 0 for this history — one missed day in the middle wiped
     * every day before it. The positive control is the same set with the gap filled: if the count
     * ever stops moving with the data, that assertion is what goes red.
     */
    @Test
    fun daysWithEntry_aGapCostsOnlyTheDaysItCovers() {
        val today = LocalDate.of(2026, 1, 30)
        val withGap = (0L..9L).filter { it != 3L }.map { today.minusDays(it) }.toSet()
        assertEquals(9, MoodStats.daysWithEntryInLast30(withGap, today))
        val filled = withGap + today.minusDays(3)
        assertEquals(10, MoodStats.daysWithEntryInLast30(filled, today))
    }

    @Test
    fun daysWithEntry_ignoresEverythingOlderThanTheWindowAndTheFuture() {
        val today = LocalDate.of(2026, 1, 30)
        val days = setOf(today.minusDays(60), today.minusDays(31), today.plusDays(1))
        assertEquals(0, MoodStats.daysWithEntryInLast30(days, today))
        // Positive control: the same call does see a day that is inside the window.
        assertEquals(1, MoodStats.daysWithEntryInLast30(days + today.minusDays(2), today))
    }

    @Test
    fun daysWithEntry_emptyHistoryIsZero() {
        assertEquals(0, MoodStats.daysWithEntryInLast30(emptySet(), LocalDate.of(2026, 1, 30)))
    }

    @Test
    fun activityAverages_perActivityMean() {
        val a = MoodCorrelations.FactorId.ofActivity(1L)
        val b = MoodCorrelations.FactorId.ofActivity(2L)
        val entries = listOf(
            5 to listOf(a, b),
            3 to listOf(a),
            1 to listOf(b),
        )
        val avgs = MoodStats.activityAverages(entries)
        assertEquals(4.0, avgs[a]!!, 0.0001) // (5 + 3) / 2
        assertEquals(3.0, avgs[b]!!, 0.0001) // (5 + 1) / 2
    }
}
