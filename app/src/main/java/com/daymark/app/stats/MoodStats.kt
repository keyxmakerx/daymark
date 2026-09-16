package com.daymark.app.stats

import java.time.LocalDate

/**
 * Pure statistics functions over mood data. Deliberately free of Android/Room types
 * so they can be unit-tested on the JVM.
 */
object MoodStats {

    /** Mean mood level (1..5), or null when there are no entries. */
    fun averageMood(levels: List<Int>): Double? =
        if (levels.isEmpty()) null else levels.sum().toDouble() / levels.size

    /** Count of entries at each mood level 1..5 (always contains all five keys). */
    fun moodCounts(levels: List<Int>): Map<Int, Int> {
        val base = (1..5).associateWith { 0 }.toMutableMap()
        levels.forEach { lvl -> base[lvl] = (base[lvl] ?: 0) + 1 }
        return base
    }

    /**
     * The length of the one continuity window this product has. Thirty days, everywhere — on Home,
     * on Stats, in the period summary, and in the companion web console, so the phone and the
     * browser never disagree about a number the person reads in both places.
     */
    const val WINDOW_DAYS = 30

    /**
     * How many calendar days in the [WINDOW_DAYS]-day window ending on [today] have at least one
     * entry. Replaces the two streak counts this object used to expose, and the difference is the
     * entire point.
     *
     * A streak is a *breakable state*: the number on screen is also a verdict on the day the run
     * ended, and the person most likely to be reading it is the one who just came back after a bad
     * week. This count has no run to break. Four days away costs four days out of thirty and
     * nothing else, and it can only be recovered by logging — never lost a second time.
     *
     * The window is a fixed thirty days ending today, not a calendar month. A month resets on the
     * 1st, which reintroduces exactly the breakable state the change was made to remove, and not
     * ninety days, whose larger denominator makes a short return look smaller than it is.
     */
    fun daysWithEntryInLast30(days: Set<LocalDate>, today: LocalDate): Int {
        val from = today.minusDays((WINDOW_DAYS - 1).toLong())
        return days.count { !it.isBefore(from) && !it.isAfter(today) }
    }

    /**
     * Average mood on days/entries where each activity appears.
     * [entries] is a list of (moodLevel, factor ids). Returns factor -> average mood.
     *
     * Takes [MoodCorrelations.FactorId] rather than a bare `Long` for the reason set out in that
     * type's header: this is a function that puts an id and a mood level together, and a person's
     * id is a `Long` like any other. The type is what stops one arriving.
     */
    fun activityAverages(
        entries: List<Pair<Int, List<MoodCorrelations.FactorId>>>,
    ): Map<MoodCorrelations.FactorId, Double> {
        val sums = mutableMapOf<MoodCorrelations.FactorId, Int>()
        val counts = mutableMapOf<MoodCorrelations.FactorId, Int>()
        for ((level, activityIds) in entries) {
            for (id in activityIds.distinct()) {
                sums[id] = (sums[id] ?: 0) + level
                counts[id] = (counts[id] ?: 0) + 1
            }
        }
        return sums.mapValues { (id, sum) -> sum.toDouble() / (counts[id] ?: 1) }
    }
}
