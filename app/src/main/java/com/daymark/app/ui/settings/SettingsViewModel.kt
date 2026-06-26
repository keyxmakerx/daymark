package com.daymark.app.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daymark.app.backup.BackupManager
import com.daymark.app.data.SettingsRepository
import com.daymark.app.export.PdfExportOptions
import com.daymark.app.export.PdfReportGenerator
import com.daymark.app.export.ReportDataBuilder
import com.daymark.app.notifications.ReminderScheduler
import com.daymark.app.security.AutoLockController
import com.daymark.app.security.PinManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class SettingsUiState(
    val reminderEnabled: Boolean = false,
    val reminderHour: Int = 20,
    val reminderMinute: Int = 0,
    val lockEnabled: Boolean = false,
    val hasPin: Boolean = false,
    val biometricEnabled: Boolean = false,
    val dynamicColor: Boolean = true,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val reminderScheduler: ReminderScheduler,
    private val pinManager: PinManager,
    private val backupManager: BackupManager,
    private val reportDataBuilder: ReportDataBuilder,
    private val pdfReportGenerator: PdfReportGenerator,
    private val autoLock: AutoLockController,
) : ViewModel() {

    /** Call right before opening a file picker so returning doesn't trigger the app lock. */
    fun prepareForFilePicker() = autoLock.suppressNextBackgroundLock()

    private val _uiState = MutableStateFlow(readState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private fun readState() = SettingsUiState(
        reminderEnabled = settings.reminderEnabled,
        reminderHour = settings.reminderHour,
        reminderMinute = settings.reminderMinute,
        lockEnabled = settings.lockEnabled,
        hasPin = pinManager.isPinSet,
        biometricEnabled = settings.biometricEnabled,
        dynamicColor = settings.dynamicColor,
    )

    private fun refresh() = _uiState.update { readState() }

    // --- Reminders ---
    fun setReminderEnabled(enabled: Boolean) {
        settings.reminderEnabled = enabled
        if (enabled) reminderScheduler.schedule(settings.reminderHour, settings.reminderMinute)
        else reminderScheduler.cancel()
        refresh()
    }

    fun setReminderTime(hour: Int, minute: Int) {
        settings.reminderHour = hour
        settings.reminderMinute = minute
        if (settings.reminderEnabled) reminderScheduler.schedule(hour, minute)
        refresh()
    }

    // --- App lock ---
    fun setPin(pin: String) {
        pinManager.setPin(pin)
        settings.lockEnabled = true
        refresh()
    }

    fun disableLock() {
        settings.lockEnabled = false
        settings.biometricEnabled = false
        pinManager.clearPin()
        refresh()
    }

    fun setBiometricEnabled(enabled: Boolean) {
        settings.biometricEnabled = enabled
        refresh()
    }

    // --- Appearance ---
    fun setDynamicColor(enabled: Boolean) {
        settings.dynamicColor = enabled
        refresh()
    }

    // --- Backup / restore ---
    fun exportTo(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                val json = backupManager.exportToJson(System.currentTimeMillis())
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                }
            }.onSuccess { _messages.tryEmit("Backup exported") }
                .onFailure { _messages.tryEmit("Export failed: ${it.message}") }
        }
    }

    fun exportPdfTo(uri: Uri, options: PdfExportOptions) {
        viewModelScope.launch {
            runCatching {
                val data = reportDataBuilder.build(options, System.currentTimeMillis())
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { pdfReportGenerator.generate(data, options, it) }
                        ?: error("Could not open file")
                }
            }.onSuccess { _messages.tryEmit("PDF report exported") }
                .onFailure { _messages.tryEmit("PDF export failed: ${it.message}") }
        }
    }

    fun exportCsvTo(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                val csv = backupManager.exportEntriesCsv()
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray()) }
                }
            }.onSuccess { _messages.tryEmit("CSV exported") }
                .onFailure { _messages.tryEmit("CSV export failed: ${it.message}") }
        }
    }

    fun importFrom(uri: Uri, mode: BackupManager.ImportMode) {
        viewModelScope.launch {
            runCatching {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                } ?: error("Could not read file")
                backupManager.importFromJson(text, mode)
            }.onSuccess {
                val verb = if (mode == BackupManager.ImportMode.MERGE) "merged" else "restored"
                _messages.tryEmit("Backup $verb")
            }.onFailure { _messages.tryEmit("Import failed: ${it.message}") }
        }
    }
}
