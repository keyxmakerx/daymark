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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.daymark.app.stats.Signals
import com.daymark.app.ui.components.PaperSurface

/**
 * The "For you" strip at the top of Insights — the ranked [Signals] for the Insights surface,
 * rendered as paper cards. Each card's copy is fixed (no AI); an optional action button maps to
 * navigation via [onAction]. Dismissible cards can be waved away for this viewing (kept in a
 * config-change-surviving set; the engine re-surfaces them next session if still relevant).
 *
 * The visible window is taken once ([max]) and dismissed cards stay in the list so their exit
 * animation can play and no lower-ranked card pops in to fill the gap — a calm wave-away, not a
 * feed that argues back.
 */
@Composable
fun SignalCards(
    signals: List<Signals.Signal>,
    onAction: (Signals.Action) -> Unit,
    modifier: Modifier = Modifier,
    surface: Signals.Surface = Signals.Surface.Insights,
    max: Int = 4,
    exclude: Set<String> = emptySet(),
    header: String? = "FOR YOU",
) {
    var dismissed by rememberSaveable(
        surface,
        stateSaver = StringSetSaver,
    ) { mutableStateOf(emptySet<String>()) }
    val window = Signals.forSurface(signals, surface, limit = Int.MAX_VALUE)
        .filterNot { it.kind in exclude }
        .take(max)

    if (window.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (header != null) {
            Text(
                header,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        window.forEach { signal ->
            key(signal.kind) {
                AnimatedVisibility(
                    visible = signal.kind !in dismissed,
                    exit = fadeOut() + shrinkVertically(),
                    enter = fadeIn(),
                ) {
                    SignalCard(
                        signal = signal,
                        onAction = onAction,
                        onDismiss = if (signal.dismissible) {
                            { dismissed = dismissed + signal.kind }
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }
}

/** Saves the dismissed-kinds set across configuration changes (rotation, etc.). */
private val StringSetSaver = listSaver<Set<String>, String>(
    save = { it.toList() },
    restore = { it.toSet() },
)

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
