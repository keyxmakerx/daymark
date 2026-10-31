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
    private val settings: SettingsRepository,
) : ViewModel() {

    /** The check-in's own choices (`stats/CheckInQuestions.kt`, #540), read once and written through. */
    data class CheckIn(val questionsAllowed: Int, val sleepQuestion: Boolean, val eveningFromSky: Boolean)

    private val _checkIn = MutableStateFlow(
        CheckIn(settings.checkInQuestionsAllowed, settings.sleepQuestionOn, settings.eveningFromSky),
    )
    val checkIn: StateFlow<CheckIn> = _checkIn.asStateFlow()

    fun setQuestionsAllowed(n: Int) {
        settings.checkInQuestionsAllowed = n
        _checkIn.value = _checkIn.value.copy(questionsAllowed = settings.checkInQuestionsAllowed)
    }

    fun setSleepQuestion(on: Boolean) {
        settings.sleepQuestionOn = on
        _checkIn.value = _checkIn.value.copy(sleepQuestion = on)
    }

    fun setEveningFromSky(on: Boolean) {
        settings.eveningFromSky = on
        _checkIn.value = _checkIn.value.copy(eveningFromSky = on)
    }

    private val _keepAsSet = MutableStateFlow(settings.remindersKeepAsSet.takeIf { settings.remindersKeepAnswered })

    /**
     * Whether the reminders keep to the person's times rather than easing off (`KeepTimesChoice`),
     * or null while the question has never been answered: nothing is shown as chosen that the
     * person did not choose (#484).
     */
    val keepAsSet: StateFlow<Boolean?> = _keepAsSet.asStateFlow()

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
