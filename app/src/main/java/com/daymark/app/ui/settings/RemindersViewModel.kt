package com.daymark.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daymark.app.data.ReminderRepository
import com.daymark.app.data.entity.Reminder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RemindersViewModel @Inject constructor(
    private val repository: ReminderRepository,
) : ViewModel() {

    val reminders: StateFlow<List<Reminder>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun add(hour: Int, minute: Int, label: String) {
        viewModelScope.launch { repository.add(hour, minute, label.trim()) }
    }

    fun update(reminder: Reminder) {
        viewModelScope.launch { repository.update(reminder) }
    }

    fun setEnabled(reminder: Reminder, enabled: Boolean) = update(reminder.copy(enabled = enabled))

    fun delete(reminder: Reminder) {
        viewModelScope.launch { repository.delete(reminder) }
    }
}
