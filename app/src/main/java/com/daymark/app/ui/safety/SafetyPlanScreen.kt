package com.daymark.app.ui.safety

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daymark.app.data.entity.SafetyPlanItem
import com.daymark.app.data.entity.SafetyPlanSection
import com.daymark.app.ui.components.PaperSurface
import com.daymark.app.ui.components.ProvenanceNote
import com.daymark.app.ui.components.ProvenanceTier
import com.daymark.app.ui.components.SentenceCaps
import com.daymark.app.ui.theme.CardShape
import com.daymark.app.ui.theme.HairlineWidth

/**
 * "My safety plan" — a plan the person writes **while things are steady**, so that a harder moment
 * doesn't have to start from a blank page.
 *
 * Read and edit are the same screen: in a hard moment nobody should have to find an edit button,
 * and the plan is short enough that a separate read view would only add a tap.
 *
 * Three things this screen deliberately does **not** do:
 * - **It does not prompt for means restriction.** See [SafetyPlanSection] for why.
 * - **It does not dial.** Like the rest of the app, the crisis row hands off to the crisis screen;
 *   Daymark is not a crisis service and never places a call on someone's behalf.
 * - **It is never surfaced by Signals.** Nothing infers from mood data that someone "needs" their
 *   safety plan — that is covert labelling, and it is ruled out in `SUPPORT_FEATURE_PLAN.md`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SafetyPlanScreen(
    onBack: () -> Unit,
    onCrisis: () -> Unit,
    viewModel: SafetyPlanViewModel = hiltViewModel(),
) {
    val plan by viewModel.plan.collectAsStateWithLifecycle()
    // The optional fourth section stays absent until wanted, so an empty one never sits there
    // reading as a reproach on a bad day. Once offered, it stays for this visit.
    var reasonsOffered by remember { mutableStateOf(false) }
    var addingPerson by remember { mutableStateOf(false) }
    val isEmpty = plan.values.all { it.isEmpty() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My safety plan") },
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
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "Yours. Private. Works offline.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            ProvenanceNote(
                tier = ProvenanceTier.ADAPTED,
                disclaimer = "A plain safety plan in our own words. It draws on the general idea " +
                    "of safety planning. It is not a validated or clinical instrument, and not " +
                    "for diagnosis.",
            )

            if (isEmpty) {
                PaperSurface(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Write it while things are steady, so a harder moment doesn't have to " +
                                "start from a blank page.",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            "Short lists in your own words. You can change them any time.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            SafetyPlanSection.required.forEach { section ->
                SectionCard(
                    section = section,
                    items = plan[section].orEmpty(),
                    onAdd = { viewModel.add(section, it) },
                    onAddPerson = { addingPerson = true },
                    onRemove = viewModel::remove,
                )
            }

            val reasons = plan[SafetyPlanSection.REASONS].orEmpty()
            if (reasons.isNotEmpty() || reasonsOffered) {
                SectionCard(
                    section = SafetyPlanSection.REASONS,
                    items = reasons,
                    onAdd = { viewModel.add(SafetyPlanSection.REASONS, it) },
                    onAddPerson = {},
                    onRemove = viewModel::remove,
                )
            } else {
                OfferReasonsCard(onAccept = { reasonsOffered = true })
            }

            // The crisis row: label and contact both come from CrisisStore, never hardcoded here.
            Button(
                onClick = onCrisis,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) { Text(viewModel.crisis.contact) }
            Text(
                "${viewModel.crisis.label} — editable, offline.\n" +
                    "A plan is not a person — reaching one is the point.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Nothing here leaves your phone.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
        }
    }

    if (addingPerson) {
        AddPersonDialog(
            onDismiss = { addingPerson = false },
            onAdd = { name, relationship ->
                viewModel.add(SafetyPlanSection.PEOPLE, name, relationship)
                addingPerson = false
            },
        )
    }
}

/** One section of the plan: its lines, and a way to add another. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SectionCard(
    section: SafetyPlanSection,
    items: List<SafetyPlanItem>,
    onAdd: (String) -> Unit,
    onAddPerson: () -> Unit,
    onRemove: (SafetyPlanItem) -> Unit,
) {
    PaperSurface(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(section.title, style = MaterialTheme.typography.titleMedium)
            if (items.isEmpty()) {
                Text(
                    section.prompt,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (section.hasDetail) {
                items.forEach { item ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(item.text, style = MaterialTheme.typography.bodyLarge)
                            if (item.detail.isNotEmpty()) {
                                Text(
                                    item.detail,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        IconButton(onClick = { onRemove(item) }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Remove ${item.text}",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                TextButton(onClick = onAddPerson) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text("  Add someone")
                }
            } else {
                if (items.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items.forEach { item -> RemovableChip(item.text) { onRemove(item) } }
                    }
                }
                AddLineField(placeholder = section.addHint, onAdd = onAdd)
            }
        }
    }
}

/** A line already in the plan, with a quiet way to take it back out. */
@Composable
private fun RemovableChip(text: String, onRemove: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(percent = 50),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(HairlineWidth, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            Icon(
                Icons.Default.Close,
                contentDescription = "Remove $text",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable { onRemove() }.padding(2.dp),
            )
        }
    }
}

/** Inline "add one more" field. Clears itself on add so a list can be written in one sitting. */
@Composable
private fun AddLineField(placeholder: String, onAdd: (String) -> Unit) {
    var draft by remember { mutableStateOf("") }
    OutlinedTextField(
        value = draft,
        onValueChange = { draft = it },
        placeholder = { Text(placeholder) },
        keyboardOptions = SentenceCaps,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        trailingIcon = {
            if (draft.isNotBlank()) {
                IconButton(onClick = { onAdd(draft); draft = "" }) {
                    Icon(Icons.Default.Add, contentDescription = "Add")
                }
            }
        },
    )
}

/**
 * The optional fourth section, offered rather than assumed. It is the heaviest thing on the screen
 * to write, so it is never pre-placed as an empty prompt.
 */
@Composable
private fun OfferReasonsCard(onAccept: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable { onAccept() },
        shape = CardShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(HairlineWidth, MaterialTheme.colorScheme.outline),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Add “Reasons I want to stay”?", style = MaterialTheme.typography.titleSmall)
            Text(
                "Optional. Only if you'd want it there.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** People get two fields — a name alone isn't much use at 3am. */
@Composable
private fun AddPersonDialog(onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var relationship by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Someone you can reach") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    keyboardOptions = SentenceCaps,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = relationship,
                    onValueChange = { relationship = it },
                    label = { Text("Who they are (optional)") },
                    keyboardOptions = SentenceCaps,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(name, relationship) },
                enabled = name.isNotBlank(),
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
