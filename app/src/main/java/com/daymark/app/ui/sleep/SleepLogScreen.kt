package com.daymark.app.ui.sleep

import com.daymark.app.ui.components.SentenceCaps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.daymark.app.data.SleepMetrics
import com.daymark.app.ui.components.PaperSurface

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepLogScreen(
    onDone: () -> Unit,
    viewModel: SleepLogViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    var bedH by remember { mutableIntStateOf(23) }
    var bedM by remember { mutableIntStateOf(0) }
    var wakeH by remember { mutableIntStateOf(7) }
    var wakeM by remember { mutableIntStateOf(0) }
    var latency by remember { mutableIntStateOf(15) }
    var awake by remember { mutableIntStateOf(0) }
    var quality by remember { mutableIntStateOf(3) }
    var note by remember { mutableStateOf("") }

    fun pick(h: Int, m: Int, onPicked: (Int, Int) -> Unit) {
        android.app.TimePickerDialog(context, { _, hh, mm -> onPicked(hh, mm) }, h, m, true).show()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Last night") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Just estimate — no need to have watched the clock. Rough numbers are fine.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            PaperSurface(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    TimeRow("Got into bed", bedH, bedM) { pick(bedH, bedM) { h, m -> bedH = h; bedM = m } }
                    TimeRow("Got out of bed", wakeH, wakeM) { pick(wakeH, wakeM) { h, m -> wakeH = h; wakeM = m } }
                }
            }

            SliderRow("About how long to fall asleep?", latency, 0..120, "min") { latency = it }
            SliderRow("Time awake during the night?", awake, 0..180, "min") { awake = it }

            PaperSurface(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("How was your sleep? (1 poor – 5 great)", style = MaterialTheme.typography.bodyLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (1..5).forEach { q ->
                            FilterChip(
                                selected = quality == q,
                                onClick = { quality = q },
                                label = { Text(q.toString()) },
                            )
                        }
                    }
                }
            }

            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                keyboardOptions = SentenceCaps,
                label = { Text("Notes (optional)") },
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = {
                    viewModel.save(bedH, bedM, wakeH, wakeM, latency, awake, quality, note)
                    onDone()
                },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            ) { Text("Save") }
        }
    }
}

@Composable
private fun TimeRow(label: String, h: Int, m: Int, onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        OutlinedButton(onClick = onClick) {
            Text(String.format(java.util.Locale.getDefault(), "%02d:%02d", h, m))
        }
    }
}

@Composable
private fun SliderRow(label: String, value: Int, range: IntRange, unit: String, onChange: (Int) -> Unit) {
    PaperSurface(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Text("$value $unit", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Slider(
                value = value.toFloat(),
                onValueChange = { onChange((it / 5).toInt() * 5) },
                valueRange = range.first.toFloat()..range.last.toFloat(),
            )
        }
    }
}
