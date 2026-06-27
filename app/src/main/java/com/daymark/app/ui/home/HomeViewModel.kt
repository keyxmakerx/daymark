package com.daymark.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daymark.app.data.EntryRepository
import com.daymark.app.data.entity.EntryWithActivities
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val entries: List<EntryWithActivities> = emptyList(),
    val loading: Boolean = true,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val entryRepository: EntryRepository,
) : ViewModel() {

    val uiState: StateFlow<HomeUiState> = entryRepository.observeAll()
        .map { HomeUiState(entries = it, loading = false) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = HomeUiState(),
        )

    fun delete(entry: EntryWithActivities) {
        viewModelScope.launch { entryRepository.delete(entry.entry) }
    }

    /** Restores an entry removed by swipe-to-delete (undo), keeping its id and activities. */
    fun restore(entry: EntryWithActivities) {
        viewModelScope.launch {
            entryRepository.restore(entry.entry, entry.activities.map { it.id })
        }
    }
}
