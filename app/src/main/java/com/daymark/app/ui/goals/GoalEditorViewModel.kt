package com.daymark.app.ui.goals

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daymark.app.data.ActivityRepository
import com.daymark.app.data.GoalRepository
import com.daymark.app.data.entity.ActivityEntity
import com.daymark.app.data.entity.Goal
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class GoalEditorUiState(
    val id: Long = 0,
    val title: String = "",
    val activityId: Long? = null,
    val targetPerWeek: Int = 3,
    val createdAt: Long = 0,
    val activities: List<ActivityEntity> = emptyList(),
    val isEditing: Boolean = false,
    val saved: Boolean = false,
)

@HiltViewModel
class GoalEditorViewModel @Inject constructor(
    private val goalRepository: GoalRepository,
    private val activityRepository: ActivityRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val goalId: Long = savedStateHandle.get<String>("goalId")?.toLongOrNull() ?: 0L

    private val _uiState = MutableStateFlow(GoalEditorUiState())
    val uiState: StateFlow<GoalEditorUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val activities = activityRepository.observeActive().first()
            _uiState.update { it.copy(activities = activities) }
            if (goalId != 0L) {
                goalRepository.getById(goalId)?.let { g ->
                    _uiState.update {
                        it.copy(
                            id = g.id, title = g.title, activityId = g.activityId,
                            targetPerWeek = g.targetPerWeek, createdAt = g.createdAt, isEditing = true,
                        )
                    }
                }
            }
        }
    }

    fun setTitle(value: String) = _uiState.update { it.copy(title = value) }
    fun setActivity(id: Long?) = _uiState.update { it.copy(activityId = id) }
    fun setTarget(value: Int) = _uiState.update { it.copy(targetPerWeek = value.coerceIn(1, 7)) }

    val canSave: Boolean get() = _uiState.value.title.isNotBlank()

    fun save() {
        val s = _uiState.value
        if (s.title.isBlank()) return
        viewModelScope.launch {
            goalRepository.save(
                Goal(
                    id = s.id,
                    title = s.title.trim(),
                    activityId = s.activityId,
                    targetPerWeek = s.targetPerWeek,
                    createdAt = if (s.createdAt == 0L) System.currentTimeMillis() else s.createdAt,
                ),
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
            goalRepository.delete(
                Goal(s.id, s.title, s.activityId, s.targetPerWeek, s.createdAt),
            )
            _uiState.update { it.copy(saved = true) }
        }
    }
}
