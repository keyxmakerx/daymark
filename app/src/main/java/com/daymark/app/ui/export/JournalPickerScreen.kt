package com.daymark.app.ui.export

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daymark.app.ui.components.PaperSurface
import com.daymark.app.util.DateUtils

/**
 * "In their words" — choosing, one entry at a time, which journal writing goes to a clinician.
 *
 * Until this screen existed the only control was a single switch over the whole date range, and the
 * report's own copy said so out loud. It now offers the two decisions the report can describe, and
 * keeps them visibly apart:
 *
 *  - **Entry by entry.** Checkboxes, every one of them off to begin with. Nothing is pre-ticked,
 *    nominated or suggested — the report's promise is that the software selected none of it.
 *  - **Everything in this range.** A separate, deliberate action, not the end state of ticking
 *    every box. It sets `PdfExportOptions.includeAllJournalInRange`, and the report prints its
 *    whole-range wording instead of its per-entry wording.
 *
 * **The two modes look different on purpose.** In blanket mode the checkboxes are not disabled —
 * they are gone, replaced by a plain list under a banner saying what was chosen. A row of ticked,
 * greyed-out boxes would read as a selection, which is the one thing the whole-range choice is not,
 * and side 3 says which of the two happened.
 *
 * Each row carries the date, the title and an opening of the body, because a person cannot consent
 * to disclosing writing they cannot identify. That opening is a recognition aid for this screen
 * only — see [JournalSelection.preview]. What prints is the entry, whole.
 *
 * Leaving with nothing ticked is a finished answer: side 3 does not appear in the report at all.
 * Nothing here asks twice.
 *
 * ## One rule for whoever hosts this
 *
 * **Give it a scope that dies with the export flow** — its own navigation destination, not a
 * dialog hung off a screen that outlives it. [JournalPickerViewModel] holds the tick set, and a
 * store owner that survives means the *next* export opens with the *last* export's entries already
 * ticked. That is pre-selected disclosure of someone's writing arriving as a default, which is the
 * one thing this screen exists to prevent.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JournalPickerScreen(
    fromMillis: Long,
    toMillis: Long,
    rangeLabel: String,
    onBack: () -> Unit,
    onConfirm: (JournalSelection.Outcome) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: JournalPickerViewModel = hiltViewModel(),
) {
    // Also re-runs when the person changes the export's date range behind this screen; ticks
    // outside the new window stop counting without being forgotten.
    LaunchedEffect(fromMillis, toMillis) { viewModel.setRange(fromMillis, toMillis) }

    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Your writing") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        bottomBar = {
            ChoiceBar(
                summary = state.summary,
                onDone = { onConfirm(state.outcome) },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = rangeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        letterSpacing = 1.sp,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                    Text(
                        text = "The report can carry your journal writing. It starts with none of " +
                            "it, and takes only what you pick here.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "Anything you include is printed whole, as you wrote it — nothing " +
                            "is shortened or summarised. The lines below are the openings of each " +
                            "entry, so you can tell which is which.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (state.wholeRangeChosen) {
                item {
                    WholeRangeBanner(
                        available = state.outcome.availableCount,
                        onPickEntries = viewModel::chooseEntryByEntry,
                    )
                }
            }

            if (state.rows.isEmpty()) {
                item {
                    Text(
                        text = "You have no journal entries in this range.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            } else {
                items(state.rows, key = { it.id }) { row ->
                    EntryRow(
                        row = row,
                        showCheck = state.showChecks,
                        onToggle = { on -> viewModel.toggle(row.id, on) },
                    )
                }

                if (!state.wholeRangeChosen) {
                    item {
                        Column(
                            modifier = Modifier.padding(top = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            HorizontalDivider()
                            TextButton(onClick = viewModel::chooseWholeRange) {
                                Text("Include everything in this range")
                            }
                            Text(
                                text = "A different choice from ticking every box, and the report " +
                                    "says which one you made.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 12.dp),
                            )
                        }
                    }
                }

                if (state.outcome.form != JournalSelection.Form.NOTHING) {
                    item {
                        TextButton(onClick = viewModel::clear) {
                            Text("Include none of it")
                        }
                    }
                }
            }
        }
    }
}

/**
 * The blanket choice, stated back. It names the count so "everything" is a number rather than a
 * word, and offers the way back to per-entry without being a warning — choosing the whole range is
 * a legitimate answer, not a mistake to talk someone out of.
 */
@Composable
private fun WholeRangeBanner(available: Int, onPickEntries: () -> Unit) {
    PaperSurface(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Everything in this range", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "All ${JournalSelection.entries(available)} you wrote between these dates " +
                    "will be in the report, and it will say you chose the whole range rather than " +
                    "picking entries out of it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onPickEntries) { Text("Pick entries instead") }
        }
    }
}

/**
 * One entry, with enough of it on screen to be recognised.
 *
 * The whole row is the target rather than the box alone, so a tick is not a small-target action on
 * a decision about someone's private writing. In blanket mode there is no box and no toggle — see
 * the screen's KDoc.
 */
@Composable
private fun EntryRow(
    row: JournalSelection.Row,
    showCheck: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val rowModifier = if (showCheck) {
        Modifier.toggleable(
            value = row.selected,
            role = Role.Checkbox,
            onValueChange = onToggle,
        )
    } else {
        Modifier
    }

    PaperSurface(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = rowModifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            if (showCheck) {
                // Null handler: the toggle lives on the row, and a second one here would fight it.
                Checkbox(checked = row.selected, onCheckedChange = null)
                Spacer(Modifier.width(10.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(row.title, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = DateUtils.formatDate(row.dateTimeMillis) + " · " +
                        DateUtils.formatTime(row.dateTimeMillis),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                Text(
                    text = row.preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * What is about to be shared, and the way out.
 *
 * The sentence comes from [JournalSelection.summary], which reads the same [JournalSelection.Outcome]
 * the export is built from — so the last thing the person reads here is derived from the same value
 * that decides what the report prints.
 */
@Composable
private fun ChoiceBar(summary: String, onDone: () -> Unit) {
    // One root child, and an opaque one: Scaffold's bottom slot places every child it is given at
    // the same origin, and a transparent bar would let the list scroll through the sentence that
    // states what is about to be disclosed.
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.fillMaxWidth()) {
            HorizontalDivider()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(summary, style = MaterialTheme.typography.bodyMedium)
                Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }
            }
        }
    }
}
