package com.daymark.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import com.daymark.app.model.Mood

/**
 * The five mood colours (and their light "wash" tints) live outside the standard Material 3
 * colour roles, so they are provided through a dedicated holder + [LocalMoodColors].
 *
 * Access ergonomically via `MaterialTheme.moodColors`.
 */
@Immutable
data class MoodColors(
    val awful: Color,
    val bad: Color,
    val meh: Color,
    val good: Color,
    val rad: Color,
    val awfulWash: Color,
    val badWash: Color,
    val mehWash: Color,
    val goodWash: Color,
    val radWash: Color,
) {
    fun forLevel(level: Int): Color = when (level) {
        1 -> awful
        2 -> bad
        3 -> meh
        4 -> good
        else -> rad
    }

    fun washForLevel(level: Int): Color = when (level) {
        1 -> awfulWash
        2 -> badWash
        3 -> mehWash
        4 -> goodWash
        else -> radWash
    }

    fun forMood(mood: Mood): Color = forLevel(mood.level)
    fun washForMood(mood: Mood): Color = washForLevel(mood.level)

    /** Ordered worst → best, for legends and pickers. */
    val ascending: List<Color> get() = listOf(awful, bad, meh, good, rad)
}

val LightMoodColors = MoodColors(
    awful = MoodAwful, bad = MoodBad, meh = MoodMeh, good = MoodGood, rad = MoodRad,
    awfulWash = MoodAwfulWash, badWash = MoodBadWash, mehWash = MoodMehWash,
    goodWash = MoodGoodWash, radWash = MoodRadWash,
)

val DarkMoodColors = MoodColors(
    awful = MoodAwful, bad = MoodBad, meh = MoodMeh, good = MoodGood, rad = MoodRad,
    awfulWash = MoodAwfulWashDark, badWash = MoodBadWashDark, mehWash = MoodMehWashDark,
    goodWash = MoodGoodWashDark, radWash = MoodRadWashDark,
)

val LocalMoodColors = staticCompositionLocalOf { LightMoodColors }

/** `MaterialTheme.moodColors` accessor for the current scheme's mood palette. */
val MaterialTheme.moodColors: MoodColors
    @Composable
    @ReadOnlyComposable
    get() = LocalMoodColors.current
