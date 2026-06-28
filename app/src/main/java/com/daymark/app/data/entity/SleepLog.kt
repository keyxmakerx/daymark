package com.daymark.app.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One night's manually-entered sleep diary record. Times are stored as epoch millis; the
 * diary intentionally asks for *estimates* (don't watch the clock — clock-watching worsens
 * insomnia). Derived metrics (total sleep, efficiency) are computed in [SleepMetrics], not stored.
 */
@Entity(tableName = "sleep_logs", indices = [Index("night")])
data class SleepLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Epoch-day of the morning you woke (LocalDate.toEpochDay()) — groups one record per night. */
    val night: Long,
    val bedTime: Long,
    val wakeTime: Long,
    /** Estimated minutes to fall asleep (sleep-onset latency). */
    val sleepLatencyMin: Int,
    /** Estimated total minutes awake during the night (wake after sleep onset). */
    val awakeMin: Int,
    /** Self-rated quality, 1 (poor) .. 5 (great). */
    val quality: Int,
    val note: String = "",
)
