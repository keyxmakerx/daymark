package com.daymark.app.ui.people

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daymark.app.data.PeopleRepository
import com.daymark.app.data.entity.PersonGroup

/**
 * "Sharing" — one list of every person and community, a default per group, an override per item.
 *
 * `docs/FEATURES.md` §11.4. The word **clinician** is used throughout: a therapist, a doctor and a
 * psychiatrist are one role to this app.
 *
 * ## Everything is off, and off is a complete answer
 *
 * Nothing on this screen leans. There is no colour that means "good", no icon that means "done",
 * no count of how much is shared and no sentence suggesting that sharing more would be better.
 * `CLAUDE.md` §4 rules out the whole vocabulary, and the reason is sharper here than anywhere
 * else: what is on this screen is who somebody tells another human being about, and an app with an
 * opinion about that is an app applying pressure at the exact point where it has no standing.
 *
 * ## Why a switch plus a separate "Follow the group" button
 *
 * The stored value has three states — the person's own yes, their own no, and *no answer, follow my
 * group* — and `Person.sharedOverride` explains why the third one has to exist. A switch can only
 * show two, so it shows the **resolved** answer and moving it always writes an answer of this
 * person's own. Going back to the group is its own control, shown only when there is an answer to
 * give back. Nothing here is a tri-state toggle nobody can operate by touch.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeopleSharingScreen(
    onBack: () -> Unit,
    viewModel: PeopleSharingViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sharing") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Everything here is off until you turn it on. A clinician sees exactly the words " +
                    "you wrote about the ones that are on, and the entries that name them.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )

            if (state.loaded && state.groups.isEmpty()) {
                Text(
                    "There is nobody to share. Names you add appear here.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            state.groups.forEach { section ->
                GroupBlock(
                    group = section.first,
                    rows = section.second,
                    groupDefault = state.groupDefaults[section.first.key] ?: PeopleRepository.SHARING_OFF,
                    onSetGroupDefault = { shared -> viewModel.setGroupDefault(section.first, shared) },
                    onSetOverride = { row, shared -> viewModel.setOverride(row.person, shared) },
                    onFollowGroup = { row -> viewModel.followGroup(row.person) },
                )
            }

            Text(
                "Turning a group on does not move anybody who has set their own answer.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 32.dp),
            )
        }
    }
}

@Composable
private fun GroupBlock(
    group: PersonGroup,
    rows: List<SharingRow>,
    groupDefault: Boolean,
    onSetGroupDefault: (Boolean) -> Unit,
    onSetOverride: (SharingRow, Boolean) -> Unit,
    onFollowGroup: (SharingRow) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(personGroupLabel(group), style = MaterialTheme.typography.titleMedium)
                Text(
                    "Everyone in this group, unless they have their own answer.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = groupDefault,
                onCheckedChange = onSetGroupDefault,
                modifier = Modifier.semantics {
                    contentDescription = "Share " + personGroupLabel(group) + " with your clinician"
                },
            )
        }

        rows.forEach { row ->
            PersonSharingRow(
                row = row,
                group = group,
                onSetOverride = { shared -> onSetOverride(row, shared) },
                onFollowGroup = { onFollowGroup(row) },
            )
        }
    }
}

/**
 * One name, what currently applies to it, and the two controls that change it.
 *
 * The state line always says the same two things in the same order — whether it is shared, and
 * whose answer that is. "Not shared, following Friends" and "Not shared, set for this one" look
 * identical on a switch and behave differently the moment the group moves, so the screen says
 * which one it is rather than leaving it to be discovered.
 */
@Composable
private fun PersonSharingRow(
    row: SharingRow,
    group: PersonGroup,
    onSetOverride: (Boolean) -> Unit,
    onFollowGroup: () -> Unit,
) {
    val stateLine: String = if (row.followsGroup) {
        (if (row.resolved) "Shared" else "Not shared") + ", following " + personGroupLabel(group)
    } else {
        (if (row.resolved) "Shared" else "Not shared") + ", set for this one"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(row.person.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                stateLine,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (row.person.archived) {
                Text(
                    "Archived. The entries that name them are still here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!row.groupIsKnown) {
                // Written by a newer version of the app. The stored key is kept exactly as it is
                // rather than rewritten to a group this build understands, so no group default
                // reaches it and its own answer is the only one that can.
                Text(
                    "This one was filed by a newer version, so no group default reaches it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!row.followsGroup) {
                TextButton(
                    onClick = onFollowGroup,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("Follow " + personGroupLabel(group) + " again")
                }
            }
        }
        Switch(
            checked = row.resolved,
            onCheckedChange = onSetOverride,
            modifier = Modifier.semantics {
                contentDescription = "Share " + row.person.name + " with your clinician"
            },
        )
    }
}
