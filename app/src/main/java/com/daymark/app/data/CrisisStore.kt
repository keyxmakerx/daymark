package com.daymark.app.data

import android.content.SharedPreferences
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Offline, user-editable crisis-resource contact. Defaults to the US 988 Suicide & Crisis
 * Lifeline; the user can replace it with their local line. Stored locally; nothing leaves the
 * device. (Showing crisis resources does NOT increase risk — the evidence is clear — but tone
 * matters, so the UI validates first and never auto-routes covertly.)
 */
@Singleton
class CrisisStore @Inject constructor(
    private val prefs: SharedPreferences,
) {
    data class Resource(val label: String, val contact: String)

    fun get(): Resource = Resource(
        label = prefs.getString(K_LABEL, DEFAULT_LABEL) ?: DEFAULT_LABEL,
        contact = prefs.getString(K_CONTACT, DEFAULT_CONTACT) ?: DEFAULT_CONTACT,
    )

    fun save(label: String, contact: String) {
        prefs.edit().putString(K_LABEL, label.trim()).putString(K_CONTACT, contact.trim()).apply()
    }

    companion object {
        const val DEFAULT_LABEL = "988 Suicide & Crisis Lifeline (US)"
        const val DEFAULT_CONTACT = "Call or text 988"
        private const val K_LABEL = "crisis_label"
        private const val K_CONTACT = "crisis_contact"
    }
}
