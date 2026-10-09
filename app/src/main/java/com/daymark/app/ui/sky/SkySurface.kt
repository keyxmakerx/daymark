package com.daymark.app.ui.sky

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.daymark.app.sky.Sky
import com.daymark.app.sky.SkyAge
import com.daymark.app.sky.SkyConstellation
import com.daymark.app.sky.SkyDetail
import com.daymark.app.sky.SkyGlyph
import com.daymark.app.sky.SkyKind
import com.daymark.app.sky.SkyLayout
import com.daymark.app.sky.SkyOpening
import com.daymark.app.sky.SkyOptions
import com.daymark.app.sky.SkyPalette
import com.daymark.app.sky.SkyTwinkle
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The Sky, drawn (`DECISIONS.md` §D11).
 *
 * This file decides nothing. Every number in it is read from `sky/`: the core's radius and alpha
 * from [SkyGlyph], the ground and ink from [SkyPalette], the zoom thresholds from [SkyDetail], the
 * star positions from [SkyLayout], the opening's pace from [SkyOpening], and constellations from
 * [SkyConstellation]. The viewport transform and every zoom limit are [SkyPresentation]'s, and the
 * camera's state is [SkyCamera]'s. What is here is draw calls and gesture plumbing — nothing a unit
 * test would have wanted to reach, because everything a unit test wants to reach is next door, on a
 * plain JVM.
 *
 * That is not a stylistic preference. There is no Android SDK in this environment, so a rule that
 * lives in this file is a rule nobody can execute until CI; a rule that lives next door is checked
 * before it ships.
 *
 * ## What is drawn
 *
 * Every star is a memory, on a night ground with nothing else on it: no background stars, no field.
 * Stars are drawn where they are today, drift included. Far out each is a point of light; leaning
 * in, kind marks resolve ([SkyDetail.drawsGlyphs]); right in, each one is drawn as a sun of its own
 * ([SkyPresentation.sunRadius]). Constellations the person drew are lines between their stars and a
 * name beside the topmost one, the only words on the canvas, and every one of them the person's.
 *
 * ## What is not drawn, and why
 *
 * **Threads between project steps.** §3.3 links a step to the previous step of the same project
 * with a hairline. A thread is a property of a *pair*, and [SkyLayout] carries no project identity
 * — a star is `(kind, id, day, mood)`. Widening it would put a grouping key on the Sky, which is a
 * field the surface could later draw something else from. So each step gets
 * [SkyGlyph.threadStubDp], which exists for exactly this case: the link is part of the glyph, drawn
 * alone, so "a step in something" is readable from one star. Not built: #152.
 *
 * **Dates.** No part of the sky is labelled with a date; dates live in a star's detail and in the
 * list (§7.5), in ordinary, scalable text.
 *
 * ## The clock, and why it cannot be left running
 *
 * Twinkle ships on, behind the motion switch, which takes the platform's reduced-motion setting as
 * its default (`docs/SKY.md` §7.4). Every number in it is [SkyTwinkle]'s and every one of them is
 * pure, so what is here is a clock, written to be impossible to leave running:
 *
 *  - it exists only while [SkyOptions.motionEnabled] is true — off, there is no loop at all;
 *  - it is wrapped in `repeatOnLifecycle(RESUMED)`, so it stops when the app is backgrounded or
 *    another screen covers this one;
 *  - it lives inside this composable, so switching to the list (§7.5) disposes it;
 *  - it drives a draw and never a recomposition — the elapsed time is read inside the `Canvas`
 *    lambda, so a frame redraws the canvas and rebuilds no layout;
 *  - and the draw skips every star the viewport cannot see, so nothing off screen is animated.
 */
