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
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.daymark.app.stats.Signals
import com.daymark.app.ui.components.PaperSurface

/**
 * The "For you" strip — the ranked [Signals] for [surface], rendered as paper cards. Each card's
 * copy is fixed (no AI); an optional action button maps to navigation via [onAction]. Dismissible
 * cards can be waved away via [onDismiss]; the engine re-surfaces them next session if still relevant.
 *
 * Dismissal state is **hoisted** ([dismissed] / [onDismiss]) so the caller can decide whether to
 * render this strip at all — that lets the surrounding layout drop the slot cleanly once everything
 * is dismissed (see [visibleSignalCount]). The visible window is taken once ([max]) and dismissed
 * cards stay in the list so their exit animation can play and no lower-ranked card pops in to fill
 * the gap — a calm wave-away, not a feed that argues back.
 */
@Composable
fun SignalCards(
    signals: List<Signals.Signal>,
    onAction: (Signals.Action) -> Unit,
    dismissed: Set<String>,
    onDismiss: (String) -> Unit,
    modifier: Modifier = Modifier,
    surface: Signals.Surface = Signals.Surface.Insights,
    max: Int = 4,
    exclude: Set<String> = emptySet(),
    header: String? = "FOR YOU",
) {
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
                            { onDismiss(signal.kind) }
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }
}

/** Saver for a hoisted dismissed-kinds set (survives configuration changes / scroll-off). */
val SignalDismissalSaver = listSaver<Set<String>, String>(
    save = { it.toList() },
    restore = { it.toSet() },
)

/**
 * How many of [surface]'s cards (after [exclude] and the [max] window) are currently NOT in
 * [dismissed] — i.e. how many would actually be visible. Lets a caller skip the whole strip (and
 * its surrounding spacing) when everything has been waved away.
 */
fun visibleSignalCount(
    signals: List<Signals.Signal>,
    surface: Signals.Surface,
    dismissed: Set<String>,
    max: Int = 4,
    exclude: Set<String> = emptySet(),
): Int = Signals.forSurface(signals, surface, limit = Int.MAX_VALUE)
    .filterNot { it.kind in exclude }
    .take(max)
    .count { it.kind !in dismissed }

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
                        TextButton(
                            onClick = onDismiss,
                            modifier = Modifier.semantics { contentDescription = "Dismiss: ${signal.title}" },
                        ) {
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
