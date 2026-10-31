package com.daymark.app.ui.goals

import com.daymark.app.ui.components.SentenceCaps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import com.daymark.app.ui.components.DaymarkSwitch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import com.daymark.app.ui.components.ConfirmDeleteDialog
import com.daymark.app.ui.components.UnsavedChangesDialog
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import java.time.format.FormatStyle
import java.time.format.DateTimeFormatter
import java.time.LocalTime
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import android.text.format.DateFormat
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daymark.app.goals.GoalKind
import com.daymark.app.goals.GoalReached
import com.daymark.app.ui.icon.ActivityIcons

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoalEditorScreen(
    onDone: () -> Unit,
    viewModel: GoalEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state.saved) {
        if (state.saved) onDone()
    }
    // The back arrow and the system Back gesture are one way out, and both ask before a change is
    // lost; the bin asks before a goal and its steps go (#486).
    var confirmingLeave by rememberSaveable { mutableStateOf(false) }
    var confirmingDelete by rememberSaveable { mutableStateOf(false) }
    fun leave() { if (viewModel.hasUnsavedChanges()) confirmingLeave = true else onDone() }
    BackHandler(enabled = !confirmingLeave) { leave() }
    if (confirmingLeave) {
        UnsavedChangesDialog(
            onSave = if (state.canSave) ({ confirmingLeave = false; viewModel.save() }) else null,
            onDiscard = { confirmingLeave = false; onDone() },
            onKeepEditing = { confirmingLeave = false },
        )
    }
    if (confirmingDelete) {
        ConfirmDeleteDialog(
            what = "goal",
            onDelete = { confirmingDelete = false; viewModel.delete() },
            onKeep = { confirmingDelete = false },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.isEditing) "Edit goal" else "New goal") },
                navigationIcon = {
                    IconButton(onClick = { leave() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (state.isEditing) {
                        IconButton(onClick = { confirmingDelete = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            OutlinedTextField(
                value = state.title,
                onValueChange = viewModel::setTitle,
                keyboardOptions = SentenceCaps,
                label = { Text("Goal") },
                placeholder = { Text("e.g. Exercise") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            // Which shape this goal is. Switching does not clear the other shape's fields — see
            // GoalEditorViewModel.setKind — so a person can look at both without losing either.
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                GoalKind.entries.forEachIndexed { index, kind ->
                    SegmentedButton(
                        selected = state.kind == kind,
                        onClick = { viewModel.setKind(kind) },
                        shape = SegmentedButtonDefaults.itemShape(index, GoalKind.entries.size),
                    ) { Text(if (kind == GoalKind.PROJECT) "Project" else "Weekly habit") }
                }
            }

            if (state.kind == GoalKind.PROJECT) {
                ProjectSteps(state = state, viewModel = viewModel)
            } else {
                Text("Track this activity", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.activities.forEach { activity ->
                        FilterChip(
                            selected = state.activityId == activity.id,
                            onClick = {
                                viewModel.setActivity(if (state.activityId == activity.id) null else activity.id)
                            },
                            label = { Text(activity.name) },
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(ActivityIcons.forKey(activity.iconKey)),
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                        )
                    }
                }

                Text("Target per week", style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    OutlinedButton(onClick = { viewModel.setTarget(state.targetPerWeek - 1) }) { Text("–") }
                    Text(
                        "${state.targetPerWeek}× / week",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    OutlinedButton(onClick = { viewModel.setTarget(state.targetPerWeek + 1) }) { Text("+") }
                }

                HabitSetTime(atMinute = state.atMinute, onChange = viewModel::setAtMinute)
            }

            Text("If-then plan (optional)", style = MaterialTheme.typography.titleMedium)
            Text(
                if (state.kind == GoalKind.PROJECT) {
                    // docs/DECISIONS.md §D5: the evidenced unit is the next concrete action,
                    // not the project. "When I want to learn statistics, I will learn statistics" is
                    // the shape this line exists to steer away from.
                    "A simple, well-evidenced nudge — aim it at the next step, not the whole project."
                } else {
                    "A simple, well-evidenced nudge: link a cue to the action."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = state.cue,
                onValueChange = viewModel::setCue,
                keyboardOptions = SentenceCaps,
                label = { Text("When… (cue)") },
                placeholder = { Text("e.g. after breakfast") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.routine,
                onValueChange = viewModel::setRoutine,
                keyboardOptions = SentenceCaps,
                label = { Text("…I will (action)") },
                placeholder = { Text("e.g. take a 10-min walk") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            if (state.isEditing) {
                // Only on a goal that already exists. On the create screen a "reached" switch would
                // be a control for something that has not happened yet, sat next to the fields that
                // describe what the person means to do.
                //
                // A switch and not a one-way "Done" button: the requirement is that a mis-tap costs
                // nothing, and a switch is the control whose reversibility a person can see before
                // they touch it. Nothing here is coloured, and there is no icon — a tick or a medal
                // would turn the state into a reward, which is what §D6 rules out under badges.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(GoalReached.LABEL, style = MaterialTheme.typography.titleMedium)
                        Text(
                            GoalReached.HINT,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // The switch's own description says which way a tap goes, because "Reached, on"
                    // read aloud does not tell someone what happens if they touch it — and the
                    // undo is the half of this control that has to be findable.
                    DaymarkSwitch(
                        checked = state.reached,
                        onCheckedChange = viewModel::setReached,
                        modifier = Modifier.semantics {
                            contentDescription = GoalReached.actionLabel(state.reachedAt)
                        },
                    )
                }
            }

            Button(
                onClick = viewModel::save,
                enabled = state.canSave,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save goal")
            }
            if (state.kind == GoalKind.PROJECT && state.steps.isEmpty()) {
                // The one refusal in this screen, stated as what is missing rather than as a
                // correction. GoalBoard.canSave carries the reasoning (§D5).
                Text(
                    "A project needs at least one step.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The board, its progress line, and the field that adds to it.
 *
 * Split out only to keep [GoalEditorScreen]'s single column readable; it holds no state of its own
 * beyond the text being typed into the "add a step" field.
 */
@Composable
private fun ProjectSteps(state: GoalEditorUiState, viewModel: GoalEditorViewModel) {
    var draft by rememberSaveable { mutableStateOf("") }

    Text("Steps", style = MaterialTheme.typography.titleMedium)
    Text(
        state.progress.summary,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            keyboardOptions = SentenceCaps,
            label = { Text("Add a step") },
            placeholder = { Text("e.g. read chapter 1") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(
            onClick = {
                viewModel.addStep(draft)
                draft = ""
            },
            enabled = draft.isNotBlank(),
        ) { Text("Add") }
    }

    GoalStepsBoard(
        steps = state.steps,
        onMove = viewModel::moveStep,
        onReorder = viewModel::reorderStep,
        onRemove = viewModel::removeStep,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * A habit's set time (#519): off unless the person turns it on, and then the time they pick. It
 * sets no reminder and nothing here notices whether the habit was done at that time; in the sky a
 * habit with a set time is drawn as a pulsar, which turns at one beat whatever the record says.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HabitSetTime(atMinute: Int?, onChange: (Int?) -> Unit) {
    var picking by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    Row(
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("At a set time", style = MaterialTheme.typography.titleMedium)
            Text(
                "Shown in your sky as a pulsar. It sets no reminder.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DaymarkSwitch(
            checked = atMinute != null,
            onCheckedChange = { on -> if (on) picking = true else onChange(null) },
        )
    }
    if (atMinute != null) {
        OutlinedButton(onClick = { picking = true }) {
            Text(
                LocalTime.of(atMinute / 60, atMinute % 60)
                    .format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)),
            )
        }
    }
    if (picking) {
        val start = atMinute ?: (8 * 60)
        val picker = rememberTimePickerState(
            initialHour = start / 60,
            initialMinute = start % 60,
            is24Hour = DateFormat.is24HourFormat(context),
        )
        AlertDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    onChange(picker.hour * 60 + picker.minute)
                    picking = false
                }) { Text("Set") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Back") } },
            text = { TimePicker(state = picker) },
        )
    }
}
