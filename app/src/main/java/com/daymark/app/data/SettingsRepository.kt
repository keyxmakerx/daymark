package com.daymark.app.data

import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin reactive wrapper over [SharedPreferences] for app settings.
 * All values are local-only; nothing leaves the device.
 */
@Singleton
class SettingsRepository @Inject constructor(
    private val prefs: SharedPreferences,
) {
    // --- Reminders ---
    var reminderEnabled: Boolean
        get() = prefs.getBoolean(KEY_REMINDER_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_REMINDER_ENABLED, value).apply()

    var reminderHour: Int
        get() = prefs.getInt(KEY_REMINDER_HOUR, 20)
        set(value) = prefs.edit().putInt(KEY_REMINDER_HOUR, value).apply()

    var reminderMinute: Int
        get() = prefs.getInt(KEY_REMINDER_MINUTE, 0)
        set(value) = prefs.edit().putInt(KEY_REMINDER_MINUTE, value).apply()

    /** Set once the legacy single reminder has been migrated into the reminders table. */
    var legacyReminderMigrated: Boolean
        get() = prefs.getBoolean(KEY_LEGACY_REMINDER_MIGRATED, false)
        set(value) = prefs.edit().putBoolean(KEY_LEGACY_REMINDER_MIGRATED, value).apply()

    // --- App lock ---
    var lockEnabled: Boolean
        get() = prefs.getBoolean(KEY_LOCK_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_LOCK_ENABLED, value).apply()

    var pinHash: String?
        get() = prefs.getString(KEY_PIN_HASH, null)
        set(value) = prefs.edit().putString(KEY_PIN_HASH, value).apply()

    var biometricEnabled: Boolean
        get() = prefs.getBoolean(KEY_BIOMETRIC_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_BIOMETRIC_ENABLED, value).apply()

    /**
     * Minutes the app may sit in the background before it re-locks. 0 = lock immediately
     * (the original behaviour). Lets people step away briefly without re-entering their PIN.
     */
    var autoLockTimeoutMinutes: Int
        get() = prefs.getInt(KEY_AUTO_LOCK_TIMEOUT, 0)
        set(value) = prefs.edit().putInt(KEY_AUTO_LOCK_TIMEOUT, value).apply()

    // --- Appearance ---
    var dynamicColor: Boolean
        get() = prefs.getBoolean(KEY_DYNAMIC_COLOR, true)
        set(value) = prefs.edit().putBoolean(KEY_DYNAMIC_COLOR, value).apply()

    // --- Onboarding ---
    var onboardingComplete: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING_DONE, false)
        set(value) = prefs.edit().putBoolean(KEY_ONBOARDING_DONE, value).apply()

    // --- Gentle support (opt-in in-the-moment help offered after a low mood) ---
    var gentleSupportEnabled: Boolean
        get() = prefs.getBoolean(KEY_GENTLE_SUPPORT, false)
        set(value) = prefs.edit().putBoolean(KEY_GENTLE_SUPPORT, value).apply()

    /** Emits the current preferences object whenever any value changes. */
    fun changes(): Flow<SharedPreferences> = callbackFlow {
        trySend(prefs)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { sp, _ -> trySend(sp) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    companion object {
        private const val KEY_REMINDER_ENABLED = "reminder_enabled"
        private const val KEY_REMINDER_HOUR = "reminder_hour"
        private const val KEY_REMINDER_MINUTE = "reminder_minute"
        private const val KEY_LEGACY_REMINDER_MIGRATED = "legacy_reminder_migrated"
        private const val KEY_LOCK_ENABLED = "lock_enabled"
        private const val KEY_PIN_HASH = "pin_hash"
        private const val KEY_BIOMETRIC_ENABLED = "biometric_enabled"
        private const val KEY_AUTO_LOCK_TIMEOUT = "auto_lock_timeout_minutes"
        private const val KEY_DYNAMIC_COLOR = "dynamic_color"
        private const val KEY_ONBOARDING_DONE = "onboarding_complete"
        private const val KEY_GENTLE_SUPPORT = "gentle_support_enabled"
    }
}
