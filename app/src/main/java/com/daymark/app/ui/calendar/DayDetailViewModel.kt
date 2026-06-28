package com.daymark.app.ui.calendar

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daymark.app.data.EntryRepository
import com.daymark.app.data.entity.EntryWithActivities
import com.daymark.app.util.DateUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import javax.inject.Inject

@HiltViewModel
class DayDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    entryRepository: EntryRepository,
) : ViewModel() {

    val date: LocalDate = LocalDate.ofEpochDay(
        savedStateHandle.get<String>("epochDay")?.toLongOrNull() ?: LocalDate.now().toEpochDay(),
    )

    val entries: StateFlow<List<EntryWithActivities>> = entryRepository
        .observeBetween(DateUtils.startOfDay(date), DateUtils.endOfDay(date))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
