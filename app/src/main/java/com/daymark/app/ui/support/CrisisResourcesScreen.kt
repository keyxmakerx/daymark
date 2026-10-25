package com.daymark.app.ui.support

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daymark.app.ui.components.PaperSurface

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrisisResourcesScreen(
    onBack: () -> Unit,
    viewModel: CrisisViewModel = hiltViewModel(),
) {
    val resource by viewModel.resource.collectAsStateWithLifecycle()
    val confirmed by viewModel.confirmed.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("If things feel like too much") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                "You don't have to handle hard moments alone. Reaching out is a strong thing to do.",
                style = MaterialTheme.typography.bodyLarge,
            )
            PaperSurface(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(resource.label, style = MaterialTheme.typography.titleMedium)
                    Text(resource.contact, style = MaterialTheme.typography.headlineSmall)
                }
            }
            if (confirmed) {
                CrisisCallButtons(resource)
            } else {
                // A line nobody has confirmed is shown as text and asked about, never handed over as
                // a one-tap call: the default is a US number (#482).
                Text(
                    "Is this the right line where you live?",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Button(onClick = viewModel::confirm, modifier = Modifier.fillMaxWidth()) {
                    Text("This one is right")
                }
            }
            Text(
                "If you're in immediate danger, call your local emergency number.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { editing = true }) { Text("Use a different number") }
            Text(
                "Daymark never places a call: Call opens your phone with the number filled in, and you " +
                    "press call. It isn't a crisis service — this line is kept on your device so it's " +
                    "always here.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (editing) {
        val draft = rememberCrisisLineDraft(resource)
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("Crisis resource") },
            text = { CrisisLineFields(draft) },
            // The crisis screen always shows a line, so blank fields are never saved.
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.save(draft.label, draft.contact, draft.number, draft.takesTexts)
                        editing = false
                    },
                    enabled = draft.complete,
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("Cancel") } },
        )
    }
}
