package com.daymark.app.sky

/**
 * How a point of light is lit: the bead at a star's heart, the thin rim of light around it, and,
 * while the star is small on the screen, the bloom and the four rays that make it read as a star.
 *
 * This is the approved phone sky's star (`docs/prototypes/sky-phone.html`, its star shader), carried
 * over number for number. There a star is a small crisp disc in its own age colour, a little
 * darker toward its edge the way a real star's limb is, with a rim of light that halves about every
 * half a dp and is gone by [RIM_REACH_DP]. Light is added up in linear light and tone-mapped once,
 * so a bright bead stays a colour instead of clipping to white. The renderer cannot run a shader
 * per star, so this file works the same arithmetic out at a handful of radii and the sprite draws
 * gradients through them (#460).
 *
 * Import-free, like the rest of `sky/`: colour in, packed colours out, and a plain-JVM test can
 * check every stop.
 *
 * **Every ordinary star is lit the same.** [LIGHT] is one number for every star of every kind,
 * age and mood. The approved sky also gave each star a random magnitude, which made some much
 * brighter than others for no reason. It is left out on purpose: on this surface a brighter star is
 * read as a day that counted for more, and `SkyGlyph`'s header is the argument for why no day's
 * mark may be louder than another's. Age still sets the colour and [SkyAge.fadeFor] still lets old
 * stars sit back a little. The landmark is the one exception, at [LANDMARK_LIGHT], because a
 * person placed it by hand.
 */
object SkyStarLight {

    /** An ordinary star's light: the approved sky's average star. The same for every one of them. */
    const val LIGHT = 0.73f

    /** A life event's light. Brighter, for the reason [SkyGlyph.LANDMARK_CORE_SCALE] gives. */
    const val LANDMARK_LIGHT = 1.6f

    /** How much brighter the bead burns than the light it is made of. The shader's `pk * 1.6`. */
    const val SURFACE_GAIN = 1.6f

    /** How much white is mixed into the bead's colour, so a red star's heart is still bright. */
    const val WHITE_SHARE = 0.3f

    /** The tone map's exposure: `1 - e^(-1.05 c)`. */
    const val EXPOSURE = 1.05f

    /** The rim's light right at the bead's edge, as a share of the star's own. */
    const val RIM_PEAK = 0.3f

    /** How fast the rim falls, per dp outside the bead: `e^(-1.5 d)`. */
    const val RIM_FALL_PER_DP = 1.5f

    /** How far past the bead the rim reaches, in dp. Nothing is drawn beyond it. */
    const val RIM_REACH_DP = 1.6f

    /** Where the bead's stops sit, as fractions of its radius. Close together toward the limb. */
    val CORE_STOP_POSITION = floatArrayOf(0f, 0.5f, 0.7f, 0.85f, 0.95f, 1f)

    /** Where the rim's stops sit, in dp outside the bead. The last is [RIM_REACH_DP]. */
    val RIM_STOP_DP = floatArrayOf(0f, 0.33f, 0.67f, 1f, RIM_REACH_DP)

    /**
     * How bright the bead is at [q], a fraction of its radius from the centre: `1` at the centre,
     * falling to `0.22` at the edge. The shader's limb darkening, `1 - 0.56(1 - z) - 0.22(1 - z)²`
     * with `z` the height of a sphere's surface at [q].
     */
    fun limbAt(q: Float): Float {
        val clamped = q.coerceIn(0f, 1f)
        val z = Math.sqrt((1f - clamped * clamped).toDouble()).toFloat()
        val edge = 1f - z
        return 1f - 0.56f * edge - 0.22f * edge * edge
    }

