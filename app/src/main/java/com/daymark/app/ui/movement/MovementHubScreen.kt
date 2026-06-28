package com.daymark.app.ui.movement

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.daymark.app.ui.components.PoseFigure
import com.daymark.app.ui.components.PaperSurface

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MovementHubScreen(
    onBack: () -> Unit,
    onStart: (routineId: String) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Move") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "Short, gentle routines — follow the figure and the timer. Haptics cue each step, " +
                        "so you can move with your eyes closed. Not a fitness program; move within comfort.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(Routines.ALL, key = { it.id }) { routine ->
                PaperSurface(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().clickable { onStart(routine.id) }.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        PoseFigure(
                            pose = PoseLibrary.byId(routine.steps.first().poseId),
                            modifier = Modifier.size(56.dp),
                        )
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(routine.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                routine.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                "${routine.steps.size} steps · ~${(routine.totalSeconds + 59) / 60} min",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }
}
