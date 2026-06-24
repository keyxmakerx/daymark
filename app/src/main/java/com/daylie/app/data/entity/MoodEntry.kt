package com.daylie.app.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A single logged moment: a mood level at a point in time, with an optional note.
 * Activities are linked via [EntryActivityCrossRef].
 */
@Entity(tableName = "mood_entries")
data class MoodEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Epoch millis of when the entry is for (defaults to now at creation). */
    val dateTime: Long,
    /** 1..5, see [com.daylie.app.model.Mood]. */
    val moodLevel: Int,
    val note: String = "",
)
