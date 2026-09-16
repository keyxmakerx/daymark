package com.daymark.app.ui.sky

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.daymark.app.sky.SkyAge
import com.daymark.app.sky.SkyDetail
import com.daymark.app.sky.SkyField
import com.daymark.app.sky.SkyGlyph
import com.daymark.app.sky.SkyKind
import com.daymark.app.sky.SkyLayout
import com.daymark.app.sky.SkyOptions
import com.daymark.app.sky.SkyPalette
import com.daymark.app.sky.SkyTwinkle
import kotlin.math.ceil
import kotlin.math.floor

/**
 * The Sky, drawn.
 *
 * This file decides nothing. Every number in it is read from `sky/`: the core's radius and alpha
 * from [SkyGlyph], the halo from [SkyGlyph.haloRadiusDp] and [SkyGlyph.haloPeakAlpha], the ground
 * and ink from [SkyPalette], the zoom thresholds from [SkyDetail], the star positions from
 * [SkyLayout], and the field from [SkyField]. What is here is draw calls, a viewport transform, and
 * two gesture detectors — nothing a unit test would have wanted to reach, because everything a unit
 * test wants to reach is in [SkyPresentation] or in `sky/`, both of which run on a plain JVM.
 *
 * That is not a stylistic preference. There is no Android SDK in this environment, so a rule that
 * lives in this file is a rule nobody can execute until CI; a rule that lives next door is checked
 * before it ships.
 *
 * ## Two canvases, not one
 *
 * The decorative field and the person's stars are drawn on separate layers so the field can carry
 * `clearAndSetSemantics {}` — §7.3 requires it to be invisible to assistive technology, because it
 * is sky and not data. One canvas with both on it could not say that about half of itself.
 *
 * ## What is not drawn, and why
 *
 * **Threads between project steps.** §3.3 links a step to the previous step of the same project
 * with a hairline. A thread is a property of a *pair*, and [SkyLayout] carries no project identity
 * — a star is `(kind, id, day, mood)`. Widening it would put a grouping key on the Sky, which is a
 * field the surface could later draw something else from. So each step gets
 * [SkyGlyph.threadStubDp], which exists for exactly this case: the link is part of the glyph, drawn
 * alone, so "a step in something" is readable from one star. The full thread stays unbuilt and
 * recorded rather than faked.
 *
 * **Per-row month labels in a gutter.** Text on a canvas needs the platform text stack, and text
 * that must scale to 200% (§7.1) needs it more. Instead the surface reports the month at the top of
 * the viewport and the screen draws one ordinary, scalable [androidx.compose.material3.Text] label
 * above it. Coarser than a gutter; it is also the version that survives a large font setting.
 *
 * ## Twinkle, which now ships, and the frame loop that carries it
 *
 * `docs/SKY.md` §7.4 said a twinkle was a vestibular risk on a full-screen surface and should be
 * off by default if it shipped at all. `docs/PLAN_2026-09-SKY-PEOPLE-TIMING.md` §1 revises that: it
 * ships, on, behind the motion switch, which already follows the platform's reduced-motion setting.
 * Every number in it is [SkyTwinkle]'s and every one of them is pure, so what is here is a clock.
 *
 * **The clock is the one performance mistake this screen can actually make**, so it is written to
 * be impossible to leave running:
 *
 *  - it exists only while [SkyOptions.motionEnabled] is true — off, there is no loop at all, not a
 *    loop that computes nothing;
 *  - it is wrapped in `repeatOnLifecycle(RESUMED)`, so it is cancelled when the app is backgrounded
 *    or another screen covers this one;
 *  - it lives inside this composable, so switching to the list (§7.5) disposes it;
 *  - it drives a draw and never a recomposition — the elapsed time is read inside the `Canvas`
 *    lambda, so a frame redraws the canvas and rebuilds no layout;
 *  - and the draw itself skips every star the viewport cannot see, so nothing off screen is
 *    animated because nothing off screen is visited. Note this is a bounds check per star and no
 *    longer an index range: when the sky was ruled into months, x was monotonic in time and the
 *    arrays were in time order, so everything on screen was one contiguous slice. A scattered field
 *    has no ordering that corresponds to anything on screen (`docs/SKY.md` §3.1), and paying a
 *    comparison per star is the accepted price of a surface with no empty regions in it.
 */
