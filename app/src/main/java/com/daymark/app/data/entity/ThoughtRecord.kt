package com.daymark.app.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A CBT-style thought record: a structured way to examine a difficult thought. Not a diagnosis or
 * a verdict on whether the thought is "true" — a reflection tool. [distortions] is a CSV of our
 * own distortion keys; [moodBefore]/[moodAfter] are 1..5 to gauge any shift after reflecting.
 */
@Entity(tableName = "thought_records", indices = [Index("dateTime")])
data class ThoughtRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dateTime: Long,
    val situation: String,
    val automaticThought: String,
    val evidenceFor: String,
    val evidenceAgainst: String,
    val balancedThought: String,
    val moodBefore: Int,
    val moodAfter: Int,
    val distortions: String,
)
