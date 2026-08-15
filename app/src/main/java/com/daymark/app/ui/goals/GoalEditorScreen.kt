package com.daymark.app.ui.goals

import com.daymark.app.ui.components.SentenceCaps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daymark.app.goals.GoalKind
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.isEditing) "Edit goal" else "New goal") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (state.isEditing) {
                        IconButton(onClick = viewModel::delete) {
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
            }

            Text("If-then plan (optional)", style = MaterialTheme.typography.titleMedium)
            Text(
                if (state.kind == GoalKind.PROJECT) {
                    // docs/DECISIONS_2026-08.md §D5: the evidenced unit is the next concrete action,
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
