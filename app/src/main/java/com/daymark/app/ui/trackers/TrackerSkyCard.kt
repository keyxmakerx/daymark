package com.daymark.app.ui.trackers

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.daymark.app.data.entity.Tracker
import com.daymark.app.ui.components.DaymarkSwitch
import com.daymark.app.ui.components.PaperSurface

/**
 * "Show in my sky" (`DECISIONS.md` §D11): whether this tracker is an object of its own in the
 * person's sky, beside their memories. Off until they switch it on, because a tracker can be about
 * something hard, and an object made of it belongs in the sky only if they want one there.
 */
@Composable
fun TrackerSkyCard(tracker: Tracker, onChange: (Tracker) -> Unit) {
    PaperSurface(modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Show in my sky", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "An object of its own beside your memories. Each time you log it is one star in it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DaymarkSwitch(
                checked = tracker.showInSky,
                onCheckedChange = { onChange(tracker.copy(showInSky = it)) },
            )
        }
    }
}
