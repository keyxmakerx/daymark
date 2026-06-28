package com.daymark.app.ui.cbt

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daymark.app.data.ThoughtRecordRepository
import com.daymark.app.data.entity.ThoughtRecord
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ThoughtRecordUiState(
    val id: Long = 0,
    val dateTime: Long = System.currentTimeMillis(),
    val situation: String = "",
    val automaticThought: String = "",
    val evidenceFor: String = "",
    val evidenceAgainst: String = "",
    val balancedThought: String = "",
    val moodBefore: Int = 3,
    val moodAfter: Int = 3,
    val distortions: Set<String> = emptySet(),
    val isEditing: Boolean = false,
    val saved: Boolean = false,
)

@HiltViewModel
class ThoughtRecordEditorViewModel @Inject constructor(
    private val repository: ThoughtRecordRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val recordId: Long = savedStateHandle.get<String>("recordId")?.toLongOrNull() ?: 0L

    private val _uiState = MutableStateFlow(ThoughtRecordUiState())
    val uiState: StateFlow<ThoughtRecordUiState> = _uiState.asStateFlow()

    init {
        if (recordId != 0L) {
            viewModelScope.launch {
                repository.getById(recordId)?.let { r ->
                    _uiState.update {
                        it.copy(
                            id = r.id, dateTime = r.dateTime, situation = r.situation,
                            automaticThought = r.automaticThought, evidenceFor = r.evidenceFor,
                            evidenceAgainst = r.evidenceAgainst, balancedThought = r.balancedThought,
                            moodBefore = r.moodBefore, moodAfter = r.moodAfter,
                            distortions = r.distortions.split(",").filter { d -> d.isNotBlank() }.toSet(),
                            isEditing = true,
                        )
                    }
                }
            }
        }
    }

    fun setSituation(v: String) = _uiState.update { it.copy(situation = v) }
    fun setThought(v: String) = _uiState.update { it.copy(automaticThought = v) }
    fun setEvidenceFor(v: String) = _uiState.update { it.copy(evidenceFor = v) }
    fun setEvidenceAgainst(v: String) = _uiState.update { it.copy(evidenceAgainst = v) }
    fun setBalanced(v: String) = _uiState.update { it.copy(balancedThought = v) }
    fun setMoodBefore(v: Int) = _uiState.update { it.copy(moodBefore = v) }
    fun setMoodAfter(v: Int) = _uiState.update { it.copy(moodAfter = v) }
    fun toggleDistortion(key: String) = _uiState.update {
        val next = it.distortions.toMutableSet()
        if (!next.add(key)) next.remove(key)
        it.copy(distortions = next)
    }

    fun save() {
        val s = _uiState.value
        if (s.situation.isBlank() && s.automaticThought.isBlank()) {
            _uiState.update { it.copy(saved = true) }
            return
        }
        viewModelScope.launch {
            repository.save(
                ThoughtRecord(
                    id = s.id, dateTime = s.dateTime, situation = s.situation.trim(),
                    automaticThought = s.automaticThought.trim(), evidenceFor = s.evidenceFor.trim(),
                    evidenceAgainst = s.evidenceAgainst.trim(), balancedThought = s.balancedThought.trim(),
                    moodBefore = s.moodBefore, moodAfter = s.moodAfter,
                    distortions = s.distortions.joinToString(","),
                ),
            )
            _uiState.update { it.copy(saved = true) }
        }
    }

    fun delete() {
        val s = _uiState.value
        if (!s.isEditing) { _uiState.update { it.copy(saved = true) }; return }
        viewModelScope.launch {
            repository.delete(
                ThoughtRecord(s.id, s.dateTime, s.situation, s.automaticThought, s.evidenceFor,
                    s.evidenceAgainst, s.balancedThought, s.moodBefore, s.moodAfter, s.distortions.joinToString(",")),
            )
            _uiState.update { it.copy(saved = true) }
        }
    }
}
