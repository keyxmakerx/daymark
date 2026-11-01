package com.daymark.app.data

import android.content.SharedPreferences
import com.daymark.app.stats.CheckInQuestions
import com.daymark.app.stats.SupportOfferFrequency
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

    /**
     * The person's "Keep reminding me at these times" for the reminders, asked when they set the
     * first one: the reminders then never ease off and never try a longer wait
     * (`CheckInEngine.paceOf`). Written only through `ReminderScheduler.setKeepAsSet`, which also
     * starts the engine over from the person's schedule.
     */
    var remindersKeepAsSet: Boolean
        get() = prefs.getBoolean(KEY_REMINDERS_KEEP_AS_SET, false)
        set(value) = prefs.edit().putBoolean(KEY_REMINDERS_KEEP_AS_SET, value).apply()

    /**
     * Whether the person has answered [remindersKeepAsSet]'s question. False for everyone who set a
     * reminder before the question existed, and for them alone Home asks it once (#484); until they
     * answer, nothing changes. Written by `ReminderScheduler.setKeepAsSet`, which every answer goes
     * through.
     */
    var remindersKeepAnswered: Boolean
        get() = prefs.getBoolean(KEY_REMINDERS_KEEP_ANSWERED, false)
        set(value) = prefs.edit().putBoolean(KEY_REMINDERS_KEEP_ANSWERED, value).apply()

    /**
     * Trackers whose "ease off or keep" question the person has answered, by id. A tracker whose
     * check-ins were switched on before the question existed is not in it, and Home asks once.
     * Ids are this phone's: a tracker restored under a new id is asked once more.
     */
    var trackersKeepAnswered: Set<Long>
        get() = prefs.getStringSet(KEY_TRACKERS_KEEP_ANSWERED, emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet()
        set(value) = prefs.edit().putStringSet(KEY_TRACKERS_KEEP_ANSWERED, value.map { it.toString() }.toSet()).apply()

    /**
     * Reminders the person switched off with **Stop asking** on a notification, by id. Home states
     * that each is off, and where to turn it back on, until it is on again or the person hides the
     * line (#484). Home never turns one back on and never asks to (§D1b).
     */
    var stoppedReminderIds: Set<Long>
        get() = prefs.getStringSet(KEY_STOPPED_REMINDERS, emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet()
        set(value) = prefs.edit().putStringSet(KEY_STOPPED_REMINDERS, value.map { it.toString() }.toSet()).apply()

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
    /**
     * Wallpaper colours in place of the paper palette, on Android 12 and later. Off unless the person
     * turns it on: the paper identity is the point of the app, and the wallpaper brings hues and an
     * alarm colour the design keeps out (`docs/DESIGN.md`, #309).
     *
     * Only an unset preference reads as off. Nothing writes this key but the Settings switch, so a
     * stored value is always a choice somebody made, and a stored "on" stays on.
     * `DynamicColorSourceTest` holds both.
     */
    var dynamicColor: Boolean
        get() = prefs.getBoolean(KEY_DYNAMIC_COLOR, false)
        set(value) = prefs.edit().putBoolean(KEY_DYNAMIC_COLOR, value).apply()

    // --- Onboarding ---
    var onboardingComplete: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING_DONE, false)
        set(value) = prefs.edit().putBoolean(KEY_ONBOARDING_DONE, value).apply()

    // --- Gentle support (opt-in in-the-moment help offered after a low mood) ---
    /**
     * Master switch. When on, picking a low mood puts a quiet "Take a moment" action in the entry
     * editor's top bar. That action is an *offer* — it sits there and costs nothing to ignore.
     */
    var gentleSupportEnabled: Boolean
        get() = prefs.getBoolean(KEY_GENTLE_SUPPORT, false)
        set(value) = prefs.edit().putBoolean(KEY_GENTLE_SUPPORT, value).apply()

    /**
     * How often saving a hard day may *take you* to the support space rather than just offering.
     * Separate from [gentleSupportEnabled] because being moved somewhere you didn't ask to go costs
     * far more than a button you can ignore. See [SupportOffer].
     */
    var supportOfferFrequency: SupportOfferFrequency
        get() = SupportOfferFrequency.fromKey(prefs.getString(KEY_SUPPORT_OFFER_FREQ, null))
        set(value) = prefs.edit().putString(KEY_SUPPORT_OFFER_FREQ, value.name).apply()

    /**
     * Whether "Take a moment" lists its calm activities (#170). On by default and off in one tap
     * under Gentle support; off removes them from the menu entirely, for anyone who finds them a
     * strain rather than a rest. Independent of [gentleSupportEnabled], because the menu can be
     * reached without it.
     */
    var calmActivitiesEnabled: Boolean
        get() = prefs.getBoolean(KEY_CALM_ACTIVITIES, true)
        set(value) = prefs.edit().putBoolean(KEY_CALM_ACTIVITIES, value).apply()

    /** Epoch millis of the last interruption; 0 = never. Rationed by [supportOfferFrequency]. */
    var supportOfferLastShownAt: Long
        get() = prefs.getLong(KEY_SUPPORT_OFFER_LAST, 0L)
        set(value) = prefs.edit().putLong(KEY_SUPPORT_OFFER_LAST, value).apply()

    // --- The check-in's extra questions and its closing screen (stats/CheckInQuestions.kt) ---
    /** How many extra questions a check-in may ask, 0..3; one until the person says otherwise. */
    var checkInQuestionsAllowed: Int
        get() = prefs.getInt(KEY_CHECKIN_QUESTIONS, CheckInQuestions.DEFAULT_ALLOWED).coerceIn(CheckInQuestions.ALLOWED)
        set(value) = prefs.edit().putInt(KEY_CHECKIN_QUESTIONS, value.coerceIn(CheckInQuestions.ALLOWED)).apply()

    /** Whether a morning check-in may ask about last night, for someone who uses the sleep diary. */
    var sleepQuestionOn: Boolean
        get() = prefs.getBoolean(KEY_SLEEP_QUESTION, true)
        set(value) = prefs.edit().putBoolean(KEY_SLEEP_QUESTION, value).apply()

    /**
     * "I'm having a hard time" (#537): until when the person said so, in epoch millis. 0 is off and
     * [HARD_TIME_UNTIL_OFF] is until they turn it off. Only the person writes this.
     */
    var hardTimeUntil: Long
        get() = prefs.getLong(KEY_HARD_TIME_UNTIL, 0L)
        set(value) = prefs.edit().putLong(KEY_HARD_TIME_UNTIL, value).apply()

    /** Whether the hard-time switch is on at [nowMillis]. */
    fun hardTimeOn(nowMillis: Long = System.currentTimeMillis()): Boolean = nowMillis < hardTimeUntil

    /**
     * The evening check-in starts in the night sky (#540). Off until the person turns it on, and
     * the notification reads the same either way.
     */
    var eveningFromSky: Boolean
        get() = prefs.getBoolean(KEY_EVENING_FROM_SKY, false)
        set(value) = prefs.edit().putBoolean(KEY_EVENING_FROM_SKY, value).apply()

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
        private const val KEY_REMINDERS_KEEP_AS_SET = "reminders_keep_as_set"
        private const val KEY_REMINDERS_KEEP_ANSWERED = "reminders_keep_answered"
        private const val KEY_TRACKERS_KEEP_ANSWERED = "trackers_keep_answered"
        private const val KEY_STOPPED_REMINDERS = "stopped_reminder_ids"
        private const val KEY_LOCK_ENABLED = "lock_enabled"
        private const val KEY_PIN_HASH = "pin_hash"
        private const val KEY_BIOMETRIC_ENABLED = "biometric_enabled"
        private const val KEY_AUTO_LOCK_TIMEOUT = "auto_lock_timeout_minutes"
        private const val KEY_DYNAMIC_COLOR = "dynamic_color"
        private const val KEY_ONBOARDING_DONE = "onboarding_complete"
        private const val KEY_GENTLE_SUPPORT = "gentle_support_enabled"
        private const val KEY_SUPPORT_OFFER_FREQ = "support_offer_frequency"
        private const val KEY_SUPPORT_OFFER_LAST = "support_offer_last_shown_at"
        private const val KEY_CALM_ACTIVITIES = "calm_activities_enabled"
        private const val KEY_CHECKIN_QUESTIONS = "checkin_questions_allowed"
        private const val KEY_SLEEP_QUESTION = "checkin_sleep_question"
        private const val KEY_HARD_TIME_UNTIL = "hard_time_until"
        private const val KEY_EVENING_FROM_SKY = "evening_from_sky"

        /** [hardTimeUntil] for "until I turn it off". */
        const val HARD_TIME_UNTIL_OFF = Long.MAX_VALUE
    }
}
