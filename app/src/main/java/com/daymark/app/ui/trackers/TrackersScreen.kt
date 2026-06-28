package com.daymark.app.ui.trackers

import com.daymark.app.ui.components.SentenceCaps
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daymark.app.data.entity.Tracker
import com.daymark.app.ui.components.PaperSurface

fun typeLabel(t: Tracker): String = when (t.type) {
    Tracker.SCALE -> "Scale 1–${t.maxValue}"
    Tracker.NUMERIC -> if (t.unit.isNotBlank()) "Number (${t.unit})" else "Number"
    else -> "Yes / No"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackersScreen(
    onBack: () -> Unit,
    onOpenTracker: (Long) -> Unit,
    viewModel: TrackersViewModel = hiltViewModel(),
) {
    val trackers by viewModel.trackers.collectAsStateWithLifecycle()
    var showEditor by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Trackers") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showEditor = true }) {
                Icon(Icons.Filled.Add, contentDescription = "New tracker")
            }
        },
    ) { padding ->
        if (trackers.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Track anything alongside your mood — energy, water, meds, pain… Tap + to make one.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(trackers, key = { it.id }) { t ->
                    PaperSurface(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { onOpenTracker(t.id) }.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(t.name, style = MaterialTheme.typography.titleMedium)
                                Text(typeLabel(t), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                        }
                    }
                }
            }
        }
    }

    if (showEditor) {
        TrackerEditorDialog(
            onDismiss = { showEditor = false },
            onConfirm = { name, type, max, unit -> viewModel.add(name, type, max, unit); showEditor = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrackerEditorDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, type: String, max: Int, unit: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(Tracker.SCALE) }
    var max by remember { mutableIntStateOf(5) }
    var unit by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New tracker") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, keyboardOptions = SentenceCaps, modifier = Modifier.fillMaxWidth())
                Text("Type", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = type == Tracker.SCALE, onClick = { type = Tracker.SCALE }, label = { Text("Scale") })
                    FilterChip(selected = type == Tracker.NUMERIC, onClick = { type = Tracker.NUMERIC }, label = { Text("Number") })
                    FilterChip(selected = type == Tracker.BOOLEAN, onClick = { type = Tracker.BOOLEAN }, label = { Text("Yes / No") })
                }
                when (type) {
                    Tracker.SCALE -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Highest value: $max", modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = { if (max > 2) max-- }) { Text("–") }
                        OutlinedButton(onClick = { if (max < 10) max++ }) { Text("+") }
                    }
                    Tracker.NUMERIC -> OutlinedTextField(
                        value = unit, onValueChange = { unit = it },
                        label = { Text("Unit (optional, e.g. glasses)") }, modifier = Modifier.fillMaxWidth(),
                    )
                    else -> {}
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(name, type, max, unit) }) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
