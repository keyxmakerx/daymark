package com.daymark.app.ui.support

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daymark.app.data.ActivityRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class SupportViewModel @Inject constructor(
    activityRepository: ActivityRepository,
) : ViewModel() {

    /** One of the user's own activities, suggested for a tiny behavioral-activation step. */
    val suggestedActivity: StateFlow<String?> = activityRepository.observeActive()
        .map { list -> list.randomOrNull()?.name }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
