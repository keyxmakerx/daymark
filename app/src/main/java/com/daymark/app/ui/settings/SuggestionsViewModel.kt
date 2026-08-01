package com.daymark.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daymark.app.data.SuggestionControlsStore
import com.daymark.app.stats.SuggestionControls
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** One suggestion family as the Suggestions screen shows it. */
data class SuggestionRow(
    val group: SuggestionControls.Group,
    val state: SuggestionControls.State,
    /** "back in 2h" while snoozed, else null. */
    val snoozeSummary: String?,
) {
    val on: Boolean get() = !state.off
    val snoozed: Boolean get() = snoozeSummary != null
}

@HiltViewModel
class SuggestionsViewModel @Inject constructor(
    private val store: SuggestionControlsStore,
) : ViewModel() {

    val rows: StateFlow<List<SuggestionRow>> = store.observe()
        .map { controls ->
            val now = System.currentTimeMillis()
            SuggestionControls.GROUPS.map { group ->
                val state = controls.stateOf(group.key)
                SuggestionRow(group, state, SuggestionControls.snoozeSummary(state, now))
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setOn(groupKey: String, on: Boolean) {
        if (on) store.turnOn(groupKey) else store.turnOff(groupKey)
    }

    /** Ends a snooze now, without changing anything else about the group. */
    fun resumeNow(groupKey: String) = store.clearSnooze(groupKey)
}
