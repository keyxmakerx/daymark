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
import javax.inject.Inject

data class YearPixelsUiState(
    val year: Int = LocalDate.now().year,
    /** date -> average mood level (1..5) for days that have entries. */
    val dayMoods: Map<LocalDate, Double> = emptyMap(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class YearPixelsViewModel @Inject constructor(
    private val entryRepository: EntryRepository,
) : ViewModel() {

    private val year = MutableStateFlow(LocalDate.now().year)

    private val dayMoods = year.flatMapLatest { y ->
        val from = DateUtils.startOfDay(LocalDate.of(y, 1, 1))
        val to = DateUtils.endOfDay(LocalDate.of(y, 12, 31))
        entryRepository.observeBetween(from, to).map { entries ->
            entries
                .groupBy { DateUtils.toLocalDate(it.entry.dateTime) }
                .mapValues { (_, list) -> list.map { it.entry.moodLevel }.average() }
        }
    }

    val uiState: StateFlow<YearPixelsUiState> = combine(year, dayMoods) { y, moods ->
        YearPixelsUiState(year = y, dayMoods = moods)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = YearPixelsUiState(),
    )

    fun previousYear() { year.value -= 1 }
    fun nextYear() { year.value += 1 }
}
