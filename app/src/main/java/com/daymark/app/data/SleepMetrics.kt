package com.daymark.app.data

import com.daymark.app.data.entity.SleepLog

/** Pure, Android-free derived sleep metrics for one night (testable like MoodStats). */
object SleepMetrics {

    /** Minutes between getting into bed and final wake (handles past-midnight automatically). */
    fun timeInBedMin(log: SleepLog): Int {
        val diff = ((log.wakeTime - log.bedTime) / 60_000L).toInt()
        return if (diff < 0) diff + 24 * 60 else diff
    }

    /** Estimated total sleep = time in bed − time to fall asleep − time awake during the night. */
    fun totalSleepMin(log: SleepLog): Int =
        (timeInBedMin(log) - log.sleepLatencyMin - log.awakeMin).coerceAtLeast(0)

    /** Sleep efficiency as a percentage (TST / TIB). ~85%+ is the commonly-cited healthy mark. */
    fun efficiencyPct(log: SleepLog): Int {
        val tib = timeInBedMin(log)
        if (tib <= 0) return 0
        return (totalSleepMin(log) * 100 / tib).coerceIn(0, 100)
    }

    fun formatDuration(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }
}
