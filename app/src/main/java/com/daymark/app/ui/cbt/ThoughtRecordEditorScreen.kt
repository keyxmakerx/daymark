package com.daymark.app.ui.cbt

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daymark.app.model.Mood
import com.daymark.app.ui.components.MoodFaceIcon

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ThoughtRecordEditorScreen(
    onDone: () -> Unit,
    viewModel: ThoughtRecordEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.saved) { if (state.saved) onDone() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.isEditing) "Thought record" else "New thought record") },
                navigationIcon = {
                    IconButton(onClick = viewModel::save) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (state.isEditing) {
                        IconButton(onClick = viewModel::delete) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete")
                        }
                    }
                    IconButton(onClick = viewModel::save) {
                        Icon(Icons.Filled.Check, contentDescription = "Save")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "A structured way to examine a thought — not a verdict on whether it's true.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Field("Situation", "What was happening?", state.situation, viewModel::setSituation)
            MoodPick("Mood before", state.moodBefore, viewModel::setMoodBefore)
            Field("Automatic thought", "What went through your mind?", state.automaticThought, viewModel::setThought)

            Text("Any thinking traps?", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CognitiveDistortions.ALL.forEach { d ->
                    FilterChip(
                        selected = state.distortions.contains(d.key),
                        onClick = { viewModel.toggleDistortion(d.key) },
                        label = { Text(d.name) },
                    )
                }
            }

            Field("Evidence for", "What supports the thought?", state.evidenceFor, viewModel::setEvidenceFor)
            Field("Evidence against", "What doesn't fit it?", state.evidenceAgainst, viewModel::setEvidenceAgainst)
            Field("Balanced thought", "A fairer way to see it?", state.balancedThought, viewModel::setBalanced)
            MoodPick("Mood after", state.moodAfter, viewModel::setMoodAfter)
        }
    }
}

@Composable
private fun Field(label: String, placeholder: String, value: String, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            placeholder = { Text(placeholder) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
        )
    }
}

@Composable
private fun MoodPick(label: String, level: Int, onPick: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Mood.ascending.forEach { mood ->
                MoodFaceIcon(
                    level = mood.level,
                    size = 40.dp,
                    selected = level == mood.level,
                    onClick = { onPick(mood.level) },
                )
            }
        }
    }
}
