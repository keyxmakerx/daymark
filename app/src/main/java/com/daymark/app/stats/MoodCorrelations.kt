package com.daymark.app.stats

/**
 * Pure correlation math over mood data — deliberately free of Android/Room types so it can be
 * unit-tested on the JVM. Everything here describes **association, not causation**; the UI must
 * always present it that way and only show results past a minimum-sample gate.
 */
object MoodCorrelations {

    /**
     * How a single factor (an activity or a yes/no tracker) relates to mood.
     * [delta] is mean-mood-when-present minus mean-mood-when-absent.
     * [r] is the point-biserial correlation with mood, or null when it's undefined
     * (factor always or never present → no variance to correlate).
     */
    data class FactorDelta(
        val id: Long,
        val meanWith: Double,
        val meanWithout: Double,
        val delta: Double,
        val n: Int,
        val r: Double?,
    )

    /** A single day's mean mood paired with that day's numeric tracker value. */
    data class DayPoint(val mood: Double, val value: Double)

    /**
     * Per-factor mean-mood deltas from [entries] = (moodLevel, idsPresentThatEntry).
     * Only factors that appear at least [minOccurrences] times **and** have at least one entry
     * where they're absent are returned (both groups are needed for a meaningful comparison).
     * Sorted by absolute delta, strongest first.
     */
    fun factorDeltas(entries: List<Pair<Int, List<Long>>>, minOccurrences: Int): List<FactorDelta> {
        if (entries.isEmpty()) return emptyList()
        val moods = entries.map { it.first.toDouble() }
        val allIds = entries.flatMap { it.second }.toSet()
        val result = ArrayList<FactorDelta>()
        for (id in allIds) {
            val withLevels = ArrayList<Int>()
            val withoutLevels = ArrayList<Int>()
            for ((level, ids) in entries) {
                if (ids.contains(id)) withLevels.add(level) else withoutLevels.add(level)
            }
            if (withLevels.size < minOccurrences || withoutLevels.isEmpty()) continue
            val meanWith = withLevels.average()
            val meanWithout = withoutLevels.average()
            val presence = entries.map { if (it.second.contains(id)) 1.0 else 0.0 }
            result.add(
                FactorDelta(
                    id = id,
                    meanWith = meanWith,
                    meanWithout = meanWithout,
                    delta = meanWith - meanWithout,
                    n = withLevels.size,
                    r = pearson(moods, presence),
                ),
            )
        }
        return result.sortedByDescending { kotlin.math.abs(it.delta) }
    }

    /**
     * Splits factor deltas into the [topN] that go with higher mood (positive delta, best first)
     * and the [topN] that go with lower mood (negative delta, worst first).
     */
    fun rankLifts(deltas: List<FactorDelta>, topN: Int): Pair<List<FactorDelta>, List<FactorDelta>> {
        val up = deltas.filter { it.delta > 0 }.sortedByDescending { it.delta }.take(topN)
        val down = deltas.filter { it.delta < 0 }.sortedBy { it.delta }.take(topN)
        return up to down
    }

    /**
     * Pearson correlation of a numeric tracker against daily mood. Returns null when there are
     * fewer than [minDays] paired days (not enough data to trust the number).
     */
    fun trackerCorrelation(points: List<DayPoint>, minDays: Int): Double? {
        if (points.size < minDays) return null
        return pearson(points.map { it.mood }, points.map { it.value })
    }

    /** Pearson correlation coefficient, or null if fewer than 2 points or either side is constant. */
    fun pearson(xs: List<Double>, ys: List<Double>): Double? {
        val n = xs.size
        if (n < 2 || ys.size != n) return null
        val meanX = xs.average()
        val meanY = ys.average()
        var sxy = 0.0
        var sxx = 0.0
        var syy = 0.0
        for (i in 0 until n) {
            val dx = xs[i] - meanX
            val dy = ys[i] - meanY
            sxy += dx * dy
            sxx += dx * dx
            syy += dy * dy
        }
        if (sxx == 0.0 || syy == 0.0) return null
        return sxy / kotlin.math.sqrt(sxx * syy)
    }
}
