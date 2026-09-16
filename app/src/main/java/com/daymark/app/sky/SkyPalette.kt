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
 * and flags `MoodAwful #AE5747` on the night ground as the one to check first. It has been
 * measured, by [contrastRatio], and it fails on both grounds this file has had. The shipped ramp
 * measures:
 *
 * | Level | Colour | On `#16150F` (the old ground) | On `#07070A` (now) | Equalised to 6.5:1 |
 * |---|---|---|---|---|
 * | 1 Awful | `#AE5747` | **3.70** | **4.08** | `#E1725E` |
 * | 2 Bad | `#C27C46` | 5.46 | 6.00 | `#CA8249` |
 * | 3 Meh | `#C6A24E` | 7.56 | 8.32 | `#AE8E44` |
 * | 4 Good | `#8FA268` | 6.56 | 7.22 | `#879962` |
 * | 5 Rad | `#5E8A66` | 4.62 | 5.08 | `#6C9E75` |
 *
 * The old column is kept because it is the record §12.1 asked for, and because it is the evidence
 * that the second finding below is a property of the *hues* and not of the ground: the spread
 * between the loudest and the faintest mood measures **2.040** on `#16150F` and **2.040** on
 * `#07070A`. A darker ground lifts every ratio by the same factor; it cannot level a ramp.
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
 *
 * ## September 2026: stars stopped using this, and it stayed anyway
 *
 * `docs/PLAN_2026-09-SKY-PEOPLE-TIMING.md` §1 moved a star's colour off the mood ramp and onto its
 * age ([SkyAge]), so **a drawn star no longer passes through [equalised] at all**. The obvious
 * next move is to delete the equalisation machinery, and it would be a mistake for two separate
 * reasons.
 *
 * **It is still on screen.** The mood ramp did not leave the Sky, it left the *star*: §1 keeps the
 * mood word and its colour *"on the sheet when a star is tapped and on every row of the list"*.
 * Those are small coloured marks on the same near-black ground, which is the case [equalised] was
 * written for, and a list dot is exactly where an un-equalised ramp would put the hardest mood at
 * 4.08:1 and the ordinary one at 8.32:1 — the ranking-by-visibility this file exists to remove,
 * moved from the sky into the list beside it. `ui/sky/SkyScreen.kt` and
 * `ui/sky/SkyPresentation.kt` both still call it, and the quiet sky's higher target
 * (`SkyPresentation.HIGH_CONTRAST_TARGET`) is built on it.
 *
 * **The measurement is the record.** The table above is the only place in the tree where the
 * shipped ramp's contrast against the night ground is written down, and `docs/SKY.md` §12.1 asked
 * for exactly that. Deleting the transform would delete the finding with it.
 *
 * ## September 2026, second pass: the ground moved, and every number here moved with it
 *
 * §1 opens with *"Ground goes near-black, not pure black. The star contrast target rises with it,
 * so stars get brighter, not dimmer (they are pinned to a ratio against the ground)"*. That is two
 * changes and they are only correct together, so they were made together, with the halo's radial
 * fade ([SkyGlyph.HALO_STOP_WEIGHT]) in the same pass. What follows is what was measured, not what
 * was intended.
 *
 * **The ground: `#16150F` to `#07070A`.** Relative luminance **0.007414 to 0.002190** — the ground
 * now carries 3.4 times less light. The old value was a warm near-black, and a warm ground is the
 * wrong ground for this surface twice over. A glow fading out over it does not fade to *nothing*,
 * it fades to a warm smudge, which is the flat translucent disc §1 is replacing, drawn softly. And
 * a star's colour is now its age — blue-white through gold to a deep red — so a warm ground sits
 * underneath the red end of that ramp and takes the redshift's last stop away. `#07070A` is the
 * prototype's ground (`docs/prototypes/your-sky.html`), which is the look that was signed off.
 *
 * **The target: 5.0 to 6.5.** Chosen by two measurements rather than by eye.
 *
 *  - **Where "brighter" starts.** A mood mark at 5.0 on the old ground emits 0.23707 relative
 *    luminance. On `#07070A`, the target that emits *exactly that* is **5.50** (0.23705). So any
 *    target at or under 5.5 is the failure §1 names — a darker ground and dimmer marks. 6.5 emits
 *    0.28924, which is **22% more light** than the old ground carried.
 *  - **Where it has to stop.** Above the target, [equalised] can no longer reach by scaling and
 *    falls to blending toward [NIGHT_INK], which desaturates the person's own colour. Swept in
 *    0.05 steps, the first shipped mood to fall off the hue-preserving path does so at **8.35**
 *    (level 1, the darkest; level 2 follows at 10.35). 6.5 leaves most of a stop of headroom
 *    under that cliff, so a custom palette a shade darker than the shipped one still keeps
 *    its hue.
 *
 * **What the move fixed by accident, and it is worth knowing.** [NIGHT_FAINT] measures 5.70:1 on
 * the new ground against a target of 6.5, where it measured 5.18:1 against a target of 5.0. The
 * chrome has gone from sitting *on top of* the person's marks to sitting a clear 0.8 of a ratio
 * below them. The warning below is therefore softer than it was — but it is not gone, and stroke
 * weight and form are still doing most of the work.
 *
 * **What it did not fix, measured and stated rather than hoped over.** A star is drawn *additively*
 * now, and alpha compositing happens in encoded sRGB, so the same glow adds slightly less linear
 * light over a darker ground. The dimmest thing the surface can produce — an oldest star's core at
 * [SkyAge.FADE_FLOOR], at the bottom of its breathe — measures **1.53:1** on `#07070A` where it
 * measured 1.70:1 on `#16150F`. Both are far under [CONTRAST_FLOOR] and both are meant to be: §1
 * asks for old stars to *"recede but never vanish"*, and that is a decorative floor, not text. It
 * is recorded because "the ground got darker so everything got brighter" is the intuition, and for
 * added light it is false.
 */
