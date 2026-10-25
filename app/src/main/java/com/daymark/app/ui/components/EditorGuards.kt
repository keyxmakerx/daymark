package com.daymark.app.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * Asked when leaving an editor with changes nobody saved, by the back arrow or the system Back
 * gesture alike, so the two can never mean different things (#486). [onSave] is null when the form
 * cannot be saved as it stands (a goal with no title); the person can still keep editing or leave.
 */
@Composable
fun UnsavedChangesDialog(
    onSave: (() -> Unit)?,
    onDiscard: () -> Unit,
    onKeepEditing: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onKeepEditing,
        title = { Text("Save your changes?") },
        text = { Text("You haven't saved this yet. Leaving without saving loses what you changed.") },
        confirmButton = {
            if (onSave != null) {
                TextButton(onClick = onSave) { Text("Save") }
            } else {
                TextButton(onClick = onKeepEditing) { Text("Keep editing") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDiscard) { Text("Leave without saving", color = MaterialTheme.colorScheme.error) }
        },
    )
}

/**
 * Asked before the bin deletes anything a person wrote (#486). The same words as the entry
 * editor's: a delete says it cannot be undone, and the safe answer is the one that keeps it.
 */
@Composable
fun ConfirmDeleteDialog(what: String, onDelete: () -> Unit, onKeep: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeep,
        title = { Text("Delete this $what?") },
        text = { Text("This can't be undone from here.") },
        confirmButton = {
            TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onKeep) { Text("Keep it") } },
    )
}
