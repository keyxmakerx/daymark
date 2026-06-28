package com.daymark.app.data

import com.daymark.app.data.entity.SleepLog
import org.junit.Assert.assertEquals
import org.junit.Test

class SleepMetricsTest {

    private val hourMs = 3600_000L

    private fun log(bed: Long, wake: Long, latency: Int = 0, awake: Int = 0, quality: Int = 3) =
        SleepLog(night = 0, bedTime = bed, wakeTime = wake, sleepLatencyMin = latency, awakeMin = awake, quality = quality)

    @Test
    fun timeInBed_eightHours() {
        assertEquals(480, SleepMetrics.timeInBedMin(log(1000L, 1000L + 8 * hourMs)))
    }

    @Test
    fun timeInBed_wrapsPastMidnight() {
        // wake earlier in raw value than bed → treated as next day (+24h).
        val bed = 23 * hourMs
        val wake = 7 * hourMs
        assertEquals(8 * 60, SleepMetrics.timeInBedMin(log(bed, wake)))
    }

    @Test
    fun totalSleep_subtractsLatencyAndWaso() {
        val l = log(0L, 8 * hourMs, latency = 15, awake = 30)
        assertEquals(480 - 15 - 30, SleepMetrics.totalSleepMin(l))
    }

    @Test
    fun totalSleep_neverNegative() {
        val l = log(0L, 1 * hourMs, latency = 120, awake = 120)
        assertEquals(0, SleepMetrics.totalSleepMin(l))
    }

    @Test
    fun efficiency_ninetyPercent() {
        val l = log(0L, 8 * hourMs, latency = 18, awake = 30) // TST 432 / 480 = 90%
        assertEquals(90, SleepMetrics.efficiencyPct(l))
    }

    @Test
    fun efficiency_zeroTimeInBedIsZero() {
        assertEquals(0, SleepMetrics.efficiencyPct(log(1000L, 1000L)))
    }

    @Test
    fun formatDuration_hoursAndMinutes() {
        assertEquals("7h 5m", SleepMetrics.formatDuration(425))
        assertEquals("45m", SleepMetrics.formatDuration(45))
    }
}
