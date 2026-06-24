package com.daylie.app.ui.lock

import androidx.lifecycle.ViewModel
import com.daylie.app.data.SettingsRepository
import com.daylie.app.security.PinManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class LockViewModel @Inject constructor(
    private val pinManager: PinManager,
    private val settings: SettingsRepository,
) : ViewModel() {

    val lockEnabled: Boolean get() = settings.lockEnabled && pinManager.isPinSet
    val biometricEnabled: Boolean get() = settings.biometricEnabled

    fun verify(pin: String): Boolean = pinManager.verify(pin)
}
