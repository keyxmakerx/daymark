package com.daymark.app.ui.sky

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.daymark.app.sky.Sky
import com.daymark.app.sky.SkyDetail
import com.daymark.app.sky.SkyField
import com.daymark.app.sky.SkyGlyph
import com.daymark.app.sky.SkyKind
import com.daymark.app.sky.SkyLayout
import com.daymark.app.sky.SkyOptions
import com.daymark.app.sky.SkyPalette
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
 * **Twinkle.** §7.4: a vestibular risk on a full-screen surface, off by default if it ships at all.
 * It does not ship. The only motion here is the field's parallax, which stops entirely under
 * [SkyOptions.motionEnabled] `= false`, and nothing is learned by watching either way.
 */
@Composable
fun SkySurface(
    layout: SkyLayout,
    fieldSeed: Long,
    options: SkyOptions,
    /** The person's mood ramp, already through [SkyPalette.equalisedRamp]. Levels 1..5. */
    equalisedRamp: IntArray,
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
    /** The month now at the top of the viewport, so the screen can label where the person is. */
    onTopMonthChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var visibleMonths by remember { mutableFloatStateOf(SkyPresentation.DEFAULT_VISIBLE_MONTHS) }
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }

    // Regenerated tiles are the field's whole cost model: cost follows screen area, never how much
    // history the person has. The cache keeps a fling from rebuilding the same 144 specks every
    // frame, and is dropped wholesale rather than evicted one by one — it is pure derived data, so
    // throwing all of it away costs one frame's regeneration and no correctness.
    val tiles = remember(fieldSeed) { HashMap<Long, SkyField.Tile>() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { viewport = it },
    ) {
        val width = viewport.width.toFloat()
        val height = viewport.height.toFloat()

        /** Pan clamped against the geometry the current zoom implies. Idempotent, so safe to reapply. */
        fun clampedPan(months: Float, rawX: Float, rawY: Float): Offset {
            if (width <= 0f || height <= 0f) return Offset(rawX, rawY)
            val rowHeight = SkyPresentation.rowHeightPx(height, months)
            val rowWidth = SkyPresentation.rowWidthPx(width, months)
            return Offset(
                SkyPresentation.clampPan(rawX, rowWidth, width),
                SkyPresentation.clampPan(rawY, SkyPresentation.contentHeightPx(layout, rowHeight), height),
            )
        }

        // `viewport` is a key: the detectors capture the viewport's size, so a block started while
        // the size was still zero would keep clamping pan against a zero-sized sky forever.
        val gestures = Modifier
            .pointerInput(layout, fieldSeed, viewport) {
                detectTransformGestures { _, pan, zoom, _ ->
                    // Zoom in = fewer months on screen, so the factor divides. Pinch and drag are
                    // one gesture because they are one movement: a person zooming into March does
                    // not first zoom and then find March again.
                    val months = SkyPresentation.clampVisibleMonths(visibleMonths / zoom)
                    val next = clampedPan(months, panX + pan.x, panY + pan.y)
                    visibleMonths = months
                    panX = next.x
                    panY = next.y
                    onTopMonthChange(topMonthOf(layout, height, months, next.y))
                }
            }
            .pointerInput(layout, fieldSeed, viewport) {
                detectTapGestures(
                    onTap = { offset ->
                        val rowHeight = SkyPresentation.rowHeightPx(height, visibleMonths)
                        val rowWidth = SkyPresentation.rowWidthPx(width, visibleMonths)
                        val pan = clampedPan(visibleMonths, panX, panY)
                        val rows = SkyPresentation.visibleRows(layout, rowHeight, pan.y, height)
                        val hit = if (rows == null) -1 else SkyPresentation.nearestStar(
                            layout = layout,
                            indices = Sky.rowRange(layout, rows.first, rows.last),
                            rowWidthPx = rowWidth,
                            rowHeightPx = rowHeight,
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
            val months = visibleMonths
            val detail = SkyPresentation.detailFor(months)
            val rowHeight = SkyPresentation.rowHeightPx(h, months)
            val rowWidth = SkyPresentation.rowWidthPx(w, months)
            val pan = clampedPan(months, panX, panY)
            val rows = SkyPresentation.visibleRows(layout, rowHeight, pan.y, h) ?: return@Canvas
            // A contiguous slice, not a scan: x is monotonic in time and the arrays are in time
            // order, so everything on screen is one index range.
            val indices = Sky.rowRange(layout, rows.first, rows.last)
            // Enough margin that a glyph whose centre has left the screen still draws its rays.
            val margin = 16.dp.toPx()

            for (i in indices) {
                val cx = SkyPresentation.screenX(layout, i, rowWidth, pan.x)
                if (cx < -margin || cx > w + margin) continue
                val cy = SkyPresentation.screenY(layout, i, rowHeight, pan.y)
                drawStar(
                    kind = layout.kindAt(i),
                    moodLevel = layout.moodLevel[i],
                    centre = Offset(cx, cy),
                    detail = detail,
                    options = options,
                    equalisedRamp = equalisedRamp,
                    selected = i == selectedStar,
                )
            }
        }
    }
}

/**
 * The month at the top of the viewport — where the person is, in dates.
 *
 * Dates are the only navigation target the Sky has (§4.2): there is no "your best month" to jump
 * to, because there is no ranking to build one from.
 */
private fun topMonthOf(layout: SkyLayout, heightPx: Float, months: Float, panYPx: Float): Int {
    if (layout.rowCount == 0 || heightPx <= 0f) return layout.firstEpochMonth
    val rowHeight = SkyPresentation.rowHeightPx(heightPx, months)
    val row = floor((-panYPx) / rowHeight).toInt().coerceIn(0, layout.rowCount - 1)
    return layout.firstEpochMonth + row
}

/** Packed `0xRRGGBB` from [SkyPalette] as an opaque Compose colour. */
private fun skyColor(rgb: Int): Color = Color(0xFF000000.toInt() or rgb)

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
 * One star.
 *
 * The core is [SkyGlyph.CORE_RADIUS_DP] at [SkyGlyph.CORE_ALPHA] for **every mood and every kind**,
 * and it is asked for by kind and mood so that the constancy is enforced at the signature rather
 * than assumed here. Mood moves the halo and nothing else: wider and softer at the hard end,
 * tighter and more concentrated at the good end, with total light held constant by construction.
 * So a hard day is neither dimmer nor smaller than a good one — which is the whole reason this
 * surface is buildable at all (§3.4).
 *
 * Nothing here varies with how many records the star covers. A folded star is drawn exactly like a
 * single one; a bigger mark for a busier day would rank days by output (§6.2).
 */
private fun DrawScope.drawStar(
    kind: SkyKind,
    moodLevel: Int,
    centre: Offset,
    detail: SkyDetail,
    options: SkyOptions,
    equalisedRamp: IntArray,
    selected: Boolean,
) {
    val colour = starColour(moodLevel, equalisedRamp)
    val coreRadius = SkyGlyph.coreRadiusDp(kind, moodLevel).dp.toPx()

    // High contrast drops halos outright (§7.1): a soft gradient around a small mark is the first
    // thing to disappear for someone with low vision, and leaving it in only fuzzes the edge of the
    // thing they are trying to find.
    if (!options.highContrast) {
        drawCircle(
            color = colour,
            radius = SkyGlyph.haloRadiusDp(moodLevel).dp.toPx(),
            center = centre,
            alpha = SkyGlyph.haloPeakAlpha(moodLevel),
        )
    }

    drawCircle(
        color = colour,
        radius = coreRadius,
        center = centre,
        alpha = SkyGlyph.coreAlpha(kind, moodLevel),
    )

    // Kind is carried by form, and form only resolves from SEASON inward. At DRIFT a star is a
    // point — which is honest, because at years-at-once a 4 dp ring is noise, not information.
    if (!SkyDetail.drawsGlyphs(detail)) {
        if (selected) drawSelection(centre, coreRadius)
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
            alpha = 0.85f,
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
        )
        drawLine(
            color = colour,
            start = Offset(centre.x - length, centre.y),
            end = Offset(centre.x + length, centre.y),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
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
 * The mark on the star whose detail is open.
 *
 * A ring at a fixed offset from the core, in the sky's own ink rather than in the mood's colour, so
 * that "this is the one you tapped" is never mistaken for something about the record. It is drawn
 * *around* the star and never by growing it — a star that swells when selected is a star whose size
 * means something [SkyGlyph] says it must not.
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
 * A star's colour: the person's own equalised ramp, or the sky's ink when the record has no mood.
 *
 * **An uncoloured star is not a lesser star.** [SkyPalette.NIGHT_INK] is the sky's *brightest*
 * value, so the four kinds that carry no mood are drawn at full presence rather than in a
 * placeholder grey — the alternative would make "no mood recorded" look like a weaker version of a
 * check-in, which is a judgement about kinds of act.
 */
private fun starColour(moodLevel: Int, equalisedRamp: IntArray): Color {
    if (moodLevel < SkyGlyph.MOOD_MIN || moodLevel > SkyGlyph.MOOD_MAX) return SkyNightInk
    val index = moodLevel - SkyGlyph.MOOD_MIN
    if (index !in equalisedRamp.indices) return SkyNightInk
    return skyColor(equalisedRamp[index])
}
