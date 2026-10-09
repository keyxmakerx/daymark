package com.daymark.app.ui.settings


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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import com.daymark.app.ui.components.DaymarkSwitch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daymark.app.data.entity.Reminder
import com.daymark.app.notifications.NotificationPermission
import com.daymark.app.ui.components.KeepTimesChoice
import com.daymark.app.ui.components.PaperSurface
import com.daymark.app.ui.components.SentenceCaps
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
    val keepAsSet by viewModel.keepAsSet.collectAsStateWithLifecycle()

    // Edit target: null = none, a Reminder = editing, Reminder(id=0) = adding a new one.
    var editing by remember { mutableStateOf<Reminder?>(null) }

    // Whether Daymark's notifications will actually show. No stored flag — read live so it
    // cannot go stale when someone flips this in system settings and comes back.
    val context = LocalContext.current
    var notificationsEnabled by remember { mutableStateOf(NotificationPermission.areEnabled(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationsEnabled = NotificationPermission.areEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

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
                Text("No reminders", style = MaterialTheme.typography.titleMedium)
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
                // App-wide, not per row — rows below are drawn exactly as when permission is on.
                if (!notificationsEnabled) {
                    item {
                        NotificationsOffNote(
                            onOpenSettings = {
                                context.startActivity(NotificationPermission.settingsIntent(context))
                            },
                        )
                    }
                }
                item {
                    PaperSurface(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("If reminders go unanswered", style = MaterialTheme.typography.titleSmall)
                            KeepTimesChoice(keep = keepAsSet, onChange = viewModel::setKeepAsSet)
                        }
                    }
                }
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
        // Setting the first reminder asks whether reminders may ease off; Save waits for the answer.
        val asks = target.id == 0L && reminders.none { it.enabled }
        ReminderDialog(
            initial = target,
            asksKeep = asks,
            onDismiss = { editing = null },
            onConfirm = { hour, minute, label, keep ->
                if (keep != null) viewModel.setKeepAsSet(keep)
                if (target.id == 0L) viewModel.add(hour, minute, label)
                else viewModel.update(target.copy(hour = hour, minute = minute, label = label.trim()))
                editing = null
            },
        )
    }
}

/**
 * The one note that stands in for a whole row of disabled toggles: permission is app-wide, so it
 * is said once, here, rather than on every row. No icon, no colour — this is a setting the person
 * chose, not an error, and words carry that on their own.
 */
@Composable
private fun NotificationsOffNote(onOpenSettings: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            "Notifications are off for Daymark. Reminders are saved but won't show.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(
            onClick = onOpenSettings,
            contentPadding = PaddingValues(vertical = 8.dp),
        ) { Text("Open notification settings") }
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.padding(top = 4.dp),
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
            DaymarkSwitch(checked = reminder.enabled, onCheckedChange = onToggle)
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
    asksKeep: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (hour: Int, minute: Int, label: String, keep: Boolean?) -> Unit,
) {
    val tpState = rememberTimePickerState(
        initialHour = initial.hour,
        initialMinute = initial.minute,
        is24Hour = false,
    )
    var label by remember { mutableStateOf(initial.label) }
    var keep by remember { mutableStateOf<Boolean?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { onConfirm(tpState.hour, tpState.minute, label, if (asksKeep) keep else null) },
                enabled = !asksKeep || keep != null,
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = {
            // Scrolls: the first reminder also asks whether reminders may ease off, which can
            // outgrow a short screen.
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                TimePicker(state = tpState)
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    keyboardOptions = SentenceCaps,
                    label = { Text("Label (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (asksKeep) {
                    Text("If reminders go unanswered", style = MaterialTheme.typography.titleSmall)
                    KeepTimesChoice(keep = keep, onChange = { keep = it })
                }
            }
        },
    )
}
