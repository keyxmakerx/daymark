package com.daymark.app.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** The report's comparison line (#520): two numbers, never against nothing. */
class PriorGapTest {

    private val start = LocalDate.of(2026, 10, 1)
    private val windowStart = start.minusMonths(6)

    private fun everyDay(from: LocalDate, until: LocalDate): MutableSet<LocalDate> {
        val days = mutableSetOf<LocalDate>()
        var d = from
        while (d.isBefore(until)) { days.add(d); d = d.plusDays(1) }
        return days
    }

    @Test
    fun the_longest_run_in_the_window_is_counted() {
        val logged = everyDay(windowStart, start)
        (0L until 4L).forEach { logged.remove(LocalDate.of(2026, 6, 10).plusDays(it)) }
        (0L until 2L).forEach { logged.remove(LocalDate.of(2026, 8, 1).plusDays(it)) }
        assertEquals(4, PriorGap.longestBefore(start, windowStart, logged))
    }

    @Test
    fun every_day_logged_is_zero() {
        assertEquals(0, PriorGap.longestBefore(start, windowStart, everyDay(windowStart, start)))
    }

    /** Less than six months of history: no answer, rather than a comparison against nothing. */
    @Test
    fun a_short_history_has_no_answer() {
        val logged = everyDay(windowStart.plusDays(1), start)
        assertNull(PriorGap.longestBefore(start, windowStart.plusDays(1), logged))
        assertNull(PriorGap.longestBefore(start, null, emptySet()))
        // The control: a first entry exactly at the window's start is a full window.
        assertEquals(1, PriorGap.longestBefore(start, windowStart, logged + windowStart - windowStart.plusDays(1)))
    }

    /** The report's own first day, and anything after it, never counts toward the window. */
    @Test
    fun the_report_itself_is_not_in_the_window() {
        val logged = everyDay(windowStart, start)
        assertEquals(0, PriorGap.longestBefore(start, windowStart, logged))
        assertEquals(1, PriorGap.longestBefore(start, windowStart, logged - start.minusDays(1)))
    }

    @Test
    fun the_line_says_two_numbers_and_nothing_else() {
        val line = PriorGap.line(4)
        assertEquals("The longest stretch without entries in the 6 months before this report was 4 days.", line)
        assertEquals("The longest stretch without entries in the 6 months before this report was 1 day.", PriorGap.line(1))
        val judging = Regex("""(?i)unusual|concern|longer|shorter|worse|better|normal|typical|because|only|just""")
        for (n in listOf(0, 1, 4, 30)) assertFalse(PriorGap.line(n), judging.containsMatchIn(PriorGap.line(n)))
        assertTrue(judging.containsMatchIn("This was unusual."))
    }
}
