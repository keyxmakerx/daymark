package com.daymark.app.ui.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Locale
import kotlin.random.Random

/**
 * The day model behind Insights → Month (#397), Insights → Week and Home's week strip (#411): which
 * dots a day draws, in which order (#412), which days a week holds, and what a screen reader hears.
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

    private fun said(moods: List<Int>, isToday: Boolean = false, cap: Int = CalendarDays.MAX_DOTS) =
        CalendarDays.description(day, moods, isToday, ::label, locale, cap)

    /** Whether [small] appears in [big] in the same order, gaps allowed. */
    private fun inOrderWithin(small: List<Int>, big: List<Int>): Boolean {
        var i = 0
        for (x in big) if (i < small.size && small[i] == x) i++
        return i == small.size
    }

    /**
     * The rule in [CalendarDays.dots], stated a second way, by the places it keeps in the day's list:
     * the first place of each mood, which is its newest entry, as many as [cap] allows, then the
     * earliest places left, all in the list's own order.
     */
    private fun expectedDots(moods: List<Int>, cap: Int): List<Int> {
        if (moods.size <= cap) return moods
        val newestOfEach = moods.indices.filter { i -> moods.indexOf(moods[i]) == i }.take(cap)
        val rest = moods.indices.filter { it !in newestOfEach }.take(cap - newestOfEach.size)
        return (newestOfEach + rest).sorted().map { moods[it] }
    }

    // ---- Which dots, in which order ----

    @Test
    fun `a day's moods are its entries, one each, newest first as the day's own list runs`() {
        val next = day.plusDays(1)
        val entries = listOf(
            DayEntry(day, epochMillis = 1_000, id = 1, moodLevel = 2),
            DayEntry(next, epochMillis = 5_000, id = 5, moodLevel = 1),
            DayEntry(day, epochMillis = 3_000, id = 3, moodLevel = 3),
            DayEntry(day, epochMillis = 2_000, id = 2, moodLevel = 4),
        )
        val expected = mapOf(day to listOf(3, 4, 2), next to listOf(1))
        assertEquals(expected, CalendarDays.moodsByDay(entries))
        // The order comes from the times, not from the order the entries arrive in: the DAO's newest
        // first, and the reverse of it.
        assertEquals(expected, CalendarDays.moodsByDay(entries.sortedByDescending { it.epochMillis }))
        assertEquals(expected, CalendarDays.moodsByDay(entries.sortedBy { it.epochMillis }))
        // Control: the earliest-first order the month used to draw is a different list for this day.
        assertNotEquals(expected.getValue(day), expected.getValue(day).reversed())
    }

    @Test
    fun `entries logged at the same moment keep the order they were made in`() {
        val entries = listOf(
            DayEntry(day, epochMillis = 1_000, id = 9, moodLevel = 5),
            DayEntry(day, epochMillis = 1_000, id = 4, moodLevel = 1),
            DayEntry(day, epochMillis = 2_000, id = 2, moodLevel = 3),
        )
        // Newest moment first; within the moment, the one made first comes first.
        assertEquals(listOf(3, 1, 5), CalendarDays.moodsByDay(entries)[day])
        // The ids decide it, not the order they arrived in.
        assertEquals(listOf(3, 1, 5), CalendarDays.moodsByDay(entries.reversed())[day])
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
    fun `a day with up to the cap draws every entry, in the day's order`() {
        for (cap in listOf(CalendarDays.MAX_DOTS, CalendarDays.STRIP_MAX_DOTS)) {
            for (n in 1..cap) {
                val moods = List(n) { 5 - (it % 5) }
                assertEquals("cap $cap", moods, CalendarDays.dots(moods, cap))
            }
        }
    }

    @Test
    fun `a full day draws six dots and keeps the newest entry of every mood it holds`() {
        assertEquals(6, CalendarDays.MAX_DOTS)
        // Newest first: an Awful logged last, then seven Goods before it.
        assertEquals(listOf(1, 4, 4, 4, 4, 4), CalendarDays.dots(listOf(1, 4, 4, 4, 4, 4, 4, 4)))
        // An Awful logged first of all, so last in the list: the first six alone would hide it.
        assertEquals(listOf(4, 4, 4, 4, 4, 1), CalendarDays.dots(listOf(4, 4, 4, 4, 4, 4, 4, 1)))
        // Every mood: the newest of each is kept, then the newest of the rest.
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
    fun `Home's strip draws at most three, and a day of three moods or fewer keeps every one`() {
        assertEquals(3, CalendarDays.STRIP_MAX_DOTS)
        // Four Goods, the Bad the oldest of all: the first three alone would hide it.
        assertEquals(listOf(4, 4, 2), CalendarDays.dots(listOf(4, 4, 4, 4, 2), CalendarDays.STRIP_MAX_DOTS))
        // Three moods among six entries: one of each, the newest, in the day's order.
        assertEquals(listOf(5, 3, 1), CalendarDays.dots(listOf(5, 3, 3, 1, 5, 1), CalendarDays.STRIP_MAX_DOTS))
        // Five moods: one dot for each of the three logged most recently.
        assertEquals(listOf(5, 4, 3), CalendarDays.dots(listOf(5, 4, 3, 2, 1), CalendarDays.STRIP_MAX_DOTS))
    }

    @Test
    fun `any day's dots are its own, newest of each mood first kept, in the day's order, at any cap`() {
        // A fixed seed: the same two thousand days on every run.
        val random = Random(412)
        val capped = IntArray(CalendarDays.MAX_DOTS + 1)
        val everyMoodKept = IntArray(CalendarDays.MAX_DOTS + 1)
        repeat(2_000) {
            val moods = List(random.nextInt(0, 25)) { random.nextInt(1, 6) }
            for (cap in 1..CalendarDays.MAX_DOTS) {
                val dots = CalendarDays.dots(moods, cap)
                val where = "cap $cap: $moods -> $dots"
                assertEquals(where, expectedDots(moods, cap), dots)
                assertEquals("how many, $where", minOf(moods.size, cap), dots.size)
                assertTrue("in the day's order, $where", inOrderWithin(dots, moods))
                if (moods.toSet().size <= cap) {
                    assertEquals("every mood the day holds is drawn, $where", moods.toSet(), dots.toSet())
                    if (moods.size > cap) everyMoodKept[cap]++
                } else {
                    assertEquals("one of each mood, as many as fit, $where", cap, dots.toSet().size)
                }
                if (moods.size > cap) capped[cap]++
            }
        }
        // Control: the sweep reached the days it exists for, at the month's cap and at the strip's.
        for (cap in listOf(CalendarDays.MAX_DOTS, CalendarDays.STRIP_MAX_DOTS)) {
            assertTrue("only ${capped[cap]} days went past cap $cap", capped[cap] > 1_000)
            assertTrue("only ${everyMoodKept[cap]} full days at cap $cap kept every mood", everyMoodKept[cap] > 100)
        }
        // Control: the reference is not the code's own answer read back, and it tells the orders apart.
        assertNotEquals(listOf(4, 4, 4, 4, 4, 4), expectedDots(listOf(4, 4, 4, 4, 4, 4, 4, 1), 6))
        assertFalse(inOrderWithin(listOf(2, 1), listOf(1, 2)))
    }

    // ---- Which days a week holds ----

    @Test
    fun `a week is the seven days ending today, oldest first, each with its moods newest first`() {
        val today = LocalDate.of(2026, 9, 26)
        val entries = listOf(
            DayEntry(LocalDate.of(2026, 9, 19), epochMillis = 100, id = 1, moodLevel = 1),
            DayEntry(LocalDate.of(2026, 9, 20), epochMillis = 200, id = 2, moodLevel = 2),
            DayEntry(LocalDate.of(2026, 9, 23), epochMillis = 300, id = 3, moodLevel = 4),
            DayEntry(LocalDate.of(2026, 9, 23), epochMillis = 400, id = 4, moodLevel = 3),
            DayEntry(LocalDate.of(2026, 9, 26), epochMillis = 500, id = 5, moodLevel = 5),
            DayEntry(LocalDate.of(2026, 9, 27), epochMillis = 600, id = 6, moodLevel = 1),
        )
        val week = CalendarDays.week(entries, today)
        assertEquals(CalendarDays.WEEK_DAYS, week.days.size)
        assertEquals((20..26).map { LocalDate.of(2026, 9, it) }, week.days)
        assertEquals(
            mapOf(
                LocalDate.of(2026, 9, 20) to listOf(2),
                LocalDate.of(2026, 9, 23) to listOf(3, 4),
                today to listOf(5),
            ),
            week.moods,
        )
        // A day with nothing is simply not there: nothing is made up for it.
        assertEquals(null, week.moods[LocalDate.of(2026, 9, 21)])
        // Control: the day before the week and a day after today were in the entries, and are out.
        assertTrue(entries.any { it.date == today.minusDays(7) } && entries.any { it.date == today.plusDays(1) })
    }

    @Test
    fun `a week runs across the end of a month`() {
        val today = LocalDate.of(2026, 10, 2)
        val week = CalendarDays.week(
            listOf(DayEntry(LocalDate.of(2026, 9, 28), epochMillis = 1, id = 1, moodLevel = 3)),
            today,
        )
        assertEquals(LocalDate.of(2026, 9, 26), week.days.first())
        assertEquals(today, week.days.last())
        assertEquals(listOf(3), week.moods[LocalDate.of(2026, 9, 28)])
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
        assertEquals("3 September: nothing recorded", said(emptyList(), cap = CalendarDays.STRIP_MAX_DOTS))
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
        for (cap in listOf(CalendarDays.MAX_DOTS, CalendarDays.STRIP_MAX_DOTS)) {
            val sentence = said(nine, cap = cap)
            val moods = sentence.substringAfter(": ")
            assertEquals("cap $cap", CalendarDays.dots(nine, cap).joinToString(", ") { label(it) }, moods)
            assertFalse("a count in \"$sentence\"", moods.any { it.isDigit() } || '+' in moods)
            // Control: the date's own number is a digit, so the check can see one.
            assertTrue(sentence.substringBefore(": ").any { it.isDigit() })
        }
        // The strip's words are its three dots, and the month's its six: not the same sentence.
        assertEquals("3 September: Good, Good, Bad", said(nine, cap = CalendarDays.STRIP_MAX_DOTS))
        assertNotEquals(said(nine), said(nine, cap = CalendarDays.STRIP_MAX_DOTS))
        // Awful and Rad average to Meh. A day that logged them is never heard as Meh...
        assertFalse(said(listOf(1, 5)).contains("Meh"))
        // ...and a day that logged Meh is.
        assertTrue(said(listOf(1, 3, 5)).contains("Meh"))
    }
}
