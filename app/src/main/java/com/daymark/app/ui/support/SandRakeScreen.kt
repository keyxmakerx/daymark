package com.daymark.app.ui.support

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas as GraphicsCanvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.daymark.app.calm.SandRake
import com.daymark.app.ui.theme.CardShape
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * "Sand to rake": a finger leaves grooves in sand, with the rake or on its own, and "Smooth" wipes
 * it flat (#170).
 *
 * The geometry and the limit on what is held are in [SandRake]. Nothing moves by itself and nothing
 * fades: a groove is where the finger put it until the person smooths it. "Smooth" draws a board
 * down the sand once, or wipes it at once with animations removed ([prefersStillness]). The strokes
 * are held for this visit only and never saved.
 *
 * Finished strokes are drawn once into a picture of the sand, so a new line costs only its own
 * drawing however much is already there.
 */
@Composable
fun SandRakeScreen(onBackToMenu: () -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val still = remember(context) { prefersStillness(context) }
    val scope = rememberCoroutineScope()
    var strokes by remember { mutableStateOf(listOf<Raked>()) }
    var current by remember { mutableStateOf<Raked?>(null) }
    var tool by remember { mutableStateOf(SandRake.Tool.RAKE) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    // Bumped whenever the picture of the settled sand must be painted again from the strokes.
    var repaint by remember { mutableIntStateOf(0) }
    // Bumped whenever a stroke is added to that picture, so the canvas draws it.
    var settledVersion by remember { mutableIntStateOf(0) }
    val board = remember { Animatable(0f) }
    var smoothing by remember { mutableStateOf(false) }

    val ink = SandInk(CalmInk.sand, CalmInk.deep, CalmInk.lit, CalmInk.shelf, dark = CalmInk.ground.luminance() < CalmInk.sand.luminance())
    val grain = remember(ink) { grainBrush(ink) }
    val minStep = with(density) { SandRake.MIN_STEP_DP.dp.toPx() }
    val settled = remember(size, ink, repaint) {
        if (size == IntSize.Zero) null
        else ImageBitmap(size.width, size.height).also { picture ->
            picture.paint(density) { flatSand(ink, grain); strokes.forEach { groove(it, ink, grain) } }
        }
    }

    // The drag handler below outlives a recomposition, so it reads these and never a stale copy.
    val latestSettled by rememberUpdatedState(settled)
    val latestInk by rememberUpdatedState(ink)
    val latestGrain by rememberUpdatedState(grain)

    CalmActivityFrame(title = "Sand to rake", onBackToMenu = onBackToMenu) {
        Text(
            "Draw a finger across the sand. There is no pattern to make.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Canvas(
            Modifier.weight(1f).fillMaxWidth().clip(CardShape)
                .onSizeChanged { size = it }
                .semantics { contentDescription = "Sand. Drag a finger to draw lines in it." }
                .pointerInput(tool, smoothing) {
                    if (smoothing) return@pointerInput
                    fun finish() {
                        val done = current ?: return
                        current = null
                        val all = strokes + done
                        val kept = SandRake.bounded(all.map { it.points })
                        strokes = all.takeLast(kept.size)
                        if (kept.size < all.size) {
                            // The oldest strokes were let go: paint the sand again from what is kept.
                            repaint++
                        } else {
                            latestSettled?.paint(density) { groove(done, latestInk, latestGrain) }
                            settledVersion++
                        }
                    }
                    detectDragGestures(
                        onDragStart = { at -> current = Raked(tool, listOf(SandRake.Point(at.x, at.y))) },
                        onDrag = { change, _ ->
                            val stroke = current ?: return@detectDragGestures
                            val next = SandRake.Point(change.position.x, change.position.y)
                            if (SandRake.worthKeeping(stroke.points.lastOrNull(), next, minStep)) {
                                current = stroke.copy(points = stroke.points + next)
                            }
                        },
                        onDragEnd = { finish() },
                        onDragCancel = { finish() },
                    )
                },
        ) {
            settledVersion // read, so a stroke added to the picture is drawn
            settled?.let { drawImage(it) }
            current?.let { groove(it, ink, grain) }
            if (smoothing) smoothingBoard(board.value, ink, grain)
            trayEdges(ink)
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf(SandRake.Tool.RAKE to "Rake", SandRake.Tool.FINGER to "Finger").forEachIndexed { i, (option, label) ->
                SegmentedButton(
                    selected = tool == option,
                    onClick = { tool = option },
                    shape = SegmentedButtonDefaults.itemShape(i, 2),
                    // No tick on the chosen one: a tick reads as getting something right (`CLAUDE.md` §4).
                    icon = {},
                ) { Text(label) }
            }
        }
        OutlinedButton(
            onClick = {
                if (smoothing) return@OutlinedButton
                val wipe = { strokes = emptyList(); current = null; repaint++ }
                if (still || strokes.isEmpty()) {
                    wipe()
                } else {
                    scope.launch {
                        smoothing = true
                        board.snapTo(0f)
                        board.animateTo(1f, tween(durationMillis = 650, easing = FastOutSlowInEasing))
                        wipe()
                        smoothing = false
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Smooth")
        }
    }
}

/** One stroke, and what drew it. */
private data class Raked(val tool: SandRake.Tool, val points: List<SandRake.Point>)

/** The sand's colours, all from [CalmInk], and whether the theme is the dark one. */
private data class SandInk(val sand: Color, val deep: Color, val lit: Color, val board: Color, val dark: Boolean)

/** A fine grain: specks of the shade and the light, drawn once per theme and tiled. */
private fun grainBrush(ink: SandInk): Brush {
    val tile = ImageBitmap(128, 128)
    val random = Random(9)
    val canvas = GraphicsCanvas(tile)
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, canvas, Size(128f, 128f)) {
        repeat(900) {
            val colour = if (random.nextBoolean()) ink.deep else ink.lit
            val side = if (random.nextFloat() < 0.85f) 1f else 1.6f
            drawRect(colour.copy(alpha = 0.05f + random.nextFloat() * 0.16f), Offset(random.nextFloat() * 128f, random.nextFloat() * 128f), Size(side, side))
        }
    }
    return ShaderBrush(ImageShader(tile, TileMode.Repeated, TileMode.Repeated))
}

private fun ImageBitmap.paint(density: Density, block: DrawScope.() -> Unit) {
    CanvasDrawScope().draw(density, LayoutDirection.Ltr, GraphicsCanvas(this), Size(width.toFloat(), height.toFloat())) { block() }
}

private fun DrawScope.flatSand(ink: SandInk, grain: Brush) {
    drawRect(ink.sand)
    drawRect(grain)
}

/** A line through [points], bending through the midpoints between them rather than in straight pieces. */
private fun smoothPath(points: List<SandRake.Point>): Path = Path().apply {
    moveTo(points[0].x, points[0].y)
    for (i in 1 until points.size - 1) {
        val a = points[i]
        val b = points[i + 1]
        quadraticTo(a.x, a.y, (a.x + b.x) / 2f, (a.y + b.y) / 2f)
    }
    lineTo(points.last().x, points.last().y)
}

/**
 * One stroke's grooves. Light falls from the top left, so the far wall of each groove catches it and
 * the near wall is in shade. Under the rake the sand is smoothed first, so crossing old lines wipes
 * them as real sand would.
 */
private fun DrawScope.groove(stroke: Raked, ink: SandInk, grain: Brush) {
    val points = stroke.points
    if (points.size < 2) return
    val tool = stroke.tool
    val spacing = SandRake.TINE_SPACING_DP.dp.toPx()
    val width = tool.grooveDp.dp.toPx()
    val middle = (tool.tines - 1) / 2f
    val round = { w: Float -> Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round) }
    if (tool.tines > 1) {
        val band = smoothPath(points)
        val span = round((tool.tines - 1) * spacing + width + 4.dp.toPx())
        drawPath(band, ink.sand, style = span)
        drawPath(band, grain, style = span)
    }
    for (i in 0 until tool.tines) {
        val path = smoothPath(SandRake.offsetLine(points, (i - middle) * spacing))
        translate(0.9.dp.toPx(), 1.1.dp.toPx()) {
            drawPath(path, ink.lit.copy(alpha = if (ink.dark) 0.35f else 0.9f), style = round(width + 1.6.dp.toPx()))
        }
        translate(-0.5.dp.toPx(), -0.6.dp.toPx()) {
            drawPath(path, ink.deep.copy(alpha = if (ink.dark) 0.75f else 0.55f), style = round(width))
        }
        drawPath(path, ink.deep.copy(alpha = if (ink.dark) 0.5f else 0.3f), style = round(width * 0.5f))
    }
}

/** The board crossing the sand, [progress] of the way down: flat sand above it, a soft shadow below. */
private fun DrawScope.smoothingBoard(progress: Float, ink: SandInk, grain: Brush) {
    val y = progress * (size.height + 8.dp.toPx())
    clipRect(bottom = y) { flatSand(ink, grain) }
    val shadow = 14.dp.toPx()
    drawRect(
        Brush.verticalGradient(listOf(ink.deep.copy(alpha = if (ink.dark) 0.6f else 0.3f), ink.deep.copy(alpha = 0f)), startY = y, endY = y + shadow),
        Offset(0f, y), Size(size.width, shadow),
    )
    val edge = 6.dp.toPx()
    drawRect(ink.board, Offset(0f, y - edge), Size(size.width, edge))
    drawRect(ink.lit.copy(alpha = 0.5f), Offset(0f, y - edge), Size(size.width, 1.2.dp.toPx()))
}

/** A shallow tray: shade along the top and left inner edges. */
private fun DrawScope.trayEdges(ink: SandInk) {
    val top = 14.dp.toPx()
    val side = 10.dp.toPx()
    drawRect(Brush.verticalGradient(listOf(ink.deep.copy(alpha = if (ink.dark) 0.6f else 0.28f), ink.deep.copy(alpha = 0f)), endY = top), size = Size(size.width, top))
    drawRect(Brush.horizontalGradient(listOf(ink.deep.copy(alpha = if (ink.dark) 0.45f else 0.2f), ink.deep.copy(alpha = 0f)), endX = side), size = Size(side, size.height))
}