@Composable
internal fun SkySurface(
    layout: SkyLayout,
    /** Where every star is today: [SkyLayout.positionsOn]. */
    positions: Array<FloatArray>,
    camera: SkyCamera,
    opening: SkyOpeningState,
    options: SkyOptions,
    /**
     * Today, as `LocalDate.toEpochDay()`, so a star's age can be worked out.
     *
     * The clock read happens in the screen and is passed down. `sky/` is import-free and has no
     * clock by design — a pure layer that asked the system what day it is would stop being
     * testable.
     */
    todayEpochDay: Long,
    /**
     * What the canvas is, for a screen reader — from [SkyPresentation.canvasDescription].
     *
     * Named `description` and not `contentDescription` deliberately: inside a `semantics {}` block
     * the name `contentDescription` resolves to the semantics property, whose getter throws, so a
     * parameter sharing that name turns `this.contentDescription = contentDescription` into a
     * crash on first composition.
     */
    description: String,
    selectedStar: Int,
    constellations: List<SkyViewModel.ShownConstellation>,
    /** The stars joined so far in a constellation being drawn, in order; empty when not drawing. */
    picked: List<Int>,
    onStarTapped: (Int) -> Unit,
    onSkipOpening: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val xs = positions[0]
    val ys = positions[1]
    val bounds = remember(positions, layout) { SkyPresentation.boundsOf(xs, ys, layout.height) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(viewport, bounds) {
        camera.measure(viewport.width.toFloat(), viewport.height.toFloat(), bounds)
    }

    // Sprites are rasterised at the device's real pixel density and stamped 1:1, so they are keyed
    // on it: a density change (a fold, a display swap) throws the whole cache away rather than
    // resampling stars that were drawn for a different screen.
    val pxPerDp = LocalDensity.current.density
    val sprites = remember(pxPerDp) { SkySprites(pxPerDp) }

    // The frame loop. See this file's header for the things that stop it running when nobody is
    // looking at it.
    val elapsedMillis by rememberSkyElapsedMillis(options.motionEnabled)

    // Each star's moment in the opening, from its own identity, so the opening is the same sky
    // every time and never a random one.
    val moments = remember(layout) {
        FloatArray(layout.starCount) {
            SkyOpening.moment(Sky.identityOf(layout.kindAt(it), SkyTwinkle.identityIdAt(layout, it)))
        }
    }
    val lineStrengths = remember(constellations, layout, todayEpochDay) {
        constellations.map { SkyConstellation.lineAlphas(it.points, it.resolved, layout, todayEpochDay) }
    }
    val measurer = rememberTextMeasurer()
    val names = remember(constellations, measurer) {
        val style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium)
        constellations.map { measurer.measure(AnnotatedString(it.name), style) }
    }

    val skipping by rememberUpdatedState(opening.playing)
    val tapped by rememberUpdatedState(onStarTapped)
    val skip by rememberUpdatedState(onSkipOpening)

    val gestures = Modifier
        .pointerInput(camera) {
            detectTransformGestures { centroid, pan, gestureZoom, _ ->
                // Pinch and drag are one movement. While the opening plays the camera is its, and
                // a tap is how to skip it.
                if (!skipping) camera.gesture(centroid.x, centroid.y, pan.x, pan.y, gestureZoom)
            }
        }
        .pointerInput(camera, xs, ys) {
            detectTapGestures(
                onTap = { offset ->
                    if (skipping) {
                        skip()
                    } else {
                        tapped(
                            SkyPresentation.nearestStar(
                                xs = xs,
                                ys = ys,
                                bounds = camera.bounds,
                                scalePx = camera.scalePx,
                                panXPx = camera.panX(),
                                panYPx = camera.panY(),
                                tapXPx = offset.x,
                                tapYPx = offset.y,
                                // The platform minimum, applied to the target and not to the drawn
                                // size: a star drawn at 1.9 dp is still a 48 dp target (§7.1).
                                maxDistancePx = SkyGlyph.TOUCH_TARGET_DP.dp.toPx() / 2f,
                            ),
                        )
                    }
                },
            )
        }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { viewport = it },
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .then(gestures)
                .semantics { this.contentDescription = description },
        ) {
            val w = size.width
            val h = size.height
            if (layout.starCount == 0 || w <= 0f || h <= 0f || !camera.ready) return@Canvas
            val scale = camera.scalePx
            val origin = Offset(camera.bounds.left, camera.bounds.top)
            val pan = Offset(camera.panX(), camera.panY())
            val across = camera.screenWidthsAcross
            val detail = SkyPresentation.detailFor(across)
            val reveal = opening.reveal
            val birth = opening.birth
            val born = opening.bornIndex
            // Enough margin that a glyph whose centre has left the screen still draws its rays.
            val margin = 16.dp.toPx()
            val sunFrom = SUN_FROM_DP.dp.toPx()

            if (born in 0 until layout.starCount && birth > 0f && birth < 1f) {
                drawBirth(
                    centre = Offset(
                        SkyPresentation.toScreen(xs[born], origin.x, scale, pan.x),
                        SkyPresentation.toScreen(ys[born], origin.y, scale, pan.y),
                    ),
                    progress = birth,
                    radius = BIRTH_RADIUS_DP.dp.toPx(),
                )
            }

            // Array order is time order, so newer stars still land on top of older ones.
            for (i in 0 until layout.starCount) {
                val kind = layout.kindAt(i)
                val sun = SkyPresentation.sunRadius(kind, scale)
                val cx = SkyPresentation.toScreen(xs[i], origin.x, scale, pan.x)
                val cy = SkyPresentation.toScreen(ys[i], origin.y, scale, pan.y)
                if (!SkyPresentation.isOnScreen(cx, cy, w, h, margin + sun * CORONA)) continue
                val arrival =
                    if (i == born) SkyOpening.bornBrightness(birth) else SkyOpening.brightness(reveal, moments[i])
                if (arrival <= 0f) continue
                drawStar(
                    sprites = sprites,
                    kind = kind,
                    // The anchor id, handed over by SkyTwinkle so the renderer cannot reach for a
                    // different record: a folded star has one position and one rhythm, and both
                    // come from the first id it covers.
                    id = SkyTwinkle.identityIdAt(layout, i),
                    ageYears = SkyAge.ageYears(layout.epochDay[i], todayEpochDay),
                    moodLevel = layout.moodLevel[i],
                    centre = Offset(cx, cy),
                    detail = detail,
                    options = options,
                    elapsedMillis = elapsedMillis,
                    selected = i == selectedStar,
                    arrival = arrival,
                    sunPx = if (sun >= sunFrom) sun else 0f,
                )
            }

            val strength = SkyPresentation.constellationAlpha(reveal, across)
            if (strength > 0f && constellations.isNotEmpty()) {
                drawConstellations(constellations, lineStrengths, names, xs, ys, origin, scale, pan, strength)
            }
            if (picked.isNotEmpty()) drawPicked(picked, xs, ys, origin, scale, pan)
        }

        // The way back to the whole sky. It appears only once there is somewhere to come back
        // FROM, so a sky at rest carries no chrome at all and a zoomed one is never a place you are
        // stuck in. Derived, so a pinch redraws the canvas without recomposing this.
        val zoomedIn by remember(camera) { derivedStateOf { camera.zoom > SkyPresentation.MIN_ZOOM * 1.01f } }
        if (zoomedIn && !opening.playing) {
            TextButton(
                onClick = { camera.fitAll(options.motionEnabled) },
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            ) {
                Text("Fit the whole sky", color = SkyNightInk)
            }
        }
    }
}

