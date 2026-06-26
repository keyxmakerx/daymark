package com.daymark.app.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A sleep-related treatment/change the user wants to watch (e.g. CPAP, surgery, positional
 * therapy). Used only to mark a "since" date and show the user's OWN self-tracked numbers
 * before vs. after — never as a measure of whether the treatment "works".
 */
@Entity(tableName = "treatments")
data class Treatment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    /** Epoch millis of the day it started. */
    val startedAt: Long,
    val note: String = "",
) {
    companion object {
        val KINDS = listOf("CPAP", "Surgery", "Oral appliance", "Positional therapy", "Medication", "Other")
    }
}
