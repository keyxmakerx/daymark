package com.daymark.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daymark.app.data.CrisisStore
import com.daymark.app.data.ReminderRepository
import com.daymark.app.data.SettingsRepository
import com.daymark.app.data.TrackerRepository
import com.daymark.app.data.entity.Reminder
import com.daymark.app.data.entity.rhythmChoice
import com.daymark.app.notifications.ReminderScheduler
import com.daymark.app.ui.components.KeepTimesChoice
import com.daymark.app.ui.components.PaperSurface
import com.daymark.app.ui.support.CrisisLineFields
import com.daymark.app.ui.support.rememberCrisisLineDraft
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject

/** One thing Home asks "ease off or keep?" about: the daily reminder ([trackerId] null), or a tracker. */
data class KeepQuestion(val key: String, val title: String, val trackerId: Long?)

/**
 * The questions Home asks once, of people who set Daymark up before onboarding asked them. Each is
 * the same question onboarding asks, with the same answers, and any answer ends it.
 */
@HiltViewModel
class HomeChecksViewModel @Inject constructor(
    private val crisisStore: CrisisStore,
    private val settings: SettingsRepository,
    private val reminderRepository: ReminderRepository,
    private val reminderScheduler: ReminderScheduler,
    private val trackerRepository: TrackerRepository,
) : ViewModel() {

    /** Bumped when an answer is written to settings, which the flows below cannot observe. */
    private val answered = MutableStateFlow(0)

    /**
     * What is still owed the "ease off or keep?" question (#484): the daily reminder, when one is
     * on and the question was never answered, and each tracker whose check-ins were switched on
     * before the question existed. Empty for everyone set up since.
     */
    val keepQuestions: StateFlow<List<KeepQuestion>> = combine(
        reminderRepository.observeAll(),
        trackerRepository.observeActive(),
        answered,
    ) { reminders, trackers, _ ->
        val out = ArrayList<KeepQuestion>()
        if (reminders.any { it.enabled } && !settings.remindersKeepAnswered) {
            out += KeepQuestion("reminders", "Your daily reminder", null)
        }
        val done = settings.trackersKeepAnswered
        trackers.filter { it.rhythmChoice.asks && it.id !in done }.forEach {
            out += KeepQuestion("tracker-${it.id}", "Check-ins for \u201c${it.name}\u201d", it.id)
        }
        out
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Writes each answer exactly as given; only an answer that changes a tracker touches it. */
    fun answerKeep(answers: Map<KeepQuestion, Boolean>) {
        viewModelScope.launch {
            for ((q, keep) in answers) {
                if (q.trackerId == null) {
                    reminderScheduler.setKeepAsSet(keep)
                } else {
                    trackerRepository.markKeepAnswered(q.trackerId)
                    trackerRepository.getAll().firstOrNull { it.id == q.trackerId }
                        ?.takeIf { it.keepAsSet != keep }
                        ?.let { trackerRepository.update(it.copy(keepAsSet = keep)) }
                }
            }
            answered.value++
        }
    }

    /** "Not now": answered, and nothing changes. Nobody is asked twice. */
    fun skipKeep(questions: List<KeepQuestion>) {
        for (q in questions) {
            if (q.trackerId == null) settings.remindersKeepAnswered = true
            else trackerRepository.markKeepAnswered(q.trackerId)
        }
        answered.value++
    }

    /** Reminders switched off with **Stop asking** that are still off, for Home's quiet line. */
    val stoppedReminders: StateFlow<List<Reminder>> = combine(
        reminderRepository.observeAll(),
        answered,
    ) { reminders, _ ->
        val stopped = settings.stoppedReminderIds
        reminders.filter { it.id in stopped && !it.enabled }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Hides the line. The reminder stays off; turning it on is the person's, in Settings. */
    fun hideStopped(reminder: Reminder) {
        settings.stoppedReminderIds = settings.stoppedReminderIds - reminder.id
        answered.value++
    }

    /** The line to ask about, or null when nothing is owed (#482). */
    private val _crisisCheck = MutableStateFlow(crisisStore.takeIf { it.needsCheck }?.get())
    val crisisCheck: StateFlow<CrisisStore.Resource?> = _crisisCheck.asStateFlow()

    fun keepCrisisLine() {
        crisisStore.confirm()
        _crisisCheck.value = null
    }

    fun saveCrisisLine(label: String, contact: String, number: String?, takesTexts: Boolean) {
        crisisStore.save(label, contact, number, takesTexts)
        _crisisCheck.value = null
    }

    fun skipCrisisLine() {
        crisisStore.markChecked()
        _crisisCheck.value = null
    }
}

/**
 * "Is this the right crisis line where you live?", once. The line has been on the crisis screen all
 * along; this asks the people who never saw onboarding's step whether it is theirs, because the
 * default is a US number and the screen's Call button waits until someone says it is (#482).
 */
@Composable
fun CrisisLineCheck(
    current: CrisisStore.Resource,
    onKeep: () -> Unit,
    onSave: (label: String, contact: String, number: String?, takesTexts: Boolean) -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var changing by remember { mutableStateOf(false) }
    val draft = rememberCrisisLineDraft(null)
    PaperSurface(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Your crisis line", style = MaterialTheme.typography.titleMedium)
            Text(
                "Daymark keeps one crisis line on your phone. Is this the right one where you live?",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(current.label, style = MaterialTheme.typography.titleSmall)
            Text(current.contact, style = MaterialTheme.typography.bodyLarge)
            if (changing) {
                CrisisLineFields(draft)
                Button(
                    onClick = { onSave(draft.label, draft.contact, draft.number, draft.takesTexts) },
                    enabled = draft.complete,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Save") }
            } else {
                Button(onClick = onKeep, modifier = Modifier.fillMaxWidth()) { Text("This one is right") }
                TextButton(onClick = { changing = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Use a different line")
                }
            }
            TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) { Text("Not now") }
            Text(
                "Asked once. You can change it any time on the crisis screen.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * "What if these go unanswered?", once, for each reminder and tracker whose check-ins were set up
 * before Daymark asked it. Until there is an answer nothing changes, and "Not now" is an answer
 * (#484, §D1a: the engine cannot tell "not wanted" from "not noticed", so it asks).
 */
@Composable
fun KeepRemindingCheck(
    questions: List<KeepQuestion>,
    onSave: (Map<KeepQuestion, Boolean>) -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val answers = remember { mutableStateMapOf<String, Boolean>() }
    PaperSurface(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("What if these go unanswered?", style = MaterialTheme.typography.titleMedium)
            Text(
                "Daymark didn't ask this when you set it up. Until you answer, nothing changes.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            questions.forEach { q ->
                Text(q.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
                KeepTimesChoice(keep = answers[q.key], onChange = { answers[q.key] = it })
            }
            Button(
                onClick = { onSave(questions.associateWith { answers.getValue(it.key) }) },
                enabled = questions.all { it.key in answers },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save") }
            TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) { Text("Not now") }
        }
    }
}

/**
 * One quiet line per reminder switched off with **Stop asking**: the fact, and where the switch is
 * (#484). It never offers to turn the reminder back on — that is a setting the person finds (§D1b).
 */
@Composable
fun StoppedReminderLine(
    reminder: Reminder,
    onOpenReminders: () -> Unit,
    onHide: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val time = LocalTime.of(reminder.hour, reminder.minute)
        .format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
    Column(modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        Text(
            "Your $time reminder is off. You switched it off from a notification.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        androidx.compose.foundation.layout.Row {
            TextButton(onClick = onOpenReminders) { Text("Settings → Reminders") }
            TextButton(onClick = onHide) { Text("Hide this") }
        }
    }
}
