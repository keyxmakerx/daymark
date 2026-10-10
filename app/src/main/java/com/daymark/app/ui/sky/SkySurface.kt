package com.daymark.app.ui.sky

import android.graphics.Bitmap
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.daymark.app.sky.Sky
import com.daymark.app.sky.SkyAge
import com.daymark.app.sky.SkyColours
import com.daymark.app.sky.SkyConstellation
import com.daymark.app.sky.SkyDetail
import com.daymark.app.sky.SkyGlyph
import com.daymark.app.sky.SkyHoles
import com.daymark.app.sky.SkyKind
import com.daymark.app.sky.SkyLayout
import com.daymark.app.sky.SkyNebula
import com.daymark.app.sky.SkyOpening
import com.daymark.app.sky.SkyOptions
import com.daymark.app.sky.SkyPalette
import com.daymark.app.sky.SkyRandom
import com.daymark.app.sky.SkyTrackers
import com.daymark.app.sky.SkyTwinkle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
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
 * Every star is a memory. Behind them is the sky's own space: its deepest tone and three soft
 * glows of colour anchored to the sky, and gas around weeks with a lot of writing
 * ([SkyNebula]), all in the sky's colours ([SkyColours]), which are blind to data. There are no
 * background stars and no field. Stars are drawn where they are today, drift included. Far out each
 * is a point of light; leaning in, kind marks resolve ([SkyDetail.drawsGlyphs]); right in, each one
 * is drawn as a sun of its own ([SkyPresentation.sunRadius]). A life event the person marked as
 * hard has a supernova's shell round it, dim, marking that day and nothing more. Trackers the
 * person shows are objects of their own beside the memories ([SkyTrackers]). Constellations the
 * person drew are lines between their stars and a name beside the topmost one, the only words on
 * the canvas, and every one of them the person's.
 *
 * A memory put away is not drawn and cannot be tapped; while a black or white hole plays
 * ([SkyHoleEvent]), the memories it moves are drawn by it instead. The quiet sky draws the plain
 * night ground and no gas, glow or shell, the same rule that takes the stars' halos away.
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
    /** The sky's colours ([SkyColours.of]), and the [seed] that places its glows. */
    look: SkyColours.Look,
    seed: Long,
    nebulae: List<SkyNebula.Nebula>,
    /** The trackers the person shows, placed beside today's stars ([SkyTrackers.place]). */
    trackers: List<SkyTrackers.Placed>,
    /** A black or white hole as it plays, or null. */
    event: SkyHoleEvent?,
    onStarTapped: (Int) -> Unit,
    onTrackerTapped: (Int) -> Unit,
    onNebulaTapped: (Int) -> Unit,
    onSkipOpening: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val xs = positions[0]
    val ys = positions[1]
    val bounds = remember(positions, layout) { SkyPresentation.boundsOf(xs, ys, layout.height) }
    val glows = remember(seed, layout.height) { SkyColours.glows(seed, layout.height) }
    val extents = remember(nebulae, positions) { nebulae.map { SkyNebula.extent(it, xs, ys) } }
    // Each nebula's gas, folded once off the main thread: years of busy weeks are dozens of them.
    val gas by produceState(initialValue = emptyList<ImageBitmap>(), nebulae, look, seed) {
        value = withContext(Dispatchers.Default) {
            nebulae.map { nebula ->
                val pixels = SkyNebula.texture(GAS_PX, look.nebulae[nebula.look], SkyRandom.mix(seed, nebula.firstDay))
                Bitmap.createBitmap(pixels, GAS_PX, GAS_PX, Bitmap.Config.ARGB_8888).asImageBitmap()
            }
        }
    }
    // What a hole is moving, as stars of today's layout, and every star the ordinary pass skips:
    // those put away, and those a hole is drawing.
    val moving = remember(layout, event) { event?.indicesIn(layout) ?: IntArray(0) }
    val hidden = remember(layout, moving) {
        BooleanArray(layout.starCount) { !layout.isShown(it) }.also { h -> for (i in moving) if (i >= 0) h[i] = true }
    }
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
        constellations.map { SkyConstellation.lineAlphas(it.points, it.inSight, layout, todayEpochDay) }
    }
    val measurer = rememberTextMeasurer()
    val names = remember(constellations, measurer) {
        val style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium)
        constellations.map { measurer.measure(AnnotatedString(it.name), style) }
    }

    val skipping by rememberUpdatedState(opening.playing)
    val tapped by rememberUpdatedState(onStarTapped)
    val trackerTapped by rememberUpdatedState(onTrackerTapped)
    val nebulaTapped by rememberUpdatedState(onNebulaTapped)
    val skip by rememberUpdatedState(onSkipOpening)
    val tappable by rememberUpdatedState(hidden)
    val objects by rememberUpdatedState(trackers)
    val gases by rememberUpdatedState(extents)

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
                        val scale = camera.scalePx
                        val skyX = SkyPresentation.fromScreen(offset.x, camera.bounds.left, scale, camera.panX())
                        val skyY = SkyPresentation.fromScreen(offset.y, camera.bounds.top, scale, camera.panY())
                        // The platform minimum, applied to the target and not to the drawn size: a
                        // star drawn a few dp across is still a 48 dp target (§7.1), and so is a tracker.
                        val reachPx = SkyGlyph.TOUCH_TARGET_DP.dp.toPx() / 2f
                        val reach = reachPx / scale
                        val tracker = SkyPresentation.objectAt(
                            FloatArray(objects.size) { objects[it].x },
                            FloatArray(objects.size) { objects[it].y },
                            FloatArray(objects.size) { max(objects[it].radius, reach) },
                            skyX,
                            skyY,
                        )
                        val star = if (tracker >= 0) {
                            -1
                        } else {
                            SkyPresentation.nearestStar(
                                xs = xs,
                                ys = ys,
                                bounds = camera.bounds,
                                scalePx = scale,
                                panXPx = camera.panX(),
                                panYPx = camera.panY(),
                                tapXPx = offset.x,
                                tapYPx = offset.y,
                                maxDistancePx = reachPx,
                                hidden = tappable,
                            )
                        }
                        // A star wins over the gas round it; the gas is reached between stars.
                        val nebula = if (tracker >= 0 || star >= 0) {
                            -1
                        } else {
                            SkyPresentation.objectAt(
                                FloatArray(gases.size) { gases[it].x },
                                FloatArray(gases.size) { gases[it].y },
                                FloatArray(gases.size) { gases[it].radius },
                                skyX,
                                skyY,
                            )
                        }
                        when {
                            tracker >= 0 -> trackerTapped(tracker)
                            nebula >= 0 -> nebulaTapped(nebula)
                            else -> tapped(star)
                        }
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
            // Everything that is not a star comes in with the opening, behind the stars.
            val backdrop = smooth(0.1f, 0.9f, reveal)
            val seconds = elapsedMillis / 1000f

            if (!options.highContrast) {
                drawSpace(look, glows, origin, scale, pan)
                val gasAlpha = SkyPresentation.nebulaAlpha(across) * backdrop
                for ((n, extent) in extents.withIndex()) {
                    val image = gas.getOrNull(n) ?: continue
                    drawGas(image, extent, nebulae[n].turn, origin, scale, pan, gasAlpha)
                }
            }
            for (t in trackers) drawTracker(t, origin, scale, pan, seconds, backdrop)

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
                if (hidden[i]) continue
                val kind = layout.kindAt(i)
                val sun = SkyPresentation.sunRadius(kind, scale)
                val cx = SkyPresentation.toScreen(xs[i], origin.x, scale, pan.x)
                val cy = SkyPresentation.toScreen(ys[i], origin.y, scale, pan.y)
                if (!SkyPresentation.isOnScreen(cx, cy, w, h, margin + sun * CORONA)) continue
                val arrival =
                    if (i == born) SkyOpening.bornBrightness(birth) else SkyOpening.brightness(reveal, moments[i])
                if (arrival <= 0f) continue
                if (layout.isSupernova(i)) {
                    drawSupernova(Offset(cx, cy), SUPERNOVA_DP.dp.toPx(), look, arrival, seconds, options.highContrast)
                }
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
                    zoom = across,
                )
            }

            val strength = SkyPresentation.constellationAlpha(reveal, across)
            if (strength > 0f && constellations.isNotEmpty()) {
                drawConstellations(constellations, lineStrengths, names, xs, ys, origin, scale, pan, strength)
            }
            if (picked.isNotEmpty()) drawPicked(picked, xs, ys, origin, scale, pan)

            val e = event
            if (e != null) {
                val hole = Offset(
                    SkyPresentation.toScreen(e.x, origin.x, scale, pan.x),
                    SkyPresentation.toScreen(e.y, origin.y, scale, pan.y),
                )
                val formed = SkyHoles.holeAt(e.seconds, moving.size)
                if (e.inward) {
                    drawBlackHole(hole, HOLE_DP.dp.toPx(), formed, look)
                } else {
                    drawWhiteHole(hole, HOLE_DP.dp.toPx(), formed, look)
                }
                for ((order, i) in moving.withIndex()) {
                    if (i !in 0 until layout.starCount) continue
                    val p = SkyHoles.travelAt(e.seconds, order)
                    val light = if (e.inward) SkyHoles.inwardLight(p) else SkyHoles.outwardLight(p)
                    if (light <= 0f) continue
                    val share = if (e.inward) SkyHoles.inwardRadius(p) else SkyHoles.outwardShare(p)
                    val turn = if (e.inward) SkyHoles.inwardTurn(p) else SkyHoles.outwardTurn(p)
                    val dx = SkyPresentation.toScreen(xs[i], origin.x, scale, pan.x) - hole.x
                    val dy = SkyPresentation.toScreen(ys[i], origin.y, scale, pan.y) - hole.y
                    val c = cos(turn)
                    val s = sin(turn)
                    drawStar(
                        sprites = sprites,
                        kind = layout.kindAt(i),
                        id = SkyTwinkle.identityIdAt(layout, i),
                        ageYears = SkyAge.ageYears(layout.epochDay[i], todayEpochDay),
                        moodLevel = layout.moodLevel[i],
                        centre = Offset(hole.x + (dx * c - dy * s) * share, hole.y + (dx * s + dy * c) * share),
                        detail = detail,
                        options = options,
                        elapsedMillis = elapsedMillis,
                        selected = false,
                        arrival = light,
                        sunPx = 0f,
                        zoom = across,
                    )
                }
            }
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

    /** From the start again, for a sky grown anew: playing, or with motion off, already over. */
    fun restart(play: Boolean) {
        playing = play
        reveal = if (play) 0f else 1f
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
                zoom = 1f,
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
                zoom = 1f,
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

/**
 * A black or white hole as it plays (`DECISIONS.md` §D11): where it is, in the sky's units, which
 * memories it moves, in the order they move, and how far into it the clock is. The memories are
 * named by kind and record id rather than by index, so the layout that arrives as they are put
 * away or brought back finds the same stars. The screen runs its clock; the surface draws it.
 */
@Stable
internal class SkyHoleEvent(
    /** A black hole taking memories in when true; a white hole sending them home when false. */
    val inward: Boolean,
    val x: Float,
    val y: Float,
    val memories: List<Pair<SkyKind, Long>>,
) {
    /** Seconds since it began, written a frame at a time while it plays. */
    var seconds by mutableFloatStateOf(0f)

    /** How long it lasts ([SkyHoles.duration]). */
    val duration: Float get() = SkyHoles.duration(memories.size)

    /** Each memory as a star of [layout], -1 for one that is gone. */
    fun indicesIn(layout: SkyLayout): IntArray = IntArray(memories.size) { layout.indexOf(memories[it].first, memories[it].second) }
}

/** How far out a sun's corona reaches, as a multiple of its radius. */
private const val CORONA = 2.4f

/** How many pixels across a nebula's gas is folded at, before it is stretched over its stars. */
private const val GAS_PX = 112

/** How far a supernova's shell reaches round its star, on the screen. */
private const val SUPERNOVA_DP = 16f

/** How big a black or white hole is, on the screen. */
private const val HOLE_DP = 11f

/** A tracker smaller than this on the screen is drawn as one soft point, not its grains. */
private const val TRACKER_GRAINS_FROM_DP = 6f

/**
 * The space behind the stars: the sky's deepest tone, then three soft glows of colour anchored to
 * the sky ([SkyColours.glows]). Every tone is near black, so the stars keep their contrast.
 */
private fun DrawScope.drawSpace(
    look: SkyColours.Look,
    glows: List<SkyColours.Glow>,
    origin: Offset,
    scale: Float,
    pan: Offset,
) {
    drawRect(skyColor(look.space[0]))
    for (g in glows) {
        val centre = Offset(
            SkyPresentation.toScreen(g.x, origin.x, scale, pan.x),
            SkyPresentation.toScreen(g.y, origin.y, scale, pan.y),
        )
        // The accent is the one tone that is not near black, so it is the faintest by far.
        drawGlow(centre, g.radius * scale, skyColor(look.space[g.tone]), if (g.tone == 3) 0.07f else 0.6f)
    }
}

/** A nebula's gas, stretched over its stars and turned its own way. Added, the way light is. */
private fun DrawScope.drawGas(
    image: ImageBitmap,
    extent: SkyNebula.Extent,
    turn: Float,
    origin: Offset,
    scale: Float,
    pan: Offset,
    alpha: Float,
) {
    if (alpha <= 0f) return
    val cx = SkyPresentation.toScreen(extent.x, origin.x, scale, pan.x)
    val cy = SkyPresentation.toScreen(extent.y, origin.y, scale, pan.y)
    val half = extent.radius * scale
    if (!SkyPresentation.isOnScreen(cx, cy, size.width, size.height, half)) return
    val side = (half * 2f).roundToInt().coerceAtLeast(1)
    rotate(degrees = turn * 180f / PI.toFloat(), pivot = Offset(cx, cy)) {
        drawImage(
            image = image,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(image.width, image.height),
            dstOffset = IntOffset((cx - half).roundToInt(), (cy - half).roundToInt()),
            dstSize = IntSize(side, side),
            alpha = alpha.coerceIn(0f, 1f),
            blendMode = BlendMode.Plus,
        )
    }
}

/**
 * A tracker's object: one grain for each time it was logged, spread by order, its total light the
 * same however many there are ([SkyTrackers.grainAlpha]). Too small on the screen to tell grains
 * apart, it is one soft point.
 */
private fun DrawScope.drawTracker(
    placed: SkyTrackers.Placed,
    origin: Offset,
    scale: Float,
    pan: Offset,
    seconds: Float,
    strength: Float,
) {
    if (strength <= 0f) return
    val centre = Offset(
        SkyPresentation.toScreen(placed.x, origin.x, scale, pan.x),
        SkyPresentation.toScreen(placed.y, origin.y, scale, pan.y),
    )
    val radius = placed.radius * scale
    if (!SkyPresentation.isOnScreen(centre.x, centre.y, size.width, size.height, radius + 8.dp.toPx())) return
    if (radius < TRACKER_GRAINS_FROM_DP.dp.toPx()) {
        drawGlow(centre, max(radius * 1.6f, 3.dp.toPx()), SkyNightInk, 0.45f * strength)
        return
    }
    val grain = FloatArray(2)
    val points = ArrayList<Offset>(SkyTrackers.grains(placed.logs))
    for (k in 0 until SkyTrackers.grains(placed.logs)) {
        SkyTrackers.grain(placed, k, seconds, grain)
        points.add(Offset(centre.x + grain[0] * scale, centre.y + grain[1] * scale))
    }
    drawPoints(
        points = points,
        pointMode = PointMode.Points,
        color = SkyNightInk,
        strokeWidth = 1.4.dp.toPx(),
        cap = StrokeCap.Round,
        alpha = (SkyTrackers.grainAlpha(placed.logs) * strength).coerceIn(0f, 1f),
        blendMode = BlendMode.Plus,
    )
}

/**
 * A supernova: a dim shell round a life event the person marked as hard, and a soft heart that
 * breathes slowly while motion is on. It marks the day and nothing more, so it is never the
 * brightest thing near it. In the quiet sky it is a thin ring, with no glow.
 */
private fun DrawScope.drawSupernova(
    centre: Offset,
    radius: Float,
    look: SkyColours.Look,
    arrival: Float,
    seconds: Float,
    quiet: Boolean,
) {
    val strength = arrival.coerceIn(0f, 1f)
    val shell = skyColor(look.supernova[0])
    if (quiet) {
        drawCircle(shell, radius = radius, center = centre, style = Stroke(width = 1.dp.toPx()), alpha = 0.8f * strength)
        return
    }
    drawCircle(
        brush = Brush.radialGradient(
            0f to shell.copy(alpha = 0f),
            0.62f to shell.copy(alpha = 0f),
            0.82f to shell.copy(alpha = 0.26f * strength),
            1f to shell.copy(alpha = 0f),
            center = centre,
            radius = radius,
        ),
        radius = radius,
        center = centre,
        blendMode = BlendMode.Plus,
    )
    val breath = 0.8f + 0.2f * sin(seconds * 2f * PI.toFloat() / SUPERNOVA_BREATH_SECONDS)
    drawGlow(centre, radius * 0.5f, skyColor(look.supernova[1]), 0.22f * breath * strength)
    drawGlow(centre, radius * 0.22f, skyColor(look.supernova[2]), 0.18f * breath * strength)
}

/** How slowly a supernova's heart breathes, in seconds. */
private const val SUPERNOVA_BREATH_SECONDS = 4.2f

/**
 * A black hole, while it forms, takes memories in and closes: its far disk, the shadow in front of
 * it, the ring of light round the shadow, and the near disk over all of it. At [strength] 0 there
 * is nothing at all, which is how it closes and is gone.
 */
private fun DrawScope.drawBlackHole(centre: Offset, radius: Float, strength: Float, look: SkyColours.Look) {
    if (strength <= 0f) return
    val hot = skyColor(look.disk[0])
    val warm = skyColor(look.disk[1])
    val r = radius * strength
    val diskSize = Size(r * 5.2f, r * 5.2f)
    val diskAt = Offset(centre.x - r * 2.6f, centre.y - r * 2.6f)
    fun disk(start: Float) {
        withTransform({
            rotate(degrees = DISK_TILT, pivot = centre)
            scale(scaleX = 1f, scaleY = DISK_OPEN, pivot = centre)
        }) {
            drawArc(warm, start, 180f, false, diskAt, diskSize, alpha = 0.35f * strength, style = Stroke(width = r * 0.9f), blendMode = BlendMode.Plus)
            drawArc(hot, start, 180f, false, diskAt, diskSize, alpha = 0.5f * strength, style = Stroke(width = r * 0.25f), blendMode = BlendMode.Plus)
        }
    }
    disk(180f)
    drawCircle(skyColor(SkyPalette.HOLE_SHADOW), radius = r, center = centre, alpha = strength)
    drawCircle(hot, radius = r * 1.18f, center = centre, style = Stroke(width = 1.2.dp.toPx()), alpha = 0.7f * strength)
    disk(0f)
}

/** How a black hole's disk is turned, in degrees, and how open it is seen, 0 edge-on to 1 face-on. */
private const val DISK_TILT = -16f
private const val DISK_OPEN = 0.3f

/** A white hole, while it sends memories home: soft light from one point, and a thin ring round it. */
private fun DrawScope.drawWhiteHole(centre: Offset, radius: Float, strength: Float, look: SkyColours.Look) {
    if (strength <= 0f) return
    val light = skyColor(look.whiteHole)
    drawGlow(centre, radius * 3f * strength, light, 0.35f * strength)
    drawGlow(centre, radius * strength, light, 0.8f * strength)
    drawCircle(light, radius = radius * 1.4f * strength, center = centre, style = Stroke(width = 1.dp.toPx()), alpha = 0.5f * strength)
}

/**
 * A star starts to be drawn as a sun once its disc would be this big, and is fully one by
 * [SUN_FULL_DP]. Bigger than the bead has grown by the zoom at which a sun reaches it, so a sun
 * never starts out as a smaller disc inside its own star (#460).
 */
private const val SUN_FROM_DP = 4f
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
            val a = c.inSight[i]
            val b = c.inSight[i + 1]
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
        for (s in c.inSight) {
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
 * `docs/SKY.md` §3.5, *"a point, then a glow"* — a crisp bead in the star's own colour, a thin rim
 * of light against it, and a soft faint outer glow spread by the mood. Those three live in the sprite
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
 * rather than snapping to whole pixels as the sky is panned. A bead that grows with the zoom is a
 * new sprite at the new size ([SkySprites.star]), never this one resampled.
 *
 * **Light above the sprite's own is a second stamp.** The top of a twinkle and a star flaring in
 * as it arrives both ask for more light than the sprite holds. Stamps add, so the remainder is
 * stamped again on top rather than clipped away.
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
    /** The camera's screen widths across, `1` for the whole sky: how far every bead has grown. */
    zoom: Float,
) {
    if (arrival <= 0f) return
    // How brightly this star burns: its age and its arrival, and then its own beat. All are
    // multipliers on the sprite's own alphas, so the twinkle is a proportion of whatever the star
    // already was — an old star's flicker is as faint as the old star.
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
        zoom = zoom,
    )
    val topLeft = Offset(centre.x - sprite.halfWidth, centre.y - sprite.halfHeight)
    drawImage(
        image = sprite.image,
        topLeft = topLeft,
        alpha = brightness.coerceIn(0f, 1f),
        blendMode = BlendMode.Plus,
    )
    if (brightness > 1f) {
        drawImage(
            image = sprite.image,
            topLeft = topLeft,
            alpha = (brightness - 1f).coerceAtMost(1f),
            blendMode = BlendMode.Plus,
        )
    }

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

    val coreRadius =
        (SkyGlyph.coreRadiusDp(kind, moodLevel) * SkyGlyph.coreScale(kind) * SkyGlyph.zoomScale(zoom)).dp.toPx()
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
 * because the glint is its own event and should not also be modulated by the flicker it happens to
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
