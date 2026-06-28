package com.daymark.app.ui.insights

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.daymark.app.stats.Signals
import com.daymark.app.ui.components.PaperSurface

/**
 * The "For you" strip at the top of Insights — the ranked [Signals] for the Insights surface,
 * rendered as paper cards. Each card's copy is fixed (no AI); an optional action button maps to
 * navigation via [onAction]. Dismissible cards can be waved away for this viewing (kept in-memory;
 * the engine will re-surface them next session if still relevant).
 */
@Composable
fun SignalCards(
    signals: List<Signals.Signal>,
    onAction: (Signals.Action) -> Unit,
    modifier: Modifier = Modifier,
    max: Int = 4,
) {
    val dismissed = remember { mutableStateMapOf<String, Boolean>() }
    val shown = Signals
        .forSurface(signals, Signals.Surface.Insights, limit = Int.MAX_VALUE)
        .filter { dismissed[it.kind] != true }
        .take(max)

    if (shown.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "FOR YOU",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.tertiary,
        )
        shown.forEach { signal ->
            AnimatedVisibility(
                visible = dismissed[signal.kind] != true,
                exit = fadeOut() + shrinkVertically(),
                enter = fadeIn(),
            ) {
                SignalCard(
                    signal = signal,
                    onAction = onAction,
                    onDismiss = if (signal.dismissible) {
                        { dismissed[signal.kind] = true }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

@Composable
private fun SignalCard(
    signal: Signals.Signal,
    onAction: (Signals.Action) -> Unit,
    onDismiss: (() -> Unit)?,
) {
    PaperSurface(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(signal.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                signal.body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val label = actionLabel(signal.action)
            if (label != null || onDismiss != null) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (label != null) {
                        TextButton(onClick = { signal.action?.let(onAction) }) { Text(label) }
                    }
                    if (onDismiss != null) {
                        TextButton(onClick = onDismiss) {
                            Text("Dismiss", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

/** Fixed button labels per action (no generated text). Null = no action button. */
private fun actionLabel(action: Signals.Action?): String? = when (action) {
    is Signals.Action.CreateGoalFromFactor -> "Make it a goal"
    is Signals.Action.TakeCheckin -> "Take the check-in"
    Signals.Action.OpenSupport -> "Take a moment"
    Signals.Action.OpenBreathing -> "Breathe"
    Signals.Action.OpenThoughtRecord -> "Open a thought record"
    Signals.Action.OpenJournal -> "Write"
    Signals.Action.OpenMovement -> "Move a little"
    Signals.Action.OpenCrisisResources -> "More support"
    Signals.Action.LogToday -> "Check in"
    null -> null
}
