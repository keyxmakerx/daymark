package com.daymark.app.ui.sleep

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daymark.app.data.TreatmentRepository
import com.daymark.app.data.entity.Treatment
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TreatmentsViewModel @Inject constructor(
    private val repository: TreatmentRepository,
) : ViewModel() {

    val treatments: StateFlow<List<Treatment>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun add(kind: String, startedAt: Long, note: String) {
        viewModelScope.launch {
            repository.add(Treatment(kind = kind, startedAt = startedAt, note = note.trim()))
        }
    }
}