@Composable
fun SkySurface(
    layout: SkyLayout,
    fieldSeed: Long,
    options: SkyOptions,
    /**
     * Today, as `LocalDate.toEpochDay()`, so a star's age can be worked out.
     *
     * The clock read happens in the renderer and is passed down. `sky/` is import-free and has no
     * clock by design — a pure layer that asked the system what day it is would stop being
     * testable, and `SkyAge`'s whole surface takes an age rather than a date for that reason.
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
    onStarTapped: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var zoom by remember { mutableFloatStateOf(SkyPresentation.DEFAULT_ZOOM) }
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }

    // Regenerated tiles are the field's whole cost model: cost follows screen area, never how much
    // history the person has. The cache keeps a fling from rebuilding the same 144 specks every
    // frame, and is dropped wholesale rather than evicted one by one — it is pure derived data, so
    // throwing all of it away costs one frame's regeneration and no correctness.
    val tiles = remember(fieldSeed) { HashMap<Long, SkyField.Tile>() }

    // Sprites are rasterised at the device's real pixel density and stamped 1:1, so they are keyed
    // on it: a density change (a fold, a display swap) throws the whole cache away rather than
    // resampling stars that were drawn for a different screen.
    val pxPerDp = LocalDensity.current.density
    val sprites = remember(pxPerDp) { SkySprites(pxPerDp) }

    // The frame loop. See this file's header for the five things that stop it running when nobody
    // is looking at it.
    val elapsedMillis by rememberSkyElapsedMillis(options.motionEnabled)

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { viewport = it },
    ) {
        val width = viewport.width.toFloat()
        val height = viewport.height.toFloat()

        /** Pan clamped against the geometry the current zoom implies. Idempotent, so safe to reapply. */
        fun clampedPan(atZoom: Float, rawX: Float, rawY: Float): Offset {
            if (width <= 0f || height <= 0f) return Offset(rawX, rawY)
            return Offset(
                SkyPresentation.clampPan(rawX, SkyPresentation.contentWidthPx(width, atZoom), width),
                SkyPresentation.clampPan(rawY, SkyPresentation.contentHeightPx(height, atZoom), height),
            )
        }

        // `viewport` is a key: the detectors capture the viewport's size, so a block started while
        // the size was still zero would keep clamping pan against a zero-sized sky forever.
        val gestures = Modifier
            .pointerInput(layout, fieldSeed, viewport) {
                detectTransformGestures { _, pan, gestureZoom, _ ->
                    // Zoom in MULTIPLIES now. It used to divide, because the number being held was
                    // how many months were on screen and zooming in meant fewer of them. The field
                    // is not measured in months any more, so the number is a plain scale factor.
                    // Pinch and drag stay one gesture because they are one movement.
                    val next = SkyPresentation.clampZoom(zoom * gestureZoom)
                    val panned = clampedPan(next, panX + pan.x, panY + pan.y)
                    zoom = next
                    panX = panned.x
                    panY = panned.y
                }
            }
            .pointerInput(layout, fieldSeed, viewport) {
                detectTapGestures(
                    onTap = { offset ->
                        val pan = clampedPan(zoom, panX, panY)
                        val hit = SkyPresentation.nearestStar(
                            layout = layout,
                            // Every star, because no ordering of the arrays corresponds to any
                            // ordering on screen once the field is scattered.
                            indices = 0 until layout.starCount,
                            contentWidthPx = SkyPresentation.contentWidthPx(width, zoom),
                            contentHeightPx = SkyPresentation.contentHeightPx(height, zoom),
                            panXPx = pan.x,
                            panYPx = pan.y,
                            tapXPx = offset.x,
                            tapYPx = offset.y,
                            // The platform minimum, applied to the target and not to the drawn
                            // size: a star drawn at 1.9 dp is still a 48 dp target (§7.1).
                            maxDistancePx = SkyGlyph.TOUCH_TARGET_DP.dp.toPx() / 2f,
                        )
                        onStarTapped(hit)
                    },
                )
            }

        if (options.fieldEnabled) {
            // Decorative, and declared so. Absent from assistive tech and absent from the text
            // equivalent — it is sky, not data (§7.3, M1).
            Canvas(modifier = Modifier.fillMaxSize().clearAndSetSemantics {}) {
                drawField(
                    tiles = tiles,
                    seed = fieldSeed,
                    panX = panX,
                    panY = panY,
                    motionEnabled = options.motionEnabled,
                )
            }
        }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .then(gestures)
                .semantics { this.contentDescription = description },
        ) {
            val w = size.width
            val h = size.height
            if (layout.starCount == 0 || w <= 0f || h <= 0f) return@Canvas
            val detail = SkyPresentation.detailFor(zoom)
            val contentW = SkyPresentation.contentWidthPx(w, zoom)
            val contentH = SkyPresentation.contentHeightPx(h, zoom)
            val pan = clampedPan(zoom, panX, panY)
            // Enough margin that a glyph whose centre has left the screen still draws its rays.
            val margin = 16.dp.toPx()

            // Array order is time order, so newer stars still land on top of older ones.
            for (i in 0 until layout.starCount) {
                val cx = SkyPresentation.screenX(layout, i, contentW, pan.x)
                val cy = SkyPresentation.screenY(layout, i, contentH, pan.y)
                if (!SkyPresentation.isOnScreen(cx, cy, w, h, margin)) continue
                drawStar(
                    sprites = sprites,
                    kind = layout.kindAt(i),
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
                )
            }
        }
    }
}

