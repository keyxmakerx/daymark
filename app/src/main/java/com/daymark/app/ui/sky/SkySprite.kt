package com.daymark.app.ui.sky

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.RadialGradientShader
import androidx.compose.ui.graphics.asImageBitmap
import com.daymark.app.sky.SkyAge
import com.daymark.app.sky.SkyGlyph
import com.daymark.app.sky.SkyKind
import com.daymark.app.sky.SkyStarLight
import com.daymark.app.sky.SkyTwinkle
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Stars, worked out once into small bitmaps and then stamped.
 *
 * `docs/SKY.md` §3.5 asks for *"a point, then a glow"*: a white-hot heart falling away into the
 * star's own colour, a soft wing round it the way a real star's light spreads in the eye, and a
 * faint outer glow whose spread is the mood. [SkyStarLight] works that light out pixel by pixel, and
 * a sky has thousands of stars, so each distinct star is worked out once and every star that looks
 * the same reuses it. A star is round: nothing about it has a ray, a spike or an edge (#474).
 *
 * **This file decides nothing that carries meaning.** Colour comes from [SkyGlyph.starTint], which
 * is age and temperature; the light from [SkyStarLight], at one light for every ordinary star;
 * brightness from [SkyGlyph.starBrightness], which is age; the halo's spread and peak from
 * [SkyGlyph]; rhythm from [SkyTwinkle]. What is here is rasterisation. There is no Android SDK in
 * this authoring environment, so a rule that lives in this file is a rule nobody can execute until
 * CI — which is exactly why none of them do.
 *
 * ## The cache key, and the one place it had to be normalised
 *
 * A sprite is keyed on **(mood level, [SkyAge.ageBucket], [SkyGlyph.temperatureIndex])** — those
 * three are the whole of what a star's appearance is a function of — plus what changes the
 * sprite's *shape* rather than its colour: whether it is a landmark, whether the quiet sky is on,
 * and how far the heart has grown with the zoom ([SkyGlyph.zoomScale]), in steps of [GROWTH_STEP].
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
        // One light for every ordinary star, whatever its kind, age or mood: `SkyStarLight`'s
        // header is why. The landmark is brighter because a person placed it.
        val light = if (landmark) SkyStarLight.LANDMARK_LIGHT else SkyStarLight.LIGHT
        val coreRadius = SkyGlyph.coreRadiusDp(kind, moodLevel) *
            SkyGlyph.coreScale(kind) * growthAt(growth) * pxPerDp
        // The quiet sky is the bead and nothing else. §7.1: a soft gradient around a small mark is
        // the first thing to disappear for someone with low vision, and leaving it in only fuzzes
        // the edge of the thing they are trying to find. The bead grows by a dp to pay back some
        // of the presence the wing and the glow were carrying.
        val beadRadius = if (quiet) coreRadius + QUIET_CORE_GROWTH_DP * pxPerDp else coreRadius
        val wing = (if (landmark) SkyStarLight.LANDMARK_WING_DP else SkyStarLight.WING_DP) * pxPerDp
        // The outer glow: the one place a mood is spent. Its spread and its peak are SkyGlyph's,
        // and the shape of its fall is the same at every mood, so only its scale moves.
        val outerRadius = SkyGlyph.outerGlowRadiusDp(kind, moodLevel) * pxPerDp
        val haloPeak = SkyGlyph.haloPeakAlpha(kind, moodLevel)

        val raster = SkyStarLight.raster(
            SkyStarLight.Shape(
                tint = starTint,
                light = light,
                beadPx = beadRadius,
                wingPx = wing,
                haloRadiusPx = outerRadius,
                haloPeak = haloPeak,
                quiet = quiet,
            ),
        )
        val image = Bitmap.createBitmap(raster.pixels, raster.size, raster.size, Bitmap.Config.ARGB_8888)
        return Sprite(image.asImageBitmap())
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

        /** How much the bead grows when the wing and the glow are dropped for the quiet sky. */
        const val QUIET_CORE_GROWTH_DP = 1f

        /**
         * How finely the heart's growth with zoom is cached: one sprite size per 5%, too small a
         * step to see a star jump as the person zooms, and few enough that a pinch does not
         * rasterise the sky afresh on every frame.
         */
        const val GROWTH_STEP = 1.05f

        /** Enough steps for the largest growth, `MAX_ZOOM^0.2` at the closest zoom, with room over. */
        const val MAX_GROWTH_STEP = 44

        /** How far past the core a prism fringe spreads, before [SkyGlyph.GLOW_SPREAD]. */
        const val FRINGE_SPREAD_DP = 1.6f

        /**
         * The cache's ceiling, in sprites.
         *
         * The key space is six moods by twenty-nine age buckets by four temperatures at each
         * growth step, so the true worst case is far above this and a sky pulled all the way out
         * can approach it. Every star on screen shares one growth step, so zooming replaces the
         * set rather than multiplying it. A sprite is as wide as its glow, about 14 dp, so at
         * roughly 8 KB a sprite this cap is about 4 MB, and overrunning it costs one frame's
         * rasterisation rather than any correctness. It is a leak stop with a known rough edge: a
         * viewport that genuinely holds more than this many distinct stars will rebuild them every
         * frame. That is the number to watch if this surface ever stutters pulled out to five years.
         */
        const val MAX_CACHED_SPRITES = 512
    }
}
