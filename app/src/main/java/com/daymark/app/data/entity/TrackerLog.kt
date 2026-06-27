package com.daymark.app.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** One recorded value for a [Tracker]. */
@Entity(tableName = "tracker_logs", indices = [Index("trackerId")])
data class TrackerLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackerId: Long,
    val dateTime: Long,
    val value: Double,
    val note: String = "",
)
