package com.daymark.app.data

import android.content.SharedPreferences
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local-only persistence for the latest sleep self-check ("screening") result per screener.
 * Stored in plain prefs — these are coarse risk bands, not clinical records. Nothing leaves
 * the device. (History/trends will move to a Room table when sensor tiers land.)
 */
@Singleton
class ScreeningStore @Inject constructor(
    private val prefs: SharedPreferences,
) {
    data class Result(val band: String, val atMillis: Long)

    fun get(key: String): Result? {
        val band = prefs.getString("screen_${key}_band", null) ?: return null
        return Result(band, prefs.getLong("screen_${key}_at", 0L))
    }

    fun save(key: String, band: String, atMillis: Long) {
        prefs.edit()
            .putString("screen_${key}_band", band)
            .putLong("screen_${key}_at", atMillis)
            .apply()
    }
}
