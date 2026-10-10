package com.daymark.app.ui.trackers

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import com.daymark.app.ui.components.DaymarkSwitch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.daymark.app.data.entity.Tracker
import com.daymark.app.data.entity.rhythmChoice
import com.daymark.app.stats.TrackerRhythm.Rhythm
import com.daymark.app.ui.components.KeepTimesChoice
import com.daymark.app.ui.components.KeepTimesDialog
import com.daymark.app.ui.components.PaperSurface
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** How many check-ins a few-times-a-day tracker can spread over its hours, from this screen. */
private val FEW_COUNTS = 2..6

/**
 * When this tracker asks to be logged, and its quick-log notification. Everything here is the
 * person's own choice and starts off: a new tracker asks nothing (`stats/TrackerRhythm.kt`).
 * Whether a due check-in is actually posted is the rules engine's call, which only ever asks less.
 */
@Composable
fun TrackerCheckInsCard(tracker: Tracker, onChange: (Tracker) -> Unit) {
    val context = LocalContext.current
    // Asked for at the moment the person switches on something that notifies, never before.
    var pending by remember { mutableStateOf<Tracker?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pending?.let(onChange)
        pending = null
    }
    fun save(next: Tracker) {
        val notifies = next.rhythmChoice.asks || next.quickLog
        if (notifies && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pending = next
            permission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            onChange(next)
        }
    }
    // Switching check-ins on asks first whether they may ease off; nothing changes until answered.
    var asking by remember { mutableStateOf<Tracker?>(null) }
    fun choose(next: Tracker) {
        if (!tracker.rhythmChoice.asks && next.rhythmChoice.asks) asking = next else save(next)
    }

    var picking by remember { mutableStateOf<TimeField?>(null) }
    val rhythm = tracker.rhythmChoice

    PaperSurface(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Check-ins", style = MaterialTheme.typography.titleMedium)
            Column(Modifier.selectableGroup()) {
                RhythmOption(
                    title = "When it happens",
                    detail = "No reminders. Log it whenever it comes up.",
                    selected = rhythm == Rhythm.WHEN_IT_HAPPENS,
                    onSelect = { choose(tracker.copy(rhythm = Rhythm.WHEN_IT_HAPPENS.key)) },
                )
                RhythmOption(
                    title = "Once a day",
                    detail = "At a time you pick.",
                    selected = rhythm == Rhythm.ONCE_A_DAY,
                    onSelect = { choose(tracker.copy(rhythm = Rhythm.ONCE_A_DAY.key)) },
                )
                if (rhythm == Rhythm.ONCE_A_DAY) {
                    TimeRow("At", tracker.onceAtMinute) { picking = TimeField.ONCE }
                }
                RhythmOption(
                    title = "A few times a day",
                    detail = "Short check-ins between hours you set, at times that change from day to day.",
                    selected = rhythm == Rhythm.FEW_A_DAY,
                    onSelect = { choose(tracker.copy(rhythm = Rhythm.FEW_A_DAY.key)) },
                )
                if (rhythm == Rhythm.FEW_A_DAY) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("How many", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(
                            onClick = { onChange(tracker.copy(fewCount = tracker.fewCount - 1)) },
                            enabled = tracker.fewCount > FEW_COUNTS.first,
                        ) { Text("Fewer") }
                        Text(tracker.fewCount.coerceIn(FEW_COUNTS).toString(), style = MaterialTheme.typography.titleMedium)
                        OutlinedButton(
                            onClick = { onChange(tracker.copy(fewCount = tracker.fewCount + 1)) },
                            enabled = tracker.fewCount < FEW_COUNTS.last,
                        ) { Text("More") }
                    }
                    TimeRow("From", tracker.windowStart) { picking = TimeField.FROM }
                    TimeRow("Until", tracker.windowEnd) { picking = TimeField.UNTIL }
                }
                RhythmOption(
                    title = "Don't ask",
                    detail = "You log it yourself, from here.",
                    selected = rhythm == Rhythm.DONT_ASK,
                    onSelect = { choose(tracker.copy(rhythm = Rhythm.DONT_ASK.key)) },
                )
            }
            if (rhythm.asks) {
                Text("If check-ins go unanswered", style = MaterialTheme.typography.titleSmall)
                KeepTimesChoice(keep = tracker.keepAsSet, onChange = { onChange(tracker.copy(keepAsSet = it)) })
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Quick-log notification", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "A quiet notification that stays, so logging is one tap away.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DaymarkSwitch(checked = tracker.quickLog, onCheckedChange = { choose(tracker.copy(quickLog = it)) })
            }
        }
    }

    asking?.let { next ->
        KeepTimesDialog(
            onAnswer = { keep ->
                asking = null
                save(next.copy(keepAsSet = keep))
            },
            onDismiss = { asking = null },
        )
    }

    picking?.let { field ->
        val initial = when (field) {
            TimeField.ONCE -> tracker.onceAtMinute
            TimeField.FROM -> tracker.windowStart
            TimeField.UNTIL -> tracker.windowEnd
        }
        TimeDialog(
            initialMinute = initial,
            is24Hour = android.text.format.DateFormat.is24HourFormat(context),
            onDismiss = { picking = null },
            onConfirm = { minute ->
                onChange(
                    when (field) {
                        TimeField.ONCE -> tracker.copy(onceAtMinute = minute)
                        TimeField.FROM -> tracker.copy(windowStart = minute)
                        TimeField.UNTIL -> tracker.copy(windowEnd = minute)
                    },
                )
                picking = null
            },
        )
    }
}

private enum class TimeField { ONCE, FROM, UNTIL }

@Composable
private fun RhythmOption(title: String, detail: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TimeRow(label: String, minuteOfDay: Int, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onClick) { Text(formatMinute(minuteOfDay)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(initialMinute: Int, is24Hour: Boolean, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    val state = rememberTimePickerState(
        initialHour = Math.floorMod(initialMinute, 24 * 60) / 60,
        initialMinute = Math.floorMod(initialMinute, 60),
        is24Hour = is24Hour,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onConfirm(state.hour * 60 + state.minute) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = { TimePicker(state = state) },
    )
}

private fun formatMinute(minuteOfDay: Int): String {
    val m = Math.floorMod(minuteOfDay, 24 * 60)
    return LocalTime.of(m / 60, m % 60).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
}
