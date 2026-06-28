package com.daymark.app.ui.cbt

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daymark.app.ui.components.MoodFaceIcon
import com.daymark.app.ui.components.PaperSurface
import com.daymark.app.util.DateUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThoughtRecordListScreen(
    onBack: () -> Unit,
    onOpen: (Long) -> Unit,
    onNew: () -> Unit,
    viewModel: ThoughtRecordListViewModel = hiltViewModel(),
) {
    val records by viewModel.records.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Thought records") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onNew) {
                Icon(Icons.Filled.Add, contentDescription = "New thought record")
            }
        },
    ) { padding ->
        if (records.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            ) {
                Text("No thought records yet", style = MaterialTheme.typography.titleMedium)
                Text(
                    "A CBT exercise to examine a tough thought and find a more balanced view.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(records, key = { it.id }) { r ->
                    PaperSurface(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth().clickable { onOpen(r.id) }.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    DateUtils.formatDate(r.dateTime),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    r.automaticThought.ifBlank { r.situation }.ifBlank { "(empty)" },
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            MoodFaceIcon(level = r.moodBefore, size = 26.dp)
                            Text("→", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            MoodFaceIcon(level = r.moodAfter, size = 26.dp)
                        }
                    }
                }
            }
        }
    }
}
