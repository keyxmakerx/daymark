package com.daymark.app.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daymark.app.data.ReminderRepository
import com.daymark.app.data.SettingsRepository
import com.daymark.app.security.PinManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val reminderRepository: ReminderRepository,
    private val pinManager: PinManager,
) : ViewModel() {

    fun enableReminder(hour: Int, minute: Int) {
        // The legacy-migration path is now a no-op concern; create a real reminder row.
        settings.legacyReminderMigrated = true
        viewModelScope.launch { reminderRepository.add(hour, minute) }
    }

    fun setPin(pin: String) {
        pinManager.setPin(pin)
        settings.lockEnabled = true
    }

    fun complete() {
        settings.onboardingComplete = true
    }
}
