package com.daymark.app.ui.settings

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daymark.app.util.DateUtils
import java.time.LocalDateTime
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onManageActivities: () -> Unit,
    onManageGoals: () -> Unit,
    onShowMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as? androidx.fragment.app.FragmentActivity

    LaunchedEffect(Unit) {
        viewModel.messages.collect { onShowMessage(it) }
    }

    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> viewModel.setReminderEnabled(granted) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> uri?.let(viewModel::exportTo) }

    var pendingImport by remember { mutableStateOf<android.net.Uri?>(null) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> pendingImport = uri }

    val csvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri -> uri?.let(viewModel::exportCsvTo) }

    var pdfOptions by remember { mutableStateOf<com.daymark.app.export.PdfExportOptions?>(null) }
    val pdfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf"),
    ) { uri -> val o = pdfOptions; if (uri != null && o != null) viewModel.exportPdfTo(uri, o) }

    var showTimePicker by remember { mutableStateOf(false) }
    var showPinDialog by remember { mutableStateOf(false) }
    var showPdfDialog by remember { mutableStateOf(false) }
    var showAutoLockMenu by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
    ) {
        SectionHeader("Reminders")
        val reminderTimeMillis = remember(state.reminderHour, state.reminderMinute) {
            LocalDateTime.now()
                .withHour(state.reminderHour).withMinute(state.reminderMinute)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }
        ListItem(
            headlineContent = { Text("Daily reminder") },
            supportingContent = { Text(if (state.reminderEnabled) "On" else "Off") },
            trailingContent = {
                Switch(
                    checked = state.reminderEnabled,
                    onCheckedChange = { enabled ->
                        if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            viewModel.setReminderEnabled(enabled)
                        }
                    },
                )
            },
        )
        if (state.reminderEnabled) {
            ListItem(
                headlineContent = { Text("Reminder time") },
                supportingContent = { Text(DateUtils.formatTime(reminderTimeMillis)) },
                modifier = Modifier.clickable { showTimePicker = true },
            )
        }

        Divider()
        SectionHeader("Privacy")
        ListItem(
            headlineContent = { Text("App lock (PIN)") },
            supportingContent = { Text(if (state.lockEnabled) "On" else "Off") },
            trailingContent = {
                Switch(
                    checked = state.lockEnabled,
                    onCheckedChange = { enabled ->
                        if (enabled) showPinDialog = true else viewModel.disableLock()
                    },
                )
            },
        )
        if (state.lockEnabled) {
            ListItem(
                headlineContent = { Text("Unlock with biometrics") },
                supportingContent = { Text("Confirm your fingerprint/face to turn this on") },
                trailingContent = {
                    Switch(
                        checked = state.biometricEnabled,
                        onCheckedChange = { enable ->
                            if (!enable) {
                                viewModel.setBiometricEnabled(false)
                            } else if (activity == null) {
                                onShowMessage("Biometrics unavailable")
                            } else if (!com.daymark.app.security.BiometricHelper.canAuthenticate(activity)) {
                                onShowMessage("No biometrics enrolled on this device")
                            } else {
                                // Only enable after a successful biometric check, so we know it works.
                                com.daymark.app.security.BiometricHelper.prompt(
                                    activity = activity,
                                    onSuccess = { viewModel.setBiometricEnabled(true) },
                                    onError = { onShowMessage("Biometric check failed — not enabled") },
                                )
                            }
                        },
                    )
                },
            )
            ListItem(
                headlineContent = { Text("Auto-lock") },
                supportingContent = { Text(autoLockLabel(state.autoLockTimeoutMinutes)) },
                trailingContent = {
                    androidx.compose.foundation.layout.Box {
                        TextButton(onClick = { showAutoLockMenu = true }) {
                            Text(autoLockLabel(state.autoLockTimeoutMinutes))
                        }
                        DropdownMenu(
                            expanded = showAutoLockMenu,
                            onDismissRequest = { showAutoLockMenu = false },
                        ) {
                            AUTO_LOCK_OPTIONS.forEach { minutes ->
                                DropdownMenuItem(
                                    text = { Text(autoLockLabel(minutes)) },
                                    onClick = {
                                        viewModel.setAutoLockTimeout(minutes)
                                        showAutoLockMenu = false
                                    },
                                )
                            }
                        }
                    }
                },
            )
        }

        Divider()
        SectionHeader("Data")
        ListItem(
            headlineContent = { Text("Manage activities") },
            modifier = Modifier.clickable { onManageActivities() },
        )
        ListItem(
            headlineContent = { Text("Goals") },
            supportingContent = { Text("Weekly habit goals") },
            modifier = Modifier.clickable { onManageGoals() },
        )
        ListItem(
            headlineContent = { Text("Export backup") },
            supportingContent = { Text("Unencrypted JSON — keep it somewhere safe") },
            modifier = Modifier.clickable {
                viewModel.prepareForFilePicker(); exportLauncher.launch("daymark-backup.json")
            },
        )
        ListItem(
            headlineContent = { Text("Restore backup") },
            supportingContent = { Text("Replace or merge from a JSON file") },
            modifier = Modifier.clickable {
                viewModel.prepareForFilePicker(); importLauncher.launch(arrayOf("application/json", "text/*"))
            },
        )
        ListItem(
            headlineContent = { Text("Export as CSV") },
            supportingContent = { Text("Unencrypted spreadsheet of all entries") },
            modifier = Modifier.clickable {
                viewModel.prepareForFilePicker(); csvLauncher.launch("daymark-entries.csv")
            },
        )
        ListItem(
            headlineContent = { Text("Export PDF for therapist") },
            supportingContent = { Text("A printable mood report with an authenticity stamp") },
            modifier = Modifier.clickable { showPdfDialog = true },
        )

        Divider()
        SectionHeader("Appearance")
        ListItem(
            headlineContent = { Text("Dynamic color") },
            supportingContent = { Text("Use wallpaper-based colors (Android 12+)") },
            trailingContent = {
                Switch(checked = state.dynamicColor, onCheckedChange = viewModel::setDynamicColor)
            },
        )

        Divider()
        SectionHeader("About")
        ListItem(
            headlineContent = { Text("Daymark") },
            supportingContent = { Text("Open-source mood tracker · all data stays on your device") },
        )
    }

    if (showTimePicker) {
        val tpState = rememberTimePickerState(
            initialHour = state.reminderHour,
            initialMinute = state.reminderMinute,
            is24Hour = false,
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setReminderTime(tpState.hour, tpState.minute)
                    showTimePicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showTimePicker = false }) { Text("Cancel") } },
            text = { TimePicker(state = tpState) },
        )
    }

    if (showPinDialog) {
        PinDialog(
            onDismiss = { showPinDialog = false },
            onConfirm = { pin ->
                viewModel.setPin(pin)
                showPinDialog = false
            },
        )
    }

    if (showPdfDialog) {
        PdfOptionsDialog(
            onDismiss = { showPdfDialog = false },
            onExport = { options ->
                pdfOptions = options
                showPdfDialog = false
                viewModel.prepareForFilePicker()
                pdfLauncher.launch("daymark-report.pdf")
            },
        )
    }

    pendingImport?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("Restore backup") },
            text = { Text("Replace all current data, or merge the backup's entries alongside what you have?") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.importFrom(uri, com.daymark.app.backup.BackupManager.ImportMode.MERGE)
                    pendingImport = null
                }) { Text("Merge") }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.importFrom(uri, com.daymark.app.backup.BackupManager.ImportMode.REPLACE)
                    pendingImport = null
                }) { Text("Replace all") }
            },
        )
    }
}

