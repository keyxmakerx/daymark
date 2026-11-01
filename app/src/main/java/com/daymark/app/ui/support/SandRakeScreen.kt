package com.daymark.app.ui.support

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.daymark.app.calm.SandRake
import com.daymark.app.ui.theme.CardShape
import androidx.compose.ui.unit.dp

/**
 * "Sand to rake": a finger leaves grooves in sand, and "Smooth" wipes it flat (#170).
 *
 * The geometry and the limit on what is held are in [SandRake]. Nothing here animates and nothing
 * fades: a groove is where the finger put it until the person smooths it. The strokes are held for
 * this visit only and never saved.
 */
@Composable
fun SandRakeScreen(onBackToMenu: () -> Unit) {
    var strokes by remember { mutableStateOf(listOf<List<SandRake.Point>>()) }
    val density = LocalDensity.current
    val minStep = with(density) { SandRake.MIN_STEP_DP.dp.toPx() }
    val spacing = with(density) { SandRake.TINE_SPACING_DP.dp.toPx() }
    val sand = MaterialTheme.colorScheme.surfaceVariant
    val groove = MaterialTheme.colorScheme.outline
    CalmActivityFrame(title = "Sand to rake", onBackToMenu = onBackToMenu) {
        Text(
            "Draw a finger across the sand. There is no pattern to make.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Canvas(
            Modifier.weight(1f).fillMaxWidth().clip(CardShape)
                .semantics { contentDescription = "Sand. Drag a finger to draw lines in it." }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { at ->
                            strokes = SandRake.bounded(strokes + listOf(listOf(SandRake.Point(at.x, at.y))))
                        },
                        onDrag = { change, _ ->
                            val next = SandRake.Point(change.position.x, change.position.y)
                            val current = strokes.lastOrNull() ?: emptyList()
                            if (SandRake.worthKeeping(current.lastOrNull(), next, minStep)) {
                                strokes = SandRake.bounded(strokes.dropLast(1) + listOf(current + next))
                            }
                        },
                    )
                },
        ) {
            drawRect(sand)
            val width = spacing * 0.35f
            strokes.forEach { stroke ->
                stroke.zipWithNext().forEach { (a, b) ->
                    SandRake.tines(a, b, spacing).forEach { (from, to) ->
                        drawLine(groove, Offset(from.x, from.y), Offset(to.x, to.y), width, StrokeCap.Round, alpha = 0.55f)
                    }
                }
            }
        }
        OutlinedButton(onClick = { strokes = emptyList() }, modifier = Modifier.fillMaxWidth()) {
            Text("Smooth")
        }
    }
}
