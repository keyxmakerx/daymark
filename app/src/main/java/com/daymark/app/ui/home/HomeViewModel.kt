package com.daymark.app.ui.home

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
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/**
 * What Home needs for the daily loop: today's entries and a two-number glance. The full archive
 * lives behind "All entries" (`ui/history`) so the first screen you see isn't a wall of history.
 */
data class HomeUiState(
    val today: List<EntryWithActivities> = emptyList(),
    val currentStreak: Int = 0,
    val totalEntries: Int = 0,
    /** Mean mood for each of the last [WEEK_DAYS] days, oldest first; null on unlogged days. */
    val week: List<Double?> = emptyList(),
    val loading: Boolean = true,
) {
    val daysLoggedThisWeek: Int get() = week.count { it != null }
}

/** How many days the Home glance looks back over. */
const val WEEK_DAYS = 7

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val entryRepository: EntryRepository,
) : ViewModel() {

    val uiState: StateFlow<HomeUiState> = entryRepository.observeAll()
        .map { all ->
            // The ViewModel owns the time zone (stats/ stays Android- and zone-free).
            val today = LocalDate.now()
            val byDay = all.groupBy { DateUtils.toLocalDate(it.entry.dateTime) }
            HomeUiState(
                today = byDay[today].orEmpty(),
                currentStreak = MoodStats.currentStreak(byDay.keys, today),
                totalEntries = all.size,
                week = (WEEK_DAYS - 1 downTo 0).map { back ->
                    val levels = byDay[today.minusDays(back.toLong())]?.map { it.entry.moodLevel }
                    levels?.let { MoodStats.averageMood(it) }
                },
                loading = false,
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = HomeUiState(),
        )

    fun delete(entry: EntryWithActivities) {
        viewModelScope.launch { entryRepository.delete(entry.entry) }
    }

    /** Restores an entry removed by swipe-to-delete (undo), keeping its id and activities. */
    fun restore(entry: EntryWithActivities) {
        viewModelScope.launch {
            entryRepository.restore(entry.entry, entry.activities.map { it.id })
        }
    }

    /** Finalizes a swipe-delete once undo is no longer possible: drop the photo file. */
    fun purgePhoto(entry: EntryWithActivities) {
        entryRepository.deletePhoto(entry.entry.photoPath)
    }
}
