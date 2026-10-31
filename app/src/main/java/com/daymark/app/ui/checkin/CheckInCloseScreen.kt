package com.daymark.app.ui.checkin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daymark.app.data.CheckInPlanner
import com.daymark.app.data.entity.Tracker
import com.daymark.app.stats.CheckInQuestions
import com.daymark.app.ui.components.PaperSurface
import com.daymark.app.ui.entry.MarkThisDayDialog

/**
 * The end of a new check-in (`stats/CheckInQuestions.kt`): the extra questions, then "Saved." and
 * a short list of things the person may do next.
 *
 * What the screen keeps true:
 * - every question has Skip, Why this question? and Stop asking this, and "Done for today" ends the
 *   whole of it from any question;
 * - there is no count of questions, no "1 of 2", and nothing about how often anything was answered;
 * - after Awful or Bad there is no question and no sky offer, and Take a moment and the safety plan
 *   lead; the safety plan is on every closing screen, offered and never opened for the person (§D1b);
 * - the sky offers appear only when the planner leaves them in, and are the same words whatever the
 *   mood (#538).
 */
@Composable
fun CheckInCloseScreen(
    onClose: () -> Unit,
    onSeeItLand: () -> Unit,
    onYourSky: () -> Unit,
    onLand: () -> Unit,
    onTakeAMoment: () -> Unit,
    onSafetyPlan: () -> Unit,
    onLogSleep: () -> Unit,
    viewModel: CheckInCloseViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.landNow) {
        if (state.landNow) {
            viewModel.landed()
            onLand()
        }
    }

    Scaffold { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            if (!state.loaded) return@Column
            val question = state.current
            if (question != null) {
                QuestionStep(
                    question = question,
                    onAnswer = viewModel::answer,
                    onLogSleep = {
                        viewModel.answerSleep()
                        onLogSleep()
                    },
                    onSkip = viewModel::skip,
                    onStop = viewModel::stopAsking,
                    onDone = viewModel::doneForToday,
                )
            } else if (!state.landNow) {
                ClosingStep(
                    state = state,
                    onClose = onClose,
                    onSeeItLand = onSeeItLand,
                    onYourSky = onYourSky,
                    onTakeAMoment = onTakeAMoment,
                    onSafetyPlan = onSafetyPlan,
                    onMark = viewModel::markThisDay,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuestionStep(
    question: CheckInPlanner.Question,
    onAnswer: (Double) -> Unit,
    onLogSleep: () -> Unit,
    onSkip: () -> Unit,
    onStop: () -> Unit,
    onDone: () -> Unit,
) {
    var showWhy by rememberSaveable(question.subject) { mutableStateOf(false) }
    val tracker = question.tracker
    Text(
        tracker?.name ?: "How did you sleep last night?",
        style = MaterialTheme.typography.headlineSmall,
    )
    when {
        tracker == null -> Button(onClick = onLogSleep, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("Log last night")
        }
        tracker.type == Tracker.BOOLEAN -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { onAnswer(1.0) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Yes") }
            OutlinedButton(onClick = { onAnswer(0.0) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("No") }
        }
        tracker.type == Tracker.SCALE -> FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (v in tracker.minValue..maxOf(tracker.minValue, tracker.maxValue)) {
                OutlinedButton(onClick = { onAnswer(v.toDouble()) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(v.toString())
                }
            }
        }
        else -> NumberAnswer(unit = tracker.unit, onAnswer = onAnswer)
    }
    if (showWhy) {
        PaperSurface(modifier = Modifier.fillMaxWidth()) {
            Text(
                if (tracker == null) {
                    "You keep a sleep diary, and it's morning. There's room in it for last night."
                } else {
                    "You chose to have this asked when you check in."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(14.dp),
            )
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = onSkip) { Text("Skip") }
        TextButton(onClick = { showWhy = !showWhy }) { Text(if (showWhy) "Hide why" else "Why this question?") }
        TextButton(onClick = onStop) { Text("Stop asking this") }
    }
    Spacer(Modifier.heightIn(min = 8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Button(onClick = onDone, modifier = Modifier.heightIn(min = 48.dp)) { Text("Done for today") }
    }
}

@Composable
private fun NumberAnswer(unit: String, onAnswer: (Double) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val value = text.replace(',', '.').toDoubleOrNull()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(unit.ifBlank { "Number" }) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        Button(onClick = { value?.let(onAnswer) }, enabled = value != null) { Text("Save") }
    }
}

@Composable
private fun ClosingStep(
    state: CheckInCloseViewModel.UiState,
    onClose: () -> Unit,
    onSeeItLand: () -> Unit,
    onYourSky: () -> Unit,
    onTakeAMoment: () -> Unit,
    onSafetyPlan: () -> Unit,
    onMark: (String) -> Unit,
) {
    var marking by rememberSaveable { mutableStateOf(false) }
    Text("Saved.", style = MaterialTheme.typography.headlineSmall)
    Text(
        if (state.hardMood) "There's nothing more you have to do." else "That's all for now.",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (state.stoppedOne) {
        Text(
            "That question is off. It can go back on where you set it up.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (state.hardMood) {
            Choice("Take a moment", "A few gentle options", onTakeAMoment)
            Choice("My safety plan", "Yours, always here", onSafetyPlan)
        }
        if (CheckInQuestions.Closing.SEE_IT_LAND in state.offers) {
            Choice("See it land", "Today's star, then back here", onSeeItLand)
        }
        if (CheckInQuestions.Closing.YOUR_SKY in state.offers) {
            Choice("Your sky", "Today is in it", onYourSky)
        }
        Choice(
            "Mark this day",
            state.marked?.let { "Marked: $it" } ?: "A few words, in your own words",
        ) { marking = true }
        if (!state.hardMood) Choice("My safety plan", "Yours, always here", onSafetyPlan)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Button(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) { Text("Close") }
    }
    if (marking) {
        MarkThisDayDialog(
            dateLabel = "this day",
            onDismiss = { marking = false },
            onConfirm = {
                marking = false
                onMark(it)
            },
        )
    }
}

@Composable
private fun Choice(title: String, detail: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
