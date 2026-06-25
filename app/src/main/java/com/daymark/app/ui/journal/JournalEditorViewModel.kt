package com.daymark.app.ui.journal

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daymark.app.data.JournalRepository
import com.daymark.app.data.entity.JournalEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class JournalEditorUiState(
    val id: Long = 0,
    val title: String = "",
    val body: String = "",
    val dateTime: Long = System.currentTimeMillis(),
    val isEditing: Boolean = false,
    val saved: Boolean = false,
)

@HiltViewModel
class JournalEditorViewModel @Inject constructor(
    private val repository: JournalRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val journalId: Long = savedStateHandle.get<String>("journalId")?.toLongOrNull() ?: 0L

    private val _uiState = MutableStateFlow(JournalEditorUiState())
    val uiState: StateFlow<JournalEditorUiState> = _uiState.asStateFlow()

    init {
        if (journalId != 0L) {
            viewModelScope.launch {
                repository.getById(journalId)?.let { e ->
                    _uiState.update {
                        it.copy(
                            id = e.id, title = e.title, body = e.body,
                            dateTime = e.dateTime, isEditing = true,
                        )
                    }
                }
            }
        }
    }

    fun setTitle(value: String) = _uiState.update { it.copy(title = value) }
    fun setBody(value: String) = _uiState.update { it.copy(body = value) }

    fun save() {
        val s = _uiState.value
        if (s.title.isBlank() && s.body.isBlank()) {
            _uiState.update { it.copy(saved = true) }
            return
        }
        viewModelScope.launch {
            repository.save(
                JournalEntry(id = s.id, dateTime = s.dateTime, title = s.title.trim(), body = s.body.trim()),
            )
            _uiState.update { it.copy(saved = true) }
        }
    }

    fun delete() {
        val s = _uiState.value
        if (!s.isEditing) {
            _uiState.update { it.copy(saved = true) }
            return
        }
        viewModelScope.launch {
            repository.delete(JournalEntry(s.id, s.dateTime, s.title, s.body))
            _uiState.update { it.copy(saved = true) }
        }
    }
}
