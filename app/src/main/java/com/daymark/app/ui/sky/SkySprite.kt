package com.daymark.app.ui.sky

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.RadialGradientShader
import com.daymark.app.sky.SkyAge
import com.daymark.app.sky.SkyGlyph
import com.daymark.app.sky.SkyKind
import com.daymark.app.sky.SkyStarLight
import com.daymark.app.sky.SkyTwinkle
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Stars, drawn once into small bitmaps and then stamped.
 *
 * `docs/SKY.md` §3.5 asks for *"a point, then a glow"*: a crisp bead of light in the star's own
 * colour, a thin rim of light around it, and a soft faint outer glow whose spread is the mood. That
 * is three radial gradients per star, and a sky has thousands of stars — so each distinct star is
 * rasterised once and every star that looks the same reuses it. The bead and the rim are the
 * approved phone sky's star (`docs/prototypes/sky-phone.html`), worked out at a handful of radii by
 * [SkyStarLight] (#460).
 *
 * **This file decides nothing that carries meaning.** Colour comes from [SkyGlyph.starTint], which
 * is age and temperature; the bead's and the rim's light from [SkyStarLight], at one light for
 * every ordinary star; brightness from [SkyGlyph.starBrightness], which is age; halo geometry and
 * the fade's stops from [SkyGlyph]; rhythm from [SkyTwinkle]. What is here is rasterisation.
 * There is no Android SDK in this authoring environment, so a rule that lives in this file is a
 * rule nobody can execute until CI — which is exactly why none of them do.
 *
 * ## The cache key, and the one place it had to be normalised
 *
 * A sprite is keyed on **(mood level, [SkyAge.ageBucket], [SkyGlyph.temperatureIndex])** — those
 * three are the whole of what a star's appearance is a function of — plus what changes the
 * sprite's *shape* rather than its colour: whether it is a landmark, whether the quiet sky is on,
 * and how far the bead has grown with the zoom ([SkyGlyph.zoomScale]), in steps of [GROWTH_STEP].
 * The zoom is the same for every star on screen, so it adds no variety between them. Nothing about
 * the record's identity beyond its temperature enters, and nothing about how much was logged
 * enters at all.
 *
 * The age bucket is a *quantised* age, so the tint has to be rebuilt from the bucket
 * ([SkyAge.bucketAgeYears]) and never from the exact age the caller passed. Otherwise two stars
 * three days apart would share a sprite whose colour came from whichever of them happened to be
 * drawn first, and a redraw could change a star's colour without anything about the star changing.
 *
 * A landmark is normalised harder still: [SkyAge.tintFor] exempts it from the redshift and
 * [SkyGlyph.starTint] gives it no temperature, so every landmark at every age is the same white
 * star and they all collapse onto one key.
 */
internal class SkySprites(private val pxPerDp: Float) {

    /** One rasterised star, and the offset from its centre to its top-left corner. */
    class Sprite(val image: ImageBitmap) {
        val halfWidth: Float = image.width / 2f
        val halfHeight: Float = image.height / 2f
    }

    private val stars = HashMap<Int, Sprite>()
    private var fringeRed: Sprite? = null
    private var fringeBlue: Sprite? = null
    private var fringeRedLandmark: Sprite? = null
    private var fringeBlueLandmark: Sprite? = null

    /**
     * The star for this record, rasterised on first use.
     *
     * [ageYears] is the renderer's own arithmetic on a clock the renderer read — `sky/` has no
     * clock and must not grow one, so the date arithmetic arrives here already done. [zoom] is the
     * camera's screen widths across, `1` for the whole sky.
     */
    fun star(
        kind: SkyKind,
        id: Long,
        ageYears: Float,
        moodLevel: Int,
        quiet: Boolean,
        zoom: Float,
    ): Sprite {
        val landmark = kind == SkyKind.LIFE_EVENT
        val bucket = if (landmark) 0 else SkyAge.ageBucket(ageYears)
        val temperature = if (landmark) 0 else SkyGlyph.temperatureIndex(kind, id)
        val growth = growthStep(zoom)
        val key = keyOf(landmark, quiet, moodLevel, bucket, temperature, growth)
        stars[key]?.let { return it }
        // Cleared wholesale rather than evicted one at a time, like the field's tiles: these are
        // pure derived data, so throwing all of them away costs one frame's rasterisation and no
        // correctness. The ceiling exists because the key space is genuinely large — six moods by
        // twenty-nine age buckets by four temperatures — and a person with five years of history
        // pulled all the way out can have several hundred distinct sprites on screen at once.
        if (stars.size >= MAX_CACHED_SPRITES) stars.clear()
        val sprite = rasterise(kind, id, bucket, moodLevel, quiet, landmark, growth)
        stars[key] = sprite
        return sprite
    }

    /** One half of the prism flash. Two sizes, because a landmark's fringe is drawn wider. */
    fun fringe(red: Boolean, landmark: Boolean): Sprite {
        val existing = when {
            red && landmark -> fringeRedLandmark
            red -> fringeRed
            landmark -> fringeBlueLandmark
            else -> fringeBlue
        }
        if (existing != null) return existing
        val scale = if (landmark) SkyTwinkle.FRINGE_SCALE_LANDMARK else 1f
        val tint = if (red) SkyTwinkle.GLINT_RED else SkyTwinkle.GLINT_BLUE
        val made = rasteriseFringe(tint, scale)
        when {
            red && landmark -> fringeRedLandmark = made
            red -> fringeRed = made
            landmark -> fringeBlueLandmark = made
            else -> fringeBlue = made
        }
        return made
    }

    // -----------------------------------------------------------------------------------------

    private fun rasterise(
        kind: SkyKind,
        id: Long,
        bucket: Int,
        moodLevel: Int,
        quiet: Boolean,
        landmark: Boolean,
        growth: Int,
    ): Sprite {
        val starTint = SkyGlyph.starTint(kind, id, SkyAge.bucketAgeYears(bucket), moodLevel)
        val tint = skyColor(starTint)
        // One light for every ordinary star, whatever its kind, age or mood: `SkyStarLight`'s
        // header is why. The landmark is brighter because a person placed it.
        val light = if (landmark) SkyStarLight.LANDMARK_LIGHT else SkyStarLight.LIGHT
        val coreRadius = SkyGlyph.coreRadiusDp(kind, moodLevel) *
            SkyGlyph.coreScale(kind) * growthAt(growth) * pxPerDp
        // The quiet sky is the bead and nothing else. §7.1: a soft gradient around a small mark is
        // the first thing to disappear for someone with low vision, and leaving it in only fuzzes
        // the edge of the thing they are trying to find. The bead grows by half a dp to pay back
        // some of the presence the rim and the glow were carrying.
        val beadRadius = if (quiet) coreRadius + QUIET_CORE_GROWTH_DP * pxPerDp else coreRadius
        val rimRadius = beadRadius + SkyStarLight.RIM_REACH_DP * pxPerDp
        val outerRadius = SkyGlyph.outerGlowRadiusDp(kind, moodLevel) * pxPerDp

        val radius = if (quiet) beadRadius else max(outerRadius, rimRadius)
        val size = ceil(radius * 2f).toInt() + 2
        val image = ImageBitmap(size, size)
        val canvas = Canvas(image)
        val centre = Offset(size / 2f, size / 2f)
        val paint = Paint().apply { isAntiAlias = true }

        if (!quiet) {
            if (landmark) spikes(canvas, paint, centre, radius)

            // The outer glow: soft, faint, and spread by the mood. The stops are SkyGlyph's, as
            // fractions of the outer radius and of the star's own peak alpha — so the shape of the
            // fall is identical at every mood and only its scale moves. The prototype's `peak +
            // 0.3` floor is deliberately not carried over; SkyGlyph's halo section says why.
            paint.shader = RadialGradientShader(
                center = centre,
                radius = outerRadius,
                colors = List(SkyGlyph.HALO_STOP_WEIGHT.size) {
                    tint.copy(alpha = SkyGlyph.haloStopAlpha(kind, moodLevel, it))
                },
                colorStops = SkyGlyph.HALO_STOP_POSITION.toList(),
            )
            canvas.drawCircle(centre, outerRadius, paint)

            // The rim: a thin ring of the star's own light against the bead's edge, gone by
            // `RIM_REACH_DP`. Nothing inside the bead, so the bead's light is not counted twice.
            // Added rather than painted over, as the sky adds every star.
            val edge = beadRadius / rimRadius
            paint.blendMode = BlendMode.Plus
            paint.shader = RadialGradientShader(
                center = centre,
                radius = rimRadius,
                colors = listOf(Color.Transparent, Color.Transparent) +
                    SkyStarLight.rimStops(starTint, light).map { Color(it) },
                colorStops = listOf(0f, edge) + SkyStarLight.RIM_STOP_DP.map {
                    (beadRadius + it * pxPerDp) / rimRadius
                },
            )
            canvas.drawCircle(centre, rimRadius, paint)
        }

        // The bead: crisp, in the star's own colour, a little darker toward its edge the way a
        // real star's limb is. Its colour is the age tint, so age reads at the heart of the star
        // and not only in a haze around it; its light is the same for every ordinary star, so
        // equal presence holds at the heart too.
        paint.blendMode = BlendMode.Plus
        paint.shader = RadialGradientShader(
            center = centre,
            radius = beadRadius,
            colors = SkyStarLight.coreStops(starTint, light).map { Color(it) },
            colorStops = SkyStarLight.CORE_STOP_POSITION.toList(),
        )
        canvas.drawCircle(centre, beadRadius, paint)
        return Sprite(image)
    }

    /**
     * The four soft spikes on a landmark, the way a bright star flares in the eye.
     *
     * Not a kind mark. `SkyDetail.drawsGlyphs` holds kind marks back until the person has leaned
     * in to one day; a landmark's spikes are part of its *light*, and `docs/SKY.md` §3.4 gives it
     * that light because the person placed the mark by hand — prominence follows authorship. They
     * are white for the same reason its bead is: a landmark is never redshifted.
     */
    private fun spikes(canvas: Canvas, paint: Paint, centre: Offset, radius: Float) {
        val half = SPIKE_THICKNESS_DP * pxPerDp / 2f
        val colors = listOf(
            SPIKE_TINT.copy(alpha = 0f),
            SPIKE_TINT.copy(alpha = SPIKE_ALPHA),
            SPIKE_TINT.copy(alpha = 0f),
        )
        val stops = listOf(0f, 0.5f, 1f)
        paint.shader = LinearGradientShader(
            from = Offset(centre.x - radius, centre.y),
            to = Offset(centre.x + radius, centre.y),
            colors = colors,
            colorStops = stops,
        )
        // The four-float overload, which is a member of the Canvas interface — the Rect one is an
        // extension and would need its own import.
        canvas.drawRect(centre.x - radius, centre.y - half, centre.x + radius, centre.y + half, paint)
        paint.shader = LinearGradientShader(
            from = Offset(centre.x, centre.y - radius),
            to = Offset(centre.x, centre.y + radius),
            colors = colors,
            colorStops = stops,
        )
        canvas.drawRect(centre.x - half, centre.y - radius, centre.x + half, centre.y + radius, paint)
    }

    private fun rasteriseFringe(tint: Int, scale: Float): Sprite {
        val radius = (SkyGlyph.CORE_RADIUS_DP + FRINGE_SPREAD_DP) *
            SkyGlyph.GLOW_SPREAD * scale * pxPerDp
        val size = ceil(radius * 2f).toInt() + 2
        val image = ImageBitmap(size, size)
        val canvas = Canvas(image)
        val centre = Offset(size / 2f, size / 2f)
        val colour = skyColor(tint)
        val paint = Paint().apply {
            isAntiAlias = true
            shader = RadialGradientShader(
                center = centre,
                radius = radius,
                colors = listOf(
                    colour.copy(alpha = 0.9f),
                    colour.copy(alpha = 0.35f),
                    colour.copy(alpha = 0f),
                ),
                colorStops = listOf(0f, 0.5f, 1f),
            )
        }
        canvas.drawCircle(centre, radius, paint)
        return Sprite(image)
    }

    private fun keyOf(
        landmark: Boolean,
        quiet: Boolean,
        moodLevel: Int,
        bucket: Int,
        temperature: Int,
        growth: Int,
    ): Int {
        // A mood level outside the ramp is folded onto MOOD_NONE rather than widening the key:
        // SkyGlyph.haloRadiusDp already draws it at the neutral radius, so it is genuinely the
        // same sprite and giving it its own slot would be caching identical bitmaps twice.
        val mood =
            if (moodLevel < SkyGlyph.MOOD_MIN || moodLevel > SkyGlyph.MOOD_MAX) SkyGlyph.MOOD_NONE
            else moodLevel
        var key = temperature
        key = key * (SkyAge.MAX_AGE_BUCKET + 1) + bucket.coerceIn(0, SkyAge.MAX_AGE_BUCKET)
        key = key * (SkyGlyph.MOOD_MAX + 1) + mood
        key = key * 2 + (if (quiet) 1 else 0)
        key = key * 2 + (if (landmark) 1 else 0)
        key = key * (MAX_GROWTH_STEP + 1) + growth.coerceIn(0, MAX_GROWTH_STEP)
        return key
    }

    /** How many [GROWTH_STEP]s the bead has grown at [zoom]. */
    private fun growthStep(zoom: Float): Int =
        (ln(SkyGlyph.zoomScale(zoom)) / ln(GROWTH_STEP)).roundToInt().coerceIn(0, MAX_GROWTH_STEP)

    /** The bead's growth at a step: [SkyGlyph.zoomScale], to within half a step. */
    private fun growthAt(step: Int): Float = GROWTH_STEP.pow(step)

    private companion object {

        /** How much the bead grows when the rim and the glow are dropped for the quiet sky. */
        const val QUIET_CORE_GROWTH_DP = 0.5f

        /**
         * How finely the bead's growth with zoom is cached: one sprite size per 5%, too small a
         * step to see a star jump as the person zooms, and few enough that a pinch does not
         * rasterise the sky afresh on every frame.
         */
        const val GROWTH_STEP = 1.05f

        /** Enough steps for the largest growth, `400^0.3` at the closest zoom, with room over. */
        const val MAX_GROWTH_STEP = 40

        const val SPIKE_THICKNESS_DP = 1.2f
        const val SPIKE_ALPHA = 0.6f

        /** How far past the core a prism fringe spreads, before [SkyGlyph.GLOW_SPREAD]. */
        const val FRINGE_SPREAD_DP = 1.6f

        /**
         * The cache's ceiling, in sprites.
         *
         * The key space is six moods by twenty-nine age buckets by four temperatures at each
         * growth step, so the true worst case is far above this and a sky pulled all the way out
         * can approach it. Every star on screen shares one growth step, so zooming replaces the
         * set rather than multiplying it. At roughly
         * 9 KB a sprite this cap is about 4 MB, and overrunning it costs one frame's rasterisation
         * rather than any correctness. It is a leak stop with a known rough edge: a viewport that
         * genuinely holds more than this many distinct stars will rebuild them every frame. That is
         * the number to watch if this surface ever stutters pulled out to five years.
         */
        const val MAX_CACHED_SPRITES = 512

        /** A landmark's spikes: near-white, like the landmark itself. */
        val SPIKE_TINT = Color(0xFFFFFDF7)
    }
}
