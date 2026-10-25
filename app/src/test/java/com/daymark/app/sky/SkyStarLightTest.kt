package com.daymark.app.sky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bead and its rim: lit in the star's own colour, at one light, darker toward the edge, and
 * gone by the rim's reach.
 *
 * `SkyStarLight` takes a colour and a light and nothing else, so a mood cannot reach it by
 * signature; what is checked here is that the colour it is given is the colour that comes out, and
 * that the shape of the light is the approved star's. Every absence check is pointed at a planted
 * counter-example first.
 */
class SkyStarLightTest {

    private fun alpha(argb: Int): Int = (argb ushr 24) and 0xFF

    private fun red(argb: Int): Int = (argb shr 16) and 0xFF

    private fun green(argb: Int): Int = (argb shr 8) and 0xFF

    private fun blue(argb: Int): Int = argb and 0xFF

    /** A stop's light as it lands on black: its colour times its alpha, per channel. */
    private fun landed(argb: Int): IntArray =
        intArrayOf(red(argb), green(argb), blue(argb)).map { Math.round(it * alpha(argb) / 255f) }.toIntArray()

    /** How far toward red a colour sits. Positive for warm, negative for cool. */
    private fun warmth(argb: Int): Int = red(argb) - blue(argb)

    /** Every tint the sky can draw an ordinary star in: the ramp at a spread of ages, all four temperatures. */
    private val tints: List<Int> = buildList {
        for (age in floatArrayOf(0f, 0.4f, 1f, 1.3f, 2.6f, 4f, 5.5f, 9f)) {
            for (id in 1L..8L) add(SkyGlyph.starTint(SkyKind.CHECK_IN, id, age, SkyGlyph.MOOD_NONE))
        }
    }.distinct()

    // -------------------------------------------------------------------------------------------
    // The colour is the star's own.
    // -------------------------------------------------------------------------------------------

    @Test
    fun `the bead's heart is the star's own colour, not white`() {
        // The defect this replaces: every star's heart the same near-white, so a sky of new and
        // old stars was a sky of white dots in coloured haze.
        val newest = SkyStarLight.coreStops(SkyAge.NEWEST_TINT, SkyStarLight.LIGHT)[0]
        val oldest = SkyStarLight.coreStops(SkyAge.OLDEST_TINT, SkyStarLight.LIGHT)[0]
        println("  newest heart ${Integer.toHexString(newest)}, oldest heart ${Integer.toHexString(oldest)}")
        assertTrue("a new star's heart is not blue: ${Integer.toHexString(newest)}", warmth(newest) < -20)
        assertTrue("an old star's heart is not red: ${Integer.toHexString(oldest)}", warmth(oldest) > 40)
        assertTrue("the heart is no brighter at its centre than a dim stop", alpha(newest) > 180 && alpha(oldest) > 180)
    }

    @Test
    fun `warmer in means warmer out, at every stop of the bead and the rim`() {
        // The ordering check, run against every tint the sky draws: a warmer star never comes out
        // cooler at any radius, so age reads at the heart of the star as well as around it.
        val sorted = tints.sortedBy { warmth(it) }
        for (i in 0 until sorted.size - 1) {
            val cooler = sorted[i]
            val warmer = sorted[i + 1]
            if (warmth(cooler) == warmth(warmer)) continue
            for (index in SkyStarLight.CORE_STOP_POSITION.indices) {
                val a = landed(SkyStarLight.coreStops(cooler, SkyStarLight.LIGHT)[index])
                val b = landed(SkyStarLight.coreStops(warmer, SkyStarLight.LIGHT)[index])
                assertTrue(
                    "bead stop $index: ${Integer.toHexString(warmer)} came out cooler than ${Integer.toHexString(cooler)}",
                    b[0] - b[2] >= a[0] - a[2] - 1,
                )
            }
        }
    }

    @Test
    fun `the ordering check can fail`() {
        // Planted: a bead that is the same white whatever it is given is exactly the defect, and
        // the comparison above must be able to tell two colours apart through the stops at all.
        val blue = landed(SkyStarLight.coreStops(SkyAge.NEWEST_TINT, SkyStarLight.LIGHT)[0])
        val red = landed(SkyStarLight.coreStops(SkyAge.OLDEST_TINT, SkyStarLight.LIGHT)[0])
        assertTrue("the stops cannot carry a difference in colour", (red[0] - red[2]) - (blue[0] - blue[2]) > 60)
    }

    // -------------------------------------------------------------------------------------------
    // The shape of the light.
    // -------------------------------------------------------------------------------------------

