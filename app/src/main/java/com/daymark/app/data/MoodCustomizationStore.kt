package com.daymark.app.data

import android.content.SharedPreferences
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Optional per-level overrides for the five mood labels and colours. The integer level (1..5)
 * stays the immutable, persisted key for every entry — this only changes how a level is *shown*,
 * so it needs no database change and stays fully backward/forward compatible. Backed by the same
 * (local-only) SharedPreferences as the rest of [SettingsRepository], so its `changes()` flow
 * also fires when these change.
 */
@Singleton
class MoodCustomizationStore @Inject constructor(
    private val prefs: SharedPreferences,
) {
    /** A custom label for [level], or null to use the built-in default. */
    fun labelFor(level: Int): String? = prefs.getString(labelKey(level), null)?.ifBlank { null }

    /** A custom ARGB colour for [level], or null to use the built-in default. */
    fun colorFor(level: Int): Int? =
        if (prefs.contains(colorKey(level))) prefs.getInt(colorKey(level), 0) else null

    fun setLabel(level: Int, label: String?) = prefs.edit().apply {
        if (label.isNullOrBlank()) remove(labelKey(level)) else putString(labelKey(level), label.trim())
    }.apply()

    fun setColor(level: Int, color: Int?) = prefs.edit().apply {
        if (color == null) remove(colorKey(level)) else putInt(colorKey(level), color)
    }.apply()

    /** Clears every override, returning to the built-in palette and labels. */
    fun reset() = prefs.edit().apply {
        (1..5).forEach { remove(labelKey(it)); remove(colorKey(it)) }
    }.apply()

    fun labels(): Map<Int, String> = (1..5).mapNotNull { l -> labelFor(l)?.let { l to it } }.toMap()

    fun colors(): Map<Int, Int> = (1..5).mapNotNull { l -> colorFor(l)?.let { l to it } }.toMap()

    private fun labelKey(level: Int) = "mood_label_$level"
    private fun colorKey(level: Int) = "mood_color_$level"
}
