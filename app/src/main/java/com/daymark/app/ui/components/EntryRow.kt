package com.daymark.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.daymark.app.data.entity.EntryWithActivities
import com.daymark.app.model.Mood
import com.daymark.app.ui.icon.ActivityIcons
import com.daymark.app.ui.theme.LocalDaymarkTextStyles
import com.daymark.app.ui.theme.moodLabels
import com.daymark.app.util.DateUtils

/**
 * The full timeline row for a logged entry: mood face, label + time, the note, an optional photo
 * and the activity chips. Used by the History timeline and anywhere a complete record belongs.
 *
 * Home deliberately uses [CompactEntryRow] instead — see its KDoc.
 */
@Composable
fun EntryRow(
    entry: EntryWithActivities,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val mood = Mood.fromLevel(entry.entry.moodLevel)
    Column(
        modifier = modifier
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
                Text(
                    text = MaterialTheme.moodLabels.forLevel(mood.level),
                    style = MaterialTheme.typography.titleMedium,
                )
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
        entry.entry.photoPath?.let { path ->
            EntryPhoto(photoPath = path, size = 84.dp, cornerRadius = 12.dp)
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

/**
 * A single, quiet line for an entry: mood face, the note (or the mood label when there's no note)
 * and the time. This is what Home shows for today — the day's shape at a glance, without turning
 * the first screen of the app into a wall of history. Tapping opens the full entry.
 */
@Composable
fun CompactEntryRow(
    entry: EntryWithActivities,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val mood = Mood.fromLevel(entry.entry.moodLevel)
    val label = MaterialTheme.moodLabels.forLevel(mood.level)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        MoodFaceIcon(level = mood.level, size = 30.dp)
        if (entry.entry.note.isNotBlank()) {
            Text(
                text = entry.entry.note,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        } else {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            text = DateUtils.formatTime(entry.entry.dateTime),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary,
        )
    }
}
