package com.daymark.app.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daymark.app.data.EntryRepository
import com.daymark.app.data.entity.EntryWithActivities
import com.daymark.app.stats.MoodCorrelations
import com.daymark.app.stats.MoodStats
import com.daymark.app.ui.calendar.CalendarDays
import com.daymark.app.ui.calendar.toDayEntry
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
    /**
     * Calendar days in the last [MoodStats.WINDOW_DAYS] with at least one entry. Zero means the
     * card is not drawn at all — see [com.daymark.app.ui.insights.InsightsScreen]. An empty window
     * is not a nought to show someone; it is nothing to say.
     */
    val daysWithEntryLast30: Int = 0,
    val moodCounts: Map<Int, Int> = emptyMap(),
    /** Last 30 days, oldest → newest; null where no entry that day. */
    val trend: List<Double?> = emptyList(),
    /**
     * Insights → Week: the last seven days, oldest first, and the mood of each entry on each, in the
     * day's own order (#411). Each entry keeps its own mood; a day is never reduced to one number.
     */
    val week: CalendarDays.Week = CalendarDays.Week(),
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
        val pairs = entries.map { it.entry.moodLevel to it.activities.map { a -> MoodCorrelations.FactorId.ofActivity(a.id) } }
        val averages = MoodStats.activityAverages(pairs)
        val nameById = entries.flatMap { it.activities }.associate { MoodCorrelations.FactorId.ofActivity(it.id) to it.name }
        val counts = mutableMapOf<MoodCorrelations.FactorId, Int>()
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
            daysWithEntryLast30 = MoodStats.daysWithEntryInLast30(days, today),
            moodCounts = MoodStats.moodCounts(levels),
            trend = trend,
            week = CalendarDays.week(entries.map { it.toDayEntry() }, today),
            topActivities = topActivities,
        )
    }
}
