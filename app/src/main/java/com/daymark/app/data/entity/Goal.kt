package com.daymark.app.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A weekly habit goal, e.g. "Exercise 5× per week". Optionally linked to an [activityId];
 * progress is then the number of distinct days this week whose entries include that activity.
 */
@Entity(tableName = "goals")
data class Goal(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val activityId: Long? = null,
    val targetPerWeek: Int = 3,
    val createdAt: Long = 0,
    val archived: Boolean = false,
    /**
     * Optional implementation intention ("when [cue], I will [routine]") — a simple, well-evidenced
     * way to turn an intention into action. Empty when unset.
     */
    @ColumnInfo(defaultValue = "") val cue: String = "",
    @ColumnInfo(defaultValue = "") val routine: String = "",
)
