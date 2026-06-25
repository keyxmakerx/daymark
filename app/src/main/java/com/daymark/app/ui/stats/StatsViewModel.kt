package com.daymark.app.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daymark.app.data.EntryRepository
import com.daymark.app.data.entity.EntryWithActivities
import com.daymark.app.stats.MoodStats
import com.daymark.app.util.DateUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import javax.inject.Inject

data class ActivityStat(val name: String, val averageMood: Double, val count: Int)

data class StatsUiState(
    val totalEntries: Int = 0,
    val averageMood: Double? = null,
    val currentStreak: Int = 0,
    val longestStreak: Int = 0,
    val moodCounts: Map<Int, Int> = emptyMap(),
    /** Last 30 days, oldest → newest; null where no entry that day. */
    val trend: List<Double?> = emptyList(),
    val topActivities: List<ActivityStat> = emptyList(),
)

@HiltViewModel
class StatsViewModel @Inject constructor(
    entryRepository: EntryRepository,
) : ViewModel() {

    val uiState: StateFlow<StatsUiState> = entryRepository.observeAll()
        .map { entries -> computeStats(entries) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = StatsUiState(),
        )

    private fun computeStats(entries: List<EntryWithActivities>): StatsUiState {
        if (entries.isEmpty()) return StatsUiState()

        val levels = entries.map { it.entry.moodLevel }
        val days = entries.map { DateUtils.toLocalDate(it.entry.dateTime) }.toSet()

        // Per-activity average mood, with names + counts.
        val pairs = entries.map { it.entry.moodLevel to it.activities.map { a -> a.id } }
        val averages = MoodStats.activityAverages(pairs)
        val nameById = entries.flatMap { it.activities }.associate { it.id to it.name }
        val counts = mutableMapOf<Long, Int>()
        pairs.forEach { (_, ids) -> ids.distinct().forEach { counts[it] = (counts[it] ?: 0) + 1 } }
        val topActivities = averages.entries
            .mapNotNull { (id, avg) -> nameById[id]?.let { ActivityStat(it, avg, counts[id] ?: 0) } }
            .sortedByDescending { it.averageMood }
            .take(8)

        // 30-day trend (daily average).
        val today = LocalDate.now()
        val byDay = entries.groupBy { DateUtils.toLocalDate(it.entry.dateTime) }
        val trend = (29 downTo 0).map { back ->
            val day = today.minusDays(back.toLong())
            byDay[day]?.map { it.entry.moodLevel }?.average()
        }

        return StatsUiState(
            totalEntries = entries.size,
            averageMood = MoodStats.averageMood(levels),
            currentStreak = MoodStats.currentStreak(days, today),
            longestStreak = MoodStats.longestStreak(days),
            moodCounts = MoodStats.moodCounts(levels),
            trend = trend,
            topActivities = topActivities,
        )
    }
}
