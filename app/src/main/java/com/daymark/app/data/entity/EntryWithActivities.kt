package com.daymark.app.data.entity

import androidx.room.Embedded
import androidx.room.Junction
import androidx.room.Relation

/** A [MoodEntry] joined with its tagged activities, for display. */
data class EntryWithActivities(
    @Embedded val entry: MoodEntry,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(
            value = EntryActivityCrossRef::class,
            parentColumn = "entryId",
            entityColumn = "activityId",
        ),
    )
    val activities: List<ActivityEntity>,
)
