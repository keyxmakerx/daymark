package com.daymark.app.ui.assessments

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daymark.app.ui.components.PaperSurface

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssessmentsHubScreen(
    onBack: () -> Unit,
    onOpen: (key: String) -> Unit,
    viewModel: AssessmentViewModel = hiltViewModel(),
) {
    val results by viewModel.latestPerKey().collectAsStateWithLifecycle(initialValue = emptyList())
    val latestByKey = results.groupBy { it.key }.mapValues { it.value.maxByOrNull { r -> r.dateTime } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Check-ins") },
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
                    "Short, well-known wellbeing self-checks you can take any time and track over the " +
                        "weeks. They're self-checks, not diagnoses.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(Assessments.ALL, key = { it.key }) { s ->
                val last = latestByKey[s.key]
                PaperSurface(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.fillMaxWidth().clickable { onOpen(s.key) }.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(s.title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            s.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (last != null) {
                            Text(
                                "Last: ${Assessments.displayScore(s.key, last.score)} · ${last.bandLabel}",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }
}
