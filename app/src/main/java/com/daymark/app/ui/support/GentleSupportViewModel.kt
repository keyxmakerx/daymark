package com.daymark.app.ui.support

import androidx.lifecycle.ViewModel
import com.daymark.app.data.SettingsRepository
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

    fun setEnabled(value: Boolean) {
        settings.gentleSupportEnabled = value
        _enabled.value = value
    }
}
