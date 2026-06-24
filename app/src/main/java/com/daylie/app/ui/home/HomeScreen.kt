package com.daylie.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daylie.app.data.entity.EntryWithActivities
import com.daylie.app.model.Mood
import com.daylie.app.ui.components.MoodFace
import com.daylie.app.util.DateUtils

@Composable
fun HomeScreen(
    onEntryClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    if (!state.loading && state.entries.isEmpty()) {
        EmptyState(modifier)
        return
    }

    // Group entries by calendar day, preserving the DESC ordering.
    val grouped = state.entries.groupBy { DateUtils.toLocalDate(it.entry.dateTime) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        grouped.forEach { (date, entries) ->
            item(key = "header-$date") {
                Text(
                    text = DateUtils.formatDate(DateUtils.startOfDay(date)),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                )
            }
            items(entries, key = { it.entry.id }) { entry ->
                EntryRow(entry = entry, onClick = { onEntryClick(entry.entry.id) })
            }
        }
    }
}

@Composable
private fun EntryRow(entry: EntryWithActivities, onClick: () -> Unit) {
    val mood = Mood.fromLevel(entry.entry.moodLevel)
    Card(
        shape = RoundedCornerShape(16.dp),
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MoodFace(mood = mood, size = 44)
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(text = mood.label, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = DateUtils.formatTime(entry.entry.dateTime),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (entry.activities.isNotEmpty()) {
                    Text(
                        text = entry.activities.joinToString(", ") { it.name },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (entry.entry.note.isNotBlank()) {
                    Text(
                        text = entry.entry.note,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MoodFace(mood = Mood.GOOD, size = 72)
            Text(text = "No entries yet", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Tap + to log how you feel.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
