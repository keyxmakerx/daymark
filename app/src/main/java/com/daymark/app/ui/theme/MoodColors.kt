package com.daymark.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
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

    /**
     * Returns a copy with the given per-level colour overrides applied (key = level 1..5, value =
     * ARGB int). Non-overridden levels keep their built-in colour and wash; overridden levels get
     * a derived wash so calendar/chart tints still work. Empty map = unchanged.
     */
    fun withOverrides(colors: Map<Int, Int>, dark: Boolean): MoodColors {
        if (colors.isEmpty()) return this
        fun base(level: Int): Color = colors[level]?.let { Color(it) } ?: forLevel(level)
        fun wash(level: Int): Color = colors[level]?.let {
            val c = Color(it)
            if (dark) lerp(c, Color.Black, 0.72f) else lerp(c, Color.White, 0.80f)
        } ?: washForLevel(level)
        return MoodColors(
            awful = base(1), bad = base(2), meh = base(3), good = base(4), rad = base(5),
            awfulWash = wash(1), badWash = wash(2), mehWash = wash(3), goodWash = wash(4), radWash = wash(5),
        )
    }
}

/**
 * Resolved mood labels. Built-in defaults come from [Mood]; users can override any of the five
 * via the customize-moods screen. Held in [LocalMoodLabels] alongside [LocalMoodColors].
 */
@Immutable
data class MoodLabels(private val overrides: Map<Int, String>) {
    fun forLevel(level: Int): String = overrides[level]?.ifBlank { null } ?: Mood.fromLevel(level).label
    fun forMood(mood: Mood): String = forLevel(mood.level)
}

val LocalMoodLabels = staticCompositionLocalOf { MoodLabels(emptyMap()) }

/** `MaterialTheme.moodLabels` accessor for the current (possibly customised) mood labels. */
val MaterialTheme.moodLabels: MoodLabels
    @Composable
    @ReadOnlyComposable
    get() = LocalMoodLabels.current

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
