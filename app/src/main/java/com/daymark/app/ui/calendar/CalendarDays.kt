package com.daymark.app.ui.calendar

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One mood entry as the month needs it: the day it falls on, when it was logged, its row id (which
 * orders entries logged at the same moment), and its mood level.
 */
data class DayEntry(val date: LocalDate, val epochMillis: Long, val id: Long, val moodLevel: Int)

/**
 * What Insights → Month draws for each day, and what a screen reader hears for it (#397).
 *
 * A day is its entries, each in its own mood: one dot per entry, in that entry's own colour, in the
 * order they were logged. Nothing here averages, blends or ranks, and there is no function here that
 * could: a day with a Bad entry and a Good one is a Bad dot and a Good dot, never a colour between
 * them on nobody's scale (docs/DESIGN.md, "Colour tokens": a mood colour is the value the person
 * logged). A day with no entry has no dots, and is otherwise drawn exactly like every other day.
 *
 * No Compose, no Android, no clock, in the same discipline as `ui/sky/SkyPresentation.kt`, so all of
 * it runs on a plain JVM through `tools/jvm-tests.sh ui/calendar`. The month's Compose code in
 * `ui/insights/InsightsScreen.kt` draws what this returns and decides nothing about a day;
 * `MonthGridSourceTest` reads it as text.
 */
object CalendarDays {

    /**
     * The most dots one day draws, in two rows of three under the number.
     *
     * At least five, the number of mood levels, so a day with more entries than this still shows
     * every mood it holds ([dots]). Six fill two rows of three, which leaves each dot large enough
     * to tell its colour at a glance in the narrowest cell. Past six a glance does not count, and
     * the day's own view lists every entry, so a full day shows no count and no "+n".
     */
    const val MAX_DOTS = 6

    /** What a screen reader hears after the date for a day with no entry. */
    const val NOTHING_RECORDED = "nothing recorded"

    private const val DAY_AND_MONTH = "d MMMM"

    /**
     * Each day's moods in the order they were logged, earliest first. Entries logged at the same
     * moment go in the order they were made.
     */
    fun moodsByDay(entries: List<DayEntry>): Map<LocalDate, List<Int>> =
        entries
            .sortedWith(compareBy<DayEntry>({ it.epochMillis }, { it.id }))
            .groupBy({ it.date }, { it.moodLevel })

    /**
     * The dots one day draws, in the order drawn: each entry's own mood, in the order logged.
     *
     * A day with more than [MAX_DOTS] entries keeps the first entry of each mood it holds, so no mood
     * logged that day goes undrawn, then the earliest of the rest, and draws them in logged order.
     * Every value returned is one of the day's own; none is made up.
     */
    fun dots(moods: List<Int>): List<Int> {
        if (moods.size <= MAX_DOTS) return moods
        val keep = BooleanArray(moods.size)
        var kept = 0
        val seen = HashSet<Int>()
        for (i in moods.indices) {
            if (kept == MAX_DOTS) break
            if (seen.add(moods[i])) {
                keep[i] = true
                kept++
            }
        }
        for (i in moods.indices) {
            if (kept == MAX_DOTS) break
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
     * The words are exactly the dots, so a listener is told what a glance shows, no more and no
     * less. Never a count and never a summary: the day's own view reads every entry.
     */
    fun description(
        date: LocalDate,
        moods: List<Int>,
        isToday: Boolean,
        moodLabel: (Int) -> String,
        locale: Locale,
    ): String {
        val day = date.format(DateTimeFormatter.ofPattern(DAY_AND_MONTH, locale))
        val drawn = dots(moods)
        val said = if (drawn.isEmpty()) NOTHING_RECORDED else drawn.joinToString(", ") { moodLabel(it) }
        return if (isToday) "$day, today: $said" else "$day: $said"
    }
}
