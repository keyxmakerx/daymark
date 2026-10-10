package com.daymark.app.sync

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daymark.app.data.entity.GamePlanItem
import com.daymark.synccrypto.ClinicianItems

/**
 * Settings → Sync with your server → From your clinicians (#177): what approved clinicians have sent,
 * waiting for the owner, and what the owner has accepted. The words are [InboxWords].
 *
 * WHAT THE SCREEN KEEPS TRUE:
 *  - "Check for new items" is the only read, and only a tap makes it.
 *  - Nothing waiting is added until the owner taps Accept; Decline and Dismiss send nothing.
 *  - An item that failed a check is shown, with what happened to it, and can only be dismissed.
 *  - The clinician's own words are shown as theirs, under their name, never as Daymark's.
 *  - Plain ink throughout: no colour of its own, and nothing drawn as a success.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(
    onBack: () -> Unit,
    viewModel: InboxViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(InboxWords.TITLE) },
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.loading) return@Column
            Text(InboxWords.FRAMING, style = MaterialTheme.typography.bodyMedium)
            if (!state.hasClinicians) {
                Text(InboxWords.NO_CLINICIANS, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Button(onClick = viewModel::check, enabled = !state.busy) {
                    Text(if (state.busy) InboxWords.CHECKING else InboxWords.CHECK)
                }
            }
            state.words?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            state.notes.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }

            if (state.checked) {
                Heading(InboxWords.WAITING_HEADING)
                if (state.entries.isEmpty()) {
                    Text(InboxWords.NONE_WAITING, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                state.entries.forEach { entry ->
                    Waiting(entry, busy = state.busy, onAccept = { viewModel.accept(entry.key) }, onDecline = { viewModel.decline(entry.key) })
                    HorizontalDivider()
                }
            }

            Heading(InboxWords.ACCEPTED_HEADING)
            if (state.plans.isEmpty() && state.suggestions.isEmpty()) {
                Text(InboxWords.NONE_ACCEPTED, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            state.plans.forEach { plan ->
                Text(InboxWords.planFrom(plan.clinician), style = MaterialTheme.typography.titleSmall)
                if (plan.withdrawn) {
                    Text(InboxWords.withdrawnBy(plan.clinician), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                plan.items.forEach { StoredItem(it) }
                Spacer(Modifier.height(4.dp))
            }
            state.suggestions.forEach { s ->
                Text(InboxWords.suggestionFrom(s.clinician), style = MaterialTheme.typography.titleSmall)
                Text(s.line, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun Heading(text: String) {
    Spacer(Modifier.height(8.dp))
    Text(text, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun Waiting(entry: InboxEntry, busy: Boolean, onAccept: () -> Unit, onDecline: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (entry) {
            is InboxEntry.Plan -> {
                val plan = entry.plan
                val heading = when {
                    plan.status == "withdrawn" -> InboxWords.planWithdrawn(entry.clinician)
                    entry.replaces -> InboxWords.planUpdate(entry.clinician)
                    else -> InboxWords.planFrom(entry.clinician)
                }
                Text(heading, style = MaterialTheme.typography.titleSmall)
                plan.items.forEach { OpenedItem(it) }
                val every = plan.reviewEvery
                if (every != null) Text(InboxWords.reviewEvery(plan.reviewCount ?: 1, every), style = MaterialTheme.typography.bodyMedium)
                Choices(busy, onAccept, onDecline)
            }
            is InboxEntry.Suggestion -> {
                val a = entry.assignment
                Text(InboxWords.suggestionFrom(entry.clinician), style = MaterialTheme.typography.titleSmall)
                Text(InboxWords.describe(a.type, a.payload), style = MaterialTheme.typography.bodyMedium)
                a.note?.let { Text(InboxWords.theirNote(it), style = MaterialTheme.typography.bodyMedium) }
                val cannotAdd = entry.cannotAdd
                if (cannotAdd != null) {
                    Text(cannotAdd, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = onDecline, enabled = !busy) { Text(InboxWords.DISMISS) }
                } else {
                    Choices(busy, onAccept, onDecline)
                }
            }
            is InboxEntry.NotBelieved -> {
                Text(entry.words, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onDecline, enabled = !busy) { Text(InboxWords.DISMISS) }
            }
        }
    }
}

@Composable
private fun Choices(busy: Boolean, onAccept: () -> Unit, onDecline: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onAccept, enabled = !busy) { Text(InboxWords.ACCEPT) }
        OutlinedButton(onClick = onDecline, enabled = !busy) { Text(InboxWords.DECLINE) }
    }
}

@Composable
private fun OpenedItem(item: ClinicianItems.PlanItem) =
    PlanLine(item.kind, item.title, item.detail, item.targetPerWeek)

@Composable
private fun StoredItem(item: GamePlanItem) =
    PlanLine(item.kind, item.title, item.detail, item.targetPerWeek)

@Composable
private fun PlanLine(kind: String, title: String, detail: String?, targetPerWeek: Int?) {
    Column(modifier = Modifier.padding(start = 8.dp)) {
        Text("${InboxWords.kind(kind)}: $title", style = MaterialTheme.typography.bodyMedium)
        detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        targetPerWeek?.let { Text(InboxWords.perWeek(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
