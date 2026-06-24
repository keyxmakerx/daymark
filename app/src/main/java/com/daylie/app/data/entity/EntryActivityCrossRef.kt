package com.daylie.app.data.entity

import androidx.room.Entity
import androidx.room.Index

/** Many-to-many link between a [MoodEntry] and the [ActivityEntity]s tagged on it. */
@Entity(
    tableName = "entry_activity",
    primaryKeys = ["entryId", "activityId"],
    indices = [Index("activityId")],
)
data class EntryActivityCrossRef(
    val entryId: Long,
    val activityId: Long,
)
