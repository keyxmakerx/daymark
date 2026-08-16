package com.daymark.app.ui.lifeevents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daymark.app.data.entity.LifeEvent
import com.daymark.app.ui.components.PaperSurface
import com.daymark.app.ui.components.SentenceCaps
import com.daymark.app.ui.components.SwipeToDeleteRow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * "Life events" — the marks the person has placed on their own history, and the one place they are
 * made.
 *
 * The screen is deliberately small: a list, an add button, a delete. `docs/SKY.md` §2.2 is the
 * design, `data/entity/LifeEvent.kt` is the record, and the three things this screen does not do
 * matter more than what it does:
 *
 * - **It never suggests one.** No "you haven't added an event in a while", no "something seems to
 *   have changed around March — want to mark it?". The app does not read someone's logging and
 *   propose an explanation for it; that is a clinical judgement about a life, and this feature
 *   exists precisely because only the person may make it.
 * - **It never asks how it felt.** No mood, no valence, no good/bad. A life event is not rated.
 * - **It never asks what kind of thing it was.** No category chips, no dropdown. A taxonomy is the
 *   software deciding what counts as a life.
 *
 * The empty state says what the screen is for and asks for nothing. There is no count, no "0
 * events", and nothing that reads as an unfilled slot.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LifeEventsScreen(
    onBack: () -> Unit,
    viewModel: LifeEventsViewModel = hiltViewModel(),
) {
    val events by viewModel.events.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Life events") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(onClick = { adding = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add a life event")
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "A date and a few words, for something that mattered. Nothing else is asked, and " +
                    "nothing here is added for you.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )

            if (events.isEmpty()) {
                PaperSurface(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Nothing marked yet. Add one whenever you decide it belongs here — " +
                            "including something from years ago.",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            } else {
                PaperSurface(modifier = Modifier.fillMaxWidth()) {
                    LazyColumn(contentPadding = PaddingValues(vertical = 4.dp)) {
                        itemsIndexed(events, key = { _, event -> event.id }) { index, event ->
                            SwipeToDeleteRow(
                                onDelete = {
                                    viewModel.delete(event)
                                    scope.launch {
                                        val result = snackbarHostState.showSnackbar(
                                            message = "Life event deleted",
                                            actionLabel = "Undo",
                                            withDismissAction = true,
                                            duration = SnackbarDuration.Short,
                                        )
                                        if (result == SnackbarResult.ActionPerformed) {
                                            viewModel.restore(event)
                                        }
                                    }
                                },
                                confirmTitle = "Delete this life event?",
                                confirmBody = "This removes the mark you placed on " +
                                    "${formatEpochDay(event.epochDay)}. You'll get a moment to undo it.",
                                swipeLabel = "Delete life event",
                            ) {
                                LifeEventRow(event)
                            }
                            if (index < events.lastIndex) {
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (adding) {
        AddLifeEventDialog(
            onDismiss = { adding = false },
            onAdd = { epochDay, label ->
                viewModel.add(epochDay, label)
                adding = false
            },
        )
    }
}

@Composable
private fun LifeEventRow(event: LifeEvent) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(event.label, style = MaterialTheme.typography.bodyLarge)
        Text(
            formatEpochDay(event.epochDay),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The add dialog: a date and a line, and no third field.
 *
 * The date defaults to today because that is the cheapest thing to confirm, not because today is
 * the expected answer — the picker opens on any year from 1900, since adding something from a
 * decade ago is the ordinary use of this screen, not an edge case.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddLifeEventDialog(
    onDismiss: () -> Unit,
    onAdd: (Long, String) -> Unit,
) {
    var epochDay by remember { mutableLongStateOf(LocalDate.now().toEpochDay()) }
    var label by remember { mutableStateOf("") }
    var pickingDate by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a life event") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "When",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { pickingDate = true }) { Text(formatEpochDay(epochDay)) }
                }
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    keyboardOptions = SentenceCaps,
                    singleLine = true,
                    label = { Text("What happened") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            // Disabled on a blank line rather than validated on tap: there is no other field, so
            // an empty label is a row that says nothing. Nothing else about the date is checked.
            TextButton(
                onClick = { onAdd(epochDay, label) },
                enabled = label.isNotBlank(),
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    if (pickingDate) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = epochDayToPickerMillis(epochDay),
            // Explicit, not the library default: "years back" is the normal case for this screen,
            // and a range that quietly narrowed in a future Material release would turn a person's
            // 1998 into an unreachable date. The upper end leaves room for a date someone knows is
            // coming, which this feature allows — see LifeEvent.
            yearRange = 1900..(LocalDate.now().year + 10),
        )
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { epochDay = pickerMillisToEpochDay(it) }
                    pickingDate = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { pickingDate = false }) { Text("Cancel") }
            },
        ) {
            DatePicker(state = state)
        }
    }
}

private const val MILLIS_PER_DAY = 86_400_000L

/**
 * Material's date picker speaks UTC midnight millis; this record speaks epoch days.
 *
 * The conversion is deliberately arithmetic and zone-free rather than a round trip through the
 * device's zone. Reading the picker's UTC midnight in a local zone west of Greenwich lands on the
 * previous day, which is how a picked date arrives in the database off by one — and on this table
 * the date *is* the record.
 */
private fun epochDayToPickerMillis(epochDay: Long): Long = epochDay * MILLIS_PER_DAY

/** `floorDiv`, not `/`: dates before 1970 are negative millis, and truncation rounds them up a day. */
private fun pickerMillisToEpochDay(millis: Long): Long = Math.floorDiv(millis, MILLIS_PER_DAY)

private val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

private fun formatEpochDay(epochDay: Long): String =
    LocalDate.ofEpochDay(epochDay).format(dateFormatter)
