package com.daymark.app.ui.settings

import com.daymark.app.ui.components.SentenceCaps

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daymark.app.data.entity.Reminder
import com.daymark.app.ui.components.PaperSurface
import com.daymark.app.util.DateUtils
import java.time.LocalDateTime
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemindersScreen(
    onBack: () -> Unit,
    viewModel: RemindersViewModel = hiltViewModel(),
) {
    val reminders by viewModel.reminders.collectAsStateWithLifecycle()

    // Edit target: null = none, a Reminder = editing, Reminder(id=0) = adding a new one.
    var editing by remember { mutableStateOf<Reminder?>(null) }

    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { editing = Reminder(hour = 20, minute = 0) }

    fun startAdd() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            editing = Reminder(hour = 20, minute = 0)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Reminders") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { startAdd() }) {
                Icon(Icons.Filled.Add, contentDescription = "Add reminder")
            }
        },
    ) { padding ->
        if (reminders.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            ) {
                Text("No reminders yet", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Add a daily nudge to check in. Tap a reminder to log straight from the notification.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(reminders, key = { it.id }) { reminder ->
                    ReminderRow(
                        reminder = reminder,
                        onToggle = { viewModel.setEnabled(reminder, it) },
                        onEdit = { editing = reminder },
                        onDelete = { viewModel.delete(reminder) },
                    )
                }
            }
        }
    }

    editing?.let { target ->
        ReminderDialog(
            initial = target,
            onDismiss = { editing = null },
            onConfirm = { hour, minute, label ->
                if (target.id == 0L) viewModel.add(hour, minute, label)
                else viewModel.update(target.copy(hour = hour, minute = minute, label = label.trim()))
                editing = null
            },
        )
    }
}

@Composable
private fun ReminderRow(
    reminder: Reminder,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val timeMillis = remember(reminder.hour, reminder.minute) {
        LocalDateTime.now().withHour(reminder.hour).withMinute(reminder.minute)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
    PaperSurface(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { onEdit() }.padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(DateUtils.formatTime(timeMillis), style = MaterialTheme.typography.titleLarge)
                if (reminder.label.isNotBlank()) {
                    Text(
                        reminder.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Switch(checked = reminder.enabled, onCheckedChange = onToggle)
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete reminder")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReminderDialog(
    initial: Reminder,
    onDismiss: () -> Unit,
    onConfirm: (hour: Int, minute: Int, label: String) -> Unit,
) {
    val tpState = rememberTimePickerState(
        initialHour = initial.hour,
        initialMinute = initial.minute,
        is24Hour = false,
    )
    var label by remember { mutableStateOf(initial.label) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(tpState.hour, tpState.minute, label) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                TimePicker(state = tpState)
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    keyboardOptions = SentenceCaps,
                    label = { Text("Label (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    )
}
