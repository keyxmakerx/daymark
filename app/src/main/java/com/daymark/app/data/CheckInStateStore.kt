package com.daymark.app.data

import android.content.SharedPreferences
import com.daymark.app.stats.CheckInEngine
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the rules engine has to remember between check-ins that the reception ledger cannot hold,
 * for the reminders (`docs/DECISIONS.md` §D1).
 *
 * The ledger is facts about moments; these are the person's answers to the engine's notices and the
 * engine's own place in its rhythm. Every value here can only move the app toward asking less,
 * except [putBack], which is the person asking for their own schedule again. None of it is about
 * the person, and none of it leaves the phone.
 */
@Singleton
class CheckInStateStore @Inject constructor(
    private val prefs: SharedPreferences,
) {

    /** The engine's state for the reminders. Defaults mean "as the person set it". */
    data class State(
        /** Ledger rows before this moment no longer count: the last Put it back. */
        val countFrom: Long = 0L,
        /** Longer waits tried and kept (`CheckInEngine.mayTryLonger`). */
        val trialSteps: Int = 0,
        /** When the last longer wait was tried; rows before it do not earn the next one. */
        val trialSince: Long = 0L,
        /** The person put a trial back, so no longer wait is tried again. */
        val trialDeclined: Boolean = false,
        /** The most recent change of pace was a trial, so Put it back also declines trials. */
        val lastChangeWasTrial: Boolean = false,
        /** The gap last announced to the person, so every change is announced exactly once. */
        val announcedGap: Long = 0L,
        /** Which of the fixed lines the next reminder uses. */
        val rotation: Int = 0,
    ) {
        val announced: CheckInEngine.Pace get() = CheckInEngine.Pace(announcedGap)
    }

    fun reminders(): State = State(
        countFrom = prefs.getLong(KEY_COUNT_FROM, 0L),
        trialSteps = prefs.getInt(KEY_TRIAL_STEPS, 0),
        trialSince = prefs.getLong(KEY_TRIAL_SINCE, 0L),
        trialDeclined = prefs.getBoolean(KEY_TRIAL_DECLINED, false),
        lastChangeWasTrial = prefs.getBoolean(KEY_LAST_WAS_TRIAL, false),
        announcedGap = prefs.getLong(KEY_ANNOUNCED_GAP, 0L),
        rotation = prefs.getInt(KEY_ROTATION, 0),
    )

    fun write(state: State) {
        prefs.edit()
            .putLong(KEY_COUNT_FROM, state.countFrom)
            .putInt(KEY_TRIAL_STEPS, state.trialSteps)
            .putLong(KEY_TRIAL_SINCE, state.trialSince)
            .putBoolean(KEY_TRIAL_DECLINED, state.trialDeclined)
            .putBoolean(KEY_LAST_WAS_TRIAL, state.lastChangeWasTrial)
            .putLong(KEY_ANNOUNCED_GAP, state.announcedGap)
            .putInt(KEY_ROTATION, state.rotation)
            .apply()
    }

    /**
     * The person's **Put it back**: the reminders return to the times they set, at once. Rows before
     * [nowMillis] stop counting, kept trials are dropped, and if the change being put back was a
     * trial, no longer wait is tried again.
     */
    fun putBack(nowMillis: Long) {
        val state = reminders()
        write(
            state.copy(
                countFrom = nowMillis,
                trialSteps = 0,
                trialSince = nowMillis,
                trialDeclined = state.trialDeclined || state.lastChangeWasTrial,
                lastChangeWasTrial = false,
                announcedGap = CheckInEngine.Pace.AsSet.gapMillis,
            ),
        )
    }

    private companion object {
        const val KEY_COUNT_FROM = "checkin_reminder_count_from"
        const val KEY_TRIAL_STEPS = "checkin_reminder_trial_steps"
        const val KEY_TRIAL_SINCE = "checkin_reminder_trial_since"
        const val KEY_TRIAL_DECLINED = "checkin_reminder_trial_declined"
        const val KEY_LAST_WAS_TRIAL = "checkin_reminder_last_was_trial"
        const val KEY_ANNOUNCED_GAP = "checkin_reminder_announced_gap"
        const val KEY_ROTATION = "checkin_reminder_rotation"
    }
}
