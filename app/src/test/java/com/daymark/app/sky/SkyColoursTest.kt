package com.daymark.app.sky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A sky's own colours (`DECISIONS.md` §D11): its seed's alone, never green, never a light. */
class SkyColoursTest {

    private fun everyColour(look: SkyColours.Look): List<Int> =
        look.space.toList() + look.nebulae.flatMap { it.toList() } + look.supernova.toList() +
            look.disk.toList() + look.whiteHole

    private fun isGreen(rgb: Int): Boolean {
        val hue = SkyColours.hueOf(rgb) ?: return false
        // A hair inside the window: a colour rounded to whole channels can land a degree past the
        // hue it was made from.
        return hue > SkyColours.GREEN_FROM + 2f && hue < SkyColours.GREEN_TO - 2f
    }

    @Test
    fun `no sky has a green anywhere`() {
        for (seed in 1L..400L) for (choice in 0..3) {
            for (rgb in everyColour(SkyColours.of(seed, choice))) {
                assertFalse("seed $seed choice $choice: ${Integer.toHexString(rgb)} is green", isGreen(rgb))
            }
        }
    }

    @Test
    fun `the green check sees a green`() {
        // The positive control: a hue the rule skips, made directly, is caught.
        assertTrue(isGreen(SkyColours.hsl(120f, 0.6f, 0.5f)))
        assertTrue(isGreen(SkyColours.hsl(170f, 0.5f, 0.45f)))
        assertFalse(isGreen(SkyColours.hsl(230f, 0.6f, 0.5f)))
        assertNull("a grey has no hue", SkyColours.hueOf(0x808080))
    }

    @Test
    fun `okHue moves every green out of the window and leaves the rest`() {
        for (h in 0 until 360) {
            val moved = SkyColours.okHue(h.toFloat()) % 360f
            assertFalse("$h stays green", moved in SkyColours.GREEN_FROM..SkyColours.GREEN_TO)
            if (h.toFloat() !in SkyColours.GREEN_FROM..SkyColours.GREEN_TO) assertEquals(h.toFloat(), moved, 0f)
        }
    }

    @Test
    fun `the space stays dark enough for every star`() {
        // Every star is measured against the night ground; the space is a colour, never a light, so
        // its lightest tone keeps the dimmest step of the ramp above the contrast floor.
        val dimmest = SkyPalette.equalisedRamp(
            intArrayOf(0x8C7BB5, 0x6E8FB0, 0xA79A86, 0xC9A66B, 0xD98F5C),
            target = SkyPalette.STAR_CONTRAST_TARGET,
        ).minBy { SkyPalette.relativeLuminance(it) }
        for (seed in 1L..400L) {
            val look = SkyColours.of(seed, 0)
            for (tone in look.space.take(3)) {
                val ratio = SkyPalette.contrastRatio(dimmest, tone)
                assertTrue("seed $seed: ${Integer.toHexString(tone)} leaves $ratio", ratio >= SkyPalette.CONTRAST_FLOOR)
            }
        }
    }

    @Test
    fun `the same seed and choice are the same look, and another choice is another`() {
        val a = SkyColours.of(42L, 0)
        val b = SkyColours.of(42L, 0)
        assertEquals(everyColour(a), everyColour(b))
        assertNotEquals(everyColour(a), everyColour(SkyColours.of(42L, 1)))
        assertNotEquals(everyColour(a), everyColour(SkyColours.of(43L, 0)))
    }

    @Test
    fun `skies differ in their colours`() {
        val looks = (1L..200L).map { SkyColours.of(it, 0).nebulae[0][0] }.toSet()
        assertTrue("only ${looks.size} first nebula colours in 200 skies", looks.size > 150)
    }

    @Test
    fun `the space's glows sit in the sky, are the same for a seed, and differ between seeds`() {
        val a = SkyColours.glows(41L, 1.4f)
        assertEquals(3, a.size)
        for (g in a) {
            assertTrue(g.x in 0f..1f && g.y in 0f..1.4f && g.radius > 0f)
            assertTrue(g.tone in 1..3)
        }
        assertEquals(a.map { it.x }, SkyColours.glows(41L, 1.4f).map { it.x })
        assertNotEquals(a.map { it.x }, SkyColours.glows(42L, 1.4f).map { it.x })
    }
}
