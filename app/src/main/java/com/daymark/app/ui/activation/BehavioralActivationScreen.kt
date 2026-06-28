package com.daymark.app.ui.activation

import com.daymark.app.ui.components.SentenceCaps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.daymark.app.ui.components.PaperSurface
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BehavioralActivationScreen(
    onBack: () -> Unit,
    onShowMessage: (String) -> Unit,
    viewModel: BehavioralActivationViewModel = hiltViewModel(),
) {
    LaunchedEffect(Unit) { viewModel.messages.collect { onShowMessage(it) } }

    var activity by remember { mutableStateOf("") }
    var showTimePicker by remember { mutableStateOf(false) }
    var enjoyment by remember { mutableFloatStateOf(5f) }
    var mastery by remember { mutableFloatStateOf(5f) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Do one thing") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                BehavioralActivation.INTRO,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            PaperSurface(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Pick something small", style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BehavioralActivation.SUGGESTIONS.forEach { s ->
                            FilterChip(
                                selected = activity == s.name,
                                onClick = { activity = s.name },
                                label = { Text(s.name) },
                            )
                        }
                    }
                    OutlinedTextField(
                        value = activity,
                        onValueChange = { activity = it },
                        keyboardOptions = SentenceCaps,
                        label = { Text("…or your own") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedButton(
                        onClick = { showTimePicker = true },
                        enabled = activity.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Remind me to do this") }
                }
            }

            PaperSurface(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("How did it go?", style = MaterialTheme.typography.titleMedium)
                    Text("Enjoyment: ${enjoyment.roundToInt()}", style = MaterialTheme.typography.bodyMedium)
                    Slider(value = enjoyment, onValueChange = { enjoyment = it }, valueRange = 0f..10f, steps = 9)
                    Text("Sense of accomplishment: ${mastery.roundToInt()}", style = MaterialTheme.typography.bodyMedium)
                    Slider(value = mastery, onValueChange = { mastery = it }, valueRange = 0f..10f, steps = 9)
                    Button(
                        onClick = { viewModel.logHowItWent(enjoyment.roundToInt(), mastery.roundToInt()) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Log how it felt") }
                    Text(
                        "Tracked under Enjoyment & Mastery, so you can see them against your mood.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (showTimePicker) {
        val tp = rememberTimePickerState(initialHour = 18, initialMinute = 0, is24Hour = false)
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.planReminder(activity, tp.hour, tp.minute)
                    showTimePicker = false
                }) { Text("Set reminder") }
            },
            dismissButton = { TextButton(onClick = { showTimePicker = false }) { Text("Cancel") } },
            text = { TimePicker(state = tp) },
        )
    }
}
