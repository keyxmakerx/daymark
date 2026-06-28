package com.daymark.app.stats

import java.time.DayOfWeek

/**
 * Pure mood-pattern aggregations (by weekday, by time of day, period vs. period). Free of
 * Android/Room types; timestamps are converted to weekday/hour by the caller (which owns the
 * time zone) so this stays trivially testable on the JVM.
 */
object MoodPatterns {

    enum class TimeBucket(val label: String) {
        Morning("Morning"), Afternoon("Afternoon"), Evening("Evening"), Night("Night");

        companion object {
            /** Morning 5–11, Afternoon 12–16, Evening 17–21, Night 22–4. */
            fun of(hour: Int): TimeBucket = when (hour) {
                in 5..11 -> Morning
                in 12..16 -> Afternoon
                in 17..21 -> Evening
                else -> Night
            }
        }
    }

    /** Mean mood per weekday from (weekday, moodLevel) pairs. Only weekdays with data appear. */
    fun byDayOfWeek(entries: List<Pair<DayOfWeek, Int>>): Map<DayOfWeek, Double> =
        entries.groupBy({ it.first }, { it.second }).mapValues { it.value.average() }

    /** Mean mood per time-of-day bucket from (hour, moodLevel) pairs (hour 0–23). */
    fun byTimeOfDay(entries: List<Pair<Int, Int>>): Map<TimeBucket, Double> =
        entries.groupBy({ TimeBucket.of(it.first) }, { it.second }).mapValues { it.value.average() }

    data class PeriodComparison(
        val currentAvg: Double?,
        val previousAvg: Double?,
        /** Percent change of the average vs. the previous period, or null if not computable. */
        val deltaPct: Double?,
        val currentCount: Int,
        val previousCount: Int,
    )

    /** Compares the mood levels of the current period against the previous one. */
    fun periodCompare(current: List<Int>, previous: List<Int>): PeriodComparison {
        val curAvg = if (current.isEmpty()) null else current.average()
        val prevAvg = if (previous.isEmpty()) null else previous.average()
        val deltaPct = if (curAvg != null && prevAvg != null && prevAvg != 0.0) {
            (curAvg - prevAvg) / prevAvg * 100.0
        } else {
            null
        }
        return PeriodComparison(curAvg, prevAvg, deltaPct, current.size, previous.size)
    }
}
