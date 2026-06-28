package com.daymark.app.ui.activation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daymark.app.data.ReminderRepository
import com.daymark.app.data.TrackerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BehavioralActivationViewModel @Inject constructor(
    private val trackerRepository: TrackerRepository,
    private val reminderRepository: ReminderRepository,
) : ViewModel() {

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** Schedules a one-off-style daily reminder nudging the user to do [activity]. */
    fun planReminder(activity: String, hour: Int, minute: Int) {
        if (activity.isBlank()) return
        viewModelScope.launch {
            reminderRepository.add(hour, minute, "Try: ${activity.trim()}")
            _messages.tryEmit("Reminder set")
        }
    }

    /** Logs how an activity felt to the Enjoyment and Mastery trackers (auto-created). */
    fun logHowItWent(enjoyment: Int, mastery: Int) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val e = trackerRepository.findOrCreateScale(BehavioralActivation.ENJOYMENT_TRACKER)
            val m = trackerRepository.findOrCreateScale(BehavioralActivation.MASTERY_TRACKER)
            trackerRepository.log(e, enjoyment.toDouble(), now)
            trackerRepository.log(m, mastery.toDouble(), now)
            _messages.tryEmit("Logged — nice work")
        }
    }
}
