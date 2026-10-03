package com.daymark.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * The person's answer to "what should happen when these go unanswered?": ease off (§D1a), or keep
 * reminding at the times they set (`CheckInEngine.paceOf`'s `keepAsSet`). Easing off is right for
 * a check-in about the day and backwards for a medication, and only the person knows which this is,
 * so it is asked whenever check-ins are switched on, and shown wherever they can be changed.
 *
 * [keep] is null while unanswered: nothing is selected, and the screens asking hold their Save
 * until there is an answer.
 */
@Composable
fun KeepTimesChoice(keep: Boolean?, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.selectableGroup()) {
        Option(
            title = EASE_TITLE,
            detail = "Fewer check-ins until you answer again. Daymark tells you each time, with a way to put it back.",
            selected = keep == false,
            onSelect = { onChange(false) },
        )
        Option(
            title = KEEP_TITLE,
            detail = "Always at the times you set, never fewer. For something like medication.",
            selected = keep == true,
            onSelect = { onChange(true) },
        )
    }
}

/** The same question as a dialog, asked at the moment check-ins are switched on. Dismissing it changes nothing. */
@Composable
fun KeepTimesDialog(onAnswer: (Boolean) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(QUESTION) },
        text = { KeepTimesChoice(keep = null, onChange = onAnswer) },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private const val QUESTION = "What if these go unanswered?"
private const val EASE_TITLE = "Ease off if I'm not answering"
private const val KEEP_TITLE = "Keep reminding me at these times"

@Composable
private fun Option(title: String, detail: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
