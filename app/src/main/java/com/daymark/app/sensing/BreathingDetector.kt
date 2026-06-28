package com.daymark.app.sensing

import kotlin.math.sqrt

/**
 * Pure, Android-free breathing analysis of an accelerometer-magnitude window (phone resting on
 * the chest/abdomen). Detects the breathing rate and flags breathing *pauses* — the snore-
 * independent signature of apnea (chest stops, then resumes), which is why this motion approach
 * is the strongest phone-only route for QUIET / central apnea (and is immune to AC/fan noise).
 *
 * Strictly non-diagnostic screening: a flagged pause is "worth a clinician's look," never an AHI
 * or a diagnosis. Kept Android-free so it is unit-testable like MoodStats.
 */
object BreathingDetector {

    /** A stretch of low chest movement consistent with a breathing pause. */
    data class PauseEvent(val startSec: Double, val durationSec: Double)

    data class Result(
        /** Estimated breaths per minute, or null if no clear breathing rhythm was found. */
        val breathingRatePerMin: Double?,
        /** 0..1 strength of the detected rhythm (normalized autocorrelation peak). */
        val confidence: Double,
        val pauses: List<PauseEvent>,
    )

    // Breathing band: 6..30 breaths/min  ->  period 2..10 s.
    private const val MIN_PERIOD_SEC = 2.0
    private const val MAX_PERIOD_SEC = 10.0
    private const val DETREND_WINDOW_SEC = 4.0
    private const val RMS_WINDOW_SEC = 2.0
    private const val MIN_ANALYSIS_SEC = 20.0
    private const val MIN_PAUSE_SEC = 10.0
    private const val PAUSE_FRACTION = 0.25

    fun analyze(samples: DoubleArray, sampleRateHz: Double): Result {
        val n = samples.size
        if (sampleRateHz <= 0 || n < (sampleRateHz * MIN_ANALYSIS_SEC).toInt()) {
            return Result(null, 0.0, emptyList())
        }

        val detrended = detrend(samples, (sampleRateHz * DETREND_WINDOW_SEC).toInt().coerceAtLeast(1))

        val (rate, confidence) = estimateRate(detrended, sampleRateHz)
        val pauses = detectPauses(detrended, sampleRateHz)
        return Result(rate, confidence, pauses)
    }

    /** Remove the slow gravity/posture baseline with a centred moving average. */
    private fun detrend(s: DoubleArray, window: Int): DoubleArray {
        val n = s.size
        val prefix = DoubleArray(n + 1)
        for (i in 0 until n) prefix[i + 1] = prefix[i] + s[i]
        val half = (window / 2).coerceAtLeast(1)
        val out = DoubleArray(n)
        for (i in 0 until n) {
            val a = (i - half).coerceAtLeast(0)
            val b = (i + half).coerceAtMost(n - 1)
            val mean = (prefix[b + 1] - prefix[a]) / (b - a + 1)
            out[i] = s[i] - mean
        }
        return out
    }

    /** Dominant period via normalized autocorrelation within the breathing band. */
    private fun estimateRate(d: DoubleArray, rate: Double): Pair<Double?, Double> {
        val n = d.size
        val energy = d.sumOf { it * it }
        if (energy < 1e-9) return null to 0.0
        val minLag = (rate * MIN_PERIOD_SEC).toInt().coerceAtLeast(1)
        val maxLag = (rate * MAX_PERIOD_SEC).toInt().coerceAtMost(n - 1)
        var bestLag = -1
        var bestVal = 0.0
        for (lag in minLag..maxLag) {
            var s = 0.0
            for (i in 0 until n - lag) s += d[i] * d[i + lag]
            val r = s / energy
            if (r > bestVal) { bestVal = r; bestLag = lag }
        }
        val bpm = if (bestLag > 0) 60.0 * rate / bestLag else null
        return bpm to bestVal.coerceIn(0.0, 1.0)
    }

    /** Flag contiguous stretches where chest-movement energy collapses for >= MIN_PAUSE_SEC. */
    private fun detectPauses(d: DoubleArray, rate: Double): List<PauseEvent> {
        val n = d.size
        val win = (rate * RMS_WINDOW_SEC).toInt().coerceAtLeast(1)
        val sq = DoubleArray(n + 1)
        for (i in 0 until n) sq[i + 1] = sq[i] + d[i] * d[i]
        val half = (win / 2).coerceAtLeast(1)
        val rms = DoubleArray(n)
        for (i in 0 until n) {
            val a = (i - half).coerceAtLeast(0)
            val b = (i + half).coerceAtMost(n - 1)
            rms[i] = sqrt((sq[b + 1] - sq[a]) / (b - a + 1))
        }
        val baseline = median(rms)
        if (baseline <= 1e-9) return emptyList()
        val threshold = PAUSE_FRACTION * baseline
        val minLen = (rate * MIN_PAUSE_SEC).toInt()

        val events = mutableListOf<PauseEvent>()
        var i = 0
        while (i < n) {
            if (rms[i] < threshold) {
                var j = i
                while (j < n && rms[j] < threshold) j++
                val len = j - i
                if (len >= minLen) events.add(PauseEvent(i / rate, len / rate))
                i = j
            } else {
                i++
            }
        }
        return events
    }

    private fun median(a: DoubleArray): Double {
        if (a.isEmpty()) return 0.0
        val sorted = a.sortedArray()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
    }
}
