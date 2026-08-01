package com.daymark.app.ui.insights

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.daymark.app.stats.Signals
import com.daymark.app.stats.SuggestionControls
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
    onControl: ((kind: String, action: SuggestionControls.Action) -> Unit)? = null,
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
                        onControl = onControl?.takeIf {
                            signal.dismissible && SuggestionControls.groupKeyOf(signal.kind) != null
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
    onControl: ((kind: String, action: SuggestionControls.Action) -> Unit)?,
) {
    PaperSurface(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        signal.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        signal.body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (onControl != null) {
                    SuggestionControlMenu(
                        label = signal.title,
                        onControl = { action ->
                            // A null action is "not right now" — this session only, nothing stored.
                            action?.let { onControl(signal.kind, it) }
                            // Step the card out now; a stored control keeps it out afterwards.
                            onDismiss?.invoke()
                        },
                    )
                }
            }
            val label = actionLabel(signal.action)
            if (label != null || (onDismiss != null && onControl == null)) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (label != null) {
                        TextButton(onClick = { signal.action?.let(onAction) }) { Text(label) }
                    }
                    // Without the control menu, a plain dismiss is the only way to wave a card away.
                    if (onDismiss != null && onControl == null) {
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

/**
 * The per-card dial: five fixed choices, from "not right now" all the way to "never again".
 *
 * The first is deliberately weightless — it passes `null` and only waves the card away for this
 * session, so "not today" and "not this again" never share a gesture. The other four are stored.
 * All of them are reversible under Settings → Suggestions, which the footnote says out loud so
 * turning something off never feels like a door closing.
 *
 * Shared so anything Home or "For you" renders in its own richer form (the memories card) offers
 * the same dial as a plain [SignalCards] card.
 */
@Composable
fun SuggestionControlMenu(
    label: String,
    onControl: (SuggestionControls.Action?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { open = true },
            modifier = Modifier.semantics { contentDescription = "Options for: $label" },
        ) {
            Icon(
                Icons.Filled.MoreVert,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            CONTROL_ITEMS.forEach { (action, itemLabel) ->
                DropdownMenuItem(
                    text = {
                        Text(
                            itemLabel,
                            color = if (action == SuggestionControls.Action.TurnOff) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    },
                    onClick = {
                        open = false
                        onControl(action)
                    },
                )
            }
            HorizontalDivider()
            Text(
                text = "Turned-off suggestions never nag. You can turn any of them back on under " +
                    "Settings → Suggestions.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.widthIn(max = 260.dp).padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}

/**
 * Fixed menu copy, in escalating order. No generated text. A null action means "this session
 * only" — nothing is written down.
 */
private val CONTROL_ITEMS: List<Pair<SuggestionControls.Action?, String>> = listOf(
    null to "Not right now",
    SuggestionControls.Action.ShowLess to "Show less like this",
    SuggestionControls.Action.RemindLater to "Remind me in a few hours",
    SuggestionControls.Action.Hide to "Not helpful — hide it",
    SuggestionControls.Action.TurnOff to "Turn this suggestion off",
)

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
