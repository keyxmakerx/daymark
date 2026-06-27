package com.daymark.app.ui.support

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Full-screen, breathing-synced pacer meant to gently pull attention away from the moment.
 * A soft orb grows on the in-breath and shrinks on the out-breath at ~6 breaths/min, with the
 * phase word synced to the motion. Deliberately calm and low-stimulation (soft, slow, muted) —
 * the UX research is clear that bright/fast motion overstimulates distressed users.
 */
@Composable
fun BreathingPacerScreen(onDone: () -> Unit) {
    val scale = remember { Animatable(0.42f) }
    var phase by remember { mutableStateOf("Breathe in") }

    LaunchedEffect(Unit) {
        // 5s in + 5s out = 10s cycle = 6 breaths/min.
        while (true) {
            phase = "Breathe in"
            scale.animateTo(1f, tween(5000, easing = FastOutSlowInEasing))
            phase = "Breathe out"
            scale.animateTo(0.42f, tween(5000, easing = FastOutSlowInEasing))
        }
    }

    val bg = MaterialTheme.colorScheme.surfaceVariant
    val orb = MaterialTheme.colorScheme.primary

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(bg, MaterialTheme.colorScheme.background),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        // The orb.
        Box(
            modifier = Modifier
                .size(300.dp)
                .scale(scale.value)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(orb.copy(alpha = 0.55f), orb.copy(alpha = 0.12f)),
                    ),
                ),
        )
        // Phase word, centred over the orb.
        Text(
            text = phase,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )

        // Unobtrusive exit at the bottom.
        TextButton(
            onClick = onDone,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 24.dp),
        ) {
            Text("I'm done", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
