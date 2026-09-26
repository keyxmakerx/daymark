package com.daymark.app.ui.entry

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daymark.app.data.entity.ActivityEntity
import com.daymark.app.data.entity.LifeEvent
import com.daymark.app.data.entity.Person
import com.daymark.app.ui.components.EntryPhoto
import com.daymark.app.ui.components.MoodFaceIcon
import com.daymark.app.ui.components.PaperSurface
import com.daymark.app.ui.components.SentenceCaps
import com.daymark.app.ui.icon.ActivityIcons
import com.daymark.app.ui.theme.LocalDaymarkTextStyles
import com.daymark.app.ui.theme.moodLabels
import com.daymark.app.util.DateUtils

/**
 * One entry, read back.
 *
 * `docs/FEATURES.md` §1.3: *"The page is descriptive only: the person's own mood word, their
 * activities, who they were with, their note and their photo. There is no commentary of any kind:
 * no "you seem", no comparison with another day, no count, no encouragement."*
 *
 * ## The sentence this page may never contain
 *
 * Not one word on this screen describes, interprets, summarises or responds to what is on it.
 * There is no "you seem", no "that sounds", no "a good day", no comparison with yesterday, no
 * count, no score and no encouragement. `CLAUDE.md` §0 is the rule and the reason: generated or
 * templated commentary aimed at somebody in distress is the hazard the whole architecture exists
 * to avoid, and a page showing one hard day is precisely where a well-meaning sentence wants to
 * appear. Every string here is a label on a field or the name of a control.
 *
 * The mood **word** is shown, because the person chose it — it is their label, possibly one they
 * renamed themselves, read back to them. What is not shown is anything the app concluded from it.
 *
 * `EntryViewCopySourceTest` reads the strings in this file and fails on the vocabulary, with
 * planted examples proving the check can see one.
 *
 * ## Why the *with* chips lead to a page and not to a statistic
 *
 * Tapping a name opens that person's page: their own description of them, the notes they wrote,
 * the entries that name them. It does not open anything that has counted, averaged or compared
 * moods across them, because no such surface exists — `docs/FEATURES.md` §11.2 forbids it and
 * `data/PeopleRepository.kt` is shaped so the query behind it cannot be written by accident.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryViewScreen(
    onBack: () -> Unit,
    onEdit: (Long) -> Unit,
    onOpenPerson: (Long) -> Unit,
    viewModel: EntryViewViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var marking by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(if (state.exists) DateUtils.formatDate(state.dateTime) else "")
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (state.exists) {
                        IconButton(onClick = { onEdit(state.entryId) }) {
                            Icon(Icons.Filled.Edit, contentDescription = "Edit this entry")
                        }
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
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (!state.exists) {
                if (state.loaded) {
                    Text(
                        "This entry is no longer here.",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
            } else {
                MoodLine(moodLevel = state.moodLevel, dateTime = state.dateTime)

                if (state.activities.isNotEmpty()) {
                    ActivitiesBlock(state.activities)
                }

                if (state.withPeople.isNotEmpty()) {
                    WithBlock(people = state.withPeople, onOpenPerson = onOpenPerson)
                }

                if (state.note.isNotBlank()) {
                    PaperSurface(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            state.note,
                            style = LocalDaymarkTextStyles.current.diaryNote,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }

                val photoPath = state.photoPath
                if (photoPath != null) {
                    EntryPhoto(photoPath = photoPath, size = 220.dp, cornerRadius = 16.dp)
                }

                MarkThisDayBlock(
                    marks = state.marksOnThisDay,
                    onMark = { marking = true },
                )
            }
        }
    }

    if (marking) {
        MarkThisDayDialog(
            dateLabel = DateUtils.formatDate(state.dateTime),
            onDismiss = { marking = false },
            onConfirm = { label ->
                viewModel.markThisDay(label)
                marking = false
            },
        )
    }
}

/**
 * The person's own word for the level they picked, and the time they picked it.
 *
 * `MaterialTheme.moodLabels` is their label, including one they renamed. Nothing is added to it —
 * no adjective, no qualifier, no second sentence about it.
 */
@Composable
private fun MoodLine(moodLevel: Int, dateTime: Long) {
    Row(
        modifier = Modifier.padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        MoodFaceIcon(level = moodLevel, size = 56.dp)
        Column {
            Text(
                MaterialTheme.moodLabels.forLevel(moodLevel),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                DateUtils.formatTime(dateTime),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ActivitiesBlock(activities: List<ActivityEntity>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Activities", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            activities.forEach { activity ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        painter = painterResource(ActivityIcons.forKey(activity.iconKey)),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        activity.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Who, or what, this entry names. A chip each, leading to that page and nowhere else. */
@Composable
private fun WithBlock(people: List<Person>, onOpenPerson: (Long) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("With", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            people.forEach { person ->
                AssistChip(
                    onClick = { onOpenPerson(person.id) },
                    label = { Text(person.name) },
                )
            }
        }
    }
}

/**
 * "Mark this day" — a life event placed on this entry's date, from the day it is about.
 *
 * `docs/SKY.md` §2.2 keeps one writer for `life_events`: a person's own tap. This is a second
 * door onto the same act and not a second kind of thing — the mark asks for a date and a few
 * words, both of which the person supplies, and the app contributes nothing to it.
 *
 * Marks already on this date are listed as a plain fact. There is no "you have not marked this
 * day", because a day without a mark is the ordinary case and not a gap in anything.
 */
@Composable
private fun MarkThisDayBlock(marks: List<LifeEvent>, onMark: () -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(bottom = 32.dp),
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text("Mark this day", style = MaterialTheme.typography.titleMedium)
        Text(
            "A date and a few words, for something that mattered. It joins your life events and " +
                "shows as a landmark in your sky.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (marks.isNotEmpty()) {
            marks.forEach { mark ->
                Text(
                    "Marked: " + mark.label,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        OutlinedButton(
            onClick = onMark,
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text("Add a mark")
        }
    }
}

@Composable
private fun MarkThisDayDialog(
    dateLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var label by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mark " + dateLabel) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    keyboardOptions = SentenceCaps,
                    label = { Text("A few words") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Text(
                    "Nothing else is asked, and nothing here is added for you.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(label) }, enabled = label.isNotBlank()) {
                Text("Mark it")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
