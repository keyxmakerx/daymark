package com.daymark.app.ui.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daymark.app.data.EntryRepository
import com.daymark.app.util.DateUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject

data class CalendarUiState(
    val month: YearMonth = YearMonth.now(),
    /**
     * date -> the mood level (1..5) of each entry that day, in the order logged, for days that have
     * entries. Each entry keeps its own mood; a day is never reduced to one number (#397).
     */
    val dayMoods: Map<LocalDate, List<Int>> = emptyMap(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CalendarViewModel @Inject constructor(
    private val entryRepository: EntryRepository,
) : ViewModel() {

    private val month = MutableStateFlow(YearMonth.now())
    val currentMonth: StateFlow<YearMonth> = month.asStateFlow()

    private val dayMoods = month.flatMapLatest { ym ->
        val from = DateUtils.startOfDay(ym.atDay(1))
        val to = DateUtils.endOfDay(ym.atEndOfMonth())
        entryRepository.observeBetween(from, to).map { entries ->
            CalendarDays.moodsByDay(
                entries.map {
                    DayEntry(
                        date = DateUtils.toLocalDate(it.entry.dateTime),
                        epochMillis = it.entry.dateTime,
                        id = it.entry.id,
                        moodLevel = it.entry.moodLevel,
                    )
                },
            )
        }
    }

    val uiState: StateFlow<CalendarUiState> = combine(month, dayMoods) { ym, moods ->
        CalendarUiState(month = ym, dayMoods = moods)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = CalendarUiState(),
    )

    fun previousMonth() { month.value = month.value.minusMonths(1) }
    fun nextMonth() { month.value = month.value.plusMonths(1) }
}
