package com.daymark.app.ui.goals

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daymark.app.data.ActivityRepository
import com.daymark.app.data.GoalRepository
import com.daymark.app.data.entity.ActivityEntity
import com.daymark.app.data.entity.Goal
import com.daymark.app.data.entity.toStep
import com.daymark.app.goals.GoalBoard
import com.daymark.app.goals.GoalKind
import com.daymark.app.goals.GoalReached
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
    val kind: GoalKind = GoalKind.DEFAULT,
    val activityId: Long? = null,
    val targetPerWeek: Int = 3,
    val createdAt: Long = 0,
    val cue: String = "",
    val routine: String = "",
    /**
     * The project board, held in memory until Save — the same contract the title and the if-then
     * fields have always had on this screen. A board that wrote through on every tap would be the
     * one control here that ignored the Save button, and backing out would then mean different
     * things for different halves of the same form.
     */
    val steps: List<GoalBoard.Step> = emptyList(),
    /**
     * The person's own mark that this goal is reached, held until Save like everything else here.
     *
     * The timestamp and not a boolean: it is the moment the Sky draws a star on, so it has to
     * survive the trip through this screen intact rather than being re-derived on write.
     */
    val reachedAt: Long? = null,
    val activities: List<ActivityEntity> = emptyList(),
    val isEditing: Boolean = false,
    val saved: Boolean = false,
) {
    val progress: GoalBoard.Progress get() = GoalBoard.progressOf(steps)

    val reached: Boolean get() = GoalReached.isReached(reachedAt)

    val canSave: Boolean get() = GoalBoard.canSave(kind, title, steps.size)
}

@HiltViewModel
class GoalEditorViewModel @Inject constructor(
    private val goalRepository: GoalRepository,
    private val activityRepository: ActivityRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val goalId: Long = savedStateHandle.get<String>("goalId")?.toLongOrNull() ?: 0L

    private val _uiState = MutableStateFlow(GoalEditorUiState())
    val uiState: StateFlow<GoalEditorUiState> = _uiState.asStateFlow()

    /**
     * Ids for steps typed but never saved.
     *
     * [GoalBoard] identifies a step by id, and a project must be usable before its goal has a row
     * (§D5 refuses to save a project that is only a title, so the steps necessarily exist first).
     * Counting **down from -1** keeps every draft distinct and keeps them impossible to confuse with
     * a row id, which is always positive. `GoalRepository.replaceSteps` discards ids on write, so
     * these never reach the database.
     */
    private var nextDraftId = -1L

    init {
        viewModelScope.launch {
            val activities = activityRepository.observeActive().first()
            _uiState.update { it.copy(activities = activities) }
            if (goalId != 0L) {
                goalRepository.getById(goalId)?.let { g ->
                    val steps = goalRepository.getSteps(goalId).map { it.toStep() }
                    _uiState.update {
                        it.copy(
                            id = g.id, title = g.title, activityId = g.activityId,
                            targetPerWeek = g.targetPerWeek, createdAt = g.createdAt,
                            cue = g.cue, routine = g.routine,
                            kind = GoalKind.fromKey(g.kind), steps = steps,
                            reachedAt = g.reachedAt,
                            isEditing = true,
                        )
                    }
                }
            }
        }
    }

    fun setTitle(value: String) = _uiState.update { it.copy(title = value) }
    fun setActivity(id: Long?) = _uiState.update { it.copy(activityId = id) }
    fun setTarget(value: Int) = _uiState.update { it.copy(targetPerWeek = value.coerceIn(1, 7)) }
    fun setCue(value: String) = _uiState.update { it.copy(cue = value) }
    fun setRoutine(value: String) = _uiState.update { it.copy(routine = value) }

    /**
     * Switches a goal between the weekly count and the step board.
     *
     * Steps are **kept** when switching to a habit rather than cleared, and the weekly target is
     * kept when switching to a project. Only the visible half changes; whichever half is not in use
     * is stored and comes back untouched if the person changes their mind. Clearing here would make
     * a mis-tap destroy writing, and there is no undo on this screen.
     */
    fun setKind(kind: GoalKind) = _uiState.update { it.copy(kind = kind) }

    /**
     * The person marking this goal reached, or taking that back.
     *
     * The only writer of [GoalEditorUiState.reachedAt] other than the load. Nothing on this screen
     * sets it from the weekly count or from the step board — see [GoalReached] for why a threshold
     * here would be the app deciding something about someone and then acting on it.
     *
     * The clock is read before the update, not inside it, for the reason [addStep] gives:
     * `MutableStateFlow.update` re-runs its lambda on contention, and a lambda that re-read the
     * clock each attempt would be doing work the retry cannot undo. [GoalReached.stamp] then keeps
     * an existing timestamp when the value is already `true`, so a recomposition that delivers the
     * switch's state a second time cannot move a star the person placed weeks ago to today.
     */
    fun setReached(reached: Boolean) {
        val markedAt = System.currentTimeMillis()
        _uiState.update { it.copy(reachedAt = GoalReached.stamp(it.reachedAt, reached, markedAt)) }
    }

    fun addStep(title: String) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        // Both the id and the clock are read before the update, not inside it: MutableStateFlow.update
        // re-runs its lambda on contention, and a lambda that consumed a draft id or re-read the
        // clock each attempt would be doing work the retry cannot undo.
        val draftId = nextDraftId--
        val addedAt = System.currentTimeMillis()
        _uiState.update { state ->
            state.copy(
                steps = state.steps + GoalBoard.Step(
                    id = draftId,
                    title = trimmed,
                    state = GoalBoard.StepState.TO_DO,
                    position = GoalBoard.nextPosition(state.steps, GoalBoard.StepState.TO_DO),
                    createdAt = addedAt,
                ),
            )
        }
    }

    fun moveStep(id: Long, to: GoalBoard.StepState) {
        val movedAt = System.currentTimeMillis()
        _uiState.update { it.copy(steps = GoalBoard.move(it.steps, id, to, movedAt)) }
    }

    fun reorderStep(id: Long, delta: Int) = _uiState.update {
        it.copy(steps = GoalBoard.reorder(it.steps, id, delta))
    }

    fun removeStep(id: Long) = _uiState.update {
        it.copy(steps = GoalBoard.remove(it.steps, id))
    }

    fun save() {
        val s = _uiState.value
        if (!s.canSave) return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val savedId = goalRepository.save(
                Goal(
                    id = s.id,
                    title = s.title.trim(),
                    activityId = s.activityId,
                    targetPerWeek = s.targetPerWeek,
                    createdAt = if (s.createdAt == 0L) now else s.createdAt,
                    cue = s.cue.trim(),
                    routine = s.routine.trim(),
                    kind = s.kind.key,
                    // Written through from state, never recomputed here: an already-reached goal
                    // saved again for an edited title keeps the date the person marked it on.
                    reachedAt = s.reachedAt,
                ),
            )
            goalRepository.replaceSteps(savedId, s.steps)
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
            goalRepository.deleteById(s.id)
            _uiState.update { it.copy(saved = true) }
        }
    }
}
