package com.daymark.app.data

import android.content.SharedPreferences

/**
 * The five answers the old "Sleep setup" screen stored (bed partner, pets, noise, where the phone
 * sits, sleep position), which nothing reads (#356, decided in #212). They sat in the settings file
 * the app does not encrypt, so they are deleted from the phone on launch, and only a phone that
 * still holds one is written to. They were never in a backup, so a restore cannot bring them back.
 */
object RetiredSleepSetup {

    /** The keys exactly as the setup wrote them. */
    val KEYS: Set<String> = setOf(
        "sleep_profile_shares_bed",
        "sleep_profile_pets",
        "sleep_profile_placement",
        "sleep_profile_noise",
        "sleep_profile_position",
    )

    /** The keys [has] still holds; deleting them is all [forget] does. */
    fun present(has: (String) -> Boolean): Set<String> = KEYS.filterTo(LinkedHashSet()) { has(it) }

    /** Deletes whichever of [KEYS] [prefs] still holds; writes nothing when it holds none. */
    fun forget(prefs: SharedPreferences) {
        val held = present(prefs::contains)
        if (held.isEmpty()) return
        prefs.edit().apply { held.forEach { remove(it) } }.apply()
    }
}
