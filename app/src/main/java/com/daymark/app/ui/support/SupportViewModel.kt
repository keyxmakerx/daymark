package com.daymark.app.ui.support

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daymark.app.data.ActivityRepository
import com.daymark.app.data.SafetyPlanRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class SupportViewModel @Inject constructor(
    activityRepository: ActivityRepository,
    safetyPlanRepository: SafetyPlanRepository,
) : ViewModel() {

    /** One of the user's own activities, suggested for a tiny behavioral-activation step. */
    val suggestedActivity: StateFlow<String?> = activityRepository.observeActive()
        .map { list -> list.randomOrNull()?.name }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Whether the person has actually written a safety plan.
     *
     * The support screen offers the plan **only if one exists**. Offering it to someone who hasn't
     * written one sends them to a blank page in exactly the moment the plan was supposed to spare
     * them that — the invitation to write one belongs in More, on a steady day, not here.
     */
    val hasSafetyPlan: StateFlow<Boolean> = safetyPlanRepository.observePlan()
        .map { plan -> plan.values.any { it.isNotEmpty() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
}
