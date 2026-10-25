package com.daymark.app.data

import android.content.SharedPreferences
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Offline, user-editable crisis-resource contact. Defaults to the US 988 Suicide & Crisis
 * Lifeline; the user can replace it with their local line. Stored locally; nothing leaves the
 * device. (Showing crisis resources does NOT increase risk — the evidence is clear — but tone
 * matters, so the UI validates first and never auto-routes covertly.)
 *
 * The line has a number of its own, apart from the free-text [Resource.contact], so the crisis
 * screen's Call and Text buttons dial exactly what the person confirmed (#482). Those buttons appear
 * only once the line is [confirmed]: a person abroad is never handed a one-tap US number they did
 * not choose.
 */
@Singleton
class CrisisStore @Inject constructor(
    private val prefs: SharedPreferences,
) {
    /**
     * [number] is what the Call and Text buttons dial, already reduced to [dialable] form, or null
     * when the line has none (a line saved before numbers existed, or one reached another way).
     * [takesTexts] says whether the Text button is offered.
     */
    data class Resource(
        val label: String,
        val contact: String,
        val number: String? = null,
        val takesTexts: Boolean = false,
    )

    private val hasOwn: Boolean get() = prefs.contains(K_LABEL) && prefs.contains(K_CONTACT)

    fun get(): Resource = if (hasOwn) {
        Resource(
            label = prefs.getString(K_LABEL, DEFAULT_LABEL) ?: DEFAULT_LABEL,
            contact = prefs.getString(K_CONTACT, DEFAULT_CONTACT) ?: DEFAULT_CONTACT,
            number = dialable(prefs.getString(K_NUMBER, null)),
            takesTexts = prefs.getBoolean(K_TEXTS, false),
        )
    } else {
        Resource(DEFAULT_LABEL, DEFAULT_CONTACT, DEFAULT_NUMBER, takesTexts = true)
    }

    /**
     * The line the person set themselves, or null while the crisis screen still shows the default.
     * A backup carries only this, so a restore can never write the default over a line somebody
     * chose (#370).
     */
    fun own(): Resource? = if (hasOwn) get() else null

    /**
     * Whether the person has said this line is right for them: by typing their own, or by keeping
     * the default when asked. Until then the screen shows the line as text and asks.
     */
    val confirmed: Boolean get() = hasOwn || prefs.getBoolean(K_CONFIRMED, false)

    /**
     * Whether the one-time "is this the right line?" question is still owed. True only for someone
     * who has neither typed a line nor answered the question — the people who set Daymark up
     * before onboarding asked it. Any answer, "Not now" included, ends it: nobody is asked twice.
     */
    val needsCheck: Boolean get() = !hasOwn && !prefs.getBoolean(K_CHECKED, false)

    /** "This one is right": the default line is the person's, and its buttons appear. */
    fun confirm() {
        prefs.edit().putBoolean(K_CONFIRMED, true).putBoolean(K_CHECKED, true).apply()
    }

    /** "Not now": the question is answered and not asked again. The line stays as text. */
    fun markChecked() {
        prefs.edit().putBoolean(K_CHECKED, true).apply()
    }

    /** Blank fields are never saved: the crisis screen must always have a line to show. */
    fun save(label: String, contact: String, number: String? = null, takesTexts: Boolean = false) {
        if (label.isBlank() || contact.isBlank()) return
        val dial = dialable(number)
        val edit = prefs.edit()
            .putString(K_LABEL, label.trim())
            .putString(K_CONTACT, contact.trim())
            .putBoolean(K_TEXTS, dial != null && takesTexts)
            .putBoolean(K_CHECKED, true)
        if (dial != null) edit.putString(K_NUMBER, dial) else edit.remove(K_NUMBER)
        edit.apply()
    }

    companion object {
        const val DEFAULT_LABEL = "988 Suicide & Crisis Lifeline (US)"
        const val DEFAULT_CONTACT = "Call or text 988"
        const val DEFAULT_NUMBER = "988"
        private const val K_LABEL = "crisis_label"
        private const val K_CONTACT = "crisis_contact"
        private const val K_NUMBER = "crisis_number"
        private const val K_TEXTS = "crisis_takes_texts"
        private const val K_CONFIRMED = "crisis_confirmed"
        private const val K_CHECKED = "crisis_checked"

        /**
         * [raw] as something a dialer takes: digits and `+ * #`, everything else dropped, or null
         * when no digit is left. "116 123" becomes "116123"; "Text HOME" becomes null.
         */
        fun dialable(raw: String?): String? =
            raw?.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }?.takeIf { s -> s.any { it.isDigit() } }
    }
}
