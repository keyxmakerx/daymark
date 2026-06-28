package com.daymark.app.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek

class MoodPatternsTest {

    @Test
    fun byDayOfWeek_averagesPerWeekday() {
        val entries = listOf(
            DayOfWeek.MONDAY to 4,
            DayOfWeek.MONDAY to 2,
            DayOfWeek.FRIDAY to 5,
        )
        val map = MoodPatterns.byDayOfWeek(entries)
        assertEquals(3.0, map[DayOfWeek.MONDAY]!!, 1e-9)
        assertEquals(5.0, map[DayOfWeek.FRIDAY]!!, 1e-9)
        assertNull(map[DayOfWeek.SUNDAY])
    }

    @Test
    fun timeBucket_boundaries() {
        assertEquals(MoodPatterns.TimeBucket.Night, MoodPatterns.TimeBucket.of(4))
        assertEquals(MoodPatterns.TimeBucket.Morning, MoodPatterns.TimeBucket.of(5))
        assertEquals(MoodPatterns.TimeBucket.Afternoon, MoodPatterns.TimeBucket.of(12))
        assertEquals(MoodPatterns.TimeBucket.Evening, MoodPatterns.TimeBucket.of(17))
        assertEquals(MoodPatterns.TimeBucket.Night, MoodPatterns.TimeBucket.of(22))
        assertEquals(MoodPatterns.TimeBucket.Night, MoodPatterns.TimeBucket.of(0))
    }

    @Test
    fun byTimeOfDay_averagesPerBucket() {
        val entries = listOf(6 to 5, 8 to 3, 14 to 2, 23 to 1)
        val map = MoodPatterns.byTimeOfDay(entries)
        assertEquals(4.0, map[MoodPatterns.TimeBucket.Morning]!!, 1e-9)
        assertEquals(2.0, map[MoodPatterns.TimeBucket.Afternoon]!!, 1e-9)
        assertEquals(1.0, map[MoodPatterns.TimeBucket.Night]!!, 1e-9)
    }

    @Test
    fun periodCompare_computesDeltaPct() {
        val c = MoodPatterns.periodCompare(current = listOf(4, 4, 4), previous = listOf(2, 2))
        assertEquals(4.0, c.currentAvg!!, 1e-9)
        assertEquals(2.0, c.previousAvg!!, 1e-9)
        assertEquals(100.0, c.deltaPct!!, 1e-9)
        assertEquals(3, c.currentCount)
        assertEquals(2, c.previousCount)
    }

    @Test
    fun periodCompare_handlesEmptyPrevious() {
        val c = MoodPatterns.periodCompare(current = listOf(3), previous = emptyList())
        assertEquals(3.0, c.currentAvg!!, 1e-9)
        assertNull(c.previousAvg)
        assertNull(c.deltaPct)
    }
}
