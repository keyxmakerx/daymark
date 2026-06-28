package com.daymark.app.ui.movement

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.daymark.app.ui.components.PoseFigure
import com.daymark.app.ui.movement.PoseLibrary
import com.daymark.app.util.Haptics
import kotlinx.coroutines.delay

@Composable
fun MovementSessionScreen(
    routineId: String,
    onDone: () -> Unit,
    viewModel: MovementSessionViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val routine = remember(routineId) { Routines.byId(routineId) }
    if (routine == null) {
        LaunchedEffect(Unit) { onDone() }
        return
    }

    var stepIndex by remember { mutableIntStateOf(0) }
    var secondsLeft by remember { mutableIntStateOf(routine.steps.first().seconds) }
    var finished by remember { mutableStateOf(false) }

    // Smoothly morph the figure from the previous pose to the current one on each step change.
    val morph = remember { Animatable(1f) }
    var prevPoseId by remember { mutableStateOf(routine.steps.first().poseId) }
    val currentPoseId = routine.steps[stepIndex].poseId
    LaunchedEffect(stepIndex) {
        morph.snapTo(0f)
        morph.animateTo(1f, tween(550, easing = FastOutSlowInEasing))
        prevPoseId = currentPoseId
    }

    LaunchedEffect(routine) {
        routine.steps.forEachIndexed { i, step ->
            stepIndex = i
            secondsLeft = step.seconds
            Haptics.pulse(context)
            while (secondsLeft > 0) {
                delay(1000)
                secondsLeft -= 1
            }
        }
        Haptics.doublePulse(context)
        viewModel.logSession(routine.totalSeconds)
        finished = true
    }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            if (finished) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("Nicely done", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "Logged to “Movement minutes”.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(onClick = onDone) { Text("Done") }
                    }
                }
            } else {
                val step = routine.steps[stepIndex]
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 16.dp)) {
                    Text("${stepIndex + 1} / ${routine.steps.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LinearProgressIndicator(
                        progress = { (stepIndex + 1).toFloat() / routine.steps.size },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                val ringFraction by animateFloatAsState(
                    targetValue = if (step.seconds > 0) secondsLeft.toFloat() / step.seconds else 0f,
                    animationSpec = tween(900),
                    label = "ring",
                )
                val ringColor = MaterialTheme.colorScheme.primary
                val ringTrack = MaterialTheme.colorScheme.surfaceVariant
                Box(modifier = Modifier.size(240.dp), contentAlignment = Alignment.Center) {
                    Canvas(modifier = Modifier.size(240.dp)) {
                        val sw = 8.dp.toPx()
                        val inset = sw / 2
                        val arcSize = Size(size.width - sw, size.height - sw)
                        drawArc(
                            color = ringTrack, startAngle = -90f, sweepAngle = 360f, useCenter = false,
                            topLeft = androidx.compose.ui.geometry.Offset(inset, inset), size = arcSize,
                            style = Stroke(width = sw, cap = StrokeCap.Round),
                        )
                        drawArc(
                            color = ringColor, startAngle = -90f, sweepAngle = 360f * ringFraction.coerceIn(0f, 1f),
                            useCenter = false,
                            topLeft = androidx.compose.ui.geometry.Offset(inset, inset), size = arcSize,
                            style = Stroke(width = sw, cap = StrokeCap.Round),
                        )
                    }
                    PoseFigure(
                        pose = com.daymark.app.ui.movement.PoseLibrary.lerp(
                            PoseLibrary.byId(prevPoseId), PoseLibrary.byId(currentPoseId), morph.value,
                        ),
                        modifier = Modifier.size(170.dp),
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(step.label, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                    Text("$secondsLeft", style = MaterialTheme.typography.displaySmall)
                    OutlinedButton(onClick = onDone) { Text("End session") }
                }
            }
        }
    }
}
