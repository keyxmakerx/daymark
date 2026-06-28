package com.daymark.app.ui.more

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.annotation.DrawableRes
import com.daymark.app.R
import com.daymark.app.ui.components.PaperSurface

/**
 * The "More" tab landing — a visual hub of cards. Surfaces destinations that were
 * previously buried in Settings (Goals especially) and gives future features (Sleep
 * check-ins) a clear home, instead of a long settings list.
 */
@Composable
fun MoreHubScreen(
    onGoals: () -> Unit,
    onActivities: () -> Unit,
    onYearPixels: () -> Unit,
    onSleep: () -> Unit,
    onTrackers: () -> Unit,
    onGentleSupport: () -> Unit,
    onCheckins: () -> Unit,
    onAchievements: () -> Unit,
    onActivation: () -> Unit,
    onThoughtRecords: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        SectionLabel("Track")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            HubCard(
                icon = R.drawable.ic_act_star,
                title = "Activities",
                subtitle = "Manage & library",
                onClick = onActivities,
                modifier = Modifier.weight(1f),
            )
            HubCard(
                icon = R.drawable.ic_ui_calendar,
                title = "Year in Pixels",
                subtitle = "Your year at a glance",
                onClick = onYearPixels,
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        ) {
            HubCard(
                icon = R.drawable.ic_act_sleep,
                title = "Sleep check-ins",
                subtitle = "Quick self-checks",
                onClick = onSleep,
                modifier = Modifier.weight(1f),
            )
            HubCard(
                icon = R.drawable.ic_act_relax,
                title = "Gentle support",
                subtitle = "In-the-moment help · opt-in",
                onClick = onGentleSupport,
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        ) {
            HubCard(
                icon = R.drawable.ic_act_exercise,
                title = "Do one thing",
                subtitle = "Behavioral activation",
                onClick = onActivation,
                modifier = Modifier.weight(1f),
            )
            HubCard(
                icon = R.drawable.ic_ui_more,
                title = "Thought records",
                subtitle = "CBT: examine a thought",
                onClick = onThoughtRecords,
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        ) {
            HubCard(
                icon = R.drawable.ic_act_star,
                title = "Trackers",
                subtitle = "Track anything vs. mood",
                onClick = onTrackers,
                modifier = Modifier.weight(1f),
            )
            HubCard(
                icon = R.drawable.ic_act_sleep,
                title = "Check-ins",
                subtitle = "PHQ-9 · GAD-7 · WHO-5",
                onClick = onCheckins,
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        ) {
            HubCard(
                icon = R.drawable.ic_act_star,
                title = "Achievements",
                subtitle = "Milestones for showing up",
                onClick = onAchievements,
                modifier = Modifier.weight(1f),
            )
            HubCard(
                icon = R.drawable.ic_ui_more,
                title = "Settings",
                subtitle = "Reminders, lock, backup",
                onClick = onSettings,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 2.dp, bottom = 10.dp),
    )
}

@Composable
private fun HubCard(
    @DrawableRes icon: Int,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PaperSurface(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(112.dp)
                .clickable { onClick() }
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(26.dp),
            )
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
