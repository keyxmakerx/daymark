package com.daymark.app.ui.people

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daymark.app.R
import com.daymark.app.data.entity.Person
import com.daymark.app.data.entity.PersonGroup
import com.daymark.app.data.entity.PersonNote
import com.daymark.app.ui.components.PaperSurface
import com.daymark.app.ui.components.SentenceCaps
import com.daymark.app.ui.components.SwipeToDeleteRow
import com.daymark.app.ui.theme.LocalDaymarkTextStyles
import com.daymark.app.util.DateUtils

/**
 * A person's or a community's page: who they are in the person's own words, the dated notes
 * written about them over time, and the entries that name them.
 *
 * `docs/PLAN_2026-09-SKY-PEOPLE-TIMING.md` §2 is the design — *"the way the mood side of the app
 * is about moods"*.
 *
 * ## What is on this page and what is not
 *
 * There is no mood on it, in any form. Not a face beside an entry, not a colour, not a word, not a
 * summary of the ones that name them. §2: *"Never in any rule that reads mood ... Correlations,
 * patterns and the cards they produce cannot receive a person or a community, groups included."*
 * A column of coloured faces under somebody's name is that correlation drawn for the eye, so the
 * entries here are a date and the person's own words, and the whole record is one tap away on the
 * entry's own page where it belongs.
 *
 * ## The one fact it may state, and the sentence it may never say
 *
 * §2 allows *last note: June* when the page is opened, and forbids *"you haven't written about X
 * in a while"* anywhere, ever. The difference is not politeness: the first is the record answering
 * a question that was just asked of it, and the second is the app noticing a silence about
 * somebody and raising it unprompted — which lands hardest on exactly the person it would find.
 * So the date line is drawn only when there is a note to date, and when there is none this page
 * says nothing about it at all.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonScreen(
    onBack: () -> Unit,
    onOpenEntry: (Long) -> Unit,
    onOpenSharing: () -> Unit,
    viewModel: PersonViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var writing by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var confirmingArchive by remember { mutableStateOf(false) }

    val person: Person? = state.person

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(person?.name ?: "") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (person != null) {
                        IconButton(onClick = { editing = true }) {
                            Icon(
                                Icons.Filled.Edit,
                                contentDescription = "Edit name, group and description",
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (person != null) {
                ExtendedFloatingActionButton(
                    onClick = { writing = true },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    text = { Text("Write") },
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_ui_plus),
                            contentDescription = null,
                        )
                    },
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (person == null) {
                // Only ever said once the read has come back. Before that the page is simply
                // blank, rather than telling somebody their friend is gone while it loads.
                if (state.loaded) {
                    Text(
                        "This page is no longer here.",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
            } else {
                WhoTheyAreBlock(person = person, onWrite = { editing = true })

                NotesBlock(
                    notes = state.notes,
                    lastNoteAt = state.lastNoteAt,
                    onDeleteNote = viewModel::deleteNote,
                )

                EntriesBlock(entries = state.entries, onOpenEntry = onOpenEntry)

                ArchiveBlock(
                    person = person,
                    onArchive = { confirmingArchive = true },
                    onRestore = { viewModel.setArchived(false) },
                )

                SharingLine(shared = state.shared, onOpenSharing = onOpenSharing)
            }
        }
    }

    if (writing && person != null) {
        WriteNoteDialog(
            personName = person.name,
            onDismiss = { writing = false },
            onConfirm = { body ->
                viewModel.addNote(body)
                writing = false
            },
        )
    }

    if (editing && person != null) {
        EditPersonDialog(
            person = person,
            onDismiss = { editing = false },
            onConfirm = { name, group, whoTheyAre ->
                viewModel.edit(name, group, whoTheyAre)
                editing = false
            },
        )
    }

    if (confirmingArchive && person != null) {
        ArchiveDialog(
            personName = person.name,
            onDismiss = { confirmingArchive = false },
            onConfirm = {
                viewModel.setArchived(true)
                confirmingArchive = false
            },
        )
    }
}

/**
 * The *"who (or what) is this to you"* line.
 *
 * Free text, shown exactly as it was typed. The app never parses it, never matches it against a
 * vocabulary and never infers a group from it — `data/entity/Person.kt` says so and means it.
 *
 * When it is blank there is an invitation to write one, and no comment on its being blank. A name
 * on its own is a whole answer.
 */
@Composable
private fun WhoTheyAreBlock(person: Person, onWrite: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            personGroupLabelForKey(person.groupKey),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (person.whoTheyAre.isNotBlank()) {
            PaperSurface(modifier = Modifier.fillMaxWidth()) {
                Text(
                    person.whoTheyAre,
                    style = LocalDaymarkTextStyles.current.diaryNote,
                    modifier = Modifier.padding(16.dp),
                )
            }
        } else {
            OutlinedButton(
                onClick = onWrite,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text("Write who or what this is to you")
            }
        }
    }
}

