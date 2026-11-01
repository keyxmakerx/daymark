package com.daymark.app.ui.support

import androidx.lifecycle.ViewModel
import com.daymark.app.data.SettingsRepository
import com.daymark.app.stats.SupportOfferFrequency
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class GentleSupportViewModel @Inject constructor(
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _enabled = MutableStateFlow(settings.gentleSupportEnabled)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _frequency = MutableStateFlow(settings.supportOfferFrequency)
    val frequency: StateFlow<SupportOfferFrequency> = _frequency.asStateFlow()

    private val _calmActivities = MutableStateFlow(settings.calmActivitiesEnabled)
    val calmActivities: StateFlow<Boolean> = _calmActivities.asStateFlow()

    fun setCalmActivities(value: Boolean) {
        settings.calmActivitiesEnabled = value
        _calmActivities.value = value
    }

    fun setEnabled(value: Boolean) {
        settings.gentleSupportEnabled = value
        _enabled.value = value
    }

    /**
     * Changing how often the app may interrupt also clears the "last interrupted" stamp. Otherwise
     * someone who has just been interrupted and reaches for this setting to ask for *more* would
     * still be held off by the old ration, with nothing on screen explaining the wait.
     */
    fun setFrequency(value: SupportOfferFrequency) {
        settings.supportOfferFrequency = value
        settings.supportOfferLastShownAt = 0L
        _frequency.value = value
    }
}