/** Packed `0xRRGGBB` from [SkyPalette] as an opaque Compose colour. */
internal fun skyColor(rgb: Int): Color = Color(0xFF000000.toInt() or rgb)

/** The Sky's night ground, from the one place that measures it. Never a fourth copy of the hex. */
internal val SkyNightBg: Color = skyColor(SkyPalette.NIGHT_BG)
internal val SkyNightInk: Color = skyColor(SkyPalette.NIGHT_INK)
internal val SkyNightFaint: Color = skyColor(SkyPalette.NIGHT_FAINT)

/** How much of the screen one field tile covers. Screen space, so density never varies with zoom. */
private val FIELD_TILE_DP = 96.dp

/** A speck. Smaller than [SkyGlyph.CORE_RADIUS_DP], so the field can never be counted as data. */
private val FIELD_SPECK_RADIUS_DP = 0.9.dp

/**
 * Cleared rather than evicted when it gets big; see the note at the call site. 256 tiles is far more
 * than any viewport needs, so this is a leak stop and not a working limit.
 */
private const val MAX_CACHED_TILES = 256

/**
 * The uniform decorative field.
 *
 * The generator takes a seed and a tile address and **nothing else** — no records, no dates, no
 * counts — which is what makes "the field cannot see data" a fact about the signature rather than a
 * claim to be checked by reading. Density is [SkyField.CELLS_PER_TILE]² per tile everywhere, so a
 * quiet month is the same sky as a busy one, and nobody can read their own lapses off the
 * background.
 *
 * Tiles are addressed in **screen** space rather than content space, so the field keeps one density
 * at every zoom. The alternative — tiles that scale with the content — makes the field thin out as
 * a person zooms into a month, and a thinning background on this surface reads as absence, which is
 * the one reading the field exists to prevent.
 */
private fun DrawScope.drawField(
    tiles: HashMap<Long, SkyField.Tile>,
    seed: Long,
    panX: Float,
    panY: Float,
    motionEnabled: Boolean,
) {
    val tilePx = FIELD_TILE_DP.toPx()
    if (tilePx <= 0f) return
    // Parallax is decoration and carries no meaning, so removing it removes nothing: at 1.0 the
    // field travels with the sky and nothing moves relative to anything else. That is what makes
    // reduced motion a rendering switch here and not a second design.
    val drift = if (motionEnabled) 0.85f else 1f
    val originX = panX * drift
    val originY = panY * drift
    val radius = FIELD_SPECK_RADIUS_DP.toPx()

    val firstX = floor((-originX) / tilePx).toInt()
    val lastX = ceil((size.width - originX) / tilePx).toInt()
    val firstY = floor((-originY) / tilePx).toInt()
    val lastY = ceil((size.height - originY) / tilePx).toInt()

    if (tiles.size > MAX_CACHED_TILES) tiles.clear()

    for (ty in firstY..lastY) {
        for (tx in firstX..lastX) {
            val key = (tx.toLong() shl 32) xor (ty.toLong() and 0xFFFFFFFFL)
            val tile = tiles.getOrPut(key) { SkyField.tile(seed, tx, ty) }
            for (i in 0 until tile.size) {
                drawCircle(
                    color = SkyNightInk,
                    radius = radius,
                    center = Offset(
                        (tx + tile.x[i]) * tilePx + originX,
                        (ty + tile.y[i]) * tilePx + originY,
                    ),
                    alpha = tile.alpha[i],
                )
            }
        }
    }
}

