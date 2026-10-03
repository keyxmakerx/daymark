package com.daymark.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daymark.app.data.ReminderRepository
import com.daymark.app.data.SettingsRepository
import com.daymark.app.data.entity.Reminder
import com.daymark.app.notifications.ReminderScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RemindersViewModel @Inject constructor(
    private val repository: ReminderRepository,
    private val scheduler: ReminderScheduler,
    settings: SettingsRepository,
) : ViewModel() {

    private val _keepAsSet = MutableStateFlow(settings.remindersKeepAsSet)

    /** Whether the reminders keep to the person's times rather than easing off (`KeepTimesChoice`). */
    val keepAsSet: StateFlow<Boolean> = _keepAsSet.asStateFlow()

    fun setKeepAsSet(keep: Boolean) {
        scheduler.setKeepAsSet(keep)
        _keepAsSet.value = keep
    }

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
