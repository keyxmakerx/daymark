package com.daymark.app.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daymark.app.data.entity.EntryWithActivities
import com.daymark.app.ui.components.EntryRow
import com.daymark.app.ui.components.PaperSurface
import com.daymark.app.ui.components.SwipeToDeleteRow
import com.daymark.app.util.DateUtils

/**
 * Everything you've logged, newest first, grouped by day — the archive Home links to.
 *
 * Deleting here is deliberately a three-step affair: a long swipe, a confirmation, and then an
 * undo snackbar ([onUndoableDelete]). Nothing about a mood log is worth losing to a stray thumb.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onBack: () -> Unit,
    onEntryClick: (Long) -> Unit,
    onUndoableDelete: (onUndo: () -> Unit, onExpire: () -> Unit) -> Unit = { _, _ -> },
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("All entries") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (!state.loading && state.entries.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Nothing logged yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }

        // Group entries by calendar day, preserving the DESC ordering.
        val grouped = state.entries.groupBy { DateUtils.toLocalDate(it.entry.dateTime) }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            grouped.forEach { (date, entries) ->
                item(key = "day-$date") {
                    DaySheet(
                        label = DateUtils.formatDate(DateUtils.startOfDay(date)),
                        entries = entries,
                        onEntryClick = onEntryClick,
                        onDelete = { entry ->
                            viewModel.delete(entry)
                            onUndoableDelete(
                                { viewModel.restore(entry) },
                                { viewModel.purgePhoto(entry) },
                            )
                        },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
            // A finite, calm ending — the timeline doesn't scroll forever.
            if (grouped.isNotEmpty()) {
                item(key = "caught-up") {
                    Text(
                        text = "That's everything.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp, bottom = 2.dp)
                            .animateItem(),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun DaySheet(
    label: String,
    entries: List<EntryWithActivities>,
    onEntryClick: (Long) -> Unit,
    onDelete: (EntryWithActivities) -> Unit,
    modifier: Modifier = Modifier,
) {
    PaperSurface(modifier = modifier.fillMaxWidth()) {
        Column {
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
                letterSpacing = 1.2.sp,
                modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 16.dp),
            )
            entries.forEachIndexed { index, entry ->
                key(entry.entry.id) {
                    SwipeToDeleteRow(
                        onDelete = { onDelete(entry) },
                        confirmTitle = "Delete this entry?",
                        confirmBody = "This removes the entry from $label. You'll get a moment to undo it.",
                        swipeLabel = "Delete entry",
                    ) {
                        EntryRow(entry = entry, onClick = { onEntryClick(entry.entry.id) })
                    }
                }
                if (index < entries.lastIndex) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.padding(horizontal = 18.dp),
                    )
                }
            }
        }
    }
}
