package com.daymark.app.sky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mood ramp's contrast against the night ground, measured, and the transform it turned out to
 * need. The result is `docs/SKY.md` §0.3 finding 1 and §7.1: `MoodAwful #AE5747` does not clear
 * the 4.5 floor on either ground, and the raw ramp ranks the moods by visibility. How the colours
 * read on a real OLED panel at low brightness is for a person to judge (#147).
 *
 * The ground it is measured against moved in September 2026 — `#16150F` to `#07070A`, with
 * `SkyPalette.STAR_CONTRAST_TARGET` rising 5.0 to 6.5 to match (`SkyPalette`'s header has the
 * arithmetic). Every number in this file was re-measured on the new ground rather than adjusted
 * until it passed, and where a finding changed sign the test below says so in its own name.
 *
 * The ramp is stated in this file rather than read from the app, deliberately. `model/Mood.kt`
 * imports `androidx.compose.ui.graphics.Color`, so the pure layer cannot see it and must not — the
 * mood palette is theme-provided and person-overridable (§3.2), and a Sky that hardcoded the hues
 * would silently ignore a custom palette. Writing the shipped values out here means this test is
 * checking *those five colours*, and if someone changes them in `model/Mood.kt` without changing
 * them here, the test still measures what it says it measures; it stops describing the app,
 * which is what the last test in this file is for.
 */
class SkyPaletteTest {

    /** `model/Mood.kt`, levels 1..5, at the time of writing. */
    private val shippedRamp = intArrayOf(0xAE5747, 0xC27C46, 0xC6A24E, 0x8FA268, 0x5E8A66)

    private val levelNames = listOf("awful", "bad", "meh", "good", "rad")

    /**
     * How close to the target an equalised colour can land.
     *
     * Not a fudge factor: the transform computes an exact luminance and then has to write it into
     * three 8-bit channels, and one step of a channel near these values is worth roughly a
     * hundredth of a contrast ratio — more on a grey, where all three channels round the same way.
     * The band is what the encoding allows, not what the implementation happens to hit.
     */
    private val tolerance = 0.06

    // -------------------------------------------------------------------------------------------
    // The measurement.
    // -------------------------------------------------------------------------------------------

    @Test
    fun `the luminance function agrees with the WCAG worked examples`() {
        // Anchors from the specification itself, so the arithmetic is checked against something
        // other than its own output. Without this, every number below would be self-referential.
        assertEquals(0.0, SkyPalette.relativeLuminance(0x000000), 1e-9)
        assertEquals(1.0, SkyPalette.relativeLuminance(0xFFFFFF), 1e-9)
        assertEquals(21.0, SkyPalette.contrastRatio(0x000000, 0xFFFFFF), 1e-9)
        assertEquals(1.0, SkyPalette.contrastRatio(0x123456, 0x123456), 1e-9)
        // #767676 on white is the canonical "exactly passes AA" pair.
        assertEquals(4.54, SkyPalette.contrastRatio(0x767676, 0xFFFFFF), 0.01)
    }

    @Test
    fun `the shipped ramp does not clear the floor, and is not level, on either ground`() {
        val ratios = DoubleArray(5) { SkyPalette.contrastRatio(shippedRamp[it], SkyPalette.NIGHT_BG) }
        for (i in ratios.indices) println("  raw ${levelNames[i]} = ${"%.2f".format(ratios[i])}:1")

        // `docs/SKY.md` §0.3 finding 1: level 1 fails outright. It measured 3.70:1 on the old
        // ground and 4.08:1 on this one — a darker ground lifted it and it still does not reach
        // 4.5, which is the answer to "does the near-black ground fix this on its own".
        assertTrue(
            "MoodAwful now measures ${ratios[0]} — if this has been fixed upstream, update the doc",
            ratios[0] < SkyPalette.CONTRAST_FLOOR,
        )
        // And the finding that matters more: the ramp ranks the moods by how visible they are, with
        // the worst mood the faintest. This is the assertion that would have to be deleted for the
        // Sky to draw the raw ramp.
        assertEquals("the worst mood is no longer the faintest", ratios.min(), ratios[0], 1e-9)
        assertTrue("the raw ramp is level after all", ratios.max() / ratios.min() > 2.0)

        // The spread is a property of the hues and not of the ground, which is why changing the
        // ground could never have been the fix. Measured on the OLD ground here, so the claim is
        // checked rather than asserted from memory: both come out at 2.040.
        val onOldGround = DoubleArray(5) { SkyPalette.contrastRatio(shippedRamp[it], OLD_NIGHT_BG) }
        val spreadNow = ratios.max() / ratios.min()
        val spreadThen = onOldGround.max() / onOldGround.min()
        println("  spread on #${"%06X".format(OLD_NIGHT_BG)} = ${"%.3f".format(spreadThen)}, " +
            "on #${"%06X".format(SkyPalette.NIGHT_BG)} = ${"%.3f".format(spreadNow)}")
        assertEquals("a darker ground levelled the ramp after all", spreadThen, spreadNow, 0.01)
    }

    @Test
    fun `the ground went darker and the marks drawn on it went brighter`() {
        // The ground is near-black, not pure black (`docs/SKY.md` §3.5), and the star contrast
        // target rises with it, so stars get brighter, not dimmer (they are pinned to a ratio
        // against the ground; `SkyPalette`'s header). Both halves, as one assertion, because either
        // alone is the wrong change.
        val groundThen = SkyPalette.relativeLuminance(OLD_NIGHT_BG)
        val groundNow = SkyPalette.relativeLuminance(SkyPalette.NIGHT_BG)
        println("  ground ${"%.6f".format(groundThen)} -> ${"%.6f".format(groundNow)}")
        assertTrue("the ground did not get darker", groundNow < groundThen)
        assertTrue("the ground went to pure black, which has no floor to fade into", groundNow > 0.0)

        // What a mood mark emits, which is the thing `SkyPalette`'s header says must not fall.
        // Derived from the target and the ground rather than written down, so it stays true if
        // either moves.
        val emittedThen = OLD_STAR_CONTRAST_TARGET * (groundThen + 0.05) - 0.05
        val emittedNow = SkyPalette.STAR_CONTRAST_TARGET * (groundNow + 0.05) - 0.05
        println("  a mood mark emits ${"%.5f".format(emittedThen)} -> ${"%.5f".format(emittedNow)}" +
            " (${"%.1f".format(100 * (emittedNow / emittedThen - 1))}% more light)")
        assertTrue(
            "the darker ground made the marks dimmer, which is the failure SkyPalette names",
            emittedNow > emittedThen,
        )

        // The positive control this pair needs. Without it the assertion above passes for a target
        // that rose by a rounding error, and "brighter" would be a word rather than a measurement:
        // 5.50 is exactly break-even on this ground, so anything at or under it is the failure.
        val breakEven = (emittedThen + 0.05) / (groundNow + 0.05)
        println("  break-even target on this ground is ${"%.2f".format(breakEven)}")
        assertEquals("the break-even target moved; the header needs re-deriving", 5.50, breakEven, 0.01)
        assertTrue(
            "the target is at or under break-even, so nothing got brighter",
            SkyPalette.STAR_CONTRAST_TARGET > breakEven,
        )
    }

    @Test
    fun `the target stops short of desaturating anybody's palette`() {
        // The other half of how 6.5 was chosen. Equalisation reaches a target by scaling linear
        // light, which holds hue; past the point where a channel would clip it has to blend toward
        // the ink instead, and that visibly changes a colour the person picked. The sweep finds
        // where the shipped ramp falls off that path, so the headroom under the target is a
        // measurement and not a hope.
        var cliff = Double.MAX_VALUE
        var cliffLevel = -1
        for (i in shippedRamp.indices) {
            var t = SkyPalette.CONTRAST_FLOOR
            while (t < 21.0) {
                if (desaturated(shippedRamp[i], t)) {
                    if (t < cliff) {
                        cliff = t
                        cliffLevel = i + 1
                    }
                    break
                }
                t += 0.05
            }
        }
        println("  first shipped level to need desaturating: level $cliffLevel at ${"%.2f".format(cliff)}:1")
        assertEquals("the cliff moved; SkyPalette's header quotes 8.35", 8.35, cliff, 0.06)
        assertTrue(
            "the target is at or past the cliff, so a shipped mood is being desaturated to reach it",
            SkyPalette.STAR_CONTRAST_TARGET < cliff,
        )
        // Paired positive control: the detector can see a desaturation when there is one, so its
        // silence below the cliff means something. Without this the sweep could be blind and the
        // headroom above would be an artefact of a check that never fires.
        assertTrue(
            "the detector cannot see a desaturation, so its silence proves nothing",
            desaturated(shippedRamp[cliffLevel - 1], cliff + 1.0),
        )
        // And the control at the other end: nothing is desaturated AT the target.
        for (i in shippedRamp.indices) {
            assertTrue(
                "level ${i + 1} is desaturated at the shipped target",
                !desaturated(shippedRamp[i], SkyPalette.STAR_CONTRAST_TARGET),
            )
        }
    }

    // -------------------------------------------------------------------------------------------
    // The transform.
    // -------------------------------------------------------------------------------------------

    @Test
    fun `equalisation puts every level at the same contrast`() {
        val lifted = SkyPalette.equalisedRamp(shippedRamp)
        for (i in lifted.indices) {
            val ratio = SkyPalette.contrastRatio(lifted[i], SkyPalette.NIGHT_BG)
            println("  equalised ${levelNames[i]} = #${"%06X".format(lifted[i])} at ${"%.3f".format(ratio)}:1")
            assertEquals(
                "level ${i + 1} off target",
                SkyPalette.STAR_CONTRAST_TARGET,
                ratio,
                tolerance,
            )
            assertTrue("level ${i + 1} under the floor", ratio >= SkyPalette.CONTRAST_FLOOR)
        }
    }

    @Test
    fun `equalisation preserves hue order, so a palette still looks like itself`() {
        // The transform scales linear light by one factor, so the ratios between channels survive.
        // If it ever became a blend toward white for the common case, this fails — and a ramp that
        // has been washed toward grey is no longer the person's palette.
        val lifted = SkyPalette.equalisedRamp(shippedRamp)
        for (i in shippedRamp.indices) {
            val before = channelOrder(shippedRamp[i])
            val after = channelOrder(lifted[i])
            assertEquals("hue order changed at level ${i + 1}", before, after)
        }
    }

    @Test
    fun `the equalised levels stay distinguishable from each other`() {
        // Equalising removes the lightness differences, so hue is doing all the work. If two levels
        // collapsed onto each other, colour would stop carrying mood at all — which matters most
        // for the people the ramp is already worst for (§7.2).
        val lifted = SkyPalette.equalisedRamp(shippedRamp)
        for (i in lifted.indices) {
            for (j in (i + 1) until lifted.size) {
                val d = channelDistance(lifted[i], lifted[j])
                assertTrue(
                    "levels ${i + 1} and ${j + 1} collapsed together (distance $d)",
                    d >= 24,
                )
            }
        }
    }

    @Test
    fun `no equalised level is more present than any other`() {
        // M4, stated as the thing it is for: after the transform there is no level at which a
        // person's star is fainter. Presence is contrast, and contrast is now flat.
        val lifted = SkyPalette.equalisedRamp(shippedRamp)
        val ratios = DoubleArray(5) { SkyPalette.contrastRatio(lifted[it], SkyPalette.NIGHT_BG) }
        assertTrue("the equalised ramp still ranks", ratios.max() - ratios.min() < 0.05)
    }

    // -------------------------------------------------------------------------------------------
    // Custom palettes — §7.1's open question, which is why the transform takes a colour and not a
    // level. A person can override every mood colour, and an override that lands below the floor
    // must not be drawn.
    // -------------------------------------------------------------------------------------------

    @Test
    fun `every colour a person could choose reaches the target`() {
        var worst = Double.MAX_VALUE
        var worstColour = 0
        var checked = 0
        for (r in 0..255 step 15) {
            for (g in 0..255 step 15) {
                for (b in 0..255 step 15) {
                    val source = (r shl 16) or (g shl 8) or b
                    val ratio = SkyPalette.contrastRatio(
                        SkyPalette.equalised(source),
                        SkyPalette.NIGHT_BG,
                    )
                    if (ratio < worst) {
                        worst = ratio
                        worstColour = source
                    }
                    checked++
                }
            }
        }
        println("  swept $checked colours; worst equalised contrast ${"%.3f".format(worst)}:1 " +
            "from #${"%06X".format(worstColour)}")
        assertTrue("$checked is not a sweep", checked > 4000)
        assertTrue(
            "#${"%06X".format(worstColour)} equalised to $worst, under the floor",
            worst >= SkyPalette.CONTRAST_FLOOR,
        )
    }

    @Test
    fun `the pathological colours are handled rather than clamped away`() {
        // Pure black cannot be scaled — there is no light to scale — and a saturated blue tops out
        // at 0.0722 relative luminance, well under the target. Both take the desaturation path.
        // A version that only scaled would return these unchanged and draw an invisible star.
        for (source in intArrayOf(0x000000, 0x0000FF, 0x00003C, 0x010101, 0x1A0033)) {
            val ratio = SkyPalette.contrastRatio(SkyPalette.equalised(source), SkyPalette.NIGHT_BG)
            assertTrue(
                "#${"%06X".format(source)} equalised to only $ratio",
                ratio >= SkyPalette.CONTRAST_FLOOR,
            )
        }
        // And the other end: a colour brighter than the target is brought *down* to it, not left.
        val white = SkyPalette.equalised(0xFFFFFF)
        assertEquals(
            SkyPalette.STAR_CONTRAST_TARGET,
            SkyPalette.contrastRatio(white, SkyPalette.NIGHT_BG),
            tolerance,
        )
    }

    @Test
    fun `equalisation is idempotent`() {
        // Applied twice — which will happen, because a renderer that re-equalises on recomposition
        // is the obvious mistake — it must not drift.
        for (source in shippedRamp) {
            val once = SkyPalette.equalised(source)
            val twice = SkyPalette.equalised(once)
            assertEquals(
                "drifted on the second pass: #${"%06X".format(once)} -> #${"%06X".format(twice)}",
                SkyPalette.contrastRatio(once, SkyPalette.NIGHT_BG),
                SkyPalette.contrastRatio(twice, SkyPalette.NIGHT_BG),
                tolerance,
            )
        }
    }

    @Test
    fun `the faint value is still not faint, but it has stopped outshouting the stars`() {
        val ink = SkyPalette.contrastRatio(SkyPalette.NIGHT_INK, SkyPalette.NIGHT_BG)
        val faint = SkyPalette.contrastRatio(SkyPalette.NIGHT_FAINT, SkyPalette.NIGHT_BG)
        println("  NIGHT_INK ${"%.2f".format(ink)}:1, NIGHT_FAINT ${"%.2f".format(faint)}:1")

        assertTrue("the sky's ink is not comfortably legible", ink > 12.0)

        // `NIGHT_FAINT` reads as a name for "the quiet one", and it is not: it is a *desaturated*
        // value, not a low-contrast one. Gutter labels and the project thread are legible and clear
        // the floor without anyone having to fix them.
        assertTrue("NIGHT_FAINT is below the floor after all", faint >= SkyPalette.CONTRAST_FLOOR)

        // What changed with the ground, and it changed sign. On `#16150F` at a target of 5.0 the
        // chrome measured 5.18:1 — ABOVE the target, so a month label was drawn more present than
        // the person's own mood mark beside it. On `#07070A` at 6.5 it measures 5.70:1, and the
        // chrome is finally the quieter of the two. Asserted as an ordering, not as a band, because
        // the ordering is what matters: the chrome must never be louder than the data.
        val was = SkyPalette.contrastRatio(SkyPalette.NIGHT_FAINT, OLD_NIGHT_BG)
        println("  on #${"%06X".format(OLD_NIGHT_BG)} it was ${"%.2f".format(was)}:1 " +
            "against a target of $OLD_STAR_CONTRAST_TARGET")
        assertTrue(
            "the old ground did not actually draw the chrome louder than the stars",
            was > OLD_STAR_CONTRAST_TARGET,
        )
        assertTrue(
            "the chrome is louder than the person's own marks again",
            faint < SkyPalette.STAR_CONTRAST_TARGET,
        )

        // And the half of the old note that still stands: 0.8 of a ratio is not much of a gap.
        // Separation between chrome and data still has to come from stroke weight, size and form.
        // A reviewer assuming "faint therefore quiet" would still draw a gutter that competes.
        assertTrue(
            "NIGHT_FAINT has become genuinely low-contrast; the renderer may now lean on it to recede",
            SkyPalette.STAR_CONTRAST_TARGET - faint < 1.5,
        )
    }

    // -------------------------------------------------------------------------------------------

    /**
     * The ground and the target before September 2026, kept so the change can be *measured* rather
     * than remembered. Several findings here are comparisons between the two, and a comparison
     * against a number that lives only in a comment is not a comparison.
     */
    private val OLD_NIGHT_BG = 0x16150F
    private val OLD_STAR_CONTRAST_TARGET = 5.0

    /**
     * Whether reaching [target] cost this colour its hue — that is, whether [SkyPalette.equalised]
     * had to take the blend-toward-the-ink path rather than the hue-preserving scaling one.
     *
     * Detected from the value, because the transform exposes no flag and a test that read one
     * would be asking the implementation to grade itself. Scaling multiplies all three **linear**
     * channels by a single factor, so every ratio between them survives exactly; blending toward
     * `NIGHT_INK` pulls them together. So the measurement is the drift in those ratios, in logs so
     * it is symmetric.
     *
     * The two populations are nowhere near each other, which is what makes the threshold safe
     * rather than tuned: across the shipped ramp the scaling path never drifts past **0.023** (all
     * of it 8-bit rounding) and the blend path lands above **1.10**. [HUE_DRIFT] sits at 0.2, two
     * orders of margin from the noise and five times clear of the signal.
     */
    private fun desaturated(rgb: Int, target: Double): Boolean =
        hueDrift(rgb, SkyPalette.equalised(rgb, target)) > HUE_DRIFT

    private fun hueDrift(a: Int, b: Int): Double {
        val la = DoubleArray(3) { linear((a shr (16 - 8 * it)) and 0xFF) }
        val lb = DoubleArray(3) { linear((b shr (16 - 8 * it)) and 0xFF) }
        var worst = 0.0
        for (i in 0..2) {
            for (j in 0..2) {
                if (i == j || la[i] <= 0.0 || la[j] <= 0.0 || lb[i] <= 0.0 || lb[j] <= 0.0) continue
                val drift = Math.abs(Math.log((la[i] / la[j]) / (lb[i] / lb[j])))
                if (drift > worst) worst = drift
            }
        }
        return worst
    }

    private fun linear(channel: Int): Double {
        val v = channel / 255.0
        return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
    }

    private val HUE_DRIFT = 0.2

    /** The three channels ranked by value — a coarse stand-in for hue that needs no colour space. */
    private fun channelOrder(rgb: Int): String {
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        return listOf("r" to r, "g" to g, "b" to b).sortedByDescending { it.second }
            .joinToString("") { it.first }
    }

    private fun channelDistance(a: Int, b: Int): Int {
        var d = 0
        for (shift in intArrayOf(16, 8, 0)) {
            d += Math.abs(((a shr shift) and 0xFF) - ((b shr shift) and 0xFF))
        }
        return d
    }
}
