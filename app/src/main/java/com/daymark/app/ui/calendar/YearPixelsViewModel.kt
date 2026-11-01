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
import javax.inject.Inject

data class YearPixelsUiState(
    val year: Int = LocalDate.now().year,
    /** date -> average mood level (1..5) for days that have entries; the year's stars read it. */
    val dayMoods: Map<LocalDate, Double> = emptyMap(),
    /** date -> every entry's mood level that day; the year grid reads it, never an average. */
    val dayLevels: Map<LocalDate, List<Int>> = emptyMap(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class YearPixelsViewModel @Inject constructor(
    private val entryRepository: EntryRepository,
) : ViewModel() {

    private val year = MutableStateFlow(LocalDate.now().year)

    private val dayLevels = year.flatMapLatest { y ->
        val from = DateUtils.startOfDay(LocalDate.of(y, 1, 1))
        val to = DateUtils.endOfDay(LocalDate.of(y, 12, 31))
        entryRepository.observeBetween(from, to).map { entries ->
            entries
                .groupBy { DateUtils.toLocalDate(it.entry.dateTime) }
                .mapValues { (_, list) -> list.map { it.entry.moodLevel } }
        }
    }

    val uiState: StateFlow<YearPixelsUiState> = combine(year, dayLevels) { y, levels ->
        YearPixelsUiState(
            year = y,
            dayMoods = levels.mapValues { (_, l) -> l.average() },
            dayLevels = levels,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = YearPixelsUiState(),
    )

    fun previousYear() { year.value -= 1 }
    fun nextYear() { year.value += 1 }
}
