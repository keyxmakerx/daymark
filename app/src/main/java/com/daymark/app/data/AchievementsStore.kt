package com.daymark.app.data

import android.content.SharedPreferences
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Records when each achievement was first unlocked (id → epoch millis), in the same local-only
 * prefs as the rest of the app. Unlocks are sticky: once earned, an achievement stays earned even
 * if the underlying number later changes.
 */
@Singleton
class AchievementsStore @Inject constructor(
    private val prefs: SharedPreferences,
) {
    fun unlockedAt(id: String): Long? =
        if (prefs.contains(key(id))) prefs.getLong(key(id), 0L) else null

    fun all(): Map<String, Long> = prefs.all.keys
        .filter { it.startsWith(PREFIX) }
        .associate { it.removePrefix(PREFIX) to prefs.getLong(it, 0L) }

    /**
     * Marks any newly-satisfied ids as unlocked at [nowMillis] (existing unlocks keep their time).
     * Returns the ids unlocked by *this* call (for an optional celebratory notice).
     */
    fun markUnlocked(satisfiedIds: Set<String>, nowMillis: Long): Set<String> {
        val newly = satisfiedIds.filterNot { prefs.contains(key(it)) }
        if (newly.isNotEmpty()) {
            prefs.edit().apply { newly.forEach { putLong(key(it), nowMillis) } }.apply()
        }
        return newly.toSet()
    }

    /** Restores unlock times from a backup (does not clear existing ones). */
    fun restore(map: Map<String, Long>) {
        if (map.isEmpty()) return
        prefs.edit().apply { map.forEach { (id, at) -> putLong(key(id), at) } }.apply()
    }

    private fun key(id: String) = "$PREFIX$id"

    private companion object {
        const val PREFIX = "achievement_"
    }
}