/**
 * The opening as it plays: how far through the stars it is, and the birth of a new star after it.
 * Written by the screen's frame loop, read inside the draw, so it redraws and never recomposes.
 */
@Stable
internal class SkyOpeningState(playing: Boolean) {
    /** Whether the opening is still playing. A tap skips the whole of it. */
    var playing by mutableStateOf(playing)

    /** How far through the stars the opening is, 0 to 1 ([SkyOpening.revealAt]). */
    var reveal by mutableFloatStateOf(if (playing) 0f else 1f)

    /** How far through its birth the new star is, 0 to 1; 1 when nothing is being born. */
    var birth by mutableFloatStateOf(1f)

    /** The star being born, or -1. Hidden until it ignites. */
    var bornIndex by mutableIntStateOf(-1)

    /** Everything at its end: every star in, nothing being born. */
    fun finish() {
        playing = false
        reveal = 1f
        birth = 1f
        bornIndex = -1
    }
}

/**
 * A constellation as the person drew it: the stars it joins where they were that day, its lines,
 * and around it, fainter toward the edges, the stars that already existed then, in the colours they
 * had then. Still, with nothing in it that opens. It is the only way back in time anywhere in the
 * sky (`DECISIONS.md` §D11).
 *
 * Decorative to assistive technology: the screen around it names the constellation and its date.
 */
