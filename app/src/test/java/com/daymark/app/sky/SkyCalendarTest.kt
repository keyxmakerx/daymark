package com.daymark.app.sky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The calendar the layout stacks its rows on.
 *
 * This is the file most likely to be wrong in a way nobody notices: an off-by-one in February of a
 * century year moves someone's stars into the wrong month, and there is no visible symptom. So the
 * anchors below are dates whose epoch day is known independently rather than dates this file
 * produced, and the sweeps run over every day of a 40-year span rather than over chosen examples.
 */
class SkyCalendarTest {

    /**
     * Dates and their epoch days, from an independent source. The three interesting ones are the
     * century boundaries: 1900 is not a leap year, 2000 is, and 2100 is not.
     */
    private val anchors = listOf(
        Triple(1970, 1, 1) to 0L,
        Triple(1969, 12, 31) to -1L,
        Triple(1900, 1, 1) to -25_567L,
        Triple(1900, 3, 1) to -25_508L, // 59 days after 1 Jan: February had 28
        Triple(2000, 3, 1) to 11_017L, // 60 days after 1 Jan 2000: February had 29
        Triple(2024, 2, 29) to 19_782L,
        Triple(2026, 8, 15) to 20_680L,
        Triple(2100, 3, 1) to 47_541L, // 59 days after 1 Jan 2100: February had 28
    )

    @Test
    fun `known dates convert both ways`() {
        for ((civil, epochDay) in anchors) {
            val (y, m, d) = civil
            assertEquals("epochDayOf($y-$m-$d)", epochDay, SkyCalendar.epochDayOf(y, m, d))
            val back = SkyCalendar.civilOf(epochDay)
            assertEquals("year of $epochDay", y, back.year)
            assertEquals("month of $epochDay", m, back.month)
            assertEquals("day of $epochDay", d, back.day)
        }
    }

    @Test
    fun `every day of a forty-year span round-trips`() {
        // 1990-01-01 to 2030-01-01, which straddles the year-2000 leap case and the epoch.
        for (day in SkyCalendar.epochDayOf(1990, 1, 1)..SkyCalendar.epochDayOf(2030, 1, 1)) {
            val c = SkyCalendar.civilOf(day)
            assertEquals("round trip at $day", day, SkyCalendar.epochDayOf(c.year, c.month, c.day))
            assertTrue("month out of range at $day: ${c.month}", c.month in 1..12)
            assertTrue("day out of range at $day: ${c.day}", c.day in 1..31)
        }
    }

    @Test
    fun `dates before the epoch round-trip too`() {
        // Kotlin's integer division truncates toward zero, so the negative side is where a naive
        // implementation of this algorithm breaks. A life event in 1952 is an ordinary thing for a
        // person to record.
        for (day in SkyCalendar.epochDayOf(1940, 1, 1)..SkyCalendar.epochDayOf(1975, 1, 1)) {
            val c = SkyCalendar.civilOf(day)
            assertEquals("round trip at $day", day, SkyCalendar.epochDayOf(c.year, c.month, c.day))
        }
    }

    @Test
    fun `epochMonth is monotonic and increments exactly once per month`() {
        var previous = SkyCalendar.epochMonth(SkyCalendar.epochDayOf(1995, 1, 1))
        var changes = 0
        val from = SkyCalendar.epochDayOf(1995, 1, 1)
        val to = SkyCalendar.epochDayOf(2005, 1, 1)
        for (day in (from + 1)..to) {
            val m = SkyCalendar.epochMonth(day)
            assertTrue("epochMonth went backwards at $day", m >= previous)
            if (m != previous) {
                assertEquals("epochMonth skipped at $day", previous + 1, m)
                changes++
            }
            previous = m
        }
        assertEquals("ten years is 120 month boundaries", 120, changes)
    }

    @Test
    fun `month length is derived, not tabulated`() {
        val february2024 = SkyCalendar.epochMonth(SkyCalendar.epochDayOf(2024, 2, 10))
        val february2023 = SkyCalendar.epochMonth(SkyCalendar.epochDayOf(2023, 2, 10))
        val february1900 = SkyCalendar.epochMonth(SkyCalendar.epochDayOf(1900, 2, 10))
        val february2000 = SkyCalendar.epochMonth(SkyCalendar.epochDayOf(2000, 2, 10))
        assertEquals(29, SkyCalendar.lengthOfMonth(february2024))
        assertEquals(28, SkyCalendar.lengthOfMonth(february2023))
        assertEquals(28, SkyCalendar.lengthOfMonth(february1900))
        assertEquals(29, SkyCalendar.lengthOfMonth(february2000))
        assertEquals(31, SkyCalendar.lengthOfMonth(SkyCalendar.epochMonth(SkyCalendar.epochDayOf(2024, 12, 1))))
        assertEquals(30, SkyCalendar.lengthOfMonth(SkyCalendar.epochMonth(SkyCalendar.epochDayOf(2024, 11, 1))))
    }

    @Test
    fun `a month's days all fall inside its own row`() {
        // The property the layout actually needs: every day of a month maps to that month's index,
        // and the offset from the month's first day stays inside its length. A star that landed at
        // x >= 1 would be drawn in the following month's row.
        val from = SkyCalendar.epochDayOf(1998, 1, 1)
        val to = SkyCalendar.epochDayOf(2028, 1, 1)
        for (day in from..to) {
            val month = SkyCalendar.epochMonth(day)
            val offset = day - SkyCalendar.firstEpochDayOfMonth(month)
            assertTrue("negative offset at $day", offset >= 0)
            assertTrue("offset $offset ran past month $month", offset < SkyCalendar.lengthOfMonth(month))
        }
    }

    @Test
    fun `yearOfMonth and monthOfMonth invert epochMonth`() {
        for (day in SkyCalendar.epochDayOf(1960, 1, 1)..SkyCalendar.epochDayOf(2040, 1, 1) step 17) {
            val c = SkyCalendar.civilOf(day)
            val m = SkyCalendar.epochMonth(day)
            assertEquals(c.year, SkyCalendar.yearOfMonth(m))
            assertEquals(c.month, SkyCalendar.monthOfMonth(m))
        }
    }
}
