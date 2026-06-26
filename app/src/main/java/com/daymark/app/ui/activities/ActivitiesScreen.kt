package com.daymark.app.ui.activities

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daymark.app.R
import com.daymark.app.data.entity.ActivityEntity
import com.daymark.app.ui.icon.ActivityIcons

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivitiesScreen(
    onBack: () -> Unit,
    onBrowseLibrary: () -> Unit = {},
    viewModel: ActivitiesViewModel = hiltViewModel(),
) {
    val activities by viewModel.activities.collectAsStateWithLifecycle()
    var showEditor by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ActivityEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Activities") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { editing = null; showEditor = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add activity")
            }
        },
    ) { padding ->
        LazyColumn(contentPadding = PaddingValues(top = 0.dp), modifier = Modifier.padding(padding)) {
            item(key = "browse_library") {
                ListItem(
                    headlineContent = { Text("Add from library") },
                    supportingContent = { Text("Browse activities by category") },
                    leadingContent = {
                        Icon(painterResource(R.drawable.ic_ui_plus), contentDescription = null)
                    },
                    trailingContent = {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                    },
                    modifier = Modifier.clickable { onBrowseLibrary() },
                )
                HorizontalDivider()
            }
            items(activities, key = { it.id }) { activity ->
                ListItem(
                    headlineContent = {
                        Text(activity.name, color = if (activity.archived) Color.Gray else Color.Unspecified)
                    },
                    supportingContent = if (activity.archived) {
                        { Text("Archived") }
                    } else null,
                    leadingContent = {
                        Icon(painterResource(ActivityIcons.forKey(activity.iconKey)), contentDescription = null)
                    },
                    trailingContent = {
                        IconButton(onClick = { viewModel.toggleArchived(activity) }) {
                            Icon(
                                if (activity.archived) Icons.Filled.Unarchive else Icons.Filled.Archive,
                                contentDescription = if (activity.archived) "Restore" else "Archive",
                            )
                        }
                    },
                    modifier = Modifier.clickable { editing = activity; showEditor = true },
                )
            }
        }
    }

    if (showEditor) {
        ActivityEditorDialog(
            initial = editing,
            onDismiss = { showEditor = false },
            onConfirm = { name, icon ->
                val current = editing
                if (current == null) {
                    viewModel.add(name, icon)
                } else {
                    viewModel.rename(current, name)
                    viewModel.setIcon(current, icon)
                }
                showEditor = false
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActivityEditorDialog(
    initial: ActivityEntity?,
    onDismiss: () -> Unit,
    onConfirm: (name: String, iconKey: String) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var iconKey by remember { mutableStateOf(initial?.iconKey ?: "star") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "New activity" else "Edit activity") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Icon", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ActivityIcons.keys.forEach { key ->
                        IconButton(onClick = { iconKey = key }) {
                            Icon(
                                painterResource(ActivityIcons.forKey(key)),
                                contentDescription = key,
                                tint = if (key == iconKey) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(name, iconKey) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
