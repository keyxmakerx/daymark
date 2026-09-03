package com.daymark.app.ui.support

import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
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

/** The circle at the bottom of the out-breath, and at the top of the in-breath. */
private const val REST_SCALE = 0.45f
private const val FULL_SCALE = 1f

/**
 * A calm breathing pacer: a circle that grows on the in-breath and shrinks on the out-breath, with
 * haptic cues (one pulse in, two out) so it can be followed eyes-closed or with the phone on the
 * chest. Pick a cadence; slow ~6 breaths/min is the gentle default. Low-stimulation by design.
 */
@Composable
fun BreathingPacerScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    var scale by remember { mutableFloatStateOf(REST_SCALE) }
    var cadence by remember { mutableStateOf(CADENCES.first()) }
    var phase by remember { mutableStateOf("Breathe in") }

    // Driven by the frame clock and elapsed-time arithmetic, not by Animatable/tween. Compose
    // scales tween durations by the system animator duration scale, and the "Remove animations"
    // accessibility setting sets that scale to zero: the circle would snap and the pacer would
    // stop pacing. The timing here IS the feature — it is what a person breathes along with — so
    // it has to run at true speed whatever that setting says. For the same reason a reduced-motion
    // preference is deliberately not consulted: a five-second breath still has to take five seconds.
    LaunchedEffect(cadence) {
        scale = REST_SCALE
        while (true) {
            phase = "Breathe in"
            Haptics.pulse(context)
            breathe(cadence.inhaleMs, from = REST_SCALE, to = FULL_SCALE) { scale = it }
            if (cadence.hold1Ms > 0) { phase = "Hold"; delay(cadence.hold1Ms.toLong()) }
            phase = "Breathe out"
            Haptics.doublePulse(context)
            breathe(cadence.exhaleMs, from = FULL_SCALE, to = REST_SCALE) { scale = it }
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
                        // Same transform Modifier.scale applies, but read in the draw phase, so a
                        // new value every frame redraws the layer instead of recomposing the screen.
                        .graphicsLayer { scaleX = scale; scaleY = scale }
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

/**
 * Moves the circle from [from] to [to] over [durationMs], one frame at a time, then returns.
 * Progress is elapsed frame time over the phase length, so the animator duration scale has no say
 * in it, and the last frame always lands exactly on [to].
 */
private suspend fun breathe(durationMs: Int, from: Float, to: Float, onFrame: (Float) -> Unit) {
    val durationNanos = durationMs * 1_000_000L
    val startNanos = withFrameNanos { it }
    onFrame(from)
    while (true) {
        val elapsedNanos = withFrameNanos { it } - startNanos
        onFrame(breathScale(elapsedNanos, durationNanos, from, to) { FastOutSlowInEasing.transform(it) })
        if (elapsedNanos >= durationNanos) return
    }
}

/**
 * Where the circle is [elapsedNanos] into a phase [durationNanos] long: [from] at the start, [to]
 * at the end, and held at [to] once the phase has run its course, so a late frame never overshoots.
 * [ease] shapes the progress in between. Pure, so the arithmetic is testable without a frame clock.
 */
internal fun breathScale(
    elapsedNanos: Long,
    durationNanos: Long,
    from: Float,
    to: Float,
    ease: (Float) -> Float,
): Float {
    val progress = if (durationNanos <= 0L) {
        1f
    } else {
        (elapsedNanos.toFloat() / durationNanos.toFloat()).coerceIn(0f, 1f)
    }
    return from + (to - from) * ease(progress)
}
