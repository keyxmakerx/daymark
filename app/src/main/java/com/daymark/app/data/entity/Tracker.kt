package com.daymark.app.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

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
) {
    companion object {
        const val SCALE = "SCALE"
        const val NUMERIC = "NUMERIC"
        const val BOOLEAN = "BOOLEAN"
        val TYPES = listOf(SCALE, NUMERIC, BOOLEAN)
    }
}