object SkyPalette {

    // ---------------------------------------------------------------------------------------
    // The night surface. These three values already exist once elsewhere in the tree, as `Color`s
    // in `ui/components/YearInStarsGrid.kt:39-41`, and `docs/SKY.md` records that duplication as
    // unowned. (There was a third copy, as canvas ints in the year keepsake renderer; that file is
    // gone with the export it served.) This is the second copy, in the one package that can be
    // unit-tested, and the renderer under `ui/sky/` reads them from here rather than making a
    // third.
    //
    // AND THEY HAVE NOW DIVERGED. `YearInStarsGrid`'s `NightBg` is still `#16150F`; the Sky's is
    // `#07070A`. That is a real inconsistency between two surfaces and it is left standing rather
    // than fixed quietly: `YearInStarsGrid` is a different component with its own design, its own
    // star sizes and its own docstring saying that "the amount of twinkle itself reads as how a
    // stretch of life went" — which is the reading this file's header exists to refuse. Moving its
    // ground to match would make the two look like one system while one of them still ranks a
    // person's months by mood. The duplication was already recorded as unowned; what is new is that
    // it is now visible, which is the better state for something nobody has decided about.
    // ---------------------------------------------------------------------------------------

    /**
     * The ground everything is drawn on: near-black, and not pure black.
     *
     * Not pure black on purpose. `#000000` gives an additive glow nothing to fade *into*, so the
     * outer edge of every halo terminates on a hard boundary between "some light" and "none", and
     * an OLED panel switches pixels off under it, which is visible as a texture the sky does not
     * have. `#07070A` keeps a floor of 0.002190 relative luminance under the whole surface.
     *
     * See this file's header for the measurement that came with the move off `#16150F`.
     */
    const val NIGHT_BG = 0x07070A

    /**
     * The Sky's brightest value: chrome, the decorative field, the selection ring.
     *
     * It used to be the colour of every star with no mood attached. Stars now take their colour
     * from [SkyAge] instead, so this is no longer a star colour — but it is still the value
     * [equalised] blends toward when a colour is too dark or too saturated to carry the target
     * contrast on its own, and it is still the sky's ceiling.
     */
    const val NIGHT_INK = 0xEBE5D8

    /**
     * Gutter labels, leader lines, the project thread. Never a star core.
     *
     * Its name is misleading and the measurement says so: `#8E887A` is **5.70:1** on the night
     * ground, which is not a low-contrast value — it is a *desaturated* one. It used to be worse:
     * on the old `#16150F` it measured 5.18:1 against a [STAR_CONTRAST_TARGET] of 5.0, so the
     * chrome was drawn at *more* contrast than the person's own mood marks. The darker ground and
     * the higher target have pulled the two apart — 5.70 against 6.5 — and the chrome is now
     * genuinely the quieter of the two.
     *
     * It is still only 0.8 of a ratio quieter, so the consequence for the renderer stands. The
     * chrome cannot recede by being faint, because it is not faint; separation from the person's
     * stars has to come from stroke weight, size and form. Month labels drawn in this and left at
     * that will sit very nearly as present as the stars beside them.
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
     * What every mood mark the person is shown is drawn at — the list dot, the sheet, and until
     * September 2026 the star itself. See this file's header for what moved and what did not.
     *
     * It was 5.0 on the old `#16150F` ground. It is 6.5 on `#07070A`, and the two measurements
     * that fix it there are in this file's header: 5.50 is where a mark emits exactly the light it
     * used to, and 8.35 is where the darkest shipped mood stops being reachable without
     * desaturating it. 6.5 sits above the first with 22% more light and below the second with
     * most of a stop to spare.
     *
     * Well above [CONTRAST_FLOOR] for two further reasons. The obvious one is rounding: the
     * transform lands on 8-bit channels, so a target sitting exactly on 4.5 would round some
     * colours to 4.49. The real one is that the target has to be reachable *downward* as well as
     * upward — level 3 measures 8.32 on this ground and equalisation darkens it — and a target
     * close to the floor would darken the mid-ramp more than the surface can carry.
     */
    const val STAR_CONTRAST_TARGET = 6.5

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
     * on the night ground is 16.0:1, so the blend always brackets the target.
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
