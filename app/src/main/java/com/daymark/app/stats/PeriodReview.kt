package com.daymark.app.stats

import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/**
 * Builds a short, rules-based "in review" summary from already-computed stats. Pure and
 * deterministic (no Android types) so it can be unit-tested and reused for both the on-screen
 * card and the PDF report. Wording stays descriptive — associations, never causes.
 */
object PeriodReview {

    data class Inputs(
        val totalEntries: Int,
        val avgMood: Double?,
        val bestDay: DayOfWeek?,
        val worstDay: DayOfWeek?,
        val topFactorUp: String?,
        /**
         * Calendar days in the last [MoodStats.WINDOW_DAYS] with at least one entry, from
         * [MoodStats.daysWithEntryInLast30]. Zero omits the sentence entirely — a summary that
         * reported "0 of the last 30 days" would be drawing someone's absence as a figure, which
         * is the failure the whole change exists to remove.
         */
        val daysWithEntryLast30: Int,
    )

    fun build(inputs: Inputs, locale: Locale = Locale.getDefault()): String {
        if (inputs.totalEntries == 0 || inputs.avgMood == null) {
            return "No entries. The summary is drawn from entries."
        }
        val parts = ArrayList<String>()
        parts.add("You logged ${inputs.totalEntries} ${if (inputs.totalEntries == 1) "entry" else "entries"}, " +
            "averaging ${String.format(locale, "%.1f", inputs.avgMood)} out of 5.")
        if (inputs.bestDay != null && inputs.worstDay != null && inputs.bestDay != inputs.worstDay) {
            parts.add(
                "Your mood tended to be highest on ${day(inputs.bestDay, locale)} and " +
                    "lowest on ${day(inputs.worstDay, locale)}.",
            )
        }
        inputs.topFactorUp?.let {
            parts.add("\"$it\" often showed up alongside your better days (association, not cause).")
        }
        // Was "You're on a 5-day logging streak — nice." Two things were wrong with that sentence
        // and only one of them was the streak: "nice" is a congratulation on an act of
        // self-monitoring, and the number was a run that the next missed day would take away. What
        // replaces it states a count against a fixed window and stops there.
        if (inputs.daysWithEntryLast30 > 0) {
            parts.add(
                "You have an entry on ${inputs.daysWithEntryLast30} of the last " +
                    "${MoodStats.WINDOW_DAYS} days.",
            )
        }
        return parts.joinToString(" ")
    }

    private fun day(d: DayOfWeek, locale: Locale): String = d.getDisplayName(TextStyle.FULL, locale)
}
