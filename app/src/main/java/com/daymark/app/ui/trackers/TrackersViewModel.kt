package com.daymark.app.ui.trackers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daymark.app.data.TrackerRepository
import com.daymark.app.data.entity.Tracker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TrackersViewModel @Inject constructor(
    private val repository: TrackerRepository,
) : ViewModel() {

    val trackers: StateFlow<List<Tracker>> = repository.observeActive()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun add(name: String, type: String, maxValue: Int, unit: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            repository.add(
                Tracker(name = name.trim(), type = type, minValue = 1, maxValue = maxValue, unit = unit.trim()),
            )
        }
    }
}
