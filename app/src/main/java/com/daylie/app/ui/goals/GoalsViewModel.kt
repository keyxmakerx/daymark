package com.daylie.app.ui.goals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daylie.app.data.ActivityRepository
import com.daylie.app.data.EntryRepository
import com.daylie.app.data.GoalRepository
import com.daylie.app.data.entity.Goal
import com.daylie.app.stats.GoalProgress
import com.daylie.app.util.DateUtils
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

data class GoalProgressUi(
    val goal: Goal,
    val activityName: String?,
    val completed: Int,
    val target: Int,
) {
    val fraction: Float get() = if (target <= 0) 0f else (completed.toFloat() / target).coerceIn(0f, 1f)
    val isMet: Boolean get() = completed >= target
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
    ) { goals, weekEntries, activities ->
        val names = activities.associate { it.id to it.name }
        goals.map { goal ->
            val completed = goal.activityId?.let {
                GoalProgress.completedDays(weekEntries, it, weekStartMillis)
            } ?: 0
            GoalProgressUi(
                goal = goal,
                activityName = goal.activityId?.let { names[it] },
                completed = completed,
                target = goal.targetPerWeek,
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )
}
