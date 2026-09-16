package com.daymark.app.ui.people

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.daymark.app.data.entity.Person
import com.daymark.app.data.entity.PersonGroup

/**
 * The entry editor's *with* picker: who, or what, you were with.
 *
 * `docs/PLAN_2026-09-SKY-PEOPLE-TIMING.md` §2: *"An entry gains **with**."* It sits beside the
 * activity chips and works the same way, because it is the same gesture — the difference is only
 * that these names came from the person rather than from a library.
 *
 * ## Grouped, and that is all a group is for
 *
 * Friends, family, partners, communities, other, in that order, and a group with nobody in it is
 * not drawn. The headings sort a list of names that would otherwise be one long run; nothing reads
 * them afterwards.
 *
 * ## Adding somebody without leaving the entry
 *
 * The dialog is right here, so a name that comes up while writing can be added and selected in one
 * move. Somebody half-way through writing about a hard day must not have to abandon it, go to
 * another screen and come back to say who they were with.
 *
 * ## No mood reaches this picker
 *
 * It is handed people and a set of selected ids, and it hands back an id. The editor writes the two
 * halves of an entry through two different repositories, and neither call carries both — see
 * `EntryEditorViewModel.save`.
 */
@Composable
fun WithPicker(
    people: List<Person>,
    selectedIds: Set<Long>,
    onToggle: (Long) -> Unit,
    onAddPerson: (String, PersonGroup) -> Unit,
    modifier: Modifier = Modifier,
) {
    var adding by remember { mutableStateOf(false) }
    val groups: List<Pair<PersonGroup, List<Person>>> = peopleByGroup(people)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (groups.isEmpty()) {
            Text(
                "Add a name and it can be picked here, and it gets a page of its own.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        groups.forEach { section ->
            Text(
                personGroupLabel(section.first),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.tertiary,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                section.second.forEach { person ->
                    FilterChip(
                        selected = selectedIds.contains(person.id),
                        onClick = { onToggle(person.id) },
                        label = { Text(person.name) },
                    )
                }
            }
        }

        OutlinedButton(
            onClick = { adding = true },
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Icon(
                Icons.Filled.Add,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text("Add someone")
        }
    }

    if (adding) {
        AddPersonDialog(
            onDismiss = { adding = false },
            onConfirm = { name, group ->
                onAddPerson(name, group)
                adding = false
            },
        )
    }
}
