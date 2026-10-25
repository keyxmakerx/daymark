package com.daymark.app.sky

/**
 * How a point of light is lit, pixel by pixel: a white-hot heart that falls away into the star's
 * own colour, a soft wing round it the way a real star's light spreads in the eye, and outside that
 * the halo whose spread is the mood ([SkyGlyph]).
 *
 * A star far off is not a disc and not a cross. It is a point brighter than anything near it, whose
 * light falls away smoothly in every direction: hottest and palest at the middle, its own colour as
 * it falls, and gone into the dark without an edge. That is the whole of what a real star looks like
 * to the eye, and it is round, so nothing here has an angle in it (#474). The heart is a Gaussian
 * [HEART_GAIN] times the star's light, the wing a Moffat profile, the shape a telescope's stars
 * actually have, and the halo [HALO_GAIN] times `SkyGlyph`'s, all added up in linear light and
 * tone-mapped once, twice over and met halfway as the sky's shaders are ([SkyShaders]): by channel,
 * which turns a bright heart white, and by the brightest channel alone, which keeps its colour.
 *
 * The renderer cannot run a shader per star, so [raster] works the profile out once per distinct
 * star into pixels, and the sprite stamps them.
 *
 * Import-free, like the rest of `sky/`: a colour and a few sizes in, packed pixels out, and a
 * plain-JVM test can check every one.
 *
 * **Every ordinary star is lit the same.** [LIGHT] is one number for every star of every kind,
 * age and mood. The approved sky also gave each star a random magnitude, which made some much
 * brighter than others for no reason. It is left out on purpose: on this surface a brighter star is
 * read as a day that counted for more, and `SkyGlyph`'s header is the argument for why no day's
 * mark may be louder than another's. Age still sets the colour and [SkyAge.fadeFor] still lets old
 * stars sit back a little. The landmark is the one exception, at [LANDMARK_LIGHT], because a
 * person placed it by hand.
 *
 * **The mood reaches the halo and nothing else.** [Shape] takes the halo's radius and peak, which
 * are the two numbers `SkyGlyph` lets a mood move, and every other number in it is the same on
 * every day. The halo's light is `SkyGlyph`'s halo times [HALO_GAIN], added before the tone map,
 * so the light it adds is the same at every mood, as `SkyGlyph.haloEmittedLight` holds.
 */
object SkyStarLight {

    /** An ordinary star's light: the approved sky's average star. The same for every one of them. */
    const val LIGHT = 0.73f

    /** A life event's light. Brighter, for the reason [SkyGlyph.LANDMARK_CORE_SCALE] gives. */
    const val LANDMARK_LIGHT = 1.6f

    /** The heart's light at the middle, over the star's own. */
    const val HEART_GAIN = 2.3f

    /** How fast the heart falls: `e^(-1.15 q²)`, with `q` the distance in bead radii. */
    const val HEART_FALL = 1.15f

    /** How much white is in the heart's colour, so even a red star's heart burns pale. */
    const val WHITE_SHARE = 0.35f

    /** How far the heart is drawn, in bead radii: by here it has fallen below a 4000th. */
    const val HEART_REACH = 3.5f

    /** The wing's light at the middle, over the star's own. */
    const val WING_PEAK = 0.3f

    /**
     * The wing's width at the whole sky, in dp: the Moffat profile's `α`. It does not grow with zoom.
     * Tight, so the wing is a star's own light spreading and not a glow it wears.
     */
    const val WING_DP = 0.9f

    /** How steeply the wing falls: `(1 + (r/α)²)^-2.3`. */
    const val WING_FALL = 2.3f

    /**
     * A life event's wing, in dp: much wider than an ordinary star's, so its extra light spreads
     * the way a brilliant star's does in the eye, without an edge to it (#474).
     */
    const val LANDMARK_WING_DP = 3.1f

    /** How far the wing is drawn, in wing widths: by here it has fallen below a 4000th. */
    const val WING_REACH = 6f

    /** The halo's light over `SkyGlyph`'s halo. Faint: it is the spread round a star, not a star. */
    const val HALO_GAIN = 0.12f

    /** The tone map's exposure: `1 - e^(-1.05 c)`. */
    const val EXPOSURE = 1.05f

    /** How much of the tone map is by the brightest channel alone, which keeps a star's colour. */
    const val COLOUR_KEPT = 0.35f

    /** Where the heart and the wing start to ease out, as a share of how far the sprite reaches. */
    const val TAPER_FROM = 0.7f

    /** The quiet sky's bead: its light over the star's own, and how much white is in it. */
    const val QUIET_GAIN = 1.6f
    const val QUIET_WHITE_SHARE = 0.3f

