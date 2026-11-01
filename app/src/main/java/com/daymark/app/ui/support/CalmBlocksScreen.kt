package com.daymark.app.ui.support

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.daymark.app.calm.CalmBlocks
import com.daymark.app.util.Haptics

/**
 * "Falling blocks": slow shapes the person turns and places, with nothing to win or lose (#170).
 *
 * Every rule lives in [CalmBlocks]; this draws it. Nothing here animates: a shape moves only when a
 * button is pressed, and letting it fall places it at once, so there is nothing for reduced motion
 * to change. One soft pulse as a shape settles, the only feedback there is.
 */
@Composable
fun CalmBlocksScreen(onBackToMenu: () -> Unit) {
    val context = LocalContext.current
    var board by remember { mutableStateOf(CalmBlocks.start(System.nanoTime().toInt())) }
    CalmActivityFrame(title = "Falling blocks", onBackToMenu = onBackToMenu) {
        Text(
            "Turn and place the shapes. A full row goes. Nothing is counted.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Board(board)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { board = board.left() }, modifier = Modifier.weight(1f)) { Text("Left") }
            OutlinedButton(onClick = { board = board.turn() }, modifier = Modifier.weight(1f)) { Text("Turn") }
            OutlinedButton(onClick = { board = board.right() }, modifier = Modifier.weight(1f)) { Text("Right") }
        }
        OutlinedButton(
            onClick = { board = board.drop(); Haptics.pulse(context, durationMs = 30, amplitude = 50) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Let it fall") }
        TextButton(onClick = { board = board.cleared() }, modifier = Modifier.align(Alignment.End)) {
            Text("Clear")
        }
    }
}

@Composable
private fun ColumnScope.Board(board: CalmBlocks) {
    // Muted container roles, never a raw colour and never the mood colours.
    val shades = listOf(
        MaterialTheme.colorScheme.secondaryContainer,
        MaterialTheme.colorScheme.tertiaryContainer,
        MaterialTheme.colorScheme.primaryContainer,
    )
    val well = MaterialTheme.colorScheme.surfaceVariant
    val line = MaterialTheme.colorScheme.outline
    val landing = board.landing().absolute.toSet()
    val inHand = board.piece.absolute.toSet()
    Box(
        Modifier.weight(1f, fill = false).align(Alignment.CenterHorizontally)
            .aspectRatio(CalmBlocks.WIDTH.toFloat() / CalmBlocks.HEIGHT)
            .semantics { contentDescription = "A board of shapes. Use the buttons below to move them." },
    ) {
        Canvas(Modifier.fillMaxWidth().aspectRatio(CalmBlocks.WIDTH.toFloat() / CalmBlocks.HEIGHT)) {
            val cell = size.width / CalmBlocks.WIDTH
            val gap = cell * 0.08f
            val corner = CornerRadius(cell * 0.18f)
            drawRoundRect(well, cornerRadius = CornerRadius(cell * 0.3f))
            fun square(x: Int, y: Int) = Offset(x * cell + gap, y * cell + gap)
            val side = Size(cell - 2 * gap, cell - 2 * gap)
            for (y in 0 until CalmBlocks.HEIGHT) for (x in 0 until CalmBlocks.WIDTH) {
                val shade = board.at(x, y)
                if (shade != CalmBlocks.EMPTY) drawRoundRect(shades[shade], square(x, y), side, corner)
            }
            landing.filterNot { it in inHand }.forEach {
                drawRoundRect(line, square(it.x, it.y), side, corner, style = Stroke(width = cell * 0.05f))
            }
            inHand.forEach { drawRoundRect(shades[board.piece.shade], square(it.x, it.y), side, corner) }
        }
    }
}
