package com.daymark.app.data

import com.daymark.app.data.entity.SleepLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TreatmentStatsTest {

    private val day = 24 * 3600_000L
    private val start = 100 * day // arbitrary epoch-ish anchor

    private fun night(wake: Long, latency: Int = 0, awake: Int = 0, quality: Int = 3) =
        SleepLog(
            night = wake / day, bedTime = wake - 8 * 3600_000L, wakeTime = wake,
            sleepLatencyMin = latency, awakeMin = awake, quality = quality,
        )

    @Test
    fun splitsLogsBeforeAndAfterStart() {
        val logs = listOf(
            night(start - 5 * day, quality = 2),
            night(start - 2 * day, quality = 4),
            night(start + 1 * day, quality = 5),
        )
        val c = TreatmentStats.compare(start, logs, emptyList())
        assertEquals(2, c.before.nights)
        assertEquals(1, c.after.nights)
        assertEquals(3.0, c.before.avgQuality!!, 0.0001) // (2+4)/2
        assertEquals(5.0, c.after.avgQuality!!, 0.0001)
    }

    @Test
    fun ignoresLogsOlderThanWindow() {
        val logs = listOf(night(start - 40 * day)) // outside the 30-day before window
        val c = TreatmentStats.compare(start, logs, emptyList())
        assertEquals(0, c.before.nights)
        assertEquals(0, c.after.nights)
    }

    @Test
    fun averagesMoodPerSide() {
        val moods = listOf(
            (start - 3 * day) to 2,
            (start - 1 * day) to 4,
            (start + 2 * day) to 5,
        )
        val c = TreatmentStats.compare(start, emptyList(), moods)
        assertEquals(2, c.before.moodCount)
        assertEquals(3.0, c.before.avgMood!!, 0.0001)
        assertEquals(5.0, c.after.avgMood!!, 0.0001)
    }

    @Test
    fun emptySideHasNullAverages() {
        val c = TreatmentStats.compare(start, emptyList(), emptyList())
        assertNull(c.before.avgSleepMin)
        assertNull(c.after.avgMood)
        assertEquals(0, c.before.nights)
    }
}
