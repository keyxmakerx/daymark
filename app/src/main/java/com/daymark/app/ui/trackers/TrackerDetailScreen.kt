package com.daymark.app.ui.trackers

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daymark.app.data.entity.Tracker
import com.daymark.app.data.entity.TrackerLog
import com.daymark.app.ui.components.PaperSurface
import com.daymark.app.util.DateUtils

private fun fmtValue(tracker: Tracker, v: Double): String = when (tracker.type) {
    Tracker.BOOLEAN -> if (v != 0.0) "Yes" else "No"
    else -> if (v == v.toLong().toDouble()) v.toLong().toString() else String.format(java.util.Locale.getDefault(), "%.1f", v)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackerDetailScreen(
    onBack: () -> Unit,
    viewModel: TrackerDetailViewModel = hiltViewModel(),
) {
    val tracker by viewModel.tracker.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tracker?.name ?: "Tracker") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        val t = tracker ?: return@Scaffold
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { LogInput(t, onLog = viewModel::log) }
            if (logs.isNotEmpty()) {
                val numeric = logs.map { it.value }
                item {
                    PaperSurface(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Summary", style = MaterialTheme.typography.titleMedium)
                            if (t.type == Tracker.BOOLEAN) {
                                val yes = numeric.count { it != 0.0 }
                                Text("$yes of ${numeric.size} logged 'Yes'", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                Text(
                                    "Average ${String.format(java.util.Locale.getDefault(), "%.1f", numeric.average())}" +
                                        (if (t.unit.isNotBlank()) " ${t.unit}" else "") + " over ${numeric.size}",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                items(logs, key = { it.id }) { log ->
                    PaperSurface(modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${DateUtils.formatDate(log.dateTime)} · ${DateUtils.formatTime(log.dateTime)}",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(fmtValue(t, log.value), style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            } else {
                item {
                    Text(
                        "No values yet — log one above.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogInput(tracker: Tracker, onLog: (Double) -> Unit) {
    PaperSurface(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Log now", style = MaterialTheme.typography.titleMedium)
            when (tracker.type) {
                Tracker.SCALE -> {
                    var selected by remember { mutableStateOf<Int?>(null) }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (tracker.minValue..tracker.maxValue).forEach { n ->
                            FilterChip(selected = selected == n, onClick = { selected = n }, label = { Text(n.toString()) })
                        }
                    }
                    Button(
                        onClick = { selected?.let { onLog(it.toDouble()); selected = null } },
                        enabled = selected != null,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Log") }
                }
                Tracker.NUMERIC -> {
                    var text by remember { mutableStateOf("") }
                    OutlinedTextField(
                        value = text, onValueChange = { text = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text(if (tracker.unit.isNotBlank()) tracker.unit else "Value") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = { text.toDoubleOrNull()?.let { onLog(it); text = "" } },
                        enabled = text.toDoubleOrNull() != null,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Log") }
                }
                else -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { onLog(1.0) }, modifier = Modifier.weight(1f)) { Text("Yes") }
                    OutlinedButton(onClick = { onLog(0.0) }, modifier = Modifier.weight(1f)) { Text("No") }
                }
            }
        }
    }
}
