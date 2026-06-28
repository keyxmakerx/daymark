package com.daymark.app.ui.sleep

import com.daymark.app.ui.components.SentenceCaps

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daymark.app.data.entity.Treatment
import com.daymark.app.ui.components.PaperSurface
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dateFmt = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault())

private fun toLocalDate(millis: Long): LocalDate =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()

private fun startOfDayMillis(date: LocalDate): Long =
    date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TreatmentsScreen(
    onBack: () -> Unit,
    onOpenTreatment: (Long) -> Unit,
    viewModel: TreatmentsViewModel = hiltViewModel(),
) {
    val treatments by viewModel.treatments.collectAsStateWithLifecycle()
    var showEditor by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Treatments") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showEditor = true },
                text = { Text("Mark a treatment") },
                icon = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null) },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "Mark a change — like starting CPAP — to see your own numbers before and since. " +
                        "This shows what changed, not why, and isn't a measure of whether a treatment works.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (treatments.isEmpty()) {
                item {
                    Text(
                        "Nothing marked yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            items(treatments, key = { it.id }) { t ->
                PaperSurface(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onOpenTreatment(t.id) }.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(t.kind, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "since ${dateFmt.format(toLocalDate(t.startedAt))}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                    }
                }
            }
        }
    }

    if (showEditor) {
        TreatmentEditorDialog(
            onDismiss = { showEditor = false },
            onConfirm = { kind, startMillis, note ->
                viewModel.add(kind, startMillis, note)
                showEditor = false
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TreatmentEditorDialog(
    onDismiss: () -> Unit,
    onConfirm: (kind: String, startMillis: Long, note: String) -> Unit,
) {
    val context = LocalContext.current
    var kind by remember { mutableStateOf(Treatment.KINDS.first()) }
    var startMillis by remember { mutableLongStateOf(startOfDayMillis(LocalDate.now())) }
    var note by remember { mutableStateOf("") }

    fun pickDate() {
        val d = toLocalDate(startMillis)
        android.app.DatePickerDialog(
            context,
            { _, y, m, day -> startMillis = startOfDayMillis(LocalDate.of(y, m + 1, day)) },
            d.year, d.monthValue - 1, d.dayOfMonth,
        ).show()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mark a treatment") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("What changed?", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Treatment.KINDS.forEach { k ->
                        FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(k) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Started", modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { pickDate() }) {
                        Text(dateFmt.format(toLocalDate(startMillis)))
                    }
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    keyboardOptions = SentenceCaps,
                    label = { Text("Note (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(kind, startMillis, note) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
