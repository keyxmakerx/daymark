package com.daymark.app.ui.sky

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.RadialGradientShader
import com.daymark.app.sky.SkyAge
import com.daymark.app.sky.SkyGlyph
import com.daymark.app.sky.SkyKind
import com.daymark.app.sky.SkyTwinkle
import kotlin.math.ceil
import kotlin.math.max

/**
 * Stars, drawn once into small bitmaps and then stamped.
 *
 * `docs/SKY.md` §3.5 asks for *"a point, then a glow"*: a hard-edged near-white core, a tight
 * bright inner glow right against it, and a soft faint outer glow whose spread is the mood. That is
 * three radial gradients per star, and a sky has thousands of stars — so each distinct star is
 * rasterised once and every star that looks the same reuses it. `docs/prototypes/your-sky.html`'s
 * `makeSprite` is the recipe this follows, and where it is departed from the reason is written at
 * the departure.
 *
 * **This file decides nothing that carries meaning.** Colour comes from [SkyGlyph.starTint], which
 * is age and temperature; brightness from [SkyGlyph.starBrightness], which is age; halo geometry
 * and the fade's stops from [SkyGlyph]; rhythm from [SkyTwinkle]. What is here is rasterisation.
 * There is no Android SDK in this authoring environment, so a rule that lives in this file is a
 * rule nobody can execute until CI — which is exactly why none of them do.
 *
 * ## The cache key, and the one place it had to be normalised
 *
 * A sprite is keyed on **(mood level, [SkyAge.ageBucket], [SkyGlyph.temperatureIndex])** — those
 * three are the whole of what a star's appearance is a function of — plus two flags that change the
 * sprite's *shape* rather than its colour: whether it is a landmark, and whether the quiet sky is
 * on. Nothing about the record's identity beyond its temperature enters, and nothing about how much
 * was logged enters at all.
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
     * clock and must not grow one, so the date arithmetic arrives here already done.
     */
    fun star(
        kind: SkyKind,
        id: Long,
        ageYears: Float,
        moodLevel: Int,
        quiet: Boolean,
    ): Sprite {
        val landmark = kind == SkyKind.LIFE_EVENT
        val bucket = if (landmark) 0 else SkyAge.ageBucket(ageYears)
        val temperature = if (landmark) 0 else SkyGlyph.temperatureIndex(kind, id)
        val key = keyOf(landmark, quiet, moodLevel, bucket, temperature)
        stars[key]?.let { return it }
        // Cleared wholesale rather than evicted one at a time, like the field's tiles: these are
        // pure derived data, so throwing all of them away costs one frame's rasterisation and no
        // correctness. The ceiling exists because the key space is genuinely large — six moods by
        // twenty-nine age buckets by four temperatures — and a person with five years of history
        // pulled all the way out can have several hundred distinct sprites on screen at once.
        if (stars.size >= MAX_CACHED_SPRITES) stars.clear()
        val sprite = rasterise(kind, id, bucket, moodLevel, quiet, landmark)
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
    ): Sprite {
        val tint = skyColor(SkyGlyph.starTint(kind, id, SkyAge.bucketAgeYears(bucket), moodLevel))
        val coreRadius = SkyGlyph.coreRadiusDp(kind, moodLevel) *
            SkyGlyph.coreScale(kind) * pxPerDp
        val innerRadius = coreRadius + INNER_GLOW_DP * pxPerDp
        val outerRadius = SkyGlyph.outerGlowRadiusDp(kind, moodLevel) * pxPerDp

        // The quiet sky is the core and nothing else. §7.1: a soft gradient around a small mark is
        // the first thing to disappear for someone with low vision, and leaving it in only fuzzes
        // the edge of the thing they are trying to find. The core grows by half a dp to pay back
        // some of the presence the glow was carrying.
        val radius = if (quiet) coreRadius + QUIET_CORE_GROWTH_DP * pxPerDp
        else max(outerRadius, innerRadius)
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

            // The inner glow: tight and bright, right against the point. This is the "then a glow"
            // in "a point, then a glow" — without it the core is a dot pasted onto a smudge.
            paint.shader = RadialGradientShader(
                center = centre,
                radius = innerRadius,
                colors = listOf(
                    tint.copy(alpha = INNER_GLOW_ALPHA),
                    tint.copy(alpha = INNER_GLOW_MID_ALPHA),
                    tint.copy(alpha = 0f),
                ),
                colorStops = listOf(0f, 0.5f, 1f),
            )
            canvas.drawCircle(centre, innerRadius, paint)
        }

        // The point: hard-edged, crisp, and the same near-white for every star on the surface.
        // `docs/SKY.md` §3.2: "The core is the same near-white for every star. Age tints the glow
        // around it." The core carries no age and no mood, which is what makes equal presence
        // structural rather than arithmetic.
        paint.shader = null
        paint.color = CORE_TINT.copy(alpha = SkyGlyph.coreAlpha(kind, moodLevel))
        canvas.drawCircle(
            centre,
            if (quiet) coreRadius + QUIET_CORE_GROWTH_DP * pxPerDp else coreRadius,
            paint,
        )
        return Sprite(image)
    }

    /**
     * The four soft spikes on a landmark, the way a bright star flares in the eye.
     *
     * Not a kind mark. `SkyDetail.drawsGlyphs` holds kind marks back until the person has leaned
     * in to one day; a landmark's spikes are part of its *light*, and `docs/SKY.md` §3.4 gives it
     * that light because the person placed the mark by hand — prominence follows authorship. They
     * are white rather than tinted for the same reason its core is: a landmark is never redshifted.
     */
    private fun spikes(canvas: Canvas, paint: Paint, centre: Offset, radius: Float) {
        val half = SPIKE_THICKNESS_DP * pxPerDp / 2f
        val colors = listOf(
            CORE_TINT.copy(alpha = 0f),
            CORE_TINT.copy(alpha = SPIKE_ALPHA),
            CORE_TINT.copy(alpha = 0f),
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
        return key
    }

    private companion object {

        /**
         * How far past the core the tight inner glow reaches, in dp. The prototype's `core + 1.5`.
         */
        const val INNER_GLOW_DP = 1.5f

        const val INNER_GLOW_ALPHA = 0.95f
        const val INNER_GLOW_MID_ALPHA = 0.55f

        /** How much the core grows when the glows are dropped for the quiet sky. */
        const val QUIET_CORE_GROWTH_DP = 0.5f

        const val SPIKE_THICKNESS_DP = 1.2f
        const val SPIKE_ALPHA = 0.6f

        /** How far past the core a prism fringe spreads, before [SkyGlyph.GLOW_SPREAD]. */
        const val FRINGE_SPREAD_DP = 1.6f

        /**
         * The cache's ceiling, in sprites.
         *
         * The key space is six moods by twenty-nine age buckets by four temperatures, so the true
         * worst case is far above this and a sky pulled all the way out can approach it. At roughly
         * 9 KB a sprite this cap is about 4 MB, and overrunning it costs one frame's rasterisation
         * rather than any correctness. It is a leak stop with a known rough edge: a viewport that
         * genuinely holds more than this many distinct stars will rebuild them every frame. That is
         * the number to watch if this surface ever stutters pulled out to five years.
         */
        const val MAX_CACHED_SPRITES = 512

        /**
         * The white heart, for every star. Near-white and not pure white, which is what stops a
         * field of cores reading as a grid of identical pixels.
         */
        val CORE_TINT = Color(0xFFFFFDF7)
    }
}
