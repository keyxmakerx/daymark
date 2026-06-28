package com.daymark.app.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One completed run of a bundled wellbeing questionnaire (PHQ-9 / GAD-7 / WHO-5). Only the total
 * [score] and resulting [bandLabel] are stored — never the individual item answers — so sensitive
 * details (e.g. the PHQ-9 self-harm item) never persist. [key] identifies the questionnaire.
 */
@Entity(tableName = "assessment_results", indices = [Index("key")])
data class AssessmentResult(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val key: String,
    val dateTime: Long,
    val score: Int,
    val bandLabel: String,
)