@Composable
internal fun SkyConstellationPhoto(
    layout: SkyLayout,
    constellation: SkyViewModel.ShownConstellation,
    options: SkyOptions,
    modifier: Modifier = Modifier,
) {
    val pxPerDp = LocalDensity.current.density
    val sprites = remember(pxPerDp) { SkySprites(pxPerDp) }
    // A photo is still: no twinkle and no glint, whatever the live sky is doing.
    val still = remember(options) { options.copy(motionEnabled = false) }
    var area by remember { mutableStateOf(IntSize.Zero) }
    val frame = remember(constellation, area) {
        PhotoFrame.of(constellation, area.width.toFloat(), area.height.toFloat())
    }
    val around = remember(layout, constellation, frame) {
        if (frame == null) {
            IntArray(0)
        } else {
            SkyConstellation.neighbours(
                constellation.points,
                constellation.resolved,
                layout,
                constellation.madeEpochDay,
                frame.reach,
            )
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { area = it }
            .clearAndSetSemantics {},
    ) {
        val f = frame ?: return@Canvas
        val made = constellation.madeEpochDay
        val middle = Offset(size.width / 2f, size.height / 2f)
        val reachPx = hypot(size.width, size.height) / 2f

        for (i in around) {
            val p = f.toScreen(layout.xOn(i, made), layout.yOn(i, made))
            if (p.x < -4f || p.y < -4f || p.x > size.width + 4f || p.y > size.height + 4f) continue
            val edge = (1f - (p - middle).getDistance() / reachPx).coerceAtLeast(0f)
            val shade = 0.62f * edge.pow(1.3f)
            if (shade < 0.03f) continue
            drawStar(
                sprites = sprites,
                kind = layout.kindAt(i),
                id = SkyTwinkle.identityIdAt(layout, i),
                ageYears = SkyAge.ageYears(layout.epochDay[i], made),
                moodLevel = layout.moodLevel[i],
                centre = p,
                detail = SkyDetail.FAR,
                options = still,
                elapsedMillis = 0L,
                selected = false,
                arrival = shade,
                sunPx = 0f,
            )
        }

        val points = constellation.points
        val resolved = constellation.resolved
        val line = skyColor(SkyPalette.CONSTELLATION_LINE)
        for (i in 0 until points.size - 1) {
            if (resolved[i] < 0 || resolved[i + 1] < 0) continue
            drawLine(
                color = line,
                start = f.toScreen(points[i].x, points[i].y),
                end = f.toScreen(points[i + 1].x, points[i + 1].y),
                strokeWidth = 1.2.dp.toPx(),
                cap = StrokeCap.Round,
                alpha = 0.55f,
            )
        }
        for (i in points.indices) {
            val s = resolved[i]
            if (s < 0) continue
            drawStar(
                sprites = sprites,
                kind = layout.kindAt(s),
                id = SkyTwinkle.identityIdAt(layout, s),
                ageYears = SkyAge.ageYears(layout.epochDay[s], made),
                moodLevel = layout.moodLevel[s],
                centre = f.toScreen(points[i].x, points[i].y),
                detail = SkyDetail.FAR,
                options = still,
                elapsedMillis = 0L,
                selected = false,
                arrival = 1f,
                sunPx = 0f,
            )
        }
    }
}

/** Where a photo's constellation sits: its middle at the screen's, across half the screen's width. */
private class PhotoFrame(
    private val centreX: Float,
    private val centreY: Float,
    private val scale: Float,
    private val width: Float,
    private val height: Float,
) {
    /** How far from the middle, in the sky's units, a star still reaches the photo's corners. */
    val reach: Float get() = hypot(width, height) / 2f / scale

    fun toScreen(x: Float, y: Float): Offset =
        Offset(width / 2f + (x - centreX) * scale, height / 2f + (y - centreY) * scale)

    companion object {
        fun of(c: SkyViewModel.ShownConstellation, width: Float, height: Float): PhotoFrame? {
            if (width <= 0f || height <= 0f) return null
            val kept = c.points.indices.filter { c.resolved[it] >= 0 }
            if (kept.isEmpty()) return null
            val left = kept.minOf { c.points[it].x }
            val right = kept.maxOf { c.points[it].x }
            val top = kept.minOf { c.points[it].y }
            val bottom = kept.maxOf { c.points[it].y }
            val span = maxOf(right - left, (bottom - top) * width / height, 0.01f)
            return PhotoFrame((left + right) / 2f, (top + bottom) / 2f, width * 0.5f / span, width, height)
        }
    }
}

/** How far out a sun's corona reaches, as a multiple of its radius. */
private const val CORONA = 2.4f

/** A star starts to be drawn as a sun once its disc would be this big, and is fully one by [SUN_FULL_DP]. */
private const val SUN_FROM_DP = 2.5f
private const val SUN_FULL_DP = 8f

/** How far a new star's birth cloud reaches, on the screen. */
private const val BIRTH_RADIUS_DP = 90f

/** How many wisps a birth cloud is drawn with as it spins in. */
private const val BIRTH_WISPS = 7

/**
 * A constellation's lines and its name.
 *
 * Each line is measured against how long it was when drawn ([SkyConstellation.lineAlphas]), so as
 * its stars drift apart over the years it fades and the constellation falls out of the sky. Lines
 * stop short of the stars they join. Names sit above the topmost star, stay on the screen, and
 * never cover one another: a name that would is left out until the sky moves.
 */
private fun DrawScope.drawConstellations(
    constellations: List<SkyViewModel.ShownConstellation>,
    lineStrengths: List<FloatArray>,
    names: List<TextLayoutResult>,
    xs: FloatArray,
    ys: FloatArray,
    origin: Offset,
    scale: Float,
    pan: Offset,
    strength: Float,
) {
    val gap = 6.dp.toPx()
    val stroke = 1.dp.toPx()
    val edge = 8.dp.toPx()
    val lineInk = skyColor(SkyPalette.CONSTELLATION_LINE)
    val nameInk = skyColor(SkyPalette.CONSTELLATION_NAME)
    val taken = ArrayList<Rect>()

    for ((n, c) in constellations.withIndex()) {
        val alphas = lineStrengths[n]
        var shown = 0
        for (i in 0 until c.points.size - 1) {
            val a = c.resolved[i]
            val b = c.resolved[i + 1]
            if (a < 0 || b < 0 || alphas[i] <= 0f) continue
            val ax = SkyPresentation.toScreen(xs[a], origin.x, scale, pan.x)
            val ay = SkyPresentation.toScreen(ys[a], origin.y, scale, pan.y)
            val bx = SkyPresentation.toScreen(xs[b], origin.x, scale, pan.x)
            val by = SkyPresentation.toScreen(ys[b], origin.y, scale, pan.y)
            val dx = bx - ax
            val dy = by - ay
            val length = sqrt(dx * dx + dy * dy)
            if (length < gap * 2f + 1f) continue
            val ux = dx / length
            val uy = dy / length
            drawLine(
                color = lineInk,
                start = Offset(ax + ux * gap, ay + uy * gap),
                end = Offset(bx - ux * gap, by - uy * gap),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
                alpha = (0.38f * strength * alphas[i]).coerceIn(0f, 1f),
            )
            shown++
        }
        if (shown == 0) continue

        var topX = 0f
        var topY = Float.MAX_VALUE
        for (s in c.resolved) {
            if (s < 0) continue
            val sy = SkyPresentation.toScreen(ys[s], origin.y, scale, pan.y)
            if (sy < topY) {
                topY = sy
                topX = SkyPresentation.toScreen(xs[s], origin.x, scale, pan.x)
            }
        }
        val name = names[n]
        val nameWidth = name.size.width.toFloat()
        val nameHeight = name.size.height.toFloat()
        val left = (topX + 10.dp.toPx()).coerceAtMost(size.width - nameWidth - edge).coerceAtLeast(edge)
        val top = topY - 8.dp.toPx() - nameHeight
        if (top < edge || top + nameHeight > size.height - edge) continue
        val box = Rect(left - 4.dp.toPx(), top, left + nameWidth + 4.dp.toPx(), top + nameHeight)
        if (taken.any { it.overlaps(box) }) continue
        taken.add(box)
        drawText(name, color = nameInk, topLeft = Offset(left, top), alpha = (0.75f * strength).coerceIn(0f, 1f))
    }
}

/** The constellation being drawn, joined as the person goes: a ring on each star, a line between. */
private fun DrawScope.drawPicked(
    picked: List<Int>,
    xs: FloatArray,
    ys: FloatArray,
    origin: Offset,
    scale: Float,
    pan: Offset,
) {
    var previous: Offset? = null
    for (s in picked) {
        if (s !in xs.indices) continue
        val p = Offset(
            SkyPresentation.toScreen(xs[s], origin.x, scale, pan.x),
            SkyPresentation.toScreen(ys[s], origin.y, scale, pan.y),
        )
        val from = previous
        if (from != null) {
            drawLine(SkyNightInk, from, p, strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round, alpha = 0.8f)
        }
        drawCircle(SkyNightInk, radius = 8.dp.toPx(), center = p, style = Stroke(width = 1.2.dp.toPx()), alpha = 0.9f)
        previous = p
    }
}

/**
 * A new star being born, at [progress] 0 to 1: a cloud spins in and collapses, a core warms, it
 * ignites in a flash, and a ring of light runs out. The star itself appears at the ignition
 * ([SkyOpening.bornBrightness]). Soft-edged throughout; nothing here is a hard shape.
 */
private fun DrawScope.drawBirth(centre: Offset, progress: Float, radius: Float) {
    val p = progress
    val reach = radius * (1f - 0.84f * smooth(0f, 0.8f, p))
    val cloud = smooth(0f, 0.12f, p) * (1f - smooth(0.8f, 0.95f, p)) * (0.12f + 0.45f * p)
    if (cloud > 0f) {
        val tint = lerp(
            skyColor(SkyPalette.BIRTH_CLOUD_FROM),
            skyColor(SkyPalette.BIRTH_CLOUD_TO),
            smooth(0.1f, 0.8f, p),
        )
        val wisp = reach * 0.55f
        for (k in 0 until BIRTH_WISPS) {
            val angle = p * 4f + k * (2f * PI.toFloat() / BIRTH_WISPS)
            val out = reach * (0.3f + 0.08f * (k % 3))
            val at = Offset(centre.x + cos(angle) * out, centre.y + sin(angle) * out)
            drawGlow(at, wisp, tint, (cloud * 0.9f).coerceIn(0f, 1f))
        }
    }
    val warm = smooth(0.25f, 0.8f, p) * (1f - smooth(0.82f, 0.9f, p)) * 0.9f
    if (warm > 0f) drawGlow(centre, reach * 0.35f, skyColor(SkyPalette.BIRTH_CORE), warm)
    val flash = exp(-((p - 0.815f) / 0.02f).pow(2))
    if (flash > 0.01f) drawGlow(centre, radius * 0.45f, skyColor(SkyPalette.SUN_CORE), flash.coerceAtMost(1f))
    if (p >= SkyOpening.IGNITES) {
        drawCircle(
            color = skyColor(SkyPalette.BIRTH_RING),
            radius = (p - SkyOpening.IGNITES) * 5f * radius,
            center = centre,
            style = Stroke(width = 2.dp.toPx()),
            alpha = ((1f - p) * 1.6f).coerceIn(0f, 1f),
        )
    }
}

/** A soft round glow, brightest in the middle and gone at [radius]. Added, the way light is. */
private fun DrawScope.drawGlow(centre: Offset, radius: Float, colour: Color, alpha: Float) {
    if (radius <= 0f || alpha <= 0f) return
    drawCircle(
        brush = Brush.radialGradient(
            0f to colour.copy(alpha = alpha),
            1f to colour.copy(alpha = 0f),
            center = centre,
            radius = radius,
        ),
        radius = radius,
        center = centre,
        blendMode = BlendMode.Plus,
    )
}

/**
 * A star close in, drawn as the sun it is: a white-hot middle, a rim in the star's own colour, and
 * a dim soft corona round it. [strength] is the star's own light, so an old star's sun is as quiet
 * as the old star.
 */
private fun DrawScope.drawSun(centre: Offset, radius: Float, colour: Color, strength: Float) {
    if (strength <= 0f) return
    drawGlow(centre, radius * CORONA, colour, 0.3f * strength)
    val core = skyColor(SkyPalette.SUN_CORE)
    drawCircle(
        brush = Brush.radialGradient(
            0f to core.copy(alpha = strength),
            0.6f to lerp(core, colour, 0.45f).copy(alpha = strength),
            1f to colour.copy(alpha = 0.8f * strength),
            center = centre,
            radius = radius,
        ),
        radius = radius,
        center = centre,
    )
}

private fun smooth(from: Float, to: Float, x: Float): Float {
    val u = ((x - from) / (to - from)).coerceIn(0f, 1f)
    return u * u * (3f - 2f * u)
}

/** Packed `0xRRGGBB` from [SkyPalette] as an opaque Compose colour. */
internal fun skyColor(rgb: Int): Color = Color(0xFF000000.toInt() or rgb)

/** The Sky's night ground, from the one place that measures it. Never a fourth copy of the hex. */
internal val SkyNightBg: Color = skyColor(SkyPalette.NIGHT_BG)
internal val SkyNightInk: Color = skyColor(SkyPalette.NIGHT_INK)
internal val SkyNightFaint: Color = skyColor(SkyPalette.NIGHT_FAINT)

/**
 * One star: a point, then a glow.
 *
 * `docs/SKY.md` §3.5, *"a point, then a glow"* — a hard-edged near-white core, a tight bright inner
 * glow against it, and a soft faint outer glow spread by the mood. Those three live in the sprite
 * ([SkySprites]); what happens here is where it goes, how bright it is at this instant, and the
 * prism flash on top.
 *
 * **Everything that decides how a star looks is asked for, never computed here.** Colour is
 * [SkyGlyph.starTint], which is age and the star's own temperature. Brightness is
 * [SkyGlyph.starBrightness], which is age, times how far the star has arrived ([SkyOpening]),
 * multiplied by [SkyTwinkle.alphaAt], which is the star's own rhythm. Halo geometry is
 * [SkyGlyph.haloRadiusDp] and [SkyGlyph.haloPeakAlpha]; the core's scale is
 * [SkyGlyph.coreScale]. Every one of those takes a mood level and all but the halo ignore
 * it, so the rule *mood moves the halo's spread and nothing else* is defended at the signature
 * rather than trusted here.
 *
 * **Additive, not painted over.** The sprite is stamped with [BlendMode.Plus], so two stars whose
 * glows overlap get brighter where they meet, the way light does. On the near-black ground
 * ([SkyPalette.NIGHT_BG]) this is very close to ordinary compositing everywhere else, which is why
 * the ground had to go near-black in the same change.
 *
 * **Sub-pixel, and therefore not scaled.** The sprite is rasterised at the device's real pixel
 * density and stamped at its natural size at a fractional offset, so stars sit where they are
 * rather than snapping to whole pixels as the sky is panned. That rules out [SkyTwinkle.scaleAt]'s
 * 2% breathe, which would need a resample: in `docs/SKY.md` §3.6 the breathe is a dip in
 * brightness, and a 2% resample of a sprite this small costs more in softness than the swell is
 * worth.
 *
 * Nothing here varies with how many records the star covers. A folded star is drawn exactly like a
 * single one; a bigger mark for a busier day would rank days by output (§6.2).
 */
private fun DrawScope.drawStar(
    sprites: SkySprites,
    kind: SkyKind,
    id: Long,
    ageYears: Float,
    moodLevel: Int,
    centre: Offset,
    detail: SkyDetail,
    options: SkyOptions,
    elapsedMillis: Long,
    selected: Boolean,
    /**
     * How far this star has arrived, from the opening or its own birth ([SkyOpening]): 0 not yet,
     * briefly above 1 as it flares in, then 1. In a constellation's photo, how far into shadow a
     * star around it stands.
     */
    arrival: Float,
    /** Its radius as a sun at this zoom, or 0 while it is still a point of light. */
    sunPx: Float,
) {
    if (arrival <= 0f) return
    // How brightly this star burns: its age and its arrival, and then its own beat. All are
    // multipliers on the sprite's own alphas, so the twinkle is a proportion of whatever the star
    // already was — an old star's breathe is as faint as the old star, and no star can be twinkled
    // up past a younger one.
    val fade = SkyGlyph.starBrightness(kind, ageYears, moodLevel) * arrival
    val brightness = fade * SkyTwinkle.alphaAt(kind, id, elapsedMillis, options)
    // The flare as a star arrives lifts the fade above 1; a stroke or a disc takes it capped.
    val ink = fade.coerceIn(0f, 1f)

    val sprite = sprites.star(
        kind = kind,
        id = id,
        ageYears = ageYears,
        moodLevel = moodLevel,
        quiet = options.highContrast,
    )
    val topLeft = Offset(centre.x - sprite.halfWidth, centre.y - sprite.halfHeight)
    drawImage(
        image = sprite.image,
        topLeft = topLeft,
        alpha = brightness.coerceIn(0f, 1f),
        blendMode = BlendMode.Plus,
    )

    drawGlint(
        sprites = sprites,
        sprite = sprite,
        kind = kind,
        id = id,
        centre = centre,
        options = options,
        elapsedMillis = elapsedMillis,
        fade = fade,
    )

    val coreRadius = (SkyGlyph.coreRadiusDp(kind, moodLevel) * SkyGlyph.coreScale(kind)).dp.toPx()
    val sun = if (sunPx > 0f) smooth(SUN_FROM_DP.dp.toPx(), SUN_FULL_DP.dp.toPx(), sunPx) else 0f
    val ringed = max(coreRadius, sunPx * sun)

    // Kind is carried by form, and form only resolves once the person has leaned in to one day.
    // `docs/SKY.md` §3.3: "Kind marks appear only at CLOSE (§4). Further out, every kind is just a
    // star, and the list always names it." A landmark still looks different at every zoom, and
    // that is its light and not a kind mark — see SkyDetail.drawsGlyphs.
    val glyphs = SkyDetail.drawsGlyphs(detail)
    if (!glyphs && sun <= 0f) {
        if (selected) drawSelection(centre, ringed)
        return
    }

    // The same tint the sprite was rasterised in, which means the same QUANTISED age: a stroke
    // drawn from the exact age and a core drawn from the bucket would be two slightly different
    // colours on one star, and the seam is visible on a glyph sitting against its own glow.
    val colour = skyColor(
        SkyGlyph.starTint(kind, id, SkyAge.bucketAgeYears(SkyAge.ageBucket(ageYears)), moodLevel),
    )
    // Right in, the point of light becomes a sun, and the kind marks step aside for it.
    if (sun > 0f) drawSun(centre, sunPx, colour, ink * sun)
    val marks = ink * (1f - sun)
    if (!glyphs || marks <= 0f) {
        if (selected) drawSelection(centre, ringed)
        return
    }
    val stroke = if (options.highContrast) 1.6.dp.toPx() else 1.0.dp.toPx()

    val ringRadius = SkyGlyph.ringRadiusDp(kind)
    if (ringRadius > 0f) {
        drawCircle(
            color = colour,
            radius = ringRadius.dp.toPx(),
            center = centre,
            style = Stroke(width = stroke),
            alpha = 0.85f * marks,
        )
    }

    val rays = SkyGlyph.rayCount(kind)
    if (rays > 0) {
        // Length follows authorship, not value: a life event is louder because the person decided
        // it mattered and typed it (§3.4). The core does not grow, so equal presence still holds.
        val length = SkyGlyph.rayLengthDp(kind).dp.toPx()
        drawLine(
            color = colour,
            start = Offset(centre.x, centre.y - length),
            end = Offset(centre.x, centre.y + length),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
            alpha = marks,
        )
        drawLine(
            color = colour,
            start = Offset(centre.x - length, centre.y),
            end = Offset(centre.x + length, centre.y),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
            alpha = marks,
        )
    }

    val underline = SkyGlyph.underlineWidthDp(kind)
    if (underline > 0f) {
        val half = underline.dp.toPx() / 2f
        val below = centre.y + coreRadius + 2.2.dp.toPx()
        drawLine(
            color = colour,
            start = Offset(centre.x - half, below),
            end = Offset(centre.x + half, below),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
            alpha = marks,
        )
    }

    // The stub, always drawn alone — see this file's header for why the thread between two steps is
    // not built rather than approximated.
    val stub = SkyGlyph.threadStubDp(kind)
    if (stub > 0f && SkyDetail.drawsThreads(detail)) {
        val from = centre.x - coreRadius - stub.dp.toPx()
        drawLine(
            color = SkyNightFaint,
            start = Offset(from, centre.y),
            end = Offset(centre.x - coreRadius, centre.y),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
            alpha = 1f - sun,
        )
    }

    if (selected) drawSelection(centre, ringed)
}

/**
 * The prism: a red fringe and a blue one, pulled apart either side of the star for a quarter of a
 * second and gone again, with one extra pass of the star itself between them.
 *
 * `docs/SKY.md` §3.6: *"for 280 ms, a red and a blue fringe are added either side of the star,
 * like a prism. The star's own tint never changes."* Added and not blended,
 * which is what makes that true — an additive fringe leaves the star underneath exactly the colour
 * it was.
 *
 * **Every alpha here is multiplied by [fade].** Without that an old star's glint would be drawn at
 * a new star's brightness and the oldest, faintest stars would be the ones flashing hardest — the
 * fade would be undone by the decoration laid over it. [fade] and not the twinkled brightness,
 * because the glint is its own event and should not also be modulated by the breathe it happens to
 * land in.
 *
 * [SkyTwinkle.glintEnvelopeAt] returns zero under the motion switch and zero in the quiet sky, so
 * there is nothing to check here: a brief coloured flash over a small mark is exactly what someone
 * who turned that switch on is trying to get away from.
 */
private fun DrawScope.drawGlint(
    sprites: SkySprites,
    sprite: SkySprites.Sprite,
    kind: SkyKind,
    id: Long,
    centre: Offset,
    options: SkyOptions,
    elapsedMillis: Long,
    fade: Float,
) {
    val envelope = SkyTwinkle.glintEnvelopeAt(kind, id, elapsedMillis, options)
    if (envelope <= 0f) return

    val landmark = kind == SkyKind.LIFE_EVENT
    val offset = SkyTwinkle.glintFringeOffsetDp(kind, envelope).dp.toPx()
    val fringeAlpha = (SkyTwinkle.glintFringeAlpha(kind, envelope) * fade).coerceIn(0f, 1f)
    val flareAlpha = (SkyTwinkle.glintFlareAlpha(kind, envelope) * fade).coerceIn(0f, 1f)

    for (red in booleanArrayOf(true, false)) {
        val half = sprites.fringe(red, landmark)
        val dx = if (red) -offset else offset
        drawImage(
            image = half.image,
            topLeft = Offset(centre.x + dx - half.halfWidth, centre.y - half.halfHeight),
            alpha = fringeAlpha,
            blendMode = BlendMode.Plus,
        )
    }

    // One more pass of the star itself, which is what makes a glint read as light rather than as
    // two coloured smudges arriving beside it.
    drawImage(
        image = sprite.image,
        topLeft = Offset(centre.x - sprite.halfWidth, centre.y - sprite.halfHeight),
        alpha = flareAlpha,
        blendMode = BlendMode.Plus,
    )
}

/**
 * The mark on the star whose detail is open.
 *
 * A ring at a fixed offset from the core, in the sky's own ink rather than in anything the star's
 * own colour says, so that "this is the one you tapped" is never mistaken for something about the
 * record. It is drawn *around* the star and never by growing it — a star that swells when selected
 * is a star whose size means something [SkyGlyph] says it must not.
 */
private fun DrawScope.drawSelection(centre: Offset, radius: Float) {
    drawCircle(
        color = SkyNightInk,
        radius = radius + 7.dp.toPx(),
        center = centre,
        style = Stroke(width = 1.2.dp.toPx()),
        alpha = 0.9f,
    )
}

/**
 * Milliseconds since this surface started animating, or a fixed `0` when it is not.
 *
 * The only clock on the Sky. [SkyTwinkle] is pure and takes an elapsed count, so this is the whole
 * of what the renderer contributes to the twinkle — which is what makes every motion-safety rule in
 * `docs/SKY.md` §7.4 a property of a file a plain-JVM test can execute.
 *
 * Three things about the shape, each of which is a battery decision:
 *
 *  - **`enabled = false` means no coroutine at all.** Not a loop that reads a flag and skips the
 *    work: a running frame loop wakes the app every 16 ms whatever it does with the wakeup.
 *  - **`repeatOnLifecycle(RESUMED)`** cancels it when the app is backgrounded or another screen
 *    covers this one, and starts it again on the way back. `withFrameMillis` alone would mostly do
 *    this — the frame clock stops when the window stops drawing — but "mostly" is not a guarantee
 *    worth resting a phone's battery on, and a surface people leave open is exactly where it would
 *    be noticed.
 *  - **The state is a `Long`, read in a draw scope.** `mutableLongStateOf` does not box, so sixty
 *    writes a second allocate nothing, and the read happens inside the `Canvas` lambda so a frame
 *    invalidates the draw rather than recomposing anything.
 *
 * The count restarts from zero each time the loop does. Nothing in [SkyTwinkle] depends on the
 * absolute value — every rhythm is a phase from the star's own hash — so a star resumes its own
 * beat at a different point in it and no two stars fall into step by doing so.
 */
@Composable
private fun rememberSkyElapsedMillis(enabled: Boolean): State<Long> {
    val elapsed = remember { mutableLongStateOf(0L) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(enabled, lifecycleOwner) {
        if (!enabled) {
            // Exactly zero, so a still sky is the ordinary sky with time removed rather than the
            // ordinary sky frozen at whatever instant the switch was thrown.
            elapsed.longValue = 0L
            return@LaunchedEffect
        }
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val start = withFrameMillis { it }
            while (true) {
                withFrameMillis { frame -> elapsed.longValue = frame - start }
            }
        }
    }
    return elapsed
}
