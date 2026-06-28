package com.daymark.app.ui.support

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daymark.app.ui.components.PaperSurface
import com.daymark.app.util.Haptics

/**
 * "Take a moment" — a calm, full-screen, gently-animated space offered (never forced) after a low
 * mood. Validates first, the options softly float in, and every path is optional. No cheering, no
 * generic affirmations. Non-diagnostic general wellness.
 */
@Composable
fun SupportScreen(
    onClose: () -> Unit,
    onTalk: () -> Unit,
    onBreathe: () -> Unit,
    onReframe: () -> Unit,
    onMove: () -> Unit,
    onCrisis: () -> Unit,
    viewModel: SupportViewModel = hiltViewModel(),
) {
    val activity by viewModel.suggestedActivity.collectAsStateWithLifecycle()
    var shown by remember { mutableStateOf(false) }
    val appear by animateFloatAsState(if (shown) 1f else 0f, tween(700), label = "appear")
    androidx.compose.runtime.LaunchedEffect(Unit) { shown = true }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
                .alpha(appear),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Hey.", style = MaterialTheme.typography.headlineMedium)
            Text(
                "You said today's been a hard one. Are you okay?",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "However you're feeling right now is okay — there's nothing you have to do.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )

            FloatingOption(0, "I'd like to talk about it", "Write out what's on your mind.", onTalk)
            FloatingOption(1, "Breathe with me", "A minute of slow, paced breathing.", onBreathe)
            FloatingOption(2, "Untangle a thought", "Look at a tough thought, gently.", onReframe)
            SmallThingOption(3, activity, onClose)
            FloatingOption(4, "Move a little", "A short, gentle stretch.", onMove)
            FloatingOption(5, "I could use more support", "Crisis resources and someone to reach.", onCrisis)
            FloatingOption(6, "Not right now", "That's okay too.", onClose)
        }
    }
}

/** A gently bobbing, tappable option. The bob period varies by [index] so they drift organically. */
@Composable
private fun FloatingOption(index: Int, title: String, subtitle: String, onClick: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "float$index")
    val bob by transition.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2400 + index * 350), RepeatMode.Reverse),
        label = "bob$index",
    )
    PaperSurface(modifier = Modifier.fillMaxWidth().offset(y = (bob * 5).dp)) {
        Column(
            modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** "Do one small thing" — expands in place to a personalized behavioral-activation nudge. */
@Composable
private fun SmallThingOption(index: Int, activity: String?, onDone: () -> Unit) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    val transition = rememberInfiniteTransition(label = "float$index")
    val bob by transition.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2400 + index * 350), RepeatMode.Reverse),
        label = "bob$index",
    )
    PaperSurface(modifier = Modifier.fillMaxWidth().offset(y = (bob * 5).dp)) {
        Column(
            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Do one small thing", style = MaterialTheme.typography.titleMedium)
            Text(
                "Even a tiny step can help shift a low moment.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AnimatedVisibility(visible = expanded, enter = fadeIn() + expandVertically()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        activity?.let { "Maybe a little $it? Just a few minutes — no pressure." }
                            ?: "Something small and kind for yourself — a glass of water, a window open, a stretch.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Button(
                        onClick = { Haptics.pulse(context); onDone() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Okay, I'll try") }
                }
            }
        }
    }
}
