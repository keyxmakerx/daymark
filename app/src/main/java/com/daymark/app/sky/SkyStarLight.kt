package com.daymark.app.sky

/**
 * How a point of light is lit: the bead at a star's heart and the thin rim of light around it.
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
