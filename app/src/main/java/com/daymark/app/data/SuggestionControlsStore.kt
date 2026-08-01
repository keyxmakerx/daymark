package com.daymark.app.data

import android.content.SharedPreferences
import com.daymark.app.stats.SuggestionControls
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Remembers what the person has said about each family of suggestions — shown less, snoozed,
 * hidden, or turned off entirely.
 *
 * The mockup's promise is that suggestion controls are "opt-out, granular, and **remembered**", so
 * unlike the per-screen dismissals (which are deliberately session-only) these survive restarts.
 * One key per field per group, matching the rest of the prefs layer; the rules that interpret them
 * live in the pure [SuggestionControls].
 */
@Singleton
class SuggestionControlsStore @Inject constructor(
    private val prefs: SharedPreferences,
) {

    fun stateOf(groupKey: String): SuggestionControls.State = SuggestionControls.State(
        off = prefs.getBoolean(offKey(groupKey), false),
        snoozedUntil = prefs.getLong(snoozeKey(groupKey), 0L),
        damping = prefs.getInt(dampingKey(groupKey), 0),
    )

    /** Every non-default group state, keyed by group. Defaults are never written. */
    fun all(): Map<String, SuggestionControls.State> = SuggestionControls.GROUPS
        .associate { it.key to stateOf(it.key) }
        .filterValues { !it.isDefault }

    fun controls(): SuggestionControls.Controls = SuggestionControls.Controls(all())

    /** Applies one card-menu action to [groupKey] and stores the result. */
    fun apply(groupKey: String, action: SuggestionControls.Action, nowMillis: Long) {
        write(groupKey, SuggestionControls.apply(stateOf(groupKey), action, nowMillis))
    }

    /** Turns a group back on, clearing any snooze and any accumulated "show less" with it. */
    fun turnOn(groupKey: String) = write(groupKey, SuggestionControls.turnOn())

    fun turnOff(groupKey: String) {
        write(groupKey, stateOf(groupKey).copy(off = true))
    }

    /** Ends a snooze early without otherwise changing the group. */
    fun clearSnooze(groupKey: String) {
        write(groupKey, stateOf(groupKey).copy(snoozedUntil = 0L))
    }

    private fun write(groupKey: String, state: SuggestionControls.State) {
        prefs.edit().apply {
            if (state.isDefault) {
                remove(offKey(groupKey))
                remove(snoozeKey(groupKey))
                remove(dampingKey(groupKey))
            } else {
                putBoolean(offKey(groupKey), state.off)
                putLong(snoozeKey(groupKey), state.snoozedUntil)
                putInt(dampingKey(groupKey), state.damping)
            }
        }.apply()
    }

    /**
     * Emits the current controls, then again whenever they change — so a card waved away on one
     * screen disappears from the others without either of them having to know.
     */
    fun observe(): Flow<SuggestionControls.Controls> = callbackFlow {
        trySend(controls())
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(controls()) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    private fun offKey(groupKey: String) = "${PREFIX}off_$groupKey"
    private fun snoozeKey(groupKey: String) = "${PREFIX}snooze_$groupKey"
    private fun dampingKey(groupKey: String) = "${PREFIX}damping_$groupKey"

    private companion object {
        const val PREFIX = "suggestion_"
    }
}
