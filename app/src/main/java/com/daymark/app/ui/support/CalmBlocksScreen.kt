package com.daymark.app.ui.support

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.daymark.app.calm.CalmBlocks
import com.daymark.app.util.Haptics
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * "Falling blocks": slow shapes the person turns and places, with nothing to win or lose (#170).
 *
 * Every rule lives in [CalmBlocks]; this draws it. Nothing moves by itself: a shape moves only when
 * the person slides, taps or swipes on the board or presses a button. Letting it fall shows a short
 * slide down, one soft pulse as it settles, and a full row fading before it goes; with animations
 * removed ([prefersStillness]) all of that happens at once. While a fall is showing, input waits.
 */
@Composable
fun CalmBlocksScreen(onBackToMenu: () -> Unit) {
    val context = LocalContext.current
    val still = remember(context) { prefersStillness(context) }
    val scope = rememberCoroutineScope()
    var board by remember { mutableStateOf(CalmBlocks.start(System.nanoTime().toInt())) }
    var fall by remember { mutableStateOf<Fall?>(null) }
    val fallY = remember { Animatable(0f) }
    val fade = remember { Animatable(1f) }

    fun move(next: (CalmBlocks) -> CalmBlocks): Boolean {
        if (fall != null) return false
        val moved = next(board)
        val changed = moved != board
        board = moved
        return changed
    }
    fun letFall() {
        if (fall != null) return
        val from = board
        if (still) {
            board = from.drop()
            Haptics.pulse(context, durationMs = 30, amplitude = 50)
            return
        }
        val landing = from.landing()
        val rows = from.fullRowsOnLanding()
        fall = Fall(landing, rows, settled = false)
        scope.launch {
            fallY.snapTo(from.piece.y.toFloat())
            val distance = landing.y - from.piece.y
            fallY.animateTo(landing.y.toFloat(), tween(durationMillis = minOf(240, 70 + distance * 14), easing = FastOutLinearInEasing))
            Haptics.pulse(context, durationMs = 30, amplitude = 50)
            if (rows.isNotEmpty()) {
                fall = Fall(landing, rows, settled = true)
                fade.snapTo(1f)
                fade.animateTo(0f, tween(durationMillis = 280, easing = LinearEasing))
            }
            board = from.drop()
            fall = null
            fade.snapTo(1f)
        }
    }
    // The board's gestures outlive a recomposition, so they reach these through updated state.
    val onMove by rememberUpdatedState(::move)
    val onFall by rememberUpdatedState(::letFall)

    CalmActivityFrame(title = "Falling blocks", onBackToMenu = onBackToMenu) {
        Text(
            "Turn and place the shapes. A full row goes. Nothing is counted.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Board(
            board, fall, fallY.value, fade.value,
            Modifier
                // A tap turns the shape.
                .pointerInput(Unit) { detectTapGestures(onTap = { onMove { it.turn() } }) }
                // A slide moves it a square at a time under the finger; a swipe down lets it fall.
                .pointerInput(Unit) {
                    val cell = size.width.toFloat() / CalmBlocks.WIDTH
                    var dx = 0f
                    var dy = 0f
                    var shifted = 0
                    detectDragGestures(
                        onDragStart = { dx = 0f; dy = 0f; shifted = 0 },
                        onDrag = { change, amount ->
                            change.consume()
                            dx += amount.x
                            dy += amount.y
                            val wanted = (dx / cell).roundToInt()
                            while (shifted < wanted && onMove { it.right() }) shifted++
                            while (shifted > wanted && onMove { it.left() }) shifted--
                            // Against a wall, the finger's start follows it, so turning back answers at once.
                            if (shifted != wanted) dx = shifted * cell
                        },
                        onDragEnd = { if (dy > cell * 2.2f && abs(shifted) <= 1) onFall() },
                    )
                },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { move { it.left() } }, modifier = Modifier.weight(1f)) { Text("Left") }
            OutlinedButton(onClick = { move { it.turn() } }, modifier = Modifier.weight(1f)) { Text("Turn") }
            OutlinedButton(onClick = { move { it.right() } }, modifier = Modifier.weight(1f)) { Text("Right") }
        }
        OutlinedButton(
            onClick = { letFall() },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Let it fall") }
        TextButton(onClick = { move { it.cleared() } }, modifier = Modifier.align(Alignment.End)) {
            Text("Clear")
        }
    }
}

/** A fall being shown: where the shape lands, the rows it fills, and whether it has landed yet. */
private data class Fall(val landing: CalmBlocks.Piece, val rows: List<Int>, val settled: Boolean)

@Composable
private fun ColumnScope.Board(board: CalmBlocks, fall: Fall?, fallY: Float, fade: Float, gestures: Modifier) {
    val shades = CalmInk.shades
    val ink = BlockInk(well = CalmInk.ground, edge = CalmInk.sand, line = CalmInk.line, deep = CalmInk.deep, lit = CalmInk.lit, dark = CalmInk.ground.luminance() < CalmInk.sand.luminance())
    Box(
        Modifier.weight(1f, fill = false).align(Alignment.CenterHorizontally)
            .aspectRatio(CalmBlocks.WIDTH.toFloat() / CalmBlocks.HEIGHT)
            .semantics { contentDescription = "A board of shapes. Slide to move the shape, tap to turn it, swipe down to let it fall. The buttons below do the same." }
            .then(gestures),
    ) {
        Canvas(Modifier.fillMaxWidth().aspectRatio(CalmBlocks.WIDTH.toFloat() / CalmBlocks.HEIGHT)) {
            val cell = size.width / CalmBlocks.WIDTH
            val gap = cell * 0.07f
            val side = cell - 2 * gap
            val corner = CornerRadius(cell * 0.16f)
            well(ink, cell)
            fun topLeft(x: Float, y: Float) = Offset(x * cell + gap, y * cell + gap)
            fun alphaOf(y: Int) = if (fall != null && fall.settled && y in fall.rows) fade else 1f
            for (y in 0 until CalmBlocks.HEIGHT) for (x in 0 until CalmBlocks.WIDTH) {
                val shade = board.at(x, y)
                if (shade != CalmBlocks.EMPTY) block(topLeft(x.toFloat(), y.toFloat()), side, corner, shades[shade], ink, alphaOf(y))
            }
            when {
                // Landed and its rows fading: drawn where it settled, faded with them.
                fall != null && fall.settled -> fall.landing.absolute.forEach {
                    block(topLeft(it.x.toFloat(), it.y.toFloat()), side, corner, shades[fall.landing.shade], ink, alphaOf(it.y))
                }
                // Falling: drawn on its way down, with no outline to aim at.
                fall != null -> board.piece.squares.forEach {
                    held(topLeft((it.x + board.piece.x).toFloat(), it.y + fallY), side, corner, shades[board.piece.shade], ink)
                }
                else -> {
                    val inHand = board.piece.absolute.toSet()
                    board.landing().absolute.filterNot { it in inHand }.forEach {
                        // Where it will land: a faint fill and a dashed outline, never a target to hit.
                        val at = topLeft(it.x.toFloat(), it.y.toFloat())
                        drawRoundRect(ink.line, at, Size(side, side), corner, alpha = 0.12f)
                        drawRoundRect(
                            ink.line, at, Size(side, side), corner, alpha = 0.7f,
                            style = Stroke(width = 1.2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
                        )
                    }
                    board.piece.absolute.forEach { held(topLeft(it.x.toFloat(), it.y.toFloat()), side, corner, shades[board.piece.shade], ink) }
                }
            }
        }
    }
}

/** The board's colours, all from [CalmInk], and whether the theme is the dark one. */
private data class BlockInk(val well: Color, val edge: Color, val line: Color, val deep: Color, val lit: Color, val dark: Boolean)

/** The well: the sheet, a dot where each square sits, a soft shade under the top edge, a hairline round it. */
private fun DrawScope.well(ink: BlockInk, cell: Float) {
    val radius = CornerRadius(cell * 0.3f)
    drawRoundRect(ink.well, cornerRadius = radius)
    for (y in 0 until CalmBlocks.HEIGHT) for (x in 0 until CalmBlocks.WIDTH) {
        drawCircle(ink.line, radius = 1.1.dp.toPx(), center = Offset(x * cell + cell / 2, y * cell + cell / 2), alpha = 0.35f)
    }
    val shade = 18.dp.toPx()
    drawRect(Brush.verticalGradient(listOf(ink.deep.copy(alpha = if (ink.dark) 0.5f else 0.18f), ink.deep.copy(alpha = 0f)), endY = shade), size = Size(size.width, shade))
    drawRoundRect(ink.edge, cornerRadius = radius, style = Stroke(width = 1.dp.toPx()))
}

/** One square: its shade, light along the top, shade along the foot, and a fine edge. */
private fun DrawScope.block(at: Offset, side: Float, corner: CornerRadius, colour: Color, ink: BlockInk, alpha: Float) {
    val size = Size(side, side)
    drawRoundRect(colour, at, size, corner, alpha = alpha)
    drawRoundRect(
        Brush.verticalGradient(
            0f to ink.lit.copy(alpha = if (ink.dark) 0.28f else 0.55f),
            0.35f to ink.lit.copy(alpha = 0f),
            0.75f to ink.deep.copy(alpha = 0f),
            1f to ink.deep.copy(alpha = if (ink.dark) 0.55f else 0.35f),
            startY = at.y, endY = at.y + side,
        ),
        at, size, corner, alpha = alpha,
    )
    drawLine(
        ink.lit.copy(alpha = if (ink.dark) 0.25f else 0.6f),
        Offset(at.x + corner.x, at.y + 1.dp.toPx()), Offset(at.x + side - corner.x, at.y + 1.dp.toPx()),
        strokeWidth = 1.2.dp.toPx(), alpha = alpha,
    )
    drawRoundRect(ink.deep.copy(alpha = if (ink.dark) 0.6f else 0.35f), at, size, corner, alpha = alpha, style = Stroke(width = 1.dp.toPx()))
}

/** The shape in hand: a square with a soft shadow under it, lifted off the board. */
private fun DrawScope.held(at: Offset, side: Float, corner: CornerRadius, colour: Color, ink: BlockInk) {
    drawRoundRect(ink.deep, at + Offset(1.5.dp.toPx(), 3.dp.toPx()), Size(side, side), corner, alpha = if (ink.dark) 0.5f else 0.18f)
    block(at, side, corner, colour, ink, 1f)
}
