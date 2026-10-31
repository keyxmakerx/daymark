package com.daymark.app.ui.hardtime

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daymark.app.data.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject

/**
 * "I'm having a hard time" (#537): one switch the person turns on themselves, for a stretch they
 * pick. While it is on, reminders and tracker check-ins are posted at least one step less often
 * (`CheckInEngine.duringHardTime`), a check-in asks no extra question and offers no "See it land",
 * and the Sky opens quiet, on today, with no flight. A check-in kept at its times is left as set.
 *
 * It is never suggested, prompted or offered after a bad answer; the one door to it is More. The
 * safety plan sits beside it, offered and never opened. Nothing here says how long it has been on
 * as a gap, and turning it off is one tap where it was turned on. Nothing is hidden or deleted.
 */
@HiltViewModel
class HardTimeViewModel @Inject constructor(
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _until = MutableStateFlow(current())
    /** Until when it is on, in epoch millis, or 0 when it is off. */
    val until: StateFlow<Long> = _until.asStateFlow()

    private fun current(): Long = if (settings.hardTimeOn()) settings.hardTimeUntil else 0L

    fun turnOn(forMillis: Long?) {
        settings.hardTimeUntil = forMillis?.let { System.currentTimeMillis() + it } ?: SettingsRepository.HARD_TIME_UNTIL_OFF
        _until.value = current()
    }

    fun turnOff() {
        settings.hardTimeUntil = 0L
        _until.value = 0L
    }

    companion object {
        const val DAY_MILLIS = 24 * 3_600_000L
        const val WEEK_MILLIS = 7 * DAY_MILLIS
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HardTimeScreen(
    onBack: () -> Unit,
    onSafetyPlan: () -> Unit,
    viewModel: HardTimeViewModel = hiltViewModel(),
) {
    val until by viewModel.until.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Having a hard time") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "Turn this on and Daymark asks less for a while. Reminders and check-ins come less " +
                    "often, a check-in asks nothing extra, and your sky opens quiet.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                "Anything you chose to keep at its times, like a medication, still comes as set. " +
                    "Nothing is hidden or deleted.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (until > 0L) {
                Text(
                    if (until == SettingsRepository.HARD_TIME_UNTIL_OFF) {
                        "On until you turn it off."
                    } else {
                        "On until " + formatUntil(until) + "."
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                Button(onClick = viewModel::turnOff, modifier = Modifier.heightIn(min = 48.dp)) { Text("Turn it off") }
            } else {
                Text("Turn it on", style = MaterialTheme.typography.titleMedium)
                OutlinedButton(
                    onClick = { viewModel.turnOn(HardTimeViewModel.DAY_MILLIS) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text("For a day") }
                OutlinedButton(
                    onClick = { viewModel.turnOn(HardTimeViewModel.WEEK_MILLIS) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text("For a week") }
                OutlinedButton(
                    onClick = { viewModel.turnOn(null) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text("Until I turn it off") }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                "Your safety plan is here whenever you want it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onSafetyPlan, modifier = Modifier.heightIn(min = 48.dp)) { Text("My safety plan") }
        }
    }
}

private fun formatUntil(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))
