package com.daymark.app.stats

import com.daymark.app.stats.YearGrid.Day
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class YearGridTest {

    private val today = LocalDate.of(2026, 11, 1)

    private fun daysOf(month: YearGrid.Month) = month.days.filter { it != Day.Pad }

    @Test
    fun twelveMonths_eachDayOnce_underItsWeekday() {
        val months = YearGrid.months(2026, emptyMap(), today)
        assertEquals(12, months.size)
        assertEquals(365, months.sumOf { daysOf(it).size })
        // 1 January 2026 is a Thursday: three places before it, Monday first.
        assertEquals(3, months[0].days.takeWhile { it == Day.Pad }.size)
        // 1 June 2026 is a Monday: none. 1 February 2026 is a Sunday: six.
        assertEquals(0, months[5].days.takeWhile { it == Day.Pad }.size)
        assertEquals(6, months[1].days.takeWhile { it == Day.Pad }.size)
        // A leap year has its 29 February.
        assertEquals(29, daysOf(YearGrid.months(2028, emptyMap(), today)[1]).size)
    }

    @Test
    fun aDayKeepsItsRange_neverAnAverage() {
        val date = LocalDate.of(2026, 4, 2)
        val day = YearGrid.day(date, listOf(1, 5), today)
        assertEquals(Day.Moods(low = 1, high = 5), day)
        assertTrue((day as Day.Moods).isSplit)
        // Positive control: the average of 1 and 5 is 3, and the grid must not hold it.
        assertFalse(day.low == 3 || day.high == 3)
    }

    @Test
    fun aDayOfOneMood_isWhole() {
        val day = YearGrid.day(LocalDate.of(2026, 4, 3), listOf(4, 4, 4), today) as Day.Moods
        assertEquals(4, day.low)
        assertEquals(4, day.high)
        assertFalse(day.isSplit)
    }

    @Test
    fun aGapAndADayToCome_areDifferent() {
        assertEquals(Day.Nothing, YearGrid.day(today, null, today))
        assertEquals(Day.Nothing, YearGrid.day(today.minusDays(1), emptyList(), today))
        assertEquals(Day.ToCome, YearGrid.day(today.plusDays(1), null, today))
        // An entry is drawn even when its date is after today.
        assertEquals(Day.Moods(2, 2), YearGrid.day(today.plusDays(1), listOf(2), today))
    }

    @Test
    fun theMapReachesTheRightDay() {
        val levels = mapOf(LocalDate.of(2026, 1, 1) to listOf(2, 4))
        val jan = YearGrid.months(2026, levels, today)[0]
        assertEquals(Day.Moods(2, 4), daysOf(jan)[0])
        assertEquals(Day.Nothing, daysOf(jan)[1])
    }
}
