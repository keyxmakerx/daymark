package com.daylie.app.ui.activities

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daylie.app.data.ActivityRepository
import com.daylie.app.data.entity.ActivityEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ActivitiesViewModel @Inject constructor(
    private val repository: ActivityRepository,
) : ViewModel() {

    val activities: StateFlow<List<ActivityEntity>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun add(name: String, iconKey: String) {
        if (name.isBlank()) return
        viewModelScope.launch { repository.add(name, iconKey) }
    }

    fun rename(activity: ActivityEntity, name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { repository.update(activity.copy(name = name.trim())) }
    }

    fun setIcon(activity: ActivityEntity, iconKey: String) {
        viewModelScope.launch { repository.update(activity.copy(iconKey = iconKey)) }
    }

    fun toggleArchived(activity: ActivityEntity) {
        viewModelScope.launch { repository.setArchived(activity, !activity.archived) }
    }
}
