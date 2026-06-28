package com.daymark.app.ui.sleep

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daymark.app.sensing.BreathingDetector
import com.daymark.app.ui.components.PaperSurface
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BreathingCaptureScreen(
    onBack: () -> Unit,
    viewModel: BreathingCaptureViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val capturing = state is BreathingCaptureViewModel.State.Capturing

    // Keep the screen on only while actively sampling.
    val view = LocalView.current
    DisposableEffect(capturing) {
        view.keepScreenOn = capturing
        onDispose { view.keepScreenOn = false }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Breathing check") },
                navigationIcon = {
                    IconButton(onClick = { viewModel.cancel(); onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (val s = state) {
                is BreathingCaptureViewModel.State.NoSensor -> {
                    Text("This device doesn't expose an accelerometer, so the breathing check isn't available.")
                }
                is BreathingCaptureViewModel.State.Idle -> IdleContent(onStart = viewModel::start)
                is BreathingCaptureViewModel.State.Capturing -> CapturingContent(s, onCancel = viewModel::cancel)
                is BreathingCaptureViewModel.State.Done -> DoneContent(s.result, onAgain = viewModel::reset, onDone = onBack)
            }
        }
    }
}

@Composable
private fun IdleContent(onStart: (Int) -> Unit) {
    var minutes by remember { mutableIntStateOf(2) }
    PaperSurface(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("How it works", style = MaterialTheme.typography.titleMedium)
            Text(
                "Lie down and rest the phone flat on your chest or upper belly. Breathe normally and " +
                    "stay still — we'll watch the tiny rise-and-fall of your chest to estimate your " +
                    "breathing and flag any pauses. Nothing is recorded; only the result is shown.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("This is experimental and not a diagnosis.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(1, 2, 3).forEach { m ->
            FilterChip(selected = minutes == m, onClick = { minutes = m }, label = { Text("$m min") })
        }
    }
    Button(onClick = { onStart(minutes * 60) }, modifier = Modifier.fillMaxWidth()) {
        Text("Start (keep the screen on)")
    }
}

@Composable
private fun CapturingContent(s: BreathingCaptureViewModel.State.Capturing, onCancel: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(top = 24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            CircularProgressIndicator(
                progress = { if (s.progress <= 0f) 0.02f else s.progress },
                modifier = Modifier.size(96.dp),
            )
            Text("${s.secondsLeft}s left", style = MaterialTheme.typography.headlineSmall)
            Text("Lie still, breathe normally.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    // Live "is it sensing anything?" indicator — helps tell a weak signal from a dead one.
    PaperSurface(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (s.level < 0.08f) "Barely any movement detected" else "Sensing movement",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Box(
                Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Box(
                    Modifier.fillMaxWidth(s.level.coerceIn(0.02f, 1f)).height(10.dp)
                        .clip(RoundedCornerShape(5.dp)).background(MaterialTheme.colorScheme.primary),
                )
            }
            if (s.level < 0.08f) {
                Text(
                    "Make sure the phone lies flat on your chest (not your belt/pocket) so it rises " +
                        "and falls as you breathe.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Stop") }
}

@Composable
private fun DoneContent(result: BreathingDetector.Result, onAgain: () -> Unit, onDone: () -> Unit) {
    // Show any plausible rate; flag low-confidence rather than hiding it (helps tuning/validation).
    val rate = result.breathingRatePerMin
    val hasRate = rate != null && rate in 5.0..40.0
    PaperSurface(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (hasRate) {
                Text("About ${rate!!.roundToInt()} breaths/min", style = MaterialTheme.typography.headlineSmall)
                if (result.confidence <= 0.4) {
                    Text(
                        "Faint signal — treat this as rough. A clearer reading comes from lying " +
                            "flat and still with the phone on your chest.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (result.pauses.isEmpty()) {
                    Text("No breathing pauses were flagged in this reading.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    val longest = result.pauses.maxByOrNull { it.durationSec }!!
                    Text(
                        "Flagged ${result.pauses.size} possible pause(s) — longest about " +
                            "${longest.durationSec.roundToInt()}s.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Pauses in breathing during sleep can be associated with sleep apnea and are " +
                            "worth a clinician's look. This is an experimental reading, not a diagnosis.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Text("Couldn't get a clear reading", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Make sure the phone is lying flat on your chest, you're still, and you breathe " +
                        "normally — then try again.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    Text(
        "Experimental. Daymark is a general-wellness tool, not a medical device.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    Button(onClick = onAgain, modifier = Modifier.fillMaxWidth()) { Text("Try again") }
    OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }
}