/**
 * One star: a point, then a glow.
 *
 * `docs/PLAN_2026-09-SKY-PEOPLE-TIMING.md` §1, *"Fidelity: a point, then a glow, never a blur
 * alone"* — a hard-edged near-white core, a tight bright inner glow against it, and a soft faint
 * outer glow spread by the mood. Those three live in the sprite ([SkySprites]); what happens here
 * is where it goes, how bright it is at this instant, and the prism flash on top.
 *
 * **Everything that decides how a star looks is asked for, never computed here.** Colour is
 * [SkyGlyph.starTint], which is age and the star's own temperature. Brightness is
 * [SkyGlyph.starBrightness], which is age, multiplied by [SkyTwinkle.alphaAt], which is the star's
 * own rhythm. Halo geometry is [SkyGlyph.haloRadiusDp] and [SkyGlyph.haloPeakAlpha]; the core's
 * scale is [SkyGlyph.coreScale]. Every one of those takes a mood level and all but the halo ignore
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
 * 2% breathe, which would need a resample: §1 asks for the breathe to be *"brightness only"*, and
 * a 2% resample of a sprite this small costs more in softness than the swell is worth.
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
) {
    // How brightly this star burns: its age, and then its own beat. Both are multipliers on the
    // sprite's own alphas, so the twinkle is a proportion of whatever the star already was — an old
    // star's breathe is as faint as the old star, and no star can be twinkled up past a younger one.
    val fade = SkyGlyph.starBrightness(kind, ageYears, moodLevel)
    val brightness = fade * SkyTwinkle.alphaAt(kind, id, elapsedMillis, options)

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

    // Kind is carried by form, and form only resolves once the person has leaned in to one day.
    // §1: "No marks for kind at ordinary zoom. A journal page, a step, a goal reached and a life
    // event are all just stars until the person leans in to a single day." A landmark still looks
    // different at every zoom, and that is its light and not a kind mark — see SkyDetail.drawsGlyphs.
    if (!SkyDetail.drawsGlyphs(detail)) {
        if (selected) drawSelection(centre, coreRadius)
        return
    }

    // The same tint the sprite was rasterised in, which means the same QUANTISED age: a stroke
    // drawn from the exact age and a core drawn from the bucket would be two slightly different
    // colours on one star, and the seam is visible on a glyph sitting against its own glow.
    val colour = skyColor(
        SkyGlyph.starTint(kind, id, SkyAge.bucketAgeYears(SkyAge.ageBucket(ageYears)), moodLevel),
    )
    val stroke = if (options.highContrast) 1.6.dp.toPx() else 1.0.dp.toPx()

    val ringRadius = SkyGlyph.ringRadiusDp(kind)
    if (ringRadius > 0f) {
        drawCircle(
            color = colour,
            radius = ringRadius.dp.toPx(),
            center = centre,
            style = Stroke(width = stroke),
            alpha = 0.85f * fade,
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
            alpha = fade,
        )
        drawLine(
            color = colour,
            start = Offset(centre.x - length, centre.y),
            end = Offset(centre.x + length, centre.y),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
            alpha = fade,
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
            alpha = fade,
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
        )
    }

    if (selected) drawSelection(centre, coreRadius)
}

/**
 * The prism: a red fringe and a blue one, pulled apart either side of the star for a quarter of a
 * second and gone again, with one extra pass of the star itself between them.
 *
 * §1: *"a quarter-second prism glint, a red and a blue fringe added on top of the star and gone
 * again... The star's own tint never changes; the glint passes over it."* Added and not blended,
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
private fun DrawScope.drawSelection(centre: Offset, coreRadius: Float) {
    drawCircle(
        color = SkyNightInk,
        radius = coreRadius + 7.dp.toPx(),
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
 * §1 a property of a file a plain-JVM test can execute.
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
