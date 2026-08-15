package com.daymark.app.ui.goals

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.daymark.app.goals.GoalBoard
import com.daymark.app.ui.components.PaperSurface

/**
 * The three columns a project's steps sit in — the clinician's "like the programming tool", with
 * the parts of that tool that do not belong in this product left out
 * (`docs/CLINICIAN_FEEDBACK.md` §3).
 *
 * ## What was left out, and why
 *
 * **No counts on the column headers.** `docs/DECISIONS_2026-08.md` §D6 rules out "count badges on
 * pending columns", and a badge on *To do* is a running total of things a person has not done. The
 * one number on the screen is the caller's "n of m steps done", stated once.
 *
 * **Nothing is coloured by state, and nothing is red.** All three columns look the same. A step that
 * has been in *Doing* for six weeks is drawn exactly like one added this morning, because the board
 * has no way to know which of those is the problem and no business guessing.
 *
 * **No drag and drop.** Buttons instead: ← → between columns, ↑ ↓ within one. Drag on a horizontally
 * scrolling board fights the scroll gesture, and every control here has a content description, which
 * a drag target would not.
 *
 * The columns scroll horizontally as a group rather than being squeezed to a third of a phone each,
 * so a step's title is readable rather than three words wrapped to four lines.
 */
@Composable
fun GoalStepsBoard(
    steps: List<GoalBoard.Step>,
    onMove: (Long, GoalBoard.StepState) -> Unit,
    onReorder: (Long, Int) -> Unit,
    onRemove: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GoalBoard.COLUMNS.forEachIndexed { index, state ->
            val column = GoalBoard.columnOf(steps, state)
            Column(
                modifier = Modifier.width(240.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    state.columnTitle,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (column.isEmpty()) {
                    Text(
                        state.emptyNote,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    column.forEachIndexed { row, step ->
                        StepCard(
                            step = step,
                            canMoveLeft = index > 0,
                            canMoveRight = index < GoalBoard.COLUMNS.lastIndex,
                            canMoveUp = row > 0,
                            canMoveDown = row < column.lastIndex,
                            onMoveLeft = { onMove(step.id, GoalBoard.COLUMNS[index - 1]) },
                            onMoveRight = { onMove(step.id, GoalBoard.COLUMNS[index + 1]) },
                            onReorder = { delta -> onReorder(step.id, delta) },
                            onRemove = { onRemove(step.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StepCard(
    step: GoalBoard.Step,
    canMoveLeft: Boolean,
    canMoveRight: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveLeft: () -> Unit,
    onMoveRight: () -> Unit,
    onReorder: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    PaperSurface(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(step.title, style = MaterialTheme.typography.bodyMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Ends of a column and ends of the board disable rather than hide their button, so
                // the row of controls does not reflow under the finger as a step moves.
                StepControl(
                    icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    description = "Move \"${step.title}\" one column left",
                    enabled = canMoveLeft,
                    onClick = onMoveLeft,
                )
                StepControl(
                    icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    description = "Move \"${step.title}\" one column right",
                    enabled = canMoveRight,
                    onClick = onMoveRight,
                )
                StepControl(
                    icon = Icons.Filled.KeyboardArrowUp,
                    description = "Move \"${step.title}\" up",
                    enabled = canMoveUp,
                    onClick = { onReorder(-1) },
                )
                StepControl(
                    icon = Icons.Filled.KeyboardArrowDown,
                    description = "Move \"${step.title}\" down",
                    enabled = canMoveDown,
                    onClick = { onReorder(1) },
                )
                StepControl(
                    icon = Icons.Filled.Close,
                    description = "Remove \"${step.title}\"",
                    enabled = true,
                    onClick = onRemove,
                )
            }
        }
    }
}

@Composable
private fun StepControl(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(36.dp)) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(18.dp))
    }
}
