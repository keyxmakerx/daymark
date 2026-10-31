package com.daymark.app.stats

import java.time.LocalDate

/**
 * The person's own longest stretch without entries in the months before a report starts, so the
 * report can set a long gap beside it as two numbers (#520). Nothing else is said about either
 * number: no "unusual", no comparison word, no cause, because the app cannot know why someone
 * stopped writing and the person reads the report too.
 *
 * Never compared against nothing: when the person's entries do not reach back the whole
 * [MONTHS] before the report, there is no answer, and the report leaves the line out.
 */
object PriorGap {

    /** How far back the comparison looks, in calendar months. */
    const val MONTHS = 6L

    /**
     * The longest run of consecutive days with no entry in the [MONTHS] months before
     * [reportStart] (that day itself excluded), or null when [firstEntry] is later than the start
     * of that window or there are no entries at all. A run that began before the window counts
     * only its days inside it. Zero means every day in the window has an entry.
     */
    fun longestBefore(reportStart: LocalDate, firstEntry: LocalDate?, loggedDays: Set<LocalDate>): Int? {
        val windowStart = reportStart.minusMonths(MONTHS)
        if (firstEntry == null || firstEntry.isAfter(windowStart)) return null
        var longest = 0
        var run = 0
        var day = windowStart
        while (day.isBefore(reportStart)) {
            if (day in loggedDays) {
                run = 0
            } else {
                run++
                if (run > longest) longest = run
            }
            day = day.plusDays(1)
        }
        return longest
    }

    /** The one fixed line the report prints for [days]. */
    fun line(days: Int): String = if (days == 0) {
        "In the $MONTHS months before this report, every day had an entry."
    } else {
        "The longest stretch without entries in the $MONTHS months before this report was " +
            "$days ${if (days == 1) "day" else "days"}."
    }
}
