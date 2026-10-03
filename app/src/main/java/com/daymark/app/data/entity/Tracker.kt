package com.daymark.app.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.daymark.app.stats.TrackerRhythm

/**
 * A user-defined thing to track over time (e.g. "Energy" 1–10, "Water" in glasses, "Took meds"
 * yes/no). The condition-agnostic primitive the broader check-ins/sleep features build on.
 */
@Entity(tableName = "trackers")
data class Tracker(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** One of [SCALE], [NUMERIC], [BOOLEAN]. */
    val type: String,
    val minValue: Int = 1,
    val maxValue: Int = 5,
    val unit: String = "",
    val sortOrder: Int = 0,
    val archived: Boolean = false,
    /**
     * When this tracker asks to be logged: a [TrackerRhythm.Rhythm] key. Every tracker, old or new,
     * starts at "when it happens", which asks nothing; only the person switches asking on (§D1a).
     */
    @ColumnInfo(defaultValue = "when") val rhythm: String = TrackerRhythm.Rhythm.DEFAULT.key,
    /** The minute of the day a once-a-day tracker asks at. 20:00 until the person picks. */
    @ColumnInfo(defaultValue = "1200") val onceAtMinute: Int = 1200,
    /** How many check-ins a few-times-a-day tracker spreads over its hours. */
    @ColumnInfo(defaultValue = "3") val fewCount: Int = 3,
    /** The hours a few-times-a-day tracker asks in, as minutes of the day. 9:00 to 21:00 at first. */
    @ColumnInfo(defaultValue = "540") val windowStart: Int = 540,
    @ColumnInfo(defaultValue = "1260") val windowEnd: Int = 1260,
    /** The quiet quick-log notification for this tracker, which the person switches on. */
    @ColumnInfo(defaultValue = "0") val quickLog: Boolean = false,
) {
    val rhythmChoice: TrackerRhythm.Rhythm get() = TrackerRhythm.Rhythm.fromKey(rhythm)
    val window: TrackerRhythm.Window get() = TrackerRhythm.Window(windowStart, windowEnd)

    companion object {
        const val SCALE = "SCALE"
        const val NUMERIC = "NUMERIC"
        const val BOOLEAN = "BOOLEAN"
        val TYPES = listOf(SCALE, NUMERIC, BOOLEAN)
    }
}
