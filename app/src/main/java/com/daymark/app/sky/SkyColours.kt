package com.daymark.app.sky

import kotlin.math.abs

/**
 * A sky's own colours: one palette per sky, from its seed and nothing else (`DECISIONS.md` §D11).
 *
 * Every object that is not a star takes its colours from here: the deep tones of the space, the
 * gas of a nebula, a supernova's shell, a black hole's disk and a white hole's light. A star's own
 * colour is never here. Age decides it, the same way in every sky ([SkyGlyph.starTint]), so what a
 * star's colour means never changes from one person to another.
 *
 * ## Blind to data
 *
 * The palette is a function of the seed and the person's colour choice alone. No mood, no count
 * and no date reaches it, the discipline the old decorative field kept: a sky that turned a colour
 * because of how someone's month went would be a verdict drawn in paint.
 *
 * ## No greens
 *
 * Hues from [GREEN_FROM]° to [GREEN_TO]°, olive and teal included, are never used, under the rule
 * the whole product keeps. A hue that lands there is turned on round the wheel by 151°, out the
 * other side, so the scheme's spacing survives. Every hue the look uses goes through [okHue],
 * offsets included.
 *
 * ## Dark enough for every star
 *
 * Every tone of the space is near black ([BACKGROUND_LIGHTNESS]), so the stars keep the contrast
 * [SkyPalette] measures them against the night ground: the space is a colour, never a light.
 *
 * Import-free, like the rest of `sky/`.
 */
object SkyColours {

    /** The first hue skipped, in degrees. */
    const val GREEN_FROM = 45f

    /** The last hue skipped, in degrees. */
    const val GREEN_TO = 195f

    /** How many days from first opening the sky the colours can be changed, then settle. */
    const val CHANGEABLE_DAYS = 30L

    /** The lightest any tone of the space may be, in HSL lightness. */
    const val BACKGROUND_LIGHTNESS = 0.11f

    /**
     * One sky's colours, as packed `0xRRGGBB`.
     *
     * @property space the deep tones of the space, darkest first, then an accent.
     * @property nebulae three gradients of three colours each, one per nebula look.
     * @property supernova the shell, then two warm tones for the heart.
     * @property disk a black hole's disk: the hot inner edge, then the warm outer part.
     * @property whiteHole a white hole's light.
     */
    class Look(
        val space: IntArray,
        val nebulae: Array<IntArray>,
        val supernova: IntArray,
        val disk: IntArray,
        val whiteHole: Int,
    )

    /**
     * The look of the sky grown from [seed], on the person's [choice] of colours: 0 for the sky's
     * own, and each "Try other colours" one more. The same pair is the same look on every device.
     */
    fun of(seed: Long, choice: Int): Look {
        val random = SkyStream(SkyRandom.mix(seed xor LOOK_SALT, choice.toLong()))
        val h0 = random.nextUnit() * 360f
        val scheme = SCHEMES[(random.nextUnit() * SCHEMES.size).toInt().coerceAtMost(SCHEMES.size - 1)]
        fun hue(i: Int): Float = okHue(h0 + scheme[i % 3] + (random.nextUnit() - 0.5f) * 30f)

        val nebulae = Array(3) { i ->
            intArrayOf(
                hsl(hue(i), 0.55f + 0.35f * random.nextUnit(), 0.48f + 0.14f * random.nextUnit()),
                hsl(hue(i + 1), 0.5f + 0.4f * random.nextUnit(), 0.5f + 0.14f * random.nextUnit()),
                hsl(hue(i + 2), 0.45f + 0.4f * random.nextUnit(), 0.55f + 0.15f * random.nextUnit()),
            )
        }
        // Mostly blues and violets for the space, now and then a warm one, never a green.
        val spaceHue = okHue(205f + random.nextUnit() * 130f + if (random.nextUnit() < 0.18f) 160f else 0f)
        val spaceSaturation = 0.45f + 0.35f * random.nextUnit()
        val warmBase = if (random.nextUnit() < 0.75f) -20f + random.nextUnit() * 60f else 300f + random.nextUnit() * 40f
        val warm = okHue(warmBase)

        return Look(
            space = intArrayOf(
                hsl(spaceHue, spaceSaturation, 0.03f),
                hsl(spaceHue, spaceSaturation, 0.065f),
                hsl(okHue(spaceHue + 10f), spaceSaturation * 0.9f, BACKGROUND_LIGHTNESS),
                hsl(hue(1), 0.4f, 0.3f),
            ),
            nebulae = nebulae,
            supernova = intArrayOf(
                hsl(hue(1), 0.6f, 0.6f),
                hsl(warm, 0.85f, 0.6f),
                hsl(okHue(warm + 30f), 0.85f, 0.72f),
            ),
            disk = intArrayOf(hsl(okHue(warm + 15f), 0.5f, 0.9f), hsl(warm, 0.9f, 0.5f)),
            whiteHole = hsl(hue(0), 0.35f, 0.9f),
        )
    }

