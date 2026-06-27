package com.daymark.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daymark.app.data.entity.EntryWithActivities
import com.daymark.app.model.Mood
import com.daymark.app.ui.components.MoodFaceIcon
import com.daymark.app.ui.components.PaperSurface
import com.daymark.app.ui.icon.ActivityIcons
import com.daymark.app.ui.theme.LocalDaymarkTextStyles
import com.daymark.app.util.DateUtils

@Composable
fun HomeScreen(
    onEntryClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    onUndoableDelete: (onUndo: () -> Unit) -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
    memoriesViewModel: MemoriesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val memories by memoriesViewModel.memories.collectAsStateWithLifecycle()

    if (!state.loading && state.entries.isEmpty()) {
        EmptyState(modifier)
        return
    }

    // Group entries by calendar day, preserving the DESC ordering.
    val grouped = state.entries.groupBy { DateUtils.toLocalDate(it.entry.dateTime) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (memories.isNotEmpty()) {
            item(key = "on-this-day") {
                OnThisDayCard(memories, onEntryClick, modifier = Modifier.animateItem())
            }
        }
        grouped.forEach { (date, entries) ->
            item(key = "day-$date") {
                DaySheet(
                    label = DateUtils.formatDate(DateUtils.startOfDay(date)),
                    entries = entries,
                    onEntryClick = onEntryClick,
                    onDelete = { entry ->
                        viewModel.delete(entry)
                        onUndoableDelete { viewModel.restore(entry) }
                    },
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }
}

@Composable
private fun OnThisDayCard(
    memories: List<EntryWithActivities>,
    onEntryClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val thisYear = java.time.LocalDate.now().year
    PaperSurface(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 4.dp)) {
            Text(
                "ON THIS DAY",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
                letterSpacing = 1.2.sp,
                modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 4.dp),
            )
            memories.take(4).forEach { m ->
                val mood = Mood.fromLevel(m.entry.moodLevel)
                val yearsAgo = thisYear - DateUtils.toLocalDate(m.entry.dateTime).year
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onEntryClick(m.entry.id) }
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(13.dp),
                ) {
                    MoodFaceIcon(level = mood.level, size = 34.dp)
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (yearsAgo == 1) "1 year ago" else "$yearsAgo years ago",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                        if (m.entry.note.isNotBlank()) {
                            Text(
                                "“${m.entry.note}”",
                                style = LocalDaymarkTextStyles.current.diaryNote,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
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
                    SwipeableEntryRow(
                        entry = entry,
                        onClick = { onEntryClick(entry.entry.id) },
                        onDelete = { onDelete(entry) },
                    )
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableEntryRow(
    entry: EntryWithActivities,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onDelete()
                true
            } else {
                false
            }
        },
    )
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        },
    ) {
        // Opaque surface so the row fully covers the red background until swiped.
        Box(modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
            EntryRow(entry = entry, onClick = onClick)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EntryRow(entry: EntryWithActivities, onClick: () -> Unit) {
    val mood = Mood.fromLevel(entry.entry.moodLevel)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            MoodFaceIcon(level = mood.level, size = 42.dp)
            Column(modifier = Modifier.weight(1f)) {
                Text(text = mood.label, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = DateUtils.formatTime(entry.entry.dateTime),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
        if (entry.entry.note.isNotBlank()) {
            Text(
                text = "“${entry.entry.note}”",
                style = LocalDaymarkTextStyles.current.diaryNote,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (entry.activities.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                entry.activities.forEach { activity ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Icon(
                            painter = painterResource(ActivityIcons.forKey(activity.iconKey)),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(15.dp),
                        )
                        Text(
                            text = activity.name,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MoodFaceIcon(level = Mood.GOOD.level, size = 72.dp)
            Text(text = "No entries yet", style = MaterialTheme.typography.titleLarge)
            Text(
                text = "Tap + to log how you feel.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
