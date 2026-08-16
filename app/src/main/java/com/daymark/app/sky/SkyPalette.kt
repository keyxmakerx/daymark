package com.daymark.app.sky

/**
 * Colour on the Sky: the night ground, the two ink values, and the one transform that makes a mood
 * ramp safe to draw on a near-black surface.
 *
 * Import-free, so the contrast arithmetic is checkable on a plain JVM. Colours are packed
 * `0xRRGGBB` integers rather than `androidx.compose.ui.graphics.Color`, because a `Color` is a
 * `ULong` in a Compose type and would drag the whole toolkit into a file whose job is arithmetic.
 * The renderer converts on the way out.
 *
 * ## The problem this file exists for
 *
 * `docs/SKY.md` §12.1 records the contrast floor as **unverified** — "nobody has measured this" —
 * and flags `MoodAwful #AE5747` on the night ground `#16150F` as the one to check first. It has now
 * been measured, by [contrastRatio], and it is **3.70:1**, under the 4.5:1 floor. The rest of the
 * shipped ramp measures:
 *
 * | Level | Colour | Ratio on `#16150F` | Equalised to 5.0:1 |
 * |---|---|---|---|
 * | 1 Awful | `#AE5747` | **3.70** | `#CE6855` |
 * | 2 Bad | `#C27C46` | 5.46 | `#B97642` |
 * | 3 Meh | `#C6A24E` | 7.56 | `#9F823D` |
 * | 4 Good | `#8FA268` | 6.56 | `#7B8C59` |
 * | 5 Rad | `#5E8A66` | 4.62 | `#62906B` |
 *
 * Two separate problems, and only one of them is the accessibility one:
 *
 *  1. Level 1 fails the floor outright.
 *  2. **The ramp is not level.** Level 3 is drawn at more than twice the contrast of level 1. On an
 *     ordinary chart that is a palette quirk. On this surface it is the thing §1 of the design is
 *     written to prevent: the worst mood is the faintest mark, so a month of hard days renders as a
 *     month of whispering stars and a month of ordinary days renders as a month of loud ones. That
 *     is a verdict drawn in light, and nobody would have decided it — it falls out of the hues.
 *
 * ## What was rejected
 *
 * **Lifting only the failures to 4.5:1.** This is the obvious fix, it is what §7.1 offers as one of
 * three options, and it solves (1) while leaving (2) exactly as it was — level 1 at 4.5 and level 3
 * at 7.8 is still a ranking, only a legal one. **Lifting everything by a fixed 18% toward white**,
 * as `ui/components/YearInStarsGrid.kt` does, has the same defect and additionally does nothing for
 * a custom palette that starts darker.
 *
 * ## What is built: equalisation, not flooring
 *
 * [equalised] moves a colour to an **exact** target contrast — every mood, and every override a
 * person supplies, is drawn at [STAR_CONTRAST_TARGET] against the night ground. Not "at least":
 * exactly. That is mechanism M4 ("equal presence across the ramp") made arithmetic instead of
 * aspirational, and it is the reason [SkyGlyph] can hold core radius and core alpha constant and
 * have that actually mean equal presence — constant alpha over unequal colours is not equal
 * presence, it only looks like a fair rule.
 *
 * The transform scales the colour in linear light, which holds the hue: a rust stays rust, a sage
 * stays sage, and the person's own palette is still recognisably their own. A colour that cannot
 * reach the target by scaling — a saturated blue tops out at 0.0722 relative luminance — is blended
 * toward the night ink instead, which desaturates it. That is a visible change to someone's chosen
 * colour and it is the lesser harm: the alternative is a mood they cannot see.
 */
object SkyPalette {

    // ---------------------------------------------------------------------------------------
    // The night surface. These three values already exist twice in the tree — as `Color`s in
    // `ui/components/YearInStarsGrid.kt:39-41` and as canvas ints in
    // `export/YearKeepsakeRenderer.kt:126-128` — and `docs/SKY.md` records that duplication as
    // unowned. This is a third copy, in the one package that can be unit-tested, and the renderer
    // under `ui/sky/` should read them from here rather than making a fourth.
    // ---------------------------------------------------------------------------------------

    /** The ground everything is drawn on. */
    const val NIGHT_BG = 0x16150F

    /** The Sky's brightest value. Every star with no mood attached is this colour, at full alpha. */
    const val NIGHT_INK = 0xEBE5D8

    /**
     * Gutter labels, leader lines, the project thread. Never a star core.
     *
     * Its name is misleading and the measurement says so: `#8E887A` is **5.18:1** on the night
     * ground, which is not a low-contrast value — it is a *desaturated* one, sitting essentially on
     * top of [STAR_CONTRAST_TARGET]. Two consequences, both for whoever writes the renderer. The
     * chrome is legible and clears [CONTRAST_FLOOR] without anyone fixing it; and the chrome cannot
     * recede by being fainter, because it is not fainter, so separation from the person's stars has
     * to come from stroke weight, size and form. Month labels drawn in this and left at that will
     * be exactly as present as the stars they sit beside.
     */
    const val NIGHT_FAINT = 0x8E887A

    // ---------------------------------------------------------------------------------------
    // Contrast.
    // ---------------------------------------------------------------------------------------

    /**
     * The floor from WCAG 2.1 for text and meaningful graphics. Stars are small, which is the case
     * where the floor matters most, so it is applied as a hard requirement and not as guidance.
     */
    const val CONTRAST_FLOOR = 4.5

