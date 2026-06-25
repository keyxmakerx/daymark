package com.daymark.app.ui.entry

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daymark.app.data.ActivityRepository
import com.daymark.app.data.EntryRepository
import com.daymark.app.data.entity.ActivityEntity
import com.daymark.app.data.entity.MoodEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class EntryEditorUiState(
    val entryId: Long = 0L,
    val moodLevel: Int = 3,
    val note: String = "",
    val dateTime: Long = System.currentTimeMillis(),
    val selectedActivityIds: Set<Long> = emptySet(),
    val activities: List<ActivityEntity> = emptyList(),
    val isEditing: Boolean = false,
    val saved: Boolean = false,
)

@HiltViewModel
class EntryEditorViewModel @Inject constructor(
    private val entryRepository: EntryRepository,
    private val activityRepository: ActivityRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val entryId: Long = savedStateHandle.get<String>("entryId")?.toLongOrNull() ?: 0L
    private val prefillMood: Int = savedStateHandle.get<String>("mood")?.toIntOrNull() ?: -1

    private val _uiState = MutableStateFlow(
        EntryEditorUiState(
            entryId = entryId,
            moodLevel = if (entryId == 0L && prefillMood in 1..5) prefillMood else 3,
        ),
    )
    val uiState: StateFlow<EntryEditorUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            activityRepository.observeActive().collect { list ->
                _uiState.update { it.copy(activities = list) }
            }
        }
        if (entryId != 0L) loadExisting(entryId)
    }

    private fun loadExisting(id: Long) {
        viewModelScope.launch {
            entryRepository.getById(id)?.let { ewa ->
                _uiState.update {
                    it.copy(
                        moodLevel = ewa.entry.moodLevel,
                        note = ewa.entry.note,
                        dateTime = ewa.entry.dateTime,
                        selectedActivityIds = ewa.activities.map { a -> a.id }.toSet(),
                        isEditing = true,
                    )
                }
            }
        }
    }

    fun setMood(level: Int) = _uiState.update { it.copy(moodLevel = level) }

    fun setNote(note: String) = _uiState.update { it.copy(note = note) }

    fun setDateTime(millis: Long) = _uiState.update { it.copy(dateTime = millis) }

    fun toggleActivity(id: Long) = _uiState.update { state ->
        val next = state.selectedActivityIds.toMutableSet()
        if (!next.add(id)) next.remove(id)
        state.copy(selectedActivityIds = next)
    }

    fun save() {
        val s = _uiState.value
        viewModelScope.launch {
            entryRepository.save(
                MoodEntry(
                    id = s.entryId,
                    dateTime = s.dateTime,
                    moodLevel = s.moodLevel,
                    note = s.note.trim(),
                ),
                s.selectedActivityIds.toList(),
            )
            _uiState.update { it.copy(saved = true) }
        }
    }

    fun delete() {
        val s = _uiState.value
        if (s.entryId == 0L) return
        viewModelScope.launch {
            entryRepository.delete(
                MoodEntry(s.entryId, s.dateTime, s.moodLevel, s.note),
            )
            _uiState.update { it.copy(saved = true) }
        }
    }
}