    /**
     * The bead's colours at [CORE_STOP_POSITION], for a star of [tint] (`0xRRGGBB`) burning at
     * [light]. Each is packed `0xAARRGGBB` with its alpha at the brightest channel, so a stop drawn
     * over black adds exactly its own light.
     */
    fun coreStops(tint: Int, light: Float): IntArray {
        val star = starLight(tint, light)
        val peak = maxOf(star[0], star[1], star[2])
        if (!(peak > 0f)) return IntArray(CORE_STOP_POSITION.size)
        return IntArray(CORE_STOP_POSITION.size) { i ->
            val limb = limbAt(CORE_STOP_POSITION[i])
            val surface = FloatArray(3) { c ->
                ((1f - WHITE_SHARE) * star[c] / peak + WHITE_SHARE) * SURFACE_GAIN * peak * limb
            }
            pack(surface)
        }
    }

    /**
     * The rim's colours at [RIM_STOP_DP], for a star of [tint] burning at [light]. The last is the
     * one before it at zero alpha, so the rim ends on nothing rather than on an edge, and fades
     * out in its own colour rather than through black.
     */
    fun rimStops(tint: Int, light: Float): IntArray {
        val star = starLight(tint, light)
        val stops = IntArray(RIM_STOP_DP.size)
        for (i in 0 until RIM_STOP_DP.size - 1) {
            val fall = RIM_PEAK * Math.exp((-RIM_FALL_PER_DP * RIM_STOP_DP[i]).toDouble()).toFloat()
            stops[i] = pack(FloatArray(3) { c -> star[c] * fall })
        }
        stops[RIM_STOP_DP.size - 1] = stops[RIM_STOP_DP.size - 2] and 0x00FFFFFF
        return stops
    }

    // -------------------------------------------------------------------------------------------
    // The bloom: a point of light spreading in the eye.
    // -------------------------------------------------------------------------------------------

    /**
     * The bloom's alpha at the star's centre, at full strength, in the star's own tint.
     *
     * Far out a bead is a few pixels across, and a few pixels with nothing round them read as a
     * speck rather than as a light (#474). The bloom is the soft light a bright point spreads in the
     * eye: it falls away as `e^(-d / BLOOM_FALL_DP)` and is gone by [BLOOM_REACH_DP]. It is the same
     * for every ordinary star and takes no mood, so it adds presence to the sky without making one
     * star louder than another, and the halo is still the only thing a mood moves.
     */
    const val BLOOM_PEAK = 0.45f

    /** How fast the bloom falls, in dp: `e^(-d / 2.2)`. */
    const val BLOOM_FALL_DP = 2.2f

    /** How far from the star's centre the bloom reaches, in dp. Nothing is drawn beyond it. */
    const val BLOOM_REACH_DP = 12f

    /** Where the bloom's stops sit, in dp from the centre. The last is [BLOOM_REACH_DP]. */
    val BLOOM_STOP_DP = floatArrayOf(0f, 1f, 2f, 3f, 4.5f, 6f, 8f, 10f, BLOOM_REACH_DP)

    /** How much of the bloom is left once the view is close: the bead has grown and carries the star. */
    const val BLOOM_CLOSE_SHARE = 0.4f

    /** By how many screen widths across the bloom has eased down to [BLOOM_CLOSE_SHARE]. */
    const val BLOOM_EASED_BY = 30f

    /**
     * How strong the bloom is at [zoom], screen widths across: whole until the view leaves
     * [SkyDetail.FAR], easing down to [BLOOM_CLOSE_SHARE] by [BLOOM_EASED_BY], and never off.
     * A function of how close the view is and nothing else, so it moves every star together.
     */
    fun bloomShare(zoom: Float): Float {
        val t = ((zoom - SkyDetail.NEAR_ZOOM) / (BLOOM_EASED_BY - SkyDetail.NEAR_ZOOM)).coerceIn(0f, 1f)
        return 1f - (1f - BLOOM_CLOSE_SHARE) * t * t * (3f - 2f * t)
    }

    /**
     * The bloom's alphas at [BLOOM_STOP_DP], at [share] of its strength. Tapered to exactly `0` at
     * [BLOOM_REACH_DP], so the bloom ends on nothing rather than on an edge.
     */
    fun bloomAlphas(share: Float): FloatArray = FloatArray(BLOOM_STOP_DP.size) { i ->
        val d = BLOOM_STOP_DP[i]
        val u = d / BLOOM_REACH_DP
        val taper = (1f - u * u) * (1f - u * u)
        BLOOM_PEAK * share.coerceIn(0f, 1f) * Math.exp((-d / BLOOM_FALL_DP).toDouble()).toFloat() * taper
    }

