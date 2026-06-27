package com.daymark.app.ui.sleep

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daymark.app.data.EntryRepository
import com.daymark.app.data.ScreeningStore
import com.daymark.app.data.SleepMoodInsight
import com.daymark.app.data.SleepRepository
import com.daymark.app.data.entity.SleepLog
import com.daymark.app.util.DateUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class SleepViewModel @Inject constructor(
    private val store: ScreeningStore,
    sleepRepository: SleepRepository,
    entryRepository: EntryRepository,
) : ViewModel() {

    private val _results = MutableStateFlow<Map<String, ScreeningStore.Result>>(emptyMap())
    val results: StateFlow<Map<String, ScreeningStore.Result>> = _results.asStateFlow()

    val logs: StateFlow<List<SleepLog>> = sleepRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Mood on better- vs. worse-sleep nights (pairs each night's quality with that day's mood). */
    val sleepMood: StateFlow<SleepMoodInsight.Result?> = combine(
        sleepRepository.observeAll(),
        entryRepository.observeBetween(0L, Long.MAX_VALUE),
    ) { logs, entries ->
        val moodByDay = entries
            .groupBy { DateUtils.toLocalDate(it.entry.dateTime).toEpochDay() }
            .mapValues { (_, list) -> list.map { it.entry.moodLevel }.average() }
        val pairs = logs.mapNotNull { log -> moodByDay[log.night]?.let { log.quality to it } }
        SleepMoodInsight.compute(pairs)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init { refresh() }

    fun refresh() {
        _results.value = SleepScreeners.all
            .mapNotNull { s -> store.get(s.key)?.let { s.key to it } }
            .toMap()
    }
}
