package com.daymark.app.data

/**
 * Pure, Android-free comparison of mood on better- vs. worse-sleep nights. Descriptive only —
 * a gentle "on nights you slept better, your mood tended to be higher", never a causal claim.
 */
object SleepMoodInsight {

    data class Result(val nights: Int, val betterSleepMood: Double, val worseSleepMood: Double)

    /**
     * [pairs] = (sleepQuality 1..5, that-day average mood 1..5). Splits the nights at the median
     * sleep quality and averages the mood on each half. Null if there aren't enough paired nights.
     */
    fun compute(pairs: List<Pair<Int, Double>>, minNights: Int = 5): Result? {
        if (pairs.size < minNights) return null
        val sorted = pairs.sortedBy { it.first }
        val mid = pairs.size / 2
        val worse = sorted.take(mid)
        val better = sorted.drop(mid)
        if (worse.isEmpty() || better.isEmpty()) return null
        return Result(
            nights = pairs.size,
            betterSleepMood = better.map { it.second }.average(),
            worseSleepMood = worse.map { it.second }.average(),
        )
    }
}