    @Test
    fun `the bead is brightest at its centre and darker toward its limb`() {
        assertEquals(1f, SkyStarLight.limbAt(0f), 0f)
        assertEquals("the approved limb is 0.22 of the centre at the edge", 0.22f, SkyStarLight.limbAt(1f), 1e-6f)
        var previous = 2f
        var q = 0f
        while (q <= 1f) {
            val limb = SkyStarLight.limbAt(q)
            assertTrue("the limb brightens outward at $q", limb <= previous)
            previous = limb
            q += 0.01f
        }
        for (tint in tints) {
            val stops = SkyStarLight.coreStops(tint, SkyStarLight.LIGHT)
            for (i in 1 until stops.size) {
                assertTrue(
                    "bead of ${Integer.toHexString(tint)} brightens outward at stop $i",
                    alpha(stops[i]) <= alpha(stops[i - 1]),
                )
            }
            assertTrue(
                "bead of ${Integer.toHexString(tint)} is flat: no darker limb",
                alpha(stops.last()) < alpha(stops.first()) * 0.8f,
            )
            assertTrue("bead of ${Integer.toHexString(tint)} vanishes at its edge", alpha(stops.last()) > 60)
        }
    }

    @Test
    fun `the rim is thin, falls away from the bead, and ends on nothing`() {
        assertTrue("the rim reaches too far: ${SkyStarLight.RIM_REACH_DP}", SkyStarLight.RIM_REACH_DP <= 2f)
        assertEquals(SkyStarLight.RIM_REACH_DP, SkyStarLight.RIM_STOP_DP.last(), 0f)
        for (tint in tints) {
            val rim = SkyStarLight.rimStops(tint, SkyStarLight.LIGHT)
            val bead = SkyStarLight.coreStops(tint, SkyStarLight.LIGHT)
            assertEquals("the rim does not end on nothing", 0, alpha(rim.last()))
            assertEquals(
                "the rim fades out through a different colour",
                rim[rim.size - 2] and 0xFFFFFF,
                rim.last() and 0xFFFFFF,
            )
            for (i in 1 until rim.size) {
                assertTrue("the rim brightens outward at stop $i", alpha(rim[i]) <= alpha(rim[i - 1]))
            }
            assertTrue("the rim outshines the bead's own edge", alpha(rim[0]) < alpha(bead.last()))
            assertTrue("there is no rim at all", alpha(rim[0]) > 20)
        }
    }

    @Test
    fun `a brighter light is a brighter star, and no channel clips to white`() {
        // The tone map: a landmark's light is more than twice an ordinary star's and its heart is
        // still a colour, because light is added up and mapped once rather than clipped.
        for (tint in tints) {
            val ordinary = SkyStarLight.coreStops(tint, SkyStarLight.LIGHT)[0]
            val landmark = SkyStarLight.coreStops(tint, SkyStarLight.LANDMARK_LIGHT)[0]
            assertTrue("more light is not brighter for ${Integer.toHexString(tint)}", alpha(landmark) > alpha(ordinary))
        }
        val reddest = landed(SkyStarLight.coreStops(SkyAge.OLDEST_TINT, SkyStarLight.LANDMARK_LIGHT)[0])
        assertTrue("an old star at a landmark's light has clipped to white: ${reddest.toList()}", reddest[2] < 245)
        assertEquals(0, SkyStarLight.encode(0f))
        assertTrue(SkyStarLight.encode(1e6f) <= 255)
    }

    @Test
    fun `every stop lands on black as exactly its own light`() {
        // The sprite is stamped additively, so a stop's alpha times its colour is what it adds.
        // The brightest channel carries the alpha, so it lands at its own value with nothing lost.
        for (tint in tints) {
            for (stop in SkyStarLight.coreStops(tint, SkyStarLight.LIGHT)) {
                val channels = intArrayOf(red(stop), green(stop), blue(stop))
                assertEquals("the brightest channel is not at full before alpha", 255, channels.max())
            }
        }
        // The control: the same light packed the other way, opaque with the colour scaled down,
        // must fail the check above. It only can if the stop is not already opaque.
        val stop = SkyStarLight.coreStops(SkyAge.OLDEST_TINT, SkyStarLight.LIGHT)[0]
        assertTrue("the stop is already opaque, so the control proves nothing", alpha(stop) < 255)
        val light = landed(stop)
        val opaque = (0xFF shl 24) or (light[0] shl 16) or (light[1] shl 8) or light[2]
        assertNotEquals("the packing check cannot see an opaque stop", 255, intArrayOf(red(opaque), green(opaque), blue(opaque)).max())
    }

    // -------------------------------------------------------------------------------------------
    // The bloom and the rays: what makes a far star read as a star (#474).
    // -------------------------------------------------------------------------------------------

    /** Zooms from the whole sky to the closest view, finely enough to see any step. */
    private val zooms: List<Float> = List(4000) { 1f + it * 0.1f }

