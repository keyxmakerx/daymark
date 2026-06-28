package com.daymark.app.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A daily check-in reminder at a fixed clock time. Multiple are supported (e.g. a morning and an
 * evening nudge). The optional [label] shows in the notification so different reminders can mean
 * different things ("Morning check-in", "Wind down").
 */
@Entity(tableName = "reminders")
data class Reminder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val hour: Int,
    val minute: Int,
    val enabled: Boolean = true,
    val label: String = "",
)
