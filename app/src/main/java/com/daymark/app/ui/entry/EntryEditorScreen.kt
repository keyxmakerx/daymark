package com.daymark.app.ui.entry

import com.daymark.app.ui.components.SentenceCaps

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daymark.app.ui.theme.moodLabels
import com.daymark.app.model.Mood
import com.daymark.app.ui.components.ActivityChip
import com.daymark.app.ui.components.EntryPhoto
import com.daymark.app.ui.components.MoodFace
import com.daymark.app.util.DateUtils
import java.time.LocalDateTime
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EntryEditorScreen(
    onDone: () -> Unit,
    onOfferSupport: () -> Unit = {},
    viewModel: EntryEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state.saved) {
        if (state.saved) {
            if (state.offerSupport) onOfferSupport() else onDone()
        }
    }

    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let(viewModel::setPhoto) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.isEditing) "Edit entry" else "Add entry") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (state.isEditing) {
                        IconButton(onClick = viewModel::delete) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                "How are you?",
                style = MaterialTheme.typography.headlineSmall,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                val view = androidx.compose.ui.platform.LocalView.current
                Mood.ascending.forEach { mood ->
                    val selected = state.moodLevel == mood.level
                    Column(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .clickable {
                                view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                                viewModel.setMood(mood.level)
                            }
                            .padding(horizontal = 6.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        MoodFace(mood = mood, size = 52, selected = selected)
                        Text(
                            text = MaterialTheme.moodLabels.forLevel(mood.level),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (selected) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
            }

            // Date & time
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { showDatePicker = true }) {
                    Text(DateUtils.formatDate(state.dateTime))
                }
                OutlinedButton(onClick = { showTimePicker = true }) {
                    Text(DateUtils.formatTime(state.dateTime))
                }
            }

            Text("What have you been up to?", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.activities.forEach { activity ->
                    ActivityChip(
                        activity = activity,
                        selected = state.selectedActivityIds.contains(activity.id),
                        onToggle = { viewModel.toggleActivity(activity.id) },
                    )
                }
            }

            Text("Why do you feel this way?", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = state.note,
                onValueChange = viewModel::setNote,
                keyboardOptions = SentenceCaps,
                placeholder = { Text("What happened, what's on your mind…") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 4,
            )

            Text("Photo", style = MaterialTheme.typography.titleMedium)
            val photoPath = state.photoPath
            if (photoPath != null) {
                Box {
                    EntryPhoto(photoPath = photoPath, size = 140.dp, cornerRadius = 14.dp)
                    FilledIconButton(
                        onClick = viewModel::clearPhoto,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .size(30.dp)
                            .clip(CircleShape),
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Remove photo",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            } else {
                OutlinedButton(onClick = {
                    viewModel.prepareForPicker()
                    photoPicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                }) {
                    Icon(Icons.Filled.AddAPhoto, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("  Add photo")
                }
            }

            Button(
                onClick = viewModel::save,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save")
            }
        }
    }

    if (showDatePicker) {
        val dpState = rememberDatePickerState(initialSelectedDateMillis = state.dateTime)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    dpState.selectedDateMillis?.let { picked ->
                        viewModel.setDateTime(mergeDate(picked, state.dateTime))
                    }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            },
        ) {
            DatePicker(state = dpState)
        }
    }

    if (showTimePicker) {
        val current = DateUtils.toLocalDateTime(state.dateTime)
        val tpState = rememberTimePickerState(
            initialHour = current.hour,
            initialMinute = current.minute,
            is24Hour = false,
        )
        DatePickerDialog(
            onDismissRequest = { showTimePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setDateTime(mergeTime(state.dateTime, tpState.hour, tpState.minute))
                    showTimePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) { Text("Cancel") }
            },
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                TimePicker(state = tpState)
            }
        }
    }
}

/** Replaces the date portion of [originalMillis] with the date from [dateMillis].
 *  Material's DatePicker reports the selection as UTC midnight, so read it in UTC. */
private fun mergeDate(dateMillis: Long, originalMillis: Long): Long {
    val time = DateUtils.toLocalDateTime(originalMillis)
    val date = java.time.Instant.ofEpochMilli(dateMillis)
        .atZone(java.time.ZoneOffset.UTC).toLocalDate()
    val merged = LocalDateTime.of(date, time.toLocalTime())
    return merged.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
}

/** Replaces the time portion of [originalMillis] with [hour]:[minute]. */
private fun mergeTime(originalMillis: Long, hour: Int, minute: Int): Long {
    val dt = DateUtils.toLocalDateTime(originalMillis)
        .withHour(hour).withMinute(minute).withSecond(0).withNano(0)
    return dt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
}
