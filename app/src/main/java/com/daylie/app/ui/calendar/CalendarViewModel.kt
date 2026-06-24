package com.daylie.app.ui.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daylie.app.data.EntryRepository
import com.daylie.app.util.DateUtils
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
    /** date -> average mood level (1..5) for days that have entries. */
    val dayMoods: Map<LocalDate, Double> = emptyMap(),
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
            entries
                .groupBy { DateUtils.toLocalDate(it.entry.dateTime) }
                .mapValues { (_, list) -> list.map { it.entry.moodLevel }.average() }
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