    /**
     * One soft glow of colour in the space behind the stars.
     *
     * @property x where its middle is, in the sky's units, and [y].
     * @property radius how far it reaches, in the sky's units.
     * @property tone which of [Look.space] it is drawn in: 1, 2, or 3 for the accent.
     */
    class Glow(val x: Float, val y: Float, val radius: Float, val tone: Int)

    /**
     * The space's three glows for the sky grown from [seed], [height] tall. Anchored to the sky,
     * so they move with it as it is panned, and blind to data like everything else here: where
     * they sit says nothing about the stars near them.
     */
    fun glows(seed: Long, height: Float): List<Glow> = List(GLOWS) { k ->
        val h = SkyRandom.mix(seed xor GLOW_SALT, k.toLong())
        Glow(
            x = 0.15f + 0.7f * SkyRandom.unit(h),
            y = height * (0.15f + 0.7f * SkyRandom.unit(SkyRandom.mix(h))),
            radius = 0.35f + 0.25f * SkyRandom.unit(SkyRandom.mix(h, 2L)),
            tone = k + 1,
        )
    }

    private const val GLOWS = 3
    private const val GLOW_SALT = 0x6_10_75L

    /** [hue] on the wheel, moved out of the greens. */
    fun okHue(hue: Float): Float {
        val h = ((hue % 360f) + 360f) % 360f
        return if (h in GREEN_FROM..GREEN_TO) h + 151f else h
    }

    /** HSL to packed sRGB `0xRRGGBB`. [hue] in degrees, the others 0..1. */
    fun hsl(hue: Float, saturation: Float, lightness: Float): Int {
        val h = ((hue % 360f) + 360f) % 360f
        val s = saturation.coerceIn(0f, 1f)
        val l = lightness.coerceIn(0f, 1f)
        val a = s * minOf(l, 1f - l)
        fun channel(n: Int): Int {
            val k = (n + h / 30f) % 12f
            val v = l - a * maxOf(-1f, minOf(k - 3f, 9f - k, 1f))
            return (v * 255f + 0.5f).toInt().coerceIn(0, 255)
        }
        return (channel(0) shl 16) or (channel(8) shl 8) or channel(4)
    }

    /** The hue of packed `0xRRGGBB` in degrees, or null for a grey, which has none. */
    fun hueOf(rgb: Int): Float? {
        val r = ((rgb shr 16) and 0xFF) / 255f
        val g = ((rgb shr 8) and 0xFF) / 255f
        val b = (rgb and 0xFF) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val chroma = max - min
        if (chroma < GREY_CHROMA) return null
        val h = when (max) {
            r -> 60f * (((g - b) / chroma) % 6f)
            g -> 60f * ((b - r) / chroma + 2f)
            else -> 60f * ((r - g) / chroma + 4f)
        }
        return if (h < 0f) h + 360f else if (abs(h - 360f) < 1e-4f) 0f else h
    }

    /** Below this chroma a colour is a grey and its hue is noise. */
    private const val GREY_CHROMA = 0.04f

    /** The four schemes: close hues, opposites, three spread apart, and split. */
    private val SCHEMES = arrayOf(
        floatArrayOf(0f, 25f, 50f),
        floatArrayOf(0f, 180f, 25f),
        floatArrayOf(0f, 120f, 240f),
        floatArrayOf(0f, 150f, 210f),
    )

    /** Keeps the look's draws apart from every other use of the seed. */
    private const val LOOK_SALT = 0x10C0_C0105L
}
