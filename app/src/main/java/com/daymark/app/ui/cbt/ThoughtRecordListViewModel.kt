package com.daymark.app.ui.cbt

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daymark.app.data.ThoughtRecordRepository
import com.daymark.app.data.entity.ThoughtRecord
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class ThoughtRecordListViewModel @Inject constructor(
    repository: ThoughtRecordRepository,
) : ViewModel() {
    val records: StateFlow<List<ThoughtRecord>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