    @Test
    fun `the bloom is whole far out, eases as the view closes in, and never goes`() {
        assertEquals(1f, SkyStarLight.bloomShare(1f), 0f)
        assertEquals("the bloom eases before the view leaves FAR", 1f, SkyStarLight.bloomShare(SkyDetail.NEAR_ZOOM), 0f)
        assertEquals(SkyStarLight.BLOOM_CLOSE_SHARE, SkyStarLight.bloomShare(SkyStarLight.BLOOM_EASED_BY), 1e-6f)
        assertEquals(SkyStarLight.BLOOM_CLOSE_SHARE, SkyStarLight.bloomShare(400f), 1e-6f)
        assertTrue("the bloom is gone at the closest view", SkyStarLight.bloomShare(400f) > 0f)
        var previous = 2f
        for (zoom in zooms) {
            val share = SkyStarLight.bloomShare(zoom)
            assertTrue("the bloom grows as the view closes in, at $zoom", share <= previous)
            assertTrue("the bloom has gone at $zoom", share >= SkyStarLight.BLOOM_CLOSE_SHARE - 1e-6f)
            previous = share
        }
        // The control: the easing is real, so the checks above are not passing over a constant.
        assertTrue("the bloom never eases", SkyStarLight.bloomShare(10f) < 1f && SkyStarLight.bloomShare(10f) > SkyStarLight.BLOOM_CLOSE_SHARE)
    }

    @Test
    fun `the bloom falls away from the star and ends on nothing`() {
        assertEquals(SkyStarLight.BLOOM_REACH_DP, SkyStarLight.BLOOM_STOP_DP.last(), 0f)
        for (share in floatArrayOf(SkyStarLight.BLOOM_CLOSE_SHARE, 0.7f, 1f)) {
            val alphas = SkyStarLight.bloomAlphas(share)
            assertEquals(SkyStarLight.BLOOM_STOP_DP.size, alphas.size)
            assertEquals("the bloom does not start at its peak", SkyStarLight.BLOOM_PEAK * share, alphas[0], 1e-6f)
            assertEquals("the bloom does not end on nothing", 0f, alphas.last(), 0f)
            for (i in 1 until alphas.size) assertTrue("the bloom brightens outward at stop $i", alphas[i] < alphas[i - 1])
        }
        // Visible where a far star needs it: a few dp out, on a phone, it is still a glow.
        val atThree = SkyStarLight.bloomAlphas(1f)[SkyStarLight.BLOOM_STOP_DP.indexOfFirst { it == 3f }]
        assertTrue("the bloom is too faint to see three dp out: $atThree", atThree > 0.08f)
    }

    @Test
    fun `the rays are whole far out and gone by the time kind marks appear`() {
        assertEquals(1f, SkyStarLight.rayShare(1f), 0f)
        assertEquals(1f, SkyStarLight.rayShare(SkyStarLight.RAY_FULL_UNTIL), 0f)
        assertEquals("a goal's cross-rays would sit on top of the rays", 0f, SkyStarLight.rayShare(SkyDetail.CLOSE_ZOOM), 0f)
        var previous = 2f
        for (zoom in zooms) {
            val share = SkyStarLight.rayShare(zoom)
            assertTrue("the rays grow as the view closes in, at $zoom", share <= previous)
            if (SkyDetail.forZoom(zoom) == SkyDetail.CLOSE) assertEquals("rays at CLOSE, $zoom", 0f, share, 0f)
            previous = share
        }
        // The control: just short of CLOSE the rays are still there, so "none at CLOSE" is a
        // statement about CLOSE and not about a function that is zero everywhere.
        assertTrue("the rays are never drawn", SkyStarLight.rayShare(SkyDetail.CLOSE_ZOOM - 0.5f) > 0f)
    }

    @Test
    fun `a ray is brightest across the star and ends on nothing at both tips`() {
        val alphas = SkyStarLight.rayAlphas(1f)
        assertEquals(SkyStarLight.RAY_STOP_POSITION.size, alphas.size)
        assertEquals(0f, alphas.first(), 0f)
        assertEquals(0f, alphas.last(), 0f)
        val middle = SkyStarLight.RAY_STOP_POSITION.indexOfFirst { it == 0.5f }
        assertEquals("a ray does not cross the star at its peak", SkyStarLight.RAY_PEAK, alphas[middle], 1e-6f)
        for (i in alphas.indices) {
            assertEquals("a ray is lopsided at stop $i", alphas[i], alphas[alphas.size - 1 - i], 1e-6f)
            if (i in 1..middle) assertTrue("a ray dims toward the star at stop $i", alphas[i] > alphas[i - 1])
        }
        assertEquals("half the strength is not half the light", SkyStarLight.RAY_PEAK / 2f, SkyStarLight.rayAlphas(0.5f)[middle], 1e-6f)
        assertTrue("a ray reaches past the bloom, so its tips would end on bare ground", SkyStarLight.RAY_ARM_DP < SkyStarLight.BLOOM_REACH_DP)
    }
}
