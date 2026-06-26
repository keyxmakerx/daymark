package com.daymark.app.ui.onboarding

import androidx.lifecycle.ViewModel
import com.daymark.app.data.SettingsRepository
import com.daymark.app.notifications.ReminderScheduler
import com.daymark.app.security.PinManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val reminderScheduler: ReminderScheduler,
    private val pinManager: PinManager,
) : ViewModel() {

    fun enableReminder(hour: Int, minute: Int) {
        settings.reminderHour = hour
        settings.reminderMinute = minute
        settings.reminderEnabled = true
        reminderScheduler.schedule(hour, minute)
    }

    fun setPin(pin: String) {
        pinManager.setPin(pin)
        settings.lockEnabled = true
    }

    fun complete() {
        settings.onboardingComplete = true
    }
}