    /**
     * What every data star is actually drawn at.
     *
     * Above the floor for two reasons. The obvious one is rounding: the transform lands on 8-bit
     * channels, so a target sitting exactly on 4.5 would round some colours to 4.49. The real one
     * is that the target has to be reachable *downward* as well as upward — level 3 measures 7.83
     * and equalisation darkens it — and a target close to the floor would darken the mid-ramp more
     * than the surface can carry. 5.0 clears the floor with room and moves every shipped colour by
     * less than a stop.
     */
    const val STAR_CONTRAST_TARGET = 5.0

    /** WCAG 2.1 relative luminance of a packed `0xRRGGBB` colour. */
    fun relativeLuminance(rgb: Int): Double {
        val r = linearise(((rgb shr 16) and 0xFF) / 255.0)
        val g = linearise(((rgb shr 8) and 0xFF) / 255.0)
        val b = linearise((rgb and 0xFF) / 255.0)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    /** WCAG 2.1 contrast ratio, `1.0..21.0`, symmetric in its arguments. */
    fun contrastRatio(a: Int, b: Int): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        val hi = if (la > lb) la else lb
        val lo = if (la > lb) lb else la
        return (hi + 0.05) / (lo + 0.05)
    }

    // ---------------------------------------------------------------------------------------
    // Equalisation.
    // ---------------------------------------------------------------------------------------

    /**
     * [rgb] moved to exactly [target] contrast against [ground], as closely as 8-bit channels allow.
     *
     * Hue is held by scaling the three linear-light channels by one factor, which is possible
     * because relative luminance is linear in those channels — so the factor is a division, not a
     * search. The search only appears in the case that scaling cannot reach the target because a
     * channel would exceed full: then the colour is blended toward [NIGHT_INK], whose own contrast
     * on the night ground is 14.6:1, so the blend always brackets the target.
     */
    fun equalised(
        rgb: Int,
        target: Double = STAR_CONTRAST_TARGET,
        ground: Int = NIGHT_BG,
    ): Int {
        val groundL = relativeLuminance(ground)
        val wanted = target * (groundL + 0.05) - 0.05
        if (wanted <= 0.0) return rgb
        if (wanted >= 1.0) return NIGHT_INK

        val r = linearise(((rgb shr 16) and 0xFF) / 255.0)
        val g = linearise(((rgb shr 8) and 0xFF) / 255.0)
        val b = linearise((rgb and 0xFF) / 255.0)
        val luminance = 0.2126 * r + 0.7152 * g + 0.0722 * b

        if (luminance > 0.0) {
            val k = wanted / luminance
            val brightest = maxOf(r, g, b) * k
            // Scaling is the hue-preserving path and is preferred whenever it fits inside the
            // gamut. `<= 1.0` and not `< 1.0`: a channel landing exactly on full is fine.
            if (brightest <= 1.0) return packLinear(r * k, g * k, b * k)
        }

        // Out of gamut: the colour is too dark or too saturated to carry this much light on its own
        // (a saturated blue cannot exceed 0.0722 however bright it is made). Desaturate toward the
        // sky's own ink until it reaches the target. Luminance is monotonic in the blend factor, so
        // bisection converges; 40 halvings is far below the precision of an 8-bit channel.
        var lo = 0.0
        var hi = 1.0
        repeat(40) {
            val mid = (lo + hi) / 2.0
            if (relativeLuminance(blend(rgb, NIGHT_INK, mid)) < wanted) lo = mid else hi = mid
        }
        return blend(rgb, NIGHT_INK, hi)
    }

    /** [equalised] over a whole ramp. The renderer passes the person's palette, never a copy. */
    fun equalisedRamp(
        ramp: IntArray,
        target: Double = STAR_CONTRAST_TARGET,
        ground: Int = NIGHT_BG,
    ): IntArray = IntArray(ramp.size) { equalised(ramp[it], target, ground) }

    // ---------------------------------------------------------------------------------------

    private fun linearise(channel: Double): Double =
        if (channel <= 0.03928) channel / 12.92 else Math.pow((channel + 0.055) / 1.055, 2.4)

    private fun delinearise(linear: Double): Double =
        if (linear <= 0.0031308) linear * 12.92 else 1.055 * Math.pow(linear, 1.0 / 2.4) - 0.055

    private fun packLinear(r: Double, g: Double, b: Double): Int =
        (channelOf(r) shl 16) or (channelOf(g) shl 8) or channelOf(b)

    private fun channelOf(linear: Double): Int {
        val v = Math.round(delinearise(clamp01(linear)) * 255.0).toInt()
        return if (v < 0) 0 else if (v > 255) 255 else v
    }

    private fun clamp01(v: Double): Double = if (v < 0.0) 0.0 else if (v > 1.0) 1.0 else v

    /** Straight-line blend in sRGB space. Used only to desaturate, never to dim. */
    private fun blend(from: Int, to: Int, t: Double): Int {
        val r = lerpChannel((from shr 16) and 0xFF, (to shr 16) and 0xFF, t)
        val g = lerpChannel((from shr 8) and 0xFF, (to shr 8) and 0xFF, t)
        val b = lerpChannel(from and 0xFF, to and 0xFF, t)
        return (r shl 16) or (g shl 8) or b
    }

    private fun lerpChannel(from: Int, to: Int, t: Double): Int =
        Math.round(from + (to - from) * t).toInt()
}
