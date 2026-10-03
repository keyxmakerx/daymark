package com.daymark.app.ui.calendar

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One mood entry as a day needs it: the day it falls on, when it was logged, its row id (which
 * orders entries logged at the same moment), and its mood level.
 */
data class DayEntry(val date: LocalDate, val epochMillis: Long, val id: Long, val moodLevel: Int)

/**
 * What a day draws wherever the phone shows a person's days as days, and what a screen reader hears
 * for it: Insights → Month (#397), Insights → Week and Home's week strip (#411).
 *
 * A day is its entries, each in its own mood: one dot per entry, in that entry's own colour, in the
 * order the day's own list shows them, newest first (#412). Nothing here averages, blends or ranks,
 * and there is no function here that could: a day with a Bad entry and a Good one is a Bad dot and a
 * Good dot, never a colour between them on nobody's scale (docs/DESIGN.md, "Colour tokens": a mood
 * colour is the value the person logged). A day with no entry has no dots, and is otherwise drawn
 * exactly like every other day.
 *
 * No Compose, no Android, no clock, in the same discipline as `ui/sky/SkyPresentation.kt`, so all of
 * it runs on a plain JVM through `tools/jvm-tests.sh ui/calendar`. The Compose code that draws a day
 * (`ui/insights/InsightsScreen.kt`, `ui/home/HomeScreen.kt`) draws what this returns and decides
 * nothing about a day; `MonthGridSourceTest` and `WeekDaysSourceTest` read it as text.
 */
object CalendarDays {

    /**
     * The most dots one day draws in the month and in Insights → Week, in two rows of three under the
     * number.
     *
     * At least five, the number of mood levels, so a day with more entries than this still shows
     * every mood it holds ([dots]). Six fill two rows of three, which leaves each dot large enough
     * to tell its colour at a glance in the narrowest cell. Past six a glance does not count, and
     * the day's own view lists every entry, so a full day shows no count and no "+n".
     */
    const val MAX_DOTS = 6

    /**
     * The most dots one day draws in Home's week strip, in one column (#411).
     *
     * A day there is a slot 13 to 20 dp wide on a phone, too narrow on most for two ringed dots side
     * by side (18 dp), and the strip is 30 dp tall: three ringed dots and their gaps take 28 dp of it.
     * Fewer than the five mood levels, so a day there that holds four or five moods shows one dot for
     * each of the three it logged most recently; one that holds three or fewer shows every one
     * ([dots]). The day's own view lists every entry.
     */
    const val STRIP_MAX_DOTS = 3

    /** How many days the week views show, ending today. */
    const val WEEK_DAYS = 7

    /** What a screen reader hears after the date for a day with no entry. */
    const val NOTHING_RECORDED = "nothing recorded"

    private const val DAY_AND_MONTH = "d MMMM"

    /**
     * The days both week views draw: [days], oldest first and ending today, and each day's moods in
     * [moods], in the day's own order. A day with no entry has no key in [moods].
     */
    data class Week(
        val days: List<LocalDate> = emptyList(),
        val moods: Map<LocalDate, List<Int>> = emptyMap(),
    )

    /**
     * Each day's moods in the order the day's own list shows its entries (`DayDetailScreen.kt`):
     * newest first, and entries logged at the same moment in the order they were made.
     *
     * The day's list is `EntryDao.observeBetween`, `ORDER BY dateTime DESC`, drawn as it comes;
     * `WeekDaysSourceTest` holds the two to the same direction. That query orders by time alone, so
     * two entries at the same moment come in whatever order SQLite returns, which in practice is the
     * order they were made; this writes that order down.
     */
    fun moodsByDay(entries: List<DayEntry>): Map<LocalDate, List<Int>> =
        entries
            .sortedWith(compareByDescending<DayEntry> { it.epochMillis }.thenBy { it.id })
            .groupBy({ it.date }, { it.moodLevel })

    /** The [WEEK_DAYS] days ending on [today], oldest first, and each one's moods ([moodsByDay]). */
    fun week(entries: List<DayEntry>, today: LocalDate): Week {
        val days = (WEEK_DAYS - 1 downTo 0).map { today.minusDays(it.toLong()) }
        val first = days.first()
        return Week(days, moodsByDay(entries.filter { !it.date.isBefore(first) && !it.date.isAfter(today) }))
    }

    /**
     * The dots one day draws, in the order drawn: each entry's own mood, in the day's own order,
     * newest first, at most [cap] of them.
     *
     * A day with more entries than [cap] keeps the newest entry of each mood it holds, as many moods
     * as [cap] has room for, then the newest of the rest, and draws them in the day's order. So a day
     * never loses a mood it holds while it holds no more moods than [cap]: at [MAX_DOTS], that is
     * every day. Every value returned is one of the day's own; none is made up.
     */
    fun dots(moods: List<Int>, cap: Int = MAX_DOTS): List<Int> {
        require(cap > 0) { "a day with an entry draws at least one dot" }
        if (moods.size <= cap) return moods
        val keep = BooleanArray(moods.size)
        var kept = 0
        val seen = HashSet<Int>()
        for (i in moods.indices) {
            if (kept == cap) break
            if (seen.add(moods[i])) {
                keep[i] = true
                kept++
            }
        }
        for (i in moods.indices) {
            if (kept == cap) break
            if (!keep[i]) {
                keep[i] = true
                kept++
            }
        }
        return moods.filterIndexed { i, _ -> keep[i] }
    }

    /**
     * What a screen reader hears for one day: the date, then the mood of each dot in the order drawn,
     * in the person's own words — "3 September: Good, Meh". A day with no entry is
     * "3 September: nothing recorded", and today is "26 September, today: …", because the ring that
     * marks today is information too.
     *
     * The words are exactly the dots, [cap] and all, so a listener is told what a glance shows, no
     * more and no less. Never a count and never a summary: the day's own view reads every entry.
     */
    fun description(
        date: LocalDate,
        moods: List<Int>,
        isToday: Boolean,
        moodLabel: (Int) -> String,
        locale: Locale,
        cap: Int = MAX_DOTS,
    ): String {
        val day = date.format(DateTimeFormatter.ofPattern(DAY_AND_MONTH, locale))
        val drawn = dots(moods, cap)
        val said = if (drawn.isEmpty()) NOTHING_RECORDED else drawn.joinToString(", ") { moodLabel(it) }
        return if (isToday) "$day, today: $said" else "$day: $said"
    }
}
