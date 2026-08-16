package com.daymark.app.ui.goals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daymark.app.data.ActivityRepository
import com.daymark.app.data.EntryRepository
import com.daymark.app.data.GoalRepository
import com.daymark.app.data.entity.Goal
import com.daymark.app.data.entity.toStep
import com.daymark.app.goals.GoalBoard
import com.daymark.app.goals.GoalKind
import com.daymark.app.goals.GoalReached
import com.daymark.app.stats.GoalProgress
import com.daymark.app.util.DateUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject

/**
 * One row of the goals list, carrying whichever of the two shapes' numbers applies.
 *
 * [completed]/[target] are the weekly habit count and mean nothing for a project; [project] is the
 * step count and means nothing for a habit. Both are filled rather than modelled as a sealed type
 * because the list needs a stable key and one card composable, and the alternative was a sealed
 * hierarchy whose only difference is which two integers get read.
 */
data class GoalProgressUi(
    val goal: Goal,
    val kind: GoalKind,
    val activityName: String?,
    val completed: Int,
    val target: Int,
    val project: GoalBoard.Progress,
) {
    val fraction: Float get() = if (target <= 0) 0f else (completed.toFloat() / target).coerceIn(0f, 1f)
    val isMet: Boolean get() = completed >= target

    /**
     * Read off the stored mark, and off nothing else.
     *
     * Note what it is *not* derived from, with both of them in scope one line above: [isMet] is a
     * habit hitting its weekly target and [project] is a board with every step in *Done*. Neither
     * makes a goal reached — see [GoalReached]. The two live in the same class precisely so the
     * temptation is visible.
     */
    val reached: Boolean get() = GoalReached.isReached(goal.reachedAt)
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class GoalsViewModel @Inject constructor(
    goalRepository: GoalRepository,
    entryRepository: EntryRepository,
    activityRepository: ActivityRepository,
) : ViewModel() {

    private val weekStartMillis: Long
        get() = DateUtils.startOfDay(LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)))

    val goals: StateFlow<List<GoalProgressUi>> = combine(
        goalRepository.observeActive(),
        entryRepository.observeBetween(weekStartMillis, DateUtils.endOfDay(LocalDate.now())),
        activityRepository.observeAll(),
        // Every goal's steps in one flow; grouped by goal below. A flow per project row would
        // resubscribe the whole list whenever any single step changed.
        goalRepository.observeAllSteps(),
    ) { goals, weekEntries, activities, steps ->
        val names = activities.associate { it.id to it.name }
        val stepsByGoal = steps.groupBy { it.goalId }
        goals.map { goal ->
            val completed = goal.activityId?.let {
                GoalProgress.completedDays(weekEntries, it, weekStartMillis)
            } ?: 0
            GoalProgressUi(
                goal = goal,
                kind = GoalKind.fromKey(goal.kind),
                activityName = goal.activityId?.let { names[it] },
                completed = completed,
                target = goal.targetPerWeek,
                project = GoalBoard.progressOf(
                    stepsByGoal[goal.id].orEmpty().map { it.toStep() },
                ),
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )
}
