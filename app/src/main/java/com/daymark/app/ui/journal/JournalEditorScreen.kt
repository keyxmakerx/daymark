package com.daymark.app.ui.journal

import com.daymark.app.ui.components.SentenceCaps

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JournalEditorScreen(
    onDone: () -> Unit,
    onOpenSupport: () -> Unit = {},
    viewModel: JournalEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showSafetyNote by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

    LaunchedEffect(state.saved) {
        if (state.saved) onDone()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.isEditing) "Edit entry" else "New journal entry") },
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
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Writing starters — only for a fresh, empty entry.
            if (!state.isEditing && state.title.isBlank() && state.body.isBlank()) {
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier.fillMaxWidth()
                        .horizontalScroll(androidx.compose.foundation.rememberScrollState())
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    JournalTemplates.ALL.forEach { t ->
                        androidx.compose.material3.AssistChip(
                            onClick = {
                                viewModel.applyTemplate(t)
                                showSafetyNote = t.safetyNote
                            },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
            if (showSafetyNote) {
                Text(
                    "Writing about hard things can stir up difficult feelings. Go at your own pace, " +
                        "and stop any time. If it feels like too much, support is available.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
                androidx.compose.material3.TextButton(onClick = onOpenSupport) {
                    Text("See support options")
                }
            }
            // A borderless title field that reads like a heading.
            OutlinedTextField(
                value = state.title,
                onValueChange = viewModel::setTitle,
                keyboardOptions = SentenceCaps,
                placeholder = { Text("Title", style = MaterialTheme.typography.headlineSmall) },
                textStyle = MaterialTheme.typography.headlineSmall,
                singleLine = true,
                colors = borderless(),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.body,
                onValueChange = viewModel::setBody,
                keyboardOptions = SentenceCaps,
                placeholder = { Text("Write freely…") },
                textStyle = MaterialTheme.typography.bodyLarge,
                colors = borderless(),
                minLines = 10,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun borderless() = TextFieldDefaults.colors(
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
)
