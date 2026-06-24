package com.daylie.app.model

import androidx.compose.ui.graphics.Color

/**
 * The fixed 5-level mood scale, modelled after Daylio (awful → rad).
 * [level] is what is persisted in the database (1 = worst, 5 = best).
 *
 * A user-customisable scale is a planned follow-up; for v1 the scale is constant.
 */
enum class Mood(
    val level: Int,
    val label: String,
    val emoji: String,
    val color: Color,
) {
    AWFUL(1, "Awful", "😡", Color(0xFFE2574C)),
    BAD(2, "Bad", "🙁", Color(0xFFEC9A3C)),
    MEH(3, "Meh", "😐", Color(0xFFEFC94C)),
    GOOD(4, "Good", "🙂", Color(0xFF8BC34A)),
    RAD(5, "Rad", "😄", Color(0xFF4CAF50));

    companion object {
        fun fromLevel(level: Int): Mood = entries.firstOrNull { it.level == level } ?: MEH

        /** Ordered worst → best for pickers and legends. */
        val ascending: List<Mood> get() = entries.sortedBy { it.level }
    }
}
