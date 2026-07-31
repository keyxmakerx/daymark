package com.daymark.app.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daymark.app.data.EntryRepository
import com.daymark.app.data.entity.EntryWithActivities
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class HistoryUiState(
    val entries: List<EntryWithActivities> = emptyList(),
    val loading: Boolean = true,
)

/**
 * The full, day-grouped timeline of everything logged — the archive that used to sit on Home.
 * Home now shows only today and links here, so the first screen stays calm without losing access
 * to a single entry.
 *
 * Read-only on purpose: deleting and undoing live in
 * [com.daymark.app.ui.entry.EntryActionsViewModel], which the scaffold owns, so an undo still
 * works after this destination has been popped.
 */
@HiltViewModel
class HistoryViewModel @Inject constructor(
    entryRepository: EntryRepository,
) : ViewModel() {

    val uiState: StateFlow<HistoryUiState> = entryRepository.observeAll()
        .map { HistoryUiState(entries = it, loading = false) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = HistoryUiState(),
        )
}
