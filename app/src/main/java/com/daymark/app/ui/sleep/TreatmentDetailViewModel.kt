package com.daymark.app.ui.sleep

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daymark.app.data.EntryRepository
import com.daymark.app.data.SleepRepository
import com.daymark.app.data.TreatmentRepository
import com.daymark.app.data.TreatmentStats
import com.daymark.app.data.entity.Treatment
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TreatmentDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val treatmentRepository: TreatmentRepository,
    sleepRepository: SleepRepository,
    entryRepository: EntryRepository,
) : ViewModel() {

    private val id: Long = savedStateHandle.get<String>("treatmentId")?.toLongOrNull() ?: 0L

    val treatment: StateFlow<Treatment?> = treatmentRepository.observeById(id)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val comparison: StateFlow<TreatmentStats.Comparison?> = treatment.filterNotNull()
        .flatMapLatest { t ->
            val now = System.currentTimeMillis()
            combine(
                sleepRepository.observeAll(),
                entryRepository.observeBetween(t.startedAt - TreatmentStats.WINDOW_MS, now),
            ) { logs, entries ->
                TreatmentStats.compare(
                    startedAt = t.startedAt,
                    logs = logs,
                    moods = entries.map { it.entry.dateTime to it.entry.moodLevel },
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun delete() {
        viewModelScope.launch { treatment.value?.let { treatmentRepository.delete(it) } }
    }
}
