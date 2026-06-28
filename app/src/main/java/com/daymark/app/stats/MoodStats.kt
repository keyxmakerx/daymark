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
     * Number of consecutive days ending at [today] (or yesterday) that have at least
     * one entry. A gap before today still counts the run that ends yesterday as broken.
     */
    fun currentStreak(days: Set<LocalDate>, today: LocalDate): Int {
        if (days.isEmpty()) return 0
        // Allow the streak to "still be alive" if logged today or yesterday.
        var cursor = when {
            days.contains(today) -> today
            days.contains(today.minusDays(1)) -> today.minusDays(1)
            else -> return 0
        }
        var count = 0
        while (days.contains(cursor)) {
            count++
            cursor = cursor.minusDays(1)
        }
        return count
    }

    /** Longest run of consecutive logged days anywhere in the history. */
    fun longestStreak(days: Set<LocalDate>): Int {
        if (days.isEmpty()) return 0
        val sorted = days.sorted()
        var best = 1
        var run = 1
        for (i in 1 until sorted.size) {
            run = if (sorted[i - 1].plusDays(1) == sorted[i]) run + 1 else 1
            if (run > best) best = run
        }
        return best
    }

    /**
     * Average mood on days/entries where each activity appears.
     * [entries] is a list of (moodLevel, activityIds). Returns activityId -> average mood.
     */
    fun activityAverages(entries: List<Pair<Int, List<Long>>>): Map<Long, Double> {
        val sums = mutableMapOf<Long, Int>()
        val counts = mutableMapOf<Long, Int>()
        for ((level, activityIds) in entries) {
            for (id in activityIds.distinct()) {
                sums[id] = (sums[id] ?: 0) + level
                counts[id] = (counts[id] ?: 0) + 1
            }
        }
        return sums.mapValues { (id, sum) -> sum.toDouble() / (counts[id] ?: 1) }
    }
}
