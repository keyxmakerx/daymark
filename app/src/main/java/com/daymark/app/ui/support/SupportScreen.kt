package com.daymark.app.ui.support

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.daymark.app.stats.Signals
import com.daymark.app.ui.components.PaperSurface
import com.daymark.app.ui.insights.SignalsViewModel
import com.daymark.app.util.Haptics

/**
 * "Take a moment" — a calm, full-screen, gently-animated space offered (never forced) after a low
 * mood. Validates first, the options fade in once, and every path is optional. No cheering, no
 * generic affirmations. Non-diagnostic general wellness.
 *
 * The order is fixed (#166, #352): "Not right now" first, then the Signals engine's Support menu
 * without its crisis entry, then "Do one small thing", then the calm activities when they are
 * switched on (#170), then "My safety plan" when one is written, and the crisis resource last
 * (`docs/FEATURES.md` §7.2, §7.3). Nothing on the screen repeats an animation: the one fade as it
 * opens is all the motion there is, and "Do one small thing" opening is the person's own tap.
 */
@Composable
fun SupportScreen(
    onClose: () -> Unit,
    onTalk: () -> Unit,
    onBreathe: () -> Unit,
    onReframe: () -> Unit,
    onMove: () -> Unit,
    onCrisis: () -> Unit,
    onSafetyPlan: () -> Unit,
    onBlocks: () -> Unit,
    onSand: () -> Unit,
    onShelf: () -> Unit,
    viewModel: SupportViewModel = hiltViewModel(),
    signalsViewModel: SignalsViewModel = hiltViewModel(),
) {
    val hasSafetyPlan by viewModel.hasSafetyPlan.collectAsStateWithLifecycle()
    val supportSignals by signalsViewModel.supportSignals.collectAsStateWithLifecycle()
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

            // First, and as easy to hit as any other option (#352).
            MenuOption("Not right now", "That's okay too.", onClose)
            // Contextual, rules-based picks from the engine (crisis is appended separately, last).
            val mainOptions = supportSignals.filter { it.kind != "support_crisis" }
            val crisis = supportSignals.firstOrNull { it.kind == "support_crisis" }
            mainOptions.forEach { signal ->
                MenuOption(
                    signal.title, signal.body,
                    supportClick(signal.action, onTalk, onBreathe, onReframe, onMove, onCrisis, onClose),
                )
            }
            // A tiny behavioural-activation step from the fixed Do one thing list (#483).
            SmallThingOption(onClose)
            // Something for the hands, never the default and gone entirely when switched off (#170).
            if (viewModel.showCalmActivities) {
                MenuOption("Falling blocks", "Turn and place slow shapes. Nothing is counted.", onBlocks)
                MenuOption("Sand to rake", "Draw lines in the sand, then smooth it flat.", onSand)
                MenuOption("Tidy a little shelf", "Put books on a shelf in any order. Shake to tumble them.", onShelf)
            }
            // The safety plan sits quietly in the list, and only when one has been written — a row
            // that leads to a blank page is the opposite of what the plan is for.
            if (hasSafetyPlan) {
                MenuOption("My safety plan", "The one you wrote.", onSafetyPlan)
            }
            // The crisis floor: last in every support menu (`docs/FEATURES.md` §7.3).
            crisis?.let {
                MenuOption(it.title, it.body, onCrisis)
            }
        }
    }
}

/** Maps a support signal's action to the screen's existing, support-context navigation callbacks. */
private fun supportClick(
    action: Signals.Action?,
    onTalk: () -> Unit,
    onBreathe: () -> Unit,
    onReframe: () -> Unit,
    onMove: () -> Unit,
    onCrisis: () -> Unit,
    onClose: () -> Unit,
): () -> Unit = when (action) {
    Signals.Action.OpenMovement -> onMove
    Signals.Action.OpenBreathing -> onBreathe
    Signals.Action.OpenThoughtRecord -> onReframe
    Signals.Action.OpenJournal -> onTalk
    Signals.Action.OpenCrisisResources -> onCrisis
    else -> onClose
}

/** A tappable option. It stays still: a moving card is harder to tap with an unsteady hand (#352). */
@Composable
private fun MenuOption(title: String, subtitle: String, onClick: () -> Unit) {
    PaperSurface(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * "Do one small thing" — expands in place to three fixed steps from the Do one thing list. Never
 * one of the person's own activities: a random one could read "Maybe a little Work?" (#483).
 */
@Composable
private fun SmallThingOption(onDone: () -> Unit) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    PaperSurface(modifier = Modifier.fillMaxWidth()) {
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
                        "One of these, for a few minutes. No pressure.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    com.daymark.app.ui.activation.BehavioralActivation.SMALL_STEPS.forEach { step ->
                        Text("· $step", style = MaterialTheme.typography.bodyLarge)
                    }
                    Button(
                        onClick = { Haptics.pulse(context); onDone() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Okay, I'll try") }
                }
            }
        }
    }
}
