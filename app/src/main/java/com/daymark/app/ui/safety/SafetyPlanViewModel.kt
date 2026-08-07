package com.daymark.app.ui.safety

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daymark.app.data.CrisisStore
import com.daymark.app.data.SafetyPlanRepository
import com.daymark.app.data.entity.SafetyPlanItem
import com.daymark.app.data.entity.SafetyPlanSection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SafetyPlanViewModel @Inject constructor(
    private val repository: SafetyPlanRepository,
    crisisStore: CrisisStore,
) : ViewModel() {

    val plan: StateFlow<Map<SafetyPlanSection, List<SafetyPlanItem>>> =
        repository.observePlan()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /**
     * The owner's own crisis line. Read from [CrisisStore], which already defaults to 988 and is
     * user-editable — so this screen never hardcodes a number, and a non-US user sees their own.
     */
    val crisis: CrisisStore.Resource = crisisStore.get()

    fun add(section: SafetyPlanSection, text: String, detail: String = "") {
        viewModelScope.launch { repository.add(section, text, detail) }
    }

    fun remove(item: SafetyPlanItem) {
        viewModelScope.launch { repository.delete(item) }
    }
}
