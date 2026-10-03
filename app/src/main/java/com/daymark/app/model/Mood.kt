package com.daymark.app.model

import androidx.compose.ui.graphics.Color

/**
 * The fixed 5-level mood scale (awful → rad).
 * [level] is what is persisted in the database (1 = worst, 5 = best).
 *
 * Colours are the muted, earthy "paper" mood palette (see ui/theme/Color.kt).
 * The five levels are fixed; a person can rename and recolour them
 * (data/MoodCustomizationStore.kt). A scale with a different number of steps is #206.
 */
enum class Mood(
    val level: Int,
    val label: String,
    val color: Color,
) {
    AWFUL(1, "Awful", Color(0xFFAE5747)),
    BAD(2, "Bad", Color(0xFFC27C46)),
    MEH(3, "Meh", Color(0xFFC6A24E)),
    GOOD(4, "Good", Color(0xFF8FA268)),
    RAD(5, "Rad", Color(0xFF5E8A66));

    companion object {
        fun fromLevel(level: Int): Mood = entries.firstOrNull { it.level == level } ?: MEH

        /** Ordered worst → best for pickers and legends. */
        val ascending: List<Mood> get() = entries.sortedBy { it.level }
    }
}
