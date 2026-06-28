package com.daymark.app.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A standalone journal / diary entry — deliberately separate from [MoodEntry].
 * A mood entry's `note` captures *why* you feel a certain way at a moment; a journal
 * entry is free-form writing not tied to any mood.
 */
@Entity(tableName = "journal_entries", indices = [Index("dateTime")])
data class JournalEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dateTime: Long,
    val title: String = "",
    val body: String = "",
)
