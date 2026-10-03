package com.daymark.app.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daymark.app.data.CrisisStore
import com.daymark.app.data.ReminderRepository
import com.daymark.app.data.SettingsRepository
import com.daymark.app.notifications.ReminderScheduler
import com.daymark.app.security.PinManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val reminderRepository: ReminderRepository,
    private val pinManager: PinManager,
    private val reminderScheduler: ReminderScheduler,
    private val crisisStore: CrisisStore,
) : ViewModel() {

    /** [keep] is the person's answer to whether reminders may ease off (`KeepTimesChoice`). */
    fun enableReminder(hour: Int, minute: Int, keep: Boolean) {
        reminderScheduler.setKeepAsSet(keep)
        // The legacy-migration path is now a no-op concern; create a real reminder row.
        settings.legacyReminderMigrated = true
        viewModelScope.launch { reminderRepository.add(hour, minute) }
    }

    /** The crisis line the crisis screen shows, which starts as a US one until the person changes it. */
    fun crisisLine(): CrisisStore.Resource = crisisStore.get()

    /** Saves the person's own crisis line. Blank fields are never saved: the crisis screen must always show one. */
    fun saveCrisisLine(label: String, contact: String) {
        if (label.isBlank() || contact.isBlank()) return
        crisisStore.save(label, contact)
    }

    /** False, changing nothing, for a PIN `PinPolicy` does not accept. See SettingsViewModel. */
    fun setPin(pin: String): Boolean {
        if (!pinManager.setChosenPin(pin)) return false
        settings.lockEnabled = true
        return true
    }

    fun complete() {
        settings.onboardingComplete = true
    }
}
