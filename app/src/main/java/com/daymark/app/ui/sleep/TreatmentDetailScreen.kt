package com.daymark.app.ui.sleep

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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daymark.app.data.SleepMetrics
import com.daymark.app.data.TreatmentStats
import com.daymark.app.ui.components.PaperSurface
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TreatmentDetailScreen(
    onBack: () -> Unit,
    viewModel: TreatmentDetailViewModel = hiltViewModel(),
) {
    val treatment by viewModel.treatment.collectAsStateWithLifecycle()
    val comparison by viewModel.comparison.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(treatment?.kind ?: "Treatment") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete")
                    }
                },
            )
        },
    ) { padding ->
        val t = treatment
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (t != null) {
                val date = Instant.ofEpochMilli(t.startedAt).atZone(ZoneId.systemDefault()).toLocalDate()
                Text(
                    "Since ${DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.getDefault()).format(date)}",
                    style = MaterialTheme.typography.titleMedium,
                )
                if (t.note.isNotBlank()) {
                    Text(t.note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            val c = comparison
            val enough = c != null && (c.before.nights >= 3 || c.after.nights >= 3)
            if (c == null || !enough) {
                PaperSurface(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Keep logging your sleep — we'll show a before-and-since comparison once " +
                            "there are a few nights on each side of the date.",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                PaperSurface(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(Modifier.fillMaxWidth()) {
                            Text("", modifier = Modifier.weight(1.2f))
                            HeaderCell("Before", "${c.before.nights} nights")
                            HeaderCell("Since", "${c.after.nights} nights")
                        }
                        HorizontalDivider()
                        MetricRow("Avg sleep", c.before.avgSleepMin?.let { SleepMetrics.formatDuration(it) }, c.after.avgSleepMin?.let { SleepMetrics.formatDuration(it) })
                        MetricRow("Efficiency", c.before.avgEfficiencyPct?.let { "$it%" }, c.after.avgEfficiencyPct?.let { "$it%" })
                        MetricRow("Quality", c.before.avgQuality?.let { fmt1(it) }, c.after.avgQuality?.let { fmt1(it) })
                        MetricRow("Avg mood", c.before.avgMood?.let { fmt1(it) }, c.after.avgMood?.let { fmt1(it) })
                    }
                }
            }

            Text(
                "These are your own self-reported numbers before vs. since the date you marked. " +
                    "Many things affect sleep — this shows what changed, not why, and isn't a measure " +
                    "of whether your treatment is working. Talk to your clinician about that.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this treatment?") },
            text = { Text("This only removes the marker — your sleep and mood entries are untouched.") },
            confirmButton = {
                TextButton(onClick = { viewModel.delete(); confirmDelete = false; onBack() }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

private fun fmt1(v: Double): String = String.format(Locale.getDefault(), "%.1f", v)

@Composable
private fun androidx.compose.foundation.layout.RowScope.HeaderCell(title: String, sub: String) {
    Column(modifier = Modifier.weight(1f)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MetricRow(label: String, before: String?, since: String?) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1.2f), style = MaterialTheme.typography.bodyMedium)
        Text(before ?: "—", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(since ?: "—", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
    }
}
