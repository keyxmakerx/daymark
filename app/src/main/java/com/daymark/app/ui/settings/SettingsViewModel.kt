package com.daymark.app.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daymark.app.backup.BackupManager
import com.daymark.app.data.JournalEncryptionGate
import com.daymark.app.data.JournalFileState
import com.daymark.app.data.SettingsRepository
import com.daymark.app.export.PdfExportOptions
import com.daymark.app.export.PdfReportGenerator
import com.daymark.app.export.ReportDataBuilder
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
    val reminderCount: Int = 0,
    val lockEnabled: Boolean = false,
    val hasPin: Boolean = false,
    val biometricEnabled: Boolean = false,
    val autoLockTimeoutMinutes: Int = 0,
    val dynamicColor: Boolean = false,
    /**
     * Whether the journal file on THIS phone is actually encrypted.
     *
     * Read off the gate rather than assumed, because a migration that has not succeeded leaves the
     * file plaintext and the app carries on working — so the sentence in Settings would otherwise be
     * a claim the app would like to make rather than the truth about this device. Defaults to false:
     * a screen that has not been told anything says the weaker thing, never the stronger.
     */
    val entriesEncrypted: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val reminderRepository: com.daymark.app.data.ReminderRepository,
    private val pinManager: PinManager,
    private val backupManager: BackupManager,
    private val reportDataBuilder: ReportDataBuilder,
    private val pdfReportGenerator: PdfReportGenerator,
    private val autoLock: AutoLockController,
    private val journalEncryption: JournalEncryptionGate,
) : ViewModel() {

    /** Call right before opening a file picker so returning doesn't trigger the app lock. */
    fun prepareForFilePicker() = autoLock.suppressNextBackgroundLock()

    private val _uiState = MutableStateFlow(readState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    init {
        viewModelScope.launch {
            reminderRepository.observeAll().collect { list ->
                _uiState.update { it.copy(reminderCount = list.size) }
            }
        }
    }

    private fun readState() = SettingsUiState(
        lockEnabled = settings.lockEnabled,
        hasPin = pinManager.isPinSet,
        biometricEnabled = settings.biometricEnabled,
        autoLockTimeoutMinutes = settings.autoLockTimeoutMinutes,
        dynamicColor = settings.dynamicColor,
        // `settled`, not `prepare()`. This runs on the main thread while a settings screen is being
        // built, and prepare() can copy a database. By the time anybody reaches Settings the answer
        // is already cached; if it somehow is not, the screen says the weaker thing.
        entriesEncrypted = journalEncryption.settled == JournalFileState.ENCRYPTED,
    )

    private fun refresh() = _uiState.update {
        readState().copy(reminderCount = it.reminderCount)
    }

    // --- App lock ---
    /**
     * Returns false, and changes nothing at all, for a PIN `PinPolicy` does not accept. The dialog
     * disables its own button too; this is the backstop, so the rule cannot be bypassed by a screen
     * that forgets it.
     */
    fun setPin(pin: String): Boolean {
        if (!pinManager.setChosenPin(pin)) return false
        settings.lockEnabled = true
        refresh()
        return true
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

    fun setAutoLockTimeout(minutes: Int) {
        settings.autoLockTimeoutMinutes = minutes
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
                        ?: error("Could not open file")
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
                        ?: error("Could not open file")
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
