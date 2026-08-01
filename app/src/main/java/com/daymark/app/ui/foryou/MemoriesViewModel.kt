package com.daymark.app.ui.foryou

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daymark.app.data.EntryRepository
import com.daymark.app.data.SuggestionControlsStore
import com.daymark.app.data.entity.EntryWithActivities
import com.daymark.app.stats.SuggestionControls
import com.daymark.app.util.DateUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import javax.inject.Inject

/**
 * "On this day" — entries from the same calendar day in previous years.
 *
 * This is the one suggestion the app renders in its own richer form rather than as a generic
 * [com.daymark.app.ui.insights.SignalCards] card, so it has to honour the person's suggestion
 * controls itself: otherwise turning "On this day" off in Settings would silence a card nobody
 * ever sees and leave the real one in place.
 */
@HiltViewModel
class MemoriesViewModel @Inject constructor(
    entryRepository: EntryRepository,
    suggestionControlsStore: SuggestionControlsStore,
) : ViewModel() {

    val memories: StateFlow<List<EntryWithActivities>> = combine(
        entryRepository.observeAll(),
        suggestionControlsStore.observe(),
    ) { all, controls ->
        val state = controls.stateOf(MEMORIES_GROUP)
        if (state.off || state.isSnoozed(System.currentTimeMillis())) {
            emptyList()
        } else {
            val today = LocalDate.now()
            all.filter {
                val d = DateUtils.toLocalDate(it.entry.dateTime)
                d.dayOfMonth == today.dayOfMonth && d.monthValue == today.monthValue && d.year < today.year
            }.sortedByDescending { it.entry.dateTime }
        }
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private companion object {
        const val MEMORIES_KIND = "on_this_day"

        /** Resolved from the catalog so renaming the group can't quietly unhook this card. */
        val MEMORIES_GROUP: String = SuggestionControls.groupKeyOf(MEMORIES_KIND)
            ?: error("no suggestion group owns \"$MEMORIES_KIND\"")
    }
}
