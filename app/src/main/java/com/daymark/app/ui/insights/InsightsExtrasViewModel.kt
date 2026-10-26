package com.daymark.app.ui.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daymark.app.data.EntryRepository
import com.daymark.app.data.entity.EntryWithActivities
import com.daymark.app.stats.MoodPatterns
import com.daymark.app.util.DateUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.DayOfWeek
import javax.inject.Inject

/**
 * The weekday and time-of-day views. There are no activity or tracker rankings here: lists of what
 * was "logged alongside higher or lower moods", and a tracker's `r` against mood, were the
 * generated insight DECISIONS.md §D6 does not build, and they went with the cards that said the
 * same thing (#483).
 */
data class InsightsExtrasState(
    val dayOfWeek: Map<DayOfWeek, Double> = emptyMap(),
    val timeOfDay: Map<MoodPatterns.TimeBucket, Double> = emptyMap(),
)

/** Computes the weekday and time-of-day Insights sections from data already stored locally. */
@HiltViewModel
class InsightsExtrasViewModel @Inject constructor(
    entryRepository: EntryRepository,
) : ViewModel() {

    val uiState: StateFlow<InsightsExtrasState> = entryRepository.observeAll()
        .map { entries -> compute(entries) }
        .flowOn(kotlinx.coroutines.Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InsightsExtrasState())

    private fun compute(entries: List<EntryWithActivities>): InsightsExtrasState {
        if (entries.isEmpty()) return InsightsExtrasState()

        // --- Weekday / time-of-day patterns ---
        val dow = MoodPatterns.byDayOfWeek(
            entries.map { DateUtils.toLocalDate(it.entry.dateTime).dayOfWeek to it.entry.moodLevel },
        )
        val tod = MoodPatterns.byTimeOfDay(
            entries.map { DateUtils.toLocalDateTime(it.entry.dateTime).hour to it.entry.moodLevel },
        )

        return InsightsExtrasState(
            dayOfWeek = dow,
            timeOfDay = tod,
        )
    }
}
