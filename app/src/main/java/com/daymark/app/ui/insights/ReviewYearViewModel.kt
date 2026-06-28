package com.daymark.app.ui.insights

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daymark.app.data.EntryRepository
import com.daymark.app.util.DateUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import javax.inject.Inject

data class ReviewYearState(
    val year: Int,
    /** date -> average mood level (1..5) for days with entries, that year. */
    val dayMoods: Map<LocalDate, Double> = emptyMap(),
)

/** Provides one year's per-day mood means for the "Review my year" walkthrough. */
@HiltViewModel
class ReviewYearViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    entryRepository: EntryRepository,
) : ViewModel() {

    private val year: Int =
        savedStateHandle.get<String>("year")?.toIntOrNull() ?: LocalDate.now().year

    val uiState: StateFlow<ReviewYearState> = run {
        val from = DateUtils.startOfDay(LocalDate.of(year, 1, 1))
        val to = DateUtils.endOfDay(LocalDate.of(year, 12, 31))
        entryRepository.observeBetween(from, to).map { entries ->
            ReviewYearState(
                year = year,
                dayMoods = entries.groupBy { DateUtils.toLocalDate(it.entry.dateTime) }
                    .mapValues { (_, list) -> list.map { it.entry.moodLevel }.average() },
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReviewYearState(year))
    }
}
