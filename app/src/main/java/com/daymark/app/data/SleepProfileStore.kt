package com.daymark.app.data

import android.content.SharedPreferences
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one-time "sleep setup" calibration profile (local prefs). It records context that lets the
 * app interpret sleep signals honestly later — e.g. a shared bed or a pet means snore/movement
 * can't be confidently attributed to the user; an AC/fan means audio screening is unreliable.
 */
@Singleton
class SleepProfileStore @Inject constructor(
    private val prefs: SharedPreferences,
) {
    data class Profile(
        val sharesBed: Boolean = false,
        val petsNearBed: Boolean = false,
        val placement: String = PLACEMENT_NIGHTSTAND,
        val noiseSource: Boolean = false,
        val position: String = POSITION_VARIES,
    )

    fun load(): Profile = Profile(
        sharesBed = prefs.getBoolean(K_BED, false),
        petsNearBed = prefs.getBoolean(K_PETS, false),
        placement = prefs.getString(K_PLACE, PLACEMENT_NIGHTSTAND) ?: PLACEMENT_NIGHTSTAND,
        noiseSource = prefs.getBoolean(K_NOISE, false),
        position = prefs.getString(K_POS, POSITION_VARIES) ?: POSITION_VARIES,
    )

    fun save(p: Profile) {
        prefs.edit()
            .putBoolean(K_BED, p.sharesBed)
            .putBoolean(K_PETS, p.petsNearBed)
            .putString(K_PLACE, p.placement)
            .putBoolean(K_NOISE, p.noiseSource)
            .putString(K_POS, p.position)
            .apply()
    }

    companion object {
        const val PLACEMENT_NIGHTSTAND = "Nightstand"
        const val PLACEMENT_MATTRESS = "On the mattress"
        const val PLACEMENT_BODY = "On my body"
        val PLACEMENTS = listOf(PLACEMENT_NIGHTSTAND, PLACEMENT_MATTRESS, PLACEMENT_BODY)

        const val POSITION_BACK = "Back"
        const val POSITION_SIDE = "Side"
        const val POSITION_STOMACH = "Stomach"
        const val POSITION_VARIES = "Varies"
        val POSITIONS = listOf(POSITION_BACK, POSITION_SIDE, POSITION_STOMACH, POSITION_VARIES)

        private const val K_BED = "sleep_profile_shares_bed"
        private const val K_PETS = "sleep_profile_pets"
        private const val K_PLACE = "sleep_profile_placement"
        private const val K_NOISE = "sleep_profile_noise"
        private const val K_POS = "sleep_profile_position"
    }
}
