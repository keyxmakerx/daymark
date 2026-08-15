package com.daymark.app.sky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The measurement `docs/SKY.md` §12.1 says nobody has taken, and the transform it turned out to
 * need.
 *
 * §12.1: *"The contrast floor is unverified. `MoodAwful #AE5747` and `MoodBad #C27C46` against
 * `#16150F` need actual measurement. The shipped 18% lerp-toward-white suggests the raw ramp does
 * not clear it. This blocks M4 and P1."* It is measured here, and the suspicion was right.
 *
 * The ramp is stated in this file rather than read from the app, deliberately. `model/Mood.kt`
 * imports `androidx.compose.ui.graphics.Color`, so the pure layer cannot see it and must not — the
 * mood palette is theme-provided and person-overridable (§3.2), and a Sky that hardcoded the hues
 * would silently ignore a custom palette. Writing the shipped values out here means this test is
 * checking *those five colours*, and if someone changes them in `model/Mood.kt` without changing
 * them here, the test still measures what it says it measures — it just stops describing the app,
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
    fun `the shipped ramp does not clear the floor, and is not level`() {
        val ratios = DoubleArray(5) { SkyPalette.contrastRatio(shippedRamp[it], SkyPalette.NIGHT_BG) }
        for (i in ratios.indices) println("  raw ${levelNames[i]} = ${"%.2f".format(ratios[i])}:1")

        // The finding §12.1 asked for: level 1 fails outright.
        assertTrue(
            "MoodAwful now measures ${ratios[0]} — if this has been fixed upstream, update the doc",
            ratios[0] < SkyPalette.CONTRAST_FLOOR,
        )
        // And the finding that matters more: the ramp ranks the moods by how visible they are, with
        // the worst mood the faintest. This is the assertion that would have to be deleted for the
        // Sky to draw the raw ramp.
        assertTrue("the raw ramp is level after all", ratios.max() / ratios.min() > 2.0)
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
    fun `the faint value is not faint, and the chrome cannot rely on contrast to recede`() {
        val ink = SkyPalette.contrastRatio(SkyPalette.NIGHT_INK, SkyPalette.NIGHT_BG)
        val faint = SkyPalette.contrastRatio(SkyPalette.NIGHT_FAINT, SkyPalette.NIGHT_BG)
        println("  NIGHT_INK ${"%.2f".format(ink)}:1, NIGHT_FAINT ${"%.2f".format(faint)}:1")

        assertTrue("the sky's ink is not comfortably legible", ink > 12.0)

        // The measurement that was worth taking. `NIGHT_FAINT` reads as a name for "the quiet one",
        // and it is not: at 5.18:1 it is a *desaturated* value, not a low-contrast one, and it sits
        // essentially on top of the target every data star is drawn at. Two things follow.
        //
        // The good one: gutter labels and the project thread are legible, and clear the floor
        // without anyone having to fix them.
        //
        // The one to be careful about: the chrome cannot recede by being fainter, because it isn't.
        // Separation between chrome and data has to come from stroke weight, size and form. A
        // reviewer assuming "faint therefore quieter" would draw month labels as present as the
        // person's stars, which is a gutter competing with the sky.
        assertTrue("NIGHT_FAINT is below the floor after all", faint >= SkyPalette.CONTRAST_FLOOR)
        assertTrue(
            "NIGHT_FAINT has become genuinely low-contrast; the note above needs revisiting",
            Math.abs(faint - SkyPalette.STAR_CONTRAST_TARGET) < 0.5,
        )
    }

    // -------------------------------------------------------------------------------------------

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
