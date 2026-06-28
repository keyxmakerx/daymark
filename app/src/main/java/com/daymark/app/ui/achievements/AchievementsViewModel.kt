package com.daymark.app.ui.achievements

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daymark.app.data.AchievementsStore
import com.daymark.app.data.AssessmentRepository
import com.daymark.app.data.EntryRepository
import com.daymark.app.stats.Achievements
import com.daymark.app.stats.MoodStats
import com.daymark.app.util.DateUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class AchievementUi(
    val achievement: Achievements.Achievement,
    val unlockedAt: Long?,
)

@HiltViewModel
class AchievementsViewModel @Inject constructor(
    entryRepository: EntryRepository,
    assessmentRepository: AssessmentRepository,
    private val store: AchievementsStore,
) : ViewModel() {

    val uiState: StateFlow<List<AchievementUi>> = combine(
        entryRepository.observeAll(),
        assessmentRepository.observeAll(),
    ) { entries, assessments ->
        val days = entries.map { DateUtils.toLocalDate(it.entry.dateTime) }.toSet()
        val inputs = Achievements.Inputs(
            totalEntries = entries.size,
            longestStreak = MoodStats.longestStreak(days),
            distinctActivities = entries.flatMap { it.activities.map { a -> a.id } }.distinct().size,
            checkInsTaken = assessments.size,
        )
        // Persist any newly-earned achievements (sticky); ignored if already unlocked.
        store.markUnlocked(Achievements.evaluate(inputs), System.currentTimeMillis())
        Achievements.CATALOG.map { AchievementUi(it, store.unlockedAt(it.id)) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