    /** Samples each way in a pixel near the heart, where the light changes fastest. */
    const val SAMPLES = 4

    /**
     * One star's light, as sizes in pixels.
     *
     * @property tint its colour, packed `0xRRGGBB`: age and temperature, from [SkyGlyph.starTint].
     * @property light how brightly it burns: [LIGHT], or [LANDMARK_LIGHT] for a life event.
     * @property beadPx the heart's radius.
     * @property wingPx the wing's width.
     * @property haloRadiusPx how far the halo reaches before it is nothing: the mood's spread.
     * @property haloPeak the halo's peak: the mood's, from [SkyGlyph.haloPeakAlpha].
     * @property quiet the quiet sky's star: a crisp bead and nothing round it (`docs/SKY.md` §7.1).
     */
    class Shape(
        val tint: Int,
        val light: Float,
        val beadPx: Float,
        val wingPx: Float,
        val haloRadiusPx: Float,
        val haloPeak: Float,
        val quiet: Boolean,
    ) {
        /** [tint] in linear light, a channel each. */
        val linearTint: FloatArray = linear(tint)

        /** How far from the middle the star has any light at all, in pixels. */
        val reachPx: Float =
            if (quiet) beadPx + 1f else maxOf(haloRadiusPx, wingPx * WING_REACH, beadPx * HEART_REACH)
    }

    /**
     * The star's light [r] pixels from its middle, linear, into [out]: before the tone map, so
     * stars and their halos add as light does.
     */
    fun lightAt(shape: Shape, r: Float, out: FloatArray) {
        val star = shape.linearTint
        val peak = maxOf(star[0], star[1], star[2])
        if (!(peak > 0f)) {
            out.fill(0f)
            return
        }
        if (shape.quiet) {
            // A crisp bead, a little darker toward its edge the way a real star's limb is: a soft
            // gradient round a small mark is the first thing to disappear for someone with low
            // vision, so the quiet sky has none.
            val inside = r < shape.beadPx
            val limb = if (inside) limbAt(r / shape.beadPx) else 0f
            for (c in 0..2) {
                val heart = (1f - QUIET_WHITE_SHARE) * star[c] / peak + QUIET_WHITE_SHARE
                out[c] = heart * QUIET_GAIN * peak * shape.light * limb
            }
            return
        }
        val q = r / shape.beadPx
        val heart = Math.exp((-HEART_FALL * q * q).toDouble()).toFloat() * HEART_GAIN * shape.light
        val w = r / shape.wingPx
        val wing = WING_PEAK * shape.light * Math.pow((1f + w * w).toDouble(), -WING_FALL.toDouble()).toFloat()
        val taper = 1f - smoothstep(TAPER_FROM * shape.reachPx, shape.reachPx, r)
        val halo = shape.haloPeak * HALO_GAIN * SkyGlyph.haloWeightAt(r / shape.haloRadiusPx)
        for (c in 0..2) {
            val hue = star[c] / peak
            val heartColour = ((1f - WHITE_SHARE) * hue + WHITE_SHARE) * peak
            out[c] = (heartColour * heart + star[c] * wing) * taper + star[c] * halo
        }
    }

    /**
     * Linear light to what the screen shows, `0..1` a channel, into [out]: tone-mapped by channel
     * and by the brightest channel and met at [COLOUR_KEPT], then encoded. Never above 1, and 0 only
     * for no light at all.
     */
    fun toneMap(linear: FloatArray, out: FloatArray) {
        val peak = maxOf(linear[0], linear[1], linear[2])
        if (!(peak > 0f)) {
            out.fill(0f)
            return
        }
        val byPeak = (1.0 - Math.exp(-EXPOSURE.toDouble() * peak)) / peak
        for (c in 0..2) {
            val v = linear[c].coerceAtLeast(0f).toDouble()
            val byChannel = 1.0 - Math.exp(-EXPOSURE.toDouble() * v)
            val mapped = byChannel * (1f - COLOUR_KEPT) + v * byPeak * COLOUR_KEPT
            out[c] = Math.pow(mapped.coerceIn(0.0, 1.0), 1.0 / GAMMA).toFloat()
        }
    }

    /**
     * One star's pixels: square, [size] each way, its middle at the middle of the square.
     *
     * Each pixel is unpremultiplied `0xAARRGGBB` with its alpha at the brightest channel, so drawn
     * over black with the light added it shows exactly the star's light, and drawn at a lower
     * alpha, as a twinkle or an old star is, it is the same star with less light.
     */
    class Raster(val size: Int, val pixels: IntArray)

