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
        val currentStreak: Int,
    )

    fun build(inputs: Inputs, locale: Locale = Locale.getDefault()): String {
        if (inputs.totalEntries == 0 || inputs.avgMood == null) {
            return "Log a few entries to see your summary."
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
        if (inputs.currentStreak >= 2) {
            parts.add("You're on a ${inputs.currentStreak}-day logging streak — nice.")
        }
        return parts.joinToString(" ")
    }

    private fun day(d: DayOfWeek, locale: Locale): String = d.getDisplayName(TextStyle.FULL, locale)
}
