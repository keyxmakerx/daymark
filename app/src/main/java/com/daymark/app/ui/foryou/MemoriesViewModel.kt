package com.daymark.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daymark.app.data.EntryRepository
import com.daymark.app.data.entity.EntryWithActivities
import com.daymark.app.util.DateUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import javax.inject.Inject

/** "On this day" — entries from the same calendar day in previous years. */
@HiltViewModel
class MemoriesViewModel @Inject constructor(
    entryRepository: EntryRepository,
) : ViewModel() {

    val memories: StateFlow<List<EntryWithActivities>> = entryRepository.observeAll()
        .map { all ->
            val today = LocalDate.now()
            all.filter {
                val d = DateUtils.toLocalDate(it.entry.dateTime)
                d.dayOfMonth == today.dayOfMonth && d.monthValue == today.monthValue && d.year < today.year
            }.sortedByDescending { it.entry.dateTime }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
