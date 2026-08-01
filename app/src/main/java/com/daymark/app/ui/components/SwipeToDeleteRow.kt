package com.daymark.app.ui.components

import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * How far a row must travel before the swipe counts as a delete gesture, as a fraction of the
 * row's width. Material's default (half the row, plus a velocity shortcut on a quick flick) makes
 * an accidental delete far too cheap on a screen you scroll every day, so this deliberately asks
 * for most of the row.
 */
private const val CommitFraction = 0.62f

/**
 * A list row that can be swiped away — deliberately, and never in one motion.
 *
 * Two guards sit in front of the delete, because losing a logged day to a stray thumb is the kind
 * of small loss this app should never cause:
 *
 *  1. **A long swipe.** A steady drag only arms past [CommitFraction] of the row's width, and the
 *     background stays muted ("Keep swiping") until it does — so a half-swipe reads as "not yet"
 *     rather than "gone". Material's velocity shortcut can still settle a hard *flick* to the
 *     dismissed anchor short of that distance, and it is not configurable — which is exactly why
 *     the second guard exists and why the gesture is never allowed to be the delete.
 *  2. **A confirmation.** The swipe itself never removes anything: [confirmValueChange] always
 *     returns `false`, so the row springs back and a dialog asks first. [onDelete] runs only when
 *     the person confirms there. A stray flick therefore costs a tap on "Keep it", never an entry.
 *
 * The caller is still expected to offer undo afterwards (the snackbar on Home / History) — this is
 * the front half of that safety net, not a replacement for it.
 *
 * @param onDelete invoked only after the confirmation dialog is accepted.
 * @param confirmTitle dialog title, e.g. "Delete this entry?".
 * @param confirmBody dialog body — say plainly what is lost and that it can be undone.
 * @param swipeLabel accessibility description of the gesture target, e.g. "Delete entry".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeToDeleteRow(
    onDelete: () -> Unit,
    confirmTitle: String,
    confirmBody: String,
    modifier: Modifier = Modifier,
    swipeLabel: String = "Delete",
    confirmLabel: String = "Delete",
    cancelLabel: String = "Keep it",
    content: @Composable () -> Unit,
) {
    var askingToDelete by remember { mutableStateOf(false) }
    val view = LocalView.current

    val dismissState = rememberSwipeToDismissBoxState(
        positionalThreshold = { totalDistance -> totalDistance * CommitFraction },
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) askingToDelete = true
            // Always false: the gesture opens the question, the dialog answers it. The row snaps
            // back either way, so nothing ever disappears from under your thumb.
            false
        },
    )

    // Armed = the swipe has passed the commit point, so releasing now would ask to delete.
    val armed = dismissState.targetValue == SwipeToDismissBoxValue.EndToStart
    LaunchedEffect(armed) {
        if (armed) view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    val backgroundColor by animateColorAsState(
        targetValue = if (armed) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        label = "swipeDeleteBackground",
    )
    val foregroundColor = if (armed) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .background(backgroundColor)
                    .padding(horizontal = 20.dp)
                    .semantics { contentDescription = swipeLabel },
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (armed) "Release to delete" else "Keep swiping",
                    style = MaterialTheme.typography.labelMedium,
                    color = foregroundColor,
                    modifier = Modifier.padding(end = 10.dp),
                )
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = null,
                    tint = foregroundColor,
                )
            }
        },
    ) {
        // Opaque surface so the row fully covers the background until swiped.
        Box(modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
            content()
        }
    }

    if (askingToDelete) {
        AlertDialog(
            onDismissRequest = { askingToDelete = false },
            title = { Text(confirmTitle) },
            text = { Text(confirmBody) },
            confirmButton = {
                TextButton(
                    onClick = {
                        askingToDelete = false
                        onDelete()
                    },
                ) {
                    Text(confirmLabel, color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { askingToDelete = false }) { Text(cancelLabel) }
            },
        )
    }
}
