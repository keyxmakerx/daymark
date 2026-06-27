package com.daymark.app.ui.trackers

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daymark.app.data.TrackerRepository
import com.daymark.app.data.entity.Tracker
import com.daymark.app.data.entity.TrackerLog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TrackerDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: TrackerRepository,
) : ViewModel() {

    private val id: Long = savedStateHandle.get<String>("trackerId")?.toLongOrNull() ?: 0L

    val tracker: StateFlow<Tracker?> = repository.observeById(id)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val logs: StateFlow<List<TrackerLog>> = repository.observeLogs(id)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun log(value: Double) {
        viewModelScope.launch { repository.log(id, value, System.currentTimeMillis()) }
    }

    fun deleteLog(log: TrackerLog) {
        viewModelScope.launch { repository.deleteLog(log) }
    }
}
