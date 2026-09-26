package com.daymark.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daymark.app.data.EntryRepository
import com.daymark.app.data.entity.EntryWithActivities
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

/**
 * What Home needs for the daily loop: today's entries and the glance, which is the number of entries
 * and the last seven days as logged. The full archive lives behind "All entries" (`ui/history`) so
 * the first screen you see isn't a wall of history.
 */
data class HomeUiState(
    val today: List<EntryWithActivities> = emptyList(),
    val totalEntries: Int = 0,
    /**
     * The last seven days, oldest first, and the mood of each entry on each, in the day's own order
     * (#411). Each entry keeps its own mood; a day is never reduced to one number.
     */
    val week: CalendarDays.Week = CalendarDays.Week(),
    val loading: Boolean = true,
)

/**
 * Read-only on purpose: deleting and undoing live in
 * [com.daymark.app.ui.entry.EntryActionsViewModel], shared with the History screen.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    entryRepository: EntryRepository,
) : ViewModel() {

    val uiState: StateFlow<HomeUiState> = entryRepository.observeAll()
        .map { all ->
            // The ViewModel owns the time zone (stats/ stays Android- and zone-free).
            val today = LocalDate.now()
            val byDay = all.groupBy { DateUtils.toLocalDate(it.entry.dateTime) }
            HomeUiState(
                today = byDay[today].orEmpty(),
                totalEntries = all.size,
                week = CalendarDays.week(all.map { it.toDayEntry() }, today),
                loading = false,
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = HomeUiState(),
        )
}