/** The dated notes, newest first, and the one fact this page is allowed to state about them. */
@Composable
private fun NotesBlock(
    notes: List<PersonNote>,
    lastNoteAt: Long?,
    onDeleteNote: (PersonNote) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Notes", style = MaterialTheme.typography.titleMedium)
        if (lastNoteAt != null) {
            Text(
                "Last note: " + DateUtils.formatMonthYear(lastNoteAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (notes.isEmpty()) {
            Text(
                "Anything you write about them stays on this page, in your own words.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            PaperSurface(modifier = Modifier.fillMaxWidth()) {
                Column {
                    notes.forEachIndexed { index, note ->
                        SwipeToDeleteRow(
                            onDelete = { onDeleteNote(note) },
                            confirmTitle = "Delete this note?",
                            confirmBody = "This removes what you wrote on " +
                                DateUtils.formatDate(note.dateTime) +
                                ". It can't be undone from here. Every entry that names them " +
                                "is untouched.",
                            swipeLabel = "Delete note",
                        ) {
                            NoteRow(note)
                        }
                        if (index < notes.lastIndex) {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NoteRow(note: PersonNote) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            DateUtils.formatDate(note.dateTime),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.tertiary,
        )
        Text(note.body, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * The entries that name them.
 *
 * A date, a time and the person's own words — never the mood word, never a face, never a colour.
 * See this file's header for why a page about somebody is the one place those must not be drawn
 * together. The entry's own page shows the whole record.
 */
@Composable
private fun EntriesBlock(entries: List<PersonEntryLine>, onOpenEntry: (Long) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Entries that name them", style = MaterialTheme.typography.titleMedium)
        if (entries.isEmpty()) {
            Text(
                "No entry names them.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            PaperSurface(modifier = Modifier.fillMaxWidth()) {
                Column {
                    entries.forEachIndexed { index, line ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenEntry(line.entryId) }
                                .heightIn(min = 48.dp)
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                DateUtils.formatDate(line.dateTime) + " · " +
                                    DateUtils.formatTime(line.dateTime),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                            if (line.note.isNotBlank()) {
                                Text(
                                    line.note,
                                    style = LocalDaymarkTextStyles.current.diaryNote,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (index < entries.lastIndex) {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Archive, and the sentence that has to be next to it.
 *
 * §2: *"Archive hides one from the picker. Entries and notes are untouched."* That promise is
 * printed under the button as well as inside the dialog, because somebody deciding whether to
 * archive a person who has left their life is deciding whether the record of the years they were
 * in it survives, and the answer has to be visible before the tap, not only after it.
 */
@Composable
private fun ArchiveBlock(person: Person, onArchive: () -> Unit, onRestore: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (person.archived) {
            Text(
                "Archived: they are not offered in the with picker. Everything written about " +
                    "them is still here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = onRestore,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text("Put back in the picker")
            }
        } else {
            Text(
                "Archiving only hides them from the with picker. Every entry that names them " +
                    "and every note you have written stays exactly as it is.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = onArchive,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text("Archive")
            }
        }
    }
}

/**
 * The one quiet line at the foot: what the sharing state *is*, and where it is changed.
 *
 * §2: *"On a person's page the state is one quiet line at the bottom: present, never a nag."* So
 * it is a statement in the same register whichever way it reads. There is no warning colour, no
 * icon, no "consider sharing this" and nothing that makes one of the two answers look like the
 * right one. Off is the default and staying off is a complete decision.
 */
@Composable
private fun SharingLine(shared: Boolean, onOpenSharing: () -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.padding(bottom = 96.dp),
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            if (shared) "Shared with your clinician." else "Not shared with your clinician.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        TextButton(
            onClick = onOpenSharing,
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text("Open the sharing screen")
        }
    }
}

/** A new dated note, in the person's own words. One field and nothing else. */
@Composable
private fun WriteNoteDialog(
    personName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var body by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Write about " + personName) },
        text = {
            OutlinedTextField(
                value = body,
                onValueChange = { body = it },
                keyboardOptions = SentenceCaps,
                placeholder = { Text("Whatever you want to keep.") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 4,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(body) }, enabled = body.isNotBlank()) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Name, group and the free-text line, in one dialog.
 *
 * The group starts unselected when the stored key is one this build does not recognise, and in
 * that case `null` is handed back so the stored string is left alone — `PersonViewModel.edit` has
 * the reason.
 */
@Composable
private fun EditPersonDialog(
    person: Person,
    onDismiss: () -> Unit,
    onConfirm: (String, PersonGroup?, String) -> Unit,
) {
    var name by remember { mutableStateOf(person.name) }
    var whoTheyAre by remember { mutableStateOf(person.whoTheyAre) }
    var group by remember {
        mutableStateOf(
            if (PersonGroup.isKnown(person.groupKey)) PersonGroup.fromKey(person.groupKey) else null,
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit") },
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
                OutlinedTextField(
                    value = whoTheyAre,
                    onValueChange = { whoTheyAre = it },
                    keyboardOptions = SentenceCaps,
                    label = { Text("Who or what is this to you") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
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
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name, group, whoTheyAre) },
                enabled = name.isNotBlank(),
            ) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * The archive confirmation.
 *
 * The body says what archiving does and what it leaves alone, in that order, and the dismiss
 * button says what keeping them means rather than "Cancel" — the two outcomes are named, so
 * neither is the one you get by not reading.
 */
@Composable
private fun ArchiveDialog(
    personName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Archive " + personName + "?") },
        text = {
            Text(
                "They stop appearing in the with picker when you write an entry. Every entry " +
                    "that names them, every note you have written and this page all stay " +
                    "exactly as they are. You can put them back at any time.",
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Archive") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep in the picker") } },
    )
}
