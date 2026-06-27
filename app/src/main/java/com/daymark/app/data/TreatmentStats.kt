package com.daymark.app.data

import com.daymark.app.data.entity.SleepLog

/**
 * Pure before/after comparison of the user's OWN self-tracked numbers around a treatment's start
 * date. This is descriptive only — "since", never "because". Not an outcome measure.
 */
object TreatmentStats {

    /** Comparison window on each side of the start date (30 days). */
    const val WINDOW_MS = 30L * 24 * 3600 * 1000

    data class Side(
        val nights: Int,
        val avgSleepMin: Int?,
        val avgEfficiencyPct: Int?,
        val avgQuality: Double?,
        val moodCount: Int,
        val avgMood: Double?,
    )

    data class Comparison(val before: Side, val after: Side)

    /** [moods] = (timestampMillis, moodLevel) pairs. */
    fun compare(
        startedAt: Long,
        logs: List<SleepLog>,
        moods: List<Pair<Long, Int>>,
    ): Comparison {
        // Equal-length windows on each side of the date, so the comparison is apples-to-apples.
        val beforeLogs = logs.filter { it.wakeTime in (startedAt - WINDOW_MS) until startedAt }
        val afterLogs = logs.filter { it.wakeTime in startedAt..(startedAt + WINDOW_MS) }
        val beforeMoods = moods.filter { it.first in (startedAt - WINDOW_MS) until startedAt }.map { it.second }
        val afterMoods = moods.filter { it.first in startedAt..(startedAt + WINDOW_MS) }.map { it.second }
        return Comparison(side(beforeLogs, beforeMoods), side(afterLogs, afterMoods))
    }

    private fun side(logs: List<SleepLog>, moods: List<Int>): Side {
        val n = logs.size
        return Side(
            nights = n,
            avgSleepMin = if (n > 0) logs.sumOf { SleepMetrics.totalSleepMin(it) } / n else null,
            avgEfficiencyPct = if (n > 0) logs.sumOf { SleepMetrics.efficiencyPct(it) } / n else null,
            avgQuality = if (n > 0) logs.map { it.quality }.average() else null,
            moodCount = moods.size,
            avgMood = if (moods.isNotEmpty()) moods.average() else null,
        )
    }
}
