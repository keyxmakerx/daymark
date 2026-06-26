package com.daymark.app.ui.sleep

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
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daymark.app.data.SleepMetrics
import com.daymark.app.ui.components.PaperSurface
import java.text.DateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

private const val TREND_MIN_NIGHTS = 7

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepScreen(
    onBack: () -> Unit,
    onOpenScreener: (String) -> Unit,
    onLogNight: () -> Unit,
    onOpenSetup: () -> Unit,
    onOpenTreatments: () -> Unit,
    viewModel: SleepViewModel = hiltViewModel(),
) {
    val results by viewModel.results.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sleep") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---- Sleep diary ----
            item { SectionHeader("Sleep diary") }
            item {
                PaperSurface(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (logs.size >= TREND_MIN_NIGHTS) {
                            val recent = logs.take(14)
                            val avgDur = recent.sumOf { SleepMetrics.totalSleepMin(it) } / recent.size
                            val avgEff = recent.sumOf { SleepMetrics.efficiencyPct(it) } / recent.size
                            Text("Your recent nights", style = MaterialTheme.typography.titleMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                                Stat("Avg sleep", SleepMetrics.formatDuration(avgDur))
                                Stat("Efficiency", "$avgEff%")
                            }
                            Text(
                                "Around 85% efficiency or more is a common good-sleep mark — a " +
                                    "reference, not a verdict.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            val left = TREND_MIN_NIGHTS - logs.size
                            Text("Build your sleep picture", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Log about a week of nights for a reliable average — $left more to go. " +
                                    "Single nights vary a lot, so we'll hold the averages until then.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Button(onClick = onLogNight, modifier = Modifier.fillMaxWidth()) {
                            Text("Log last night")
                        }
                    }
                }
            }
            if (logs.isNotEmpty()) {
                items(logs.take(5), key = { it.id }) { log ->
                    PaperSurface(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    LocalDate.ofEpochDay(log.night)
                                        .format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault())),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Text(
                                    "${SleepMetrics.formatDuration(SleepMetrics.totalSleepMin(log))} · " +
                                        "${SleepMetrics.efficiencyPct(log)}% · quality ${log.quality}/5",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            item { NavCard("Treatments", "Is something helping? Track before & since", onOpenTreatments) }
            item { NavCard("Sleep setup", "Partner, pets, phone placement", onOpenSetup) }

            // ---- Self-checks ----
            item { SectionHeader("Self-checks") }
            item {
                Text(
                    "Short self-checks, not a diagnosis — they can flag signs worth discussing with " +
                        "a clinician, but can't rule anything in or out.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(SleepScreeners.all, key = { it.key }) { screener ->
                val last = results[screener.key]
                NavCard(
                    title = screener.title,
                    subtitle = if (last != null) {
                        "${last.band} · ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(last.atMillis))}"
                    } else {
                        screener.subtitle
                    },
                    onClick = { onOpenScreener(screener.key) },
                )
            }
            item {
                Text(
                    "Daymark is a general-wellness tool, not a medical device.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp, start = 2.dp),
    )
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(value, style = MaterialTheme.typography.headlineSmall)
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
    }
}

@Composable
private fun NavCard(title: String, subtitle: String, onClick: () -> Unit) {
    PaperSurface(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
        }
    }
}
