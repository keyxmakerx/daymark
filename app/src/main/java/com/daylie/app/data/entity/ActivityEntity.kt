package com.daylie.app.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A taggable activity (Daylio's "activities"), e.g. "work", "exercise", "friends".
 * [iconKey] maps to a Material icon in [com.daylie.app.ui.icon.ActivityIcons].
 */
@Entity(tableName = "activities")
data class ActivityEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val iconKey: String = "star",
    val sortOrder: Int = 0,
    val archived: Boolean = false,
)