    // -------------------------------------------------------------------------------------------
    // The rays: four faint points, far out only.
    // -------------------------------------------------------------------------------------------

    /**
     * The rays' alpha where they cross the star, at full strength, in the star's own tint.
     *
     * Four faint rays are what makes a point a few pixels across read as a star rather than a dot
     * (#474). Every ordinary star carries the same four, still, in its own colour, and nothing about
     * them comes from mood or from anything the app measured, so they are the texture of the sky and
     * not a mark on any one day. They belong to the far views only: they are whole until
     * [RAY_FULL_UNTIL] and gone by [SkyDetail.CLOSE_ZOOM], where a goal's cross-rays resolve and
     * have to be its own. A landmark's rays are longer, white, and drawn at every zoom
     * (`docs/SKY.md` §3.4), so it is still the one bright star.
     */
    const val RAY_PEAK = 0.45f

    /** How far each ray reaches from the star's centre, in dp. */
    const val RAY_ARM_DP = 6f

    /** How thick each ray is, in dp. */
    const val RAY_THICKNESS_DP = 0.7f

    /** Up to how many screen widths across the rays are whole. */
    const val RAY_FULL_UNTIL = 3f

    /** Where each ray's stops sit, as fractions of its length from one tip to the other. */
    val RAY_STOP_POSITION = floatArrayOf(0f, 0.15f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 0.85f, 1f)

    /**
     * How strong the rays are at [zoom], screen widths across: whole until [RAY_FULL_UNTIL],
     * falling evenly to nothing at [SkyDetail.CLOSE_ZOOM]. A function of how close the view is and
     * nothing else.
     */
    fun rayShare(zoom: Float): Float =
        1f - ((zoom - RAY_FULL_UNTIL) / (SkyDetail.CLOSE_ZOOM - RAY_FULL_UNTIL)).coerceIn(0f, 1f)

    /**
     * A ray's alphas at [RAY_STOP_POSITION], at [share] of its strength: brightest where it crosses
     * the star and falling as the square of the way out, to exactly `0` at both tips.
     */
    fun rayAlphas(share: Float): FloatArray = FloatArray(RAY_STOP_POSITION.size) { i ->
        val out = 1f - Math.abs(RAY_STOP_POSITION[i] - 0.5f) * 2f
        RAY_PEAK * share.coerceIn(0f, 1f) * out * out
    }

    /** One channel of a packed `0xRRGGBB` colour, `0..255`, in linear light. */
    fun toLinear(channel: Int): Float =
        Math.pow((channel.coerceIn(0, 255) / 255.0), GAMMA).toFloat()

    /** Linear light, tone-mapped and encoded back to a `0..255` channel. Never above 255. */
    fun encode(linear: Float): Int {
        if (!(linear > 0f)) return 0
        val mapped = 1.0 - Math.exp(-EXPOSURE.toDouble() * linear)
        return Math.round(Math.pow(mapped, 1.0 / GAMMA) * 255.0).toInt().coerceIn(0, 255)
    }

    private const val GAMMA = 2.2

    private fun starLight(tint: Int, light: Float): FloatArray = floatArrayOf(
        toLinear((tint shr 16) and 0xFF) * light,
        toLinear((tint shr 8) and 0xFF) * light,
        toLinear(tint and 0xFF) * light,
    )

    private fun pack(linear: FloatArray): Int {
        val r = encode(linear[0])
        val g = encode(linear[1])
        val b = encode(linear[2])
        val a = maxOf(r, g, b)
        if (a == 0) return 0
        fun unpremultiplied(v: Int) = Math.round(v * 255f / a).coerceIn(0, 255)
        return (a shl 24) or (unpremultiplied(r) shl 16) or (unpremultiplied(g) shl 8) or unpremultiplied(b)
    }
}
