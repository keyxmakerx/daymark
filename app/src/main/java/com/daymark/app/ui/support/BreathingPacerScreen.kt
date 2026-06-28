package com.daymark.app.ui.support

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.daymark.app.util.Haptics
import kotlinx.coroutines.delay

/** A paced-breathing cadence. Zero-length holds are skipped. Durations in milliseconds. */
private data class Cadence(
    val name: String,
    val inhaleMs: Int,
    val hold1Ms: Int,
    val exhaleMs: Int,
    val hold2Ms: Int,
)

// Described generically — no brand names or health claims. Slow ~6/min is the best-evidenced and
// is the default; box and 4-7-8 are popular alternatives.
private val CADENCES = listOf(
    Cadence("Slow 6/min", 5000, 0, 5000, 0),
    Cadence("Box 4·4·4·4", 4000, 4000, 4000, 4000),
    Cadence("4·7·8", 4000, 7000, 8000, 0),
)

/**
 * A calm breathing pacer: a circle that grows on the in-breath and shrinks on the out-breath, with
 * haptic cues (one pulse in, two out) so it can be followed eyes-closed or with the phone on the
 * chest. Pick a cadence; slow ~6 breaths/min is the gentle default. Low-stimulation by design.
 */
@Composable
fun BreathingPacerScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val scale = remember { Animatable(0.45f) }
    var cadence by remember { mutableStateOf(CADENCES.first()) }
    var phase by remember { mutableStateOf("Breathe in") }

    LaunchedEffect(cadence) {
        scale.snapTo(0.45f)
        while (true) {
            phase = "Breathe in"
            Haptics.pulse(context)
            scale.animateTo(1f, tween(cadence.inhaleMs, easing = FastOutSlowInEasing))
            if (cadence.hold1Ms > 0) { phase = "Hold"; delay(cadence.hold1Ms.toLong()) }
            phase = "Breathe out"
            Haptics.doublePulse(context)
            scale.animateTo(0.45f, tween(cadence.exhaleMs, easing = FastOutSlowInEasing))
            if (cadence.hold2Ms > 0) { phase = "Hold"; delay(cadence.hold2Ms.toLong()) }
        }
    }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CADENCES.forEach { c ->
                        FilterChip(
                            selected = cadence == c,
                            onClick = { cadence = c },
                            label = { Text(c.name) },
                        )
                    }
                }
                Text(
                    "$phase — follow the circle. No need to force it.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 24.dp),
                )
            }
            Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(220.dp)
                        .scale(scale.value)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                )
            }
            OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Text("Done")
            }
        }
    }
}
