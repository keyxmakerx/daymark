package com.daylie.app.ui.settings

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daylie.app.util.DateUtils
import java.time.LocalDateTime
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onManageActivities: () -> Unit,
    onShowMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.messages.collect { onShowMessage(it) }
    }

    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> viewModel.setReminderEnabled(granted) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> uri?.let(viewModel::exportTo) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::importFrom) }

    var showTimePicker by remember { mutableStateOf(false) }
    var showPinDialog by remember { mutableStateOf(false) }

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
                trailingContent = {
                    Switch(
                        checked = state.biometricEnabled,
                        onCheckedChange = viewModel::setBiometricEnabled,
                    )
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
            headlineContent = { Text("Export backup") },
            supportingContent = { Text("Save all data to a JSON file") },
            modifier = Modifier.clickable { exportLauncher.launch("daylie-backup.json") },
        )
        ListItem(
            headlineContent = { Text("Restore backup") },
            supportingContent = { Text("Replace all data from a JSON file") },
            modifier = Modifier.clickable { importLauncher.launch(arrayOf("application/json", "text/*")) },
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
            headlineContent = { Text("Daylie") },
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
}

@Composable
private fun PinDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val valid = pin.length in 4..8 && pin == confirm

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set a PIN") },
        text = {
            Column {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { if (it.all(Char::isDigit)) pin = it },
                    label = { Text("PIN (4–8 digits)") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { if (it.all(Char::isDigit)) confirm = it },
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
private fun SectionHeader(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        letterSpacing = 1.sp,
        color = MaterialTheme.colorScheme.tertiary,
        modifier = Modifier.padding(start = 18.dp, top = 18.dp, bottom = 6.dp),
    )
}
