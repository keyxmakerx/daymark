package com.daymark.app.ui.support

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.daymark.app.calm.CalmSession
import kotlinx.coroutines.delay

/**
 * What every calm activity in Take a moment sits inside (#170, `docs/FEATURES.md` §7.2.1).
 *
 *  - "Back to the menu" is on screen at all times, first in reading order, and the system back
 *    gesture does the same thing because the activity is its own destination.
 *  - About ten minutes in, one fixed line appears and stays: [CalmSession.NATURAL_END_LINE]. It is
 *    not a countdown and nothing counts up; the wait is a single delay that is never shown.
 *  - Nothing is recorded. No view model, no repository: a visit leaves no trace to keep up.
 */
@Composable
internal fun CalmActivityFrame(
    title: String,
    onBackToMenu: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    var naturalEnd by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(CalmSession.NATURAL_END_MS)
        naturalEnd = true
    }
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(onClick = onBackToMenu, modifier = Modifier.fillMaxWidth()) {
                Text(CalmSession.BACK_TO_MENU)
            }
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (naturalEnd) {
                Text(
                    CalmSession.NATURAL_END_LINE,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
            }
        }
    }
}
