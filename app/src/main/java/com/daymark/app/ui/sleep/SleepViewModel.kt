package com.daymark.app.ui.sleep

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daymark.app.data.ScreeningStore
import com.daymark.app.data.SleepRepository
import com.daymark.app.data.entity.SleepLog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class SleepViewModel @Inject constructor(
    private val store: ScreeningStore,
    sleepRepository: SleepRepository,
) : ViewModel() {

    private val _results = MutableStateFlow<Map<String, ScreeningStore.Result>>(emptyMap())
    val results: StateFlow<Map<String, ScreeningStore.Result>> = _results.asStateFlow()

    val logs: StateFlow<List<SleepLog>> = sleepRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init { refresh() }

    /** Re-read the latest stored result for each screener (call when the screen is shown). */
    fun refresh() {
        _results.value = SleepScreeners.all
            .mapNotNull { s -> store.get(s.key)?.let { s.key to it } }
            .toMap()
    }
}
