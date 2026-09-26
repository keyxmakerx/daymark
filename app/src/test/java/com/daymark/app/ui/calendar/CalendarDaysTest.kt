package com.daymark.app.ui.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Locale
import kotlin.random.Random

/**
 * Insights → Month's day model (#397): which dots a day draws, in which order, and what a screen
 * reader hears for it.
 *
 * The Compose side cannot be compiled in this environment, so everything about a day that a person
 * would feel if it were wrong lives in [CalendarDays] and is executed here. Several of these assert
 * that something is absent: a mood that was not logged, an average, a count. Each is paired with a
 * control showing the same check finds the thing when it is there (CLAUDE.md §5).
 */
class CalendarDaysTest {

    private val locale: Locale = Locale.UK

    // 2026-09-03. Fixed, because a test that starts from "today" is a test that changes.
    private val day = LocalDate.of(2026, 9, 3)

    private fun label(level: Int) = listOf("", "Awful", "Bad", "Meh", "Good", "Rad")[level]

    private fun said(moods: List<Int>, isToday: Boolean = false) =
        CalendarDays.description(day, moods, isToday, ::label, locale)

    /** Whether [small] appears in [big] in the same order, gaps allowed. */
    private fun inOrderWithin(small: List<Int>, big: List<Int>): Boolean {
        var i = 0
        for (x in big) if (i < small.size && small[i] == x) i++
        return i == small.size
    }

    // ---- Which dots, in which order ----

    @Test
    fun `a day's moods are its entries, one each, earliest first`() {
        val next = day.plusDays(1)
        // The DAO hands entries over newest first; a day reads earliest first.
        val entries = listOf(
            DayEntry(next, epochMillis = 5_000, id = 5, moodLevel = 1),
            DayEntry(day, epochMillis = 3_000, id = 3, moodLevel = 3),
            DayEntry(day, epochMillis = 2_000, id = 2, moodLevel = 4),
            DayEntry(day, epochMillis = 1_000, id = 1, moodLevel = 2),
        )
        assertEquals(mapOf(day to listOf(2, 4, 3), next to listOf(1)), CalendarDays.moodsByDay(entries))
    }

    @Test
    fun `entries logged at the same moment keep the order they were made in`() {
        val entries = listOf(
            DayEntry(day, epochMillis = 1_000, id = 9, moodLevel = 5),
            DayEntry(day, epochMillis = 1_000, id = 4, moodLevel = 1),
        )
        assertEquals(listOf(1, 5), CalendarDays.moodsByDay(entries)[day])
        // The ids decide it, not the order they arrived in.
        assertEquals(listOf(1, 5), CalendarDays.moodsByDay(entries.reversed())[day])
    }

    @Test
    fun `a day with two moods draws both, and nothing between them`() {
        val dots = CalendarDays.dots(listOf(1, 5))
        assertEquals(listOf(1, 5), dots)
        // Meh is the average of Awful and Rad, the colour the old fill painted this day. Not drawn.
        assertFalse(3 in dots)
        // Control: a day that did log Meh draws it.
        assertTrue(3 in CalendarDays.dots(listOf(1, 3, 5)))
    }

    @Test
    fun `a day with up to the cap draws every entry, as logged`() {
        for (n in 1..CalendarDays.MAX_DOTS) {
            val moods = List(n) { 5 - (it % 5) }
            assertEquals(moods, CalendarDays.dots(moods))
        }
    }

    @Test
    fun `a full day draws six dots and keeps every mood it holds, in logged order`() {
        assertEquals(6, CalendarDays.MAX_DOTS)
        // Seven Goods, then one Awful: the first six alone would hide the Awful one.
        assertEquals(listOf(4, 4, 4, 4, 4, 1), CalendarDays.dots(listOf(4, 4, 4, 4, 4, 4, 4, 1)))
        // Every mood, logged late: the first of each is kept, then the earliest of the rest.
        assertEquals(listOf(5, 5, 1, 2, 3, 4), CalendarDays.dots(listOf(5, 5, 5, 5, 5, 5, 1, 2, 3, 4)))
    }

    @Test
    fun `the cap leaves room for every mood on the scale`() {
        assertTrue(
            "a day past the cap must still be able to show all five mood levels (a longer scale is #206)",
            CalendarDays.MAX_DOTS >= 5,
        )
    }

    @Test
    fun `any day's dots are its own moods, every one of them, in logged order, and at most six`() {
        // A fixed seed: the same two thousand days on every run.
        val random = Random(397)
        var capped = 0
        repeat(2_000) {
            val moods = List(random.nextInt(0, 25)) { random.nextInt(1, 6) }
            val dots = CalendarDays.dots(moods)
            if (moods.size > CalendarDays.MAX_DOTS) capped++
            assertEquals("how many: $moods", minOf(moods.size, CalendarDays.MAX_DOTS), dots.size)
            assertEquals("every mood the day holds is drawn: $moods -> $dots", moods.toSet(), dots.toSet())
            assertTrue("in logged order: $moods -> $dots", inOrderWithin(dots, moods))
        }
        // Control: the sweep reached the days it exists for.
        assertTrue("only $capped days went past the cap", capped > 1_000)
        assertFalse(inOrderWithin(listOf(2, 1), listOf(1, 2)))
    }

    // ---- What a screen reader hears ----

    @Test
    fun `a day is heard as its date and each mood in the person's own words`() {
        assertEquals("3 September: Good, Meh", said(listOf(4, 3)))
        val own = listOf("", "Heavy", "Low", "Flat", "Light", "Bright")
        assertEquals(
            "3 September: Heavy, Bright",
            CalendarDays.description(day, listOf(1, 5), false, { own[it] }, locale),
        )
    }

    @Test
    fun `a day with nothing is heard as nothing recorded, and draws no dots`() {
        assertEquals("3 September: nothing recorded", said(emptyList()))
        assertEquals(emptyList<Int>(), CalendarDays.dots(emptyList()))
    }

    @Test
    fun `today is said to be today`() {
        assertEquals("3 September, today: Rad", said(listOf(5), isToday = true))
        assertEquals("3 September, today: ${CalendarDays.NOTHING_RECORDED}", said(emptyList(), isToday = true))
        // Control: any other day is not.
        assertFalse(said(listOf(5)).contains("today"))
    }

    @Test
    fun `a listener hears exactly the dots, never a count and never an average`() {
        val nine = listOf(4, 4, 4, 4, 4, 4, 4, 4, 2)
        val sentence = said(nine)
        val moods = sentence.substringAfter(": ")
        assertEquals(CalendarDays.dots(nine).joinToString(", ") { label(it) }, moods)
        assertFalse("a count in \"$sentence\"", moods.any { it.isDigit() } || '+' in moods)
        // Control: the date's own number is a digit, so the check can see one.
        assertTrue(sentence.substringBefore(": ").any { it.isDigit() })
        // Awful and Rad average to Meh. A day that logged them is never heard as Meh...
        assertFalse(said(listOf(1, 5)).contains("Meh"))
        // ...and a day that logged Meh is.
        assertTrue(said(listOf(1, 3, 5)).contains("Meh"))
    }
}
