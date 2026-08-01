package com.daymark.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Settings → Suggestions: one place to tune every gentle nudge the app can make.
 *
 * Everything here is a switch over stored numbers — nothing is learned from what you tap, and
 * turning something off is a setting rather than a signal. A snoozed suggestion says when it comes
 * back and offers to bring it back now, so "why am I not seeing this?" always has an answer on
 * screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SuggestionsScreen(
    onBack: () -> Unit,
    viewModel: SuggestionsViewModel = hiltViewModel(),
) {
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val on = rows.filter { it.on }
    val off = rows.filterNot { it.on }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Suggestions") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = "Everything Daymark might offer you, and whether you want it. " +
                    "Turning one off is remembered — it never comes back on by itself.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
            )

            if (on.isNotEmpty()) {
                SuggestionSectionHeader("On")
                on.forEach { row ->
                    SuggestionRowItem(
                        row = row,
                        onToggle = { viewModel.setOn(row.group.key, it) },
                        onResumeNow = { viewModel.resumeNow(row.group.key) },
                    )
                }
            }

            if (off.isNotEmpty()) {
                HorizontalDivider()
                SuggestionSectionHeader("Off")
                off.forEach { row ->
                    SuggestionRowItem(
                        row = row,
                        onToggle = { viewModel.setOn(row.group.key, it) },
                        onResumeNow = { viewModel.resumeNow(row.group.key) },
                    )
                }
            }

            Text(
                text = "Suggestions are opt-out, granular, and remembered. " +
                    "Crisis resources stay available no matter what you set here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp, vertical = 24.dp),
            )
        }
    }
}

@Composable
private fun SuggestionRowItem(
    row: SuggestionRow,
    onToggle: (Boolean) -> Unit,
    onResumeNow: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(row.group.title) },
        supportingContent = {
            Column {
                val summary = row.snoozeSummary
                if (row.on && summary != null) {
                    Text("Snoozed · $summary", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else if (row.group.subtitle != null) {
                    Text(row.group.subtitle)
                }
                if (row.on && summary != null) {
                    TextButton(
                        onClick = onResumeNow,
                        modifier = Modifier.padding(top = 2.dp),
                    ) {
                        Text("Show it again now")
                    }
                }
            }
        },
        trailingContent = {
            Switch(
                checked = row.on,
                onCheckedChange = onToggle,
                modifier = Modifier.semantics {
                    contentDescription = "${row.group.title}: ${if (row.on) "on" else "off"}"
                },
            )
        },
    )
}

@Composable
private fun SuggestionSectionHeader(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        letterSpacing = 1.sp,
        color = MaterialTheme.colorScheme.tertiary,
        modifier = Modifier.padding(start = 18.dp, top = 18.dp, bottom = 6.dp),
    )
}