    /**
     * [shape], worked out pixel by pixel ([lightAt], [toneMap]).
     *
     * The light is a function of distance alone, so it is worked out once along a line from the
     * middle, [STEPS_PER_PX] steps to a pixel, and read off that between steps; and a star is round,
     * so one eighth of the square is worked out and the rest is the same eighth turned and mirrored.
     * Both are exact for a round star, and between them a star is cheap enough to work out on the
     * frame that first needs it.
     */
    fun raster(shape: Shape): Raster {
        val size = Math.ceil((shape.reachPx * 2f).toDouble()).toInt() + 2
        val middle = size / 2f
        val fine = shape.beadPx * HEART_REACH + 1f

        val steps = Math.ceil((middle * SQRT2 + 2f) * STEPS_PER_PX.toDouble()).toInt() + 2
        val table = FloatArray(steps * 3)
        val sample = FloatArray(3)
        for (k in 0 until steps) {
            lightAt(shape, k / STEPS_PER_PX, sample)
            for (c in 0..2) table[k * 3 + c] = sample[c]
        }

        val half = (size + 1) / 2
        val eighth = IntArray(half * half)
        val sum = FloatArray(3)
        val shown = FloatArray(3)
        for (i in 0 until half) {
            for (j in 0..i) {
                val dx = i + 0.5f - middle
                val dy = j + 0.5f - middle
                sum.fill(0f)
                // Near the heart the light changes faster than a pixel, so it is sampled finely
                // there and averaged in linear light, as the eye averages it.
                val n = if (dx * dx + dy * dy < fine * fine) SAMPLES else 1
                for (b in 0 until n) {
                    for (a in 0 until n) {
                        val sx = dx + (a + 0.5f) / n - 0.5f
                        val sy = dy + (b + 0.5f) / n - 0.5f
                        val at = Math.sqrt((sx * sx + sy * sy).toDouble()).toFloat() * STEPS_PER_PX
                        val k = at.toInt().coerceAtMost(steps - 2)
                        val f = (at - k).coerceIn(0f, 1f)
                        for (c in 0..2) sum[c] += table[k * 3 + c] + (table[(k + 1) * 3 + c] - table[k * 3 + c]) * f
                    }
                }
                for (c in 0..2) sum[c] /= (n * n).toFloat()
                toneMap(sum, shown)
                eighth[i * half + j] = pack(shown)
            }
        }

        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            val j = minOf(y, size - 1 - y)
            for (x in 0 until size) {
                val i = minOf(x, size - 1 - x)
                pixels[y * size + x] = if (i >= j) eighth[i * half + j] else eighth[j * half + i]
            }
        }
        return Raster(size, pixels)
    }

    /** How finely the light is worked out along the line from the middle, in steps a pixel. */
    const val STEPS_PER_PX = 16f

    private const val SQRT2 = 1.4142135f

    /**
     * How bright the quiet bead is at [q], a fraction of its radius from the centre: `1` at the
     * centre, falling to `0.22` at the edge. A sphere's limb darkening, `1 - 0.56(1 - z) - 0.22(1 - z)²`
     * with `z` the height of its surface at [q].
     */
    fun limbAt(q: Float): Float {
        val clamped = q.coerceIn(0f, 1f)
        val z = Math.sqrt((1f - clamped * clamped).toDouble()).toFloat()
        val edge = 1f - z
        return 1f - 0.56f * edge - 0.22f * edge * edge
    }

    /** A packed `0xRRGGBB` colour in linear light, a channel each. */
    fun linear(rgb: Int): FloatArray = floatArrayOf(
        toLinear((rgb shr 16) and 0xFF),
        toLinear((rgb shr 8) and 0xFF),
        toLinear(rgb and 0xFF),
    )

    /** One channel of a packed `0xRRGGBB` colour, `0..255`, in linear light. */
    fun toLinear(channel: Int): Float =
        Math.pow((channel.coerceIn(0, 255) / 255.0), GAMMA).toFloat()

    private const val GAMMA = 2.2

    private fun smoothstep(from: Float, to: Float, x: Float): Float {
        if (!(to > from)) return if (x < from) 0f else 1f
        val t = ((x - from) / (to - from)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** Shown colour to an unpremultiplied pixel, its alpha at the brightest channel. */
    private fun pack(shown: FloatArray): Int {
        val r = Math.round(shown[0] * 255f).coerceIn(0, 255)
        val g = Math.round(shown[1] * 255f).coerceIn(0, 255)
        val b = Math.round(shown[2] * 255f).coerceIn(0, 255)
        val a = maxOf(r, g, b)
        if (a == 0) return 0
        fun unpremultiplied(v: Int) = Math.round(v * 255f / a).coerceIn(0, 255)
        return (a shl 24) or (unpremultiplied(r) shl 16) or (unpremultiplied(g) shl 8) or unpremultiplied(b)
    }
}
