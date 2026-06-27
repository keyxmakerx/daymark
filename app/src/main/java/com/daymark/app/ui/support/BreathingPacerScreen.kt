package com.daymark.app.ui.support

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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

/**
 * A simple, calm breathing pacer (~6 breaths/min): a circle that grows on the in-breath and
 * shrinks on the out-breath, with haptic cues (one pulse in, two out) so it can be followed
 * eyes-closed or with the phone on the chest. Low-stimulation by design.
 */
@Composable
fun BreathingPacerScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val scale = remember { Animatable(0.45f) }
    var phase by remember { mutableStateOf("Breathe in") }

    LaunchedEffect(Unit) {
        // 5s in + 5s out = 10s cycle = 6 breaths/min.
        while (true) {
            phase = "Breathe in"
            Haptics.pulse(context)
            scale.animateTo(1f, tween(5000, easing = FastOutSlowInEasing))
            phase = "Breathe out"
            Haptics.doublePulse(context)
            scale.animateTo(0.45f, tween(5000, easing = FastOutSlowInEasing))
        }
    }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "$phase — follow the circle. No need to force it.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 24.dp),
            )
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