@Composable
private fun PinDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val valid = pin.length in 3..8 && pin == confirm

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set a PIN") },
        text = {
            Column {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { if (it.all(Char::isDigit) && it.length <= 8) pin = it },
                    label = { Text("PIN (3–8 digits, 4 recommended)") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { if (it.all(Char::isDigit) && it.length <= 8) confirm = it },
                    label = { Text("Confirm PIN") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onConfirm(pin) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PdfOptionsDialog(
    onDismiss: () -> Unit,
    onExport: (com.daymark.app.export.PdfExportOptions) -> Unit,
) {
    var days by remember { mutableStateOf(90) } // 0 = all time
    var notes by remember { mutableStateOf(true) }
    var charts by remember { mutableStateOf(true) }
    var journal by remember { mutableStateOf(false) }
    val ranges = listOf(30 to "Last 30 days", 90 to "Last 90 days", 365 to "Last 12 months", 0 to "All time")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export PDF report") },
        text = {
            Column {
                Text("Date range", style = MaterialTheme.typography.labelLarge)
                ranges.forEach { (d, label) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { days = d },
                    ) {
                        RadioButton(selected = days == d, onClick = { days = d })
                        Text(label)
                    }
                }
                Spacer(Modifier.height(8.dp))
                ToggleRow("Include notes", notes) { notes = it }
                ToggleRow("Include charts", charts) { charts = it }
                ToggleRow("Include journal entries", journal) { journal = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val now = System.currentTimeMillis()
                val from = if (days == 0) 0L else now - days.toLong() * 86_400_000L
                val label = ranges.first { it.first == days }.second
                onExport(
                    com.daymark.app.export.PdfExportOptions(
                        fromMillis = from,
                        toMillis = now,
                        rangeLabel = label,
                        includeNotes = notes,
                        includeCharts = charts,
                        includeJournal = journal,
                    ),
                )
            }) { Text("Export") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        letterSpacing = 1.sp,
        color = MaterialTheme.colorScheme.tertiary,
        modifier = Modifier.padding(start = 18.dp, top = 18.dp, bottom = 6.dp),
    )
}

private val AUTO_LOCK_OPTIONS = listOf(0, 1, 5, 15)

private fun autoLockLabel(minutes: Int): String = when (minutes) {
    0 -> "Immediately"
    1 -> "After 1 minute"
    else -> "After $minutes minutes"
}
