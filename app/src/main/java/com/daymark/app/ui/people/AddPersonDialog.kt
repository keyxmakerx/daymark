package com.daymark.app.ui.people

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.daymark.app.data.entity.PersonGroup
import com.daymark.app.ui.components.SentenceCaps

/**
 * "Add a person or community" — one field and five chips, used by the people list and by the
 * entry editor's *with* picker so somebody can be added without leaving the entry they are
 * half-way through writing.
 *
 * ## What it asks for, and what it does not
 *
 * A name, and which drawer of the picker it goes in. That is all. There is no closeness, no
 * status, no "how do you know them", no date and no photo — `data/entity/Person.kt` sets out why
 * each of those is absent, and the short version is that nothing here grades anybody's
 * relationships back at them.
 *
 * The "who (or what) is this to you" line is deliberately **not** asked for here. It belongs on
 * their page, in their own time, and a second empty box in front of somebody mid-entry is a form.
 */
@Composable
fun AddPersonDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, PersonGroup) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var group by remember { mutableStateOf(PersonGroup.DEFAULT) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a person or community") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    keyboardOptions = SentenceCaps,
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Group", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PersonGroupOrder.forEach { candidate ->
                        FilterChip(
                            selected = candidate == group,
                            onClick = { group = candidate },
                            label = { Text(personGroupLabel(candidate)) },
                        )
                    }
                }
                Text(
                    "Groups only sort the picker. Nothing else reads them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim(), group) },
                enabled = name.isNotBlank(),
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
