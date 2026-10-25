package com.daymark.app.sky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A point of light: a pale hot heart in the star's own colour, a soft wing, the halo whose spread
 * is the mood, round in every direction and gone into the dark without an edge (#474).
 *
 * `SkyStarLight` takes a colour, a light and sizes, and the only sizes a mood moves are the halo's,
 * so what is checked here is that the colour it is given is the colour that comes out, that the
 * light has the shape of a star and not of a disc or a cross, and that the halo adds the same light
 * at every mood. Every absence check is pointed at a planted counter-example first.
 */
class SkyStarLightTest {

    private val density = 2.75f

    private fun alpha(argb: Int): Int = (argb ushr 24) and 0xFF

    private fun red(argb: Int): Int = (argb shr 16) and 0xFF

    private fun green(argb: Int): Int = (argb shr 8) and 0xFF

    private fun blue(argb: Int): Int = argb and 0xFF

    /** A pixel's light as it lands on black: its colour times its alpha, per channel. */
    private fun landed(argb: Int): IntArray =
        intArrayOf(red(argb), green(argb), blue(argb)).map { Math.round(it * alpha(argb) / 255f) }.toIntArray()

    /** How far toward red a shown colour sits. Positive for warm, negative for cool. */
    private fun warmth(rgb: IntArray): Int = rgb[0] - rgb[2]

    /** Every tint the sky can draw an ordinary star in: the ramp at a spread of ages, all four temperatures. */
    private val tints: List<Int> = buildList {
        for (age in floatArrayOf(0f, 0.4f, 1f, 1.3f, 2.6f, 4f, 5.5f, 9f)) {
            for (id in 1L..8L) add(SkyGlyph.starTint(SkyKind.CHECK_IN, id, age, SkyGlyph.MOOD_NONE))
        }
    }.distinct()

    /** A star as the sprite builds it, at the whole-sky view. */
    private fun shape(
        tint: Int,
        mood: Int = SkyGlyph.MOOD_NONE,
        kind: SkyKind = SkyKind.CHECK_IN,
        quiet: Boolean = false,
        light: Float = if (kind == SkyKind.LIFE_EVENT) SkyStarLight.LANDMARK_LIGHT else SkyStarLight.LIGHT,
    ): SkyStarLight.Shape {
        val landmark = kind == SkyKind.LIFE_EVENT
        val bead = SkyGlyph.coreRadiusDp(kind, mood) * SkyGlyph.coreScale(kind) * density
        return SkyStarLight.Shape(
            tint = tint,
            light = light,
            beadPx = if (quiet) bead + density else bead,
            wingPx = (if (landmark) SkyStarLight.LANDMARK_WING_DP else SkyStarLight.WING_DP) * density,
            haloRadiusPx = SkyGlyph.outerGlowRadiusDp(kind, mood) * density,
            haloPeak = SkyGlyph.haloPeakAlpha(kind, mood),
            quiet = quiet,
        )
    }

    /** What the screen shows [r] pixels from the star's middle, `0..255` a channel. */
    private fun shownAt(shape: SkyStarLight.Shape, r: Float): IntArray {
        val linear = FloatArray(3)
        val shown = FloatArray(3)
        SkyStarLight.lightAt(shape, r, linear)
        SkyStarLight.toneMap(linear, shown)
        return IntArray(3) { Math.round(shown[it] * 255f) }
    }

    private fun middle(raster: SkyStarLight.Raster): Int = raster.pixels[(raster.size / 2) * raster.size + raster.size / 2]

    // -------------------------------------------------------------------------------------------
    // The colour is the star's own.
    // -------------------------------------------------------------------------------------------

    @Test
    fun `the heart is the star's own colour, paled, and not white`() {
        // The defect this guards against: every star's heart the same near-white, so a sky of new
        // and old stars is a sky of white dots in coloured haze. A heart is paler than the star's
        // colour, as a real star's is, and still that colour.
        val newest = landed(middle(SkyStarLight.raster(shape(SkyAge.NEWEST_TINT))))
        val oldest = landed(middle(SkyStarLight.raster(shape(SkyAge.OLDEST_TINT))))
        println("  newest heart ${newest.toList()}, oldest heart ${oldest.toList()}")
        assertTrue("a new star's heart is not blue: ${newest.toList()}", warmth(newest) < -20)
        assertTrue("an old star's heart is not red: ${oldest.toList()}", warmth(oldest) > 40)
        assertTrue("the heart is not the brightest thing in the star", newest.max() > 200 && oldest.max() > 200)
    }

    @Test
    fun `warmer in means warmer out, at every distance from the middle`() {
        // Run against every tint the sky draws: a warmer star never comes out cooler at any radius,
        // so age reads at the heart of the star as well as around it.
        val sorted = tints.sortedBy { warmth(intArrayOf((it shr 16) and 0xFF, 0, it and 0xFF)) }
        val radii = floatArrayOf(0f, 1f, 2f, 3f, 5f, 8f, 12f)
        for (i in 0 until sorted.size - 1) {
            val cooler = shape(sorted[i])
            val warmer = shape(sorted[i + 1])
            for (r in radii) {
                val a = shownAt(cooler, r)
                val b = shownAt(warmer, r)
                assertTrue(
                    "at $r px: ${Integer.toHexString(sorted[i + 1])} came out cooler than ${Integer.toHexString(sorted[i])}",
                    warmth(b) >= warmth(a) - 1,
                )
            }
        }
    }

    @Test
    fun `the ordering check can fail`() {
        // Planted: a heart that is the same white whatever it is given is exactly the defect, and
        // the comparison above must be able to tell two colours apart through the light at all.
        val blue = shownAt(shape(SkyAge.NEWEST_TINT), 0f)
        val red = shownAt(shape(SkyAge.OLDEST_TINT), 0f)
        assertTrue("the light cannot carry a difference in colour", warmth(red) - warmth(blue) > 60)
    }

    // -------------------------------------------------------------------------------------------
    // The shape of the light.
    // -------------------------------------------------------------------------------------------

    @Test
    fun `the light falls away from the middle and ends on nothing`() {
        for (tint in tints + SkyAge.LANDMARK_TINT) {
            val kind = if (tint == SkyAge.LANDMARK_TINT) SkyKind.LIFE_EVENT else SkyKind.CHECK_IN
            val raster = SkyStarLight.raster(shape(tint, kind = kind))
            val n = raster.size
            val row = raster.pixels.copyOfRange((n / 2) * n, (n / 2) * n + n)
            for (x in n / 2 + 1 until n) {
                assertTrue(
                    "${Integer.toHexString(tint)} brightens outward at $x of $n",
                    alpha(row[x]) <= alpha(row[x - 1]),
                )
            }
            assertEquals("${Integer.toHexString(tint)} is not brightest at its middle", row.maxOf { alpha(it) }, alpha(middle(raster)))
            // Ends on nothing: every pixel on the square's edge is empty, so no star is cut off.
            for (k in 0 until n) {
                for (edge in intArrayOf(raster.pixels[k], raster.pixels[(n - 1) * n + k], raster.pixels[k * n], raster.pixels[k * n + n - 1])) {
                    assertEquals("${Integer.toHexString(tint)} is cut off at its edge", 0, alpha(edge))
                }
            }
        }
    }

    /**
     * Whether every pixel the same distance from the middle shows the same light, to within a step
     * of rounding: a round star. A ray, a spike or a square edge is light at one angle and not at
     * another.
     */
    private fun isRound(raster: SkyStarLight.Raster): Boolean {
        val n = raster.size
        val c = n / 2f
        val byRing = HashMap<Int, IntArray>()
        for (y in 0 until n) {
            for (x in 0 until n) {
                val d = Math.hypot((x + 0.5 - c), (y + 0.5 - c))
                // Rings a tenth of a pixel wide: close enough that a round falloff barely moves
                // across one, so only a difference in angle shows.
                val ring = Math.round(d * 10).toInt()
                val a = alpha(raster.pixels[y * n + x])
                val seen = byRing.getOrPut(ring) { intArrayOf(a, a) }
                seen[0] = minOf(seen[0], a)
                seen[1] = maxOf(seen[1], a)
            }
        }
        return byRing.values.all { it[1] - it[0] <= 3 }
    }

    @Test
    fun `a star is round, with no ray, no spike and no edge`() {
        // #474: rays and a landmark's spikes read as a flat drawing of a star, not a star.
        for (tint in listOf(SkyAge.NEWEST_TINT, SkyAge.OLDEST_TINT)) {
            for (mood in SkyGlyph.MOOD_MIN..SkyGlyph.MOOD_MAX) {
                assertTrue("a star at mood $mood is not round", isRound(SkyStarLight.raster(shape(tint, mood))))
            }
        }
        assertTrue("a landmark is not round", isRound(SkyStarLight.raster(shape(SkyAge.LANDMARK_TINT, kind = SkyKind.LIFE_EVENT))))
        // The quiet sky's bead is a crisp disc on purpose (§7.1), so its one pixel of smoothing
        // differs with angle the way any pixel edge does; that it has nothing round it is checked
        // below.
    }

    @Test
    fun `the roundness check sees a ray`() {
        // Planted: one faint ray along the row through the middle, which is what #474 drew. The
        // check above must call it not round, or it proves only that it is blind.
        val star = SkyStarLight.raster(shape(SkyAge.NEWEST_TINT))
        val n = star.size
        val rayed = star.pixels.copyOf()
        for (x in 0 until n) {
            val at = (n / 2) * n + x
            val a = minOf(255, alpha(rayed[at]) + 40)
            rayed[at] = (a shl 24) or (rayed[at] and 0xFFFFFF).let { if (it == 0) 0xFFFFFF else it }
        }
        assertFalse("a ray was not planted", rayed.contentEquals(star.pixels))
        assertFalse("the roundness check cannot see a ray", isRound(SkyStarLight.Raster(n, rayed)))
    }

    @Test
    fun `every pixel lands on black as exactly its own light`() {
        // The sprite is stamped additively, so a pixel's alpha times its colour is what it adds.
        // The brightest channel carries the alpha, so it lands at its own value with nothing lost.
        val raster = SkyStarLight.raster(shape(SkyAge.OLDEST_TINT))
        var lit = 0
        for (p in raster.pixels) {
            if (alpha(p) == 0) continue
            lit++
            assertEquals("the brightest channel is not at full before alpha", 255, maxOf(red(p), green(p), blue(p)))
        }
        assertTrue("the star has almost no lit pixels: $lit", lit > 100)
        // The control: the same light packed the other way, opaque with the colour scaled down,
        // must fail the check above. It only can if the pixel is not already opaque.
        val p = middle(raster)
        assertTrue("the middle is already opaque, so the control proves nothing", alpha(p) < 255)
        val light = landed(p)
        val opaque = (0xFF shl 24) or (light[0] shl 16) or (light[1] shl 8) or light[2]
        assertNotEquals("the packing check cannot see an opaque pixel", 255, maxOf(red(opaque), green(opaque), blue(opaque)))
    }

    @Test
    fun `more light is a brighter star, and no channel clips to white`() {
        // The tone map: a landmark's light is more than twice an ordinary star's and its heart is
        // still a colour, because light is added up and mapped once rather than clipped.
        for (tint in tints) {
            val ordinary = shownAt(shape(tint), 0f)
            val brighter = shownAt(shape(tint, light = SkyStarLight.LANDMARK_LIGHT), 0f)
            assertTrue("more light is not brighter for ${Integer.toHexString(tint)}", brighter.max() >= ordinary.max() && brighter.sum() > ordinary.sum())
        }
        val reddest = shownAt(shape(SkyAge.OLDEST_TINT, light = SkyStarLight.LANDMARK_LIGHT), 0f)
        assertTrue("an old star at a landmark's light has clipped to white: ${reddest.toList()}", reddest[2] < 245)

        val out = FloatArray(3)
        SkyStarLight.toneMap(floatArrayOf(0f, 0f, 0f), out)
        assertEquals("no light is not black", 0f, out.max(), 0f)
        SkyStarLight.toneMap(floatArrayOf(1e6f, 1e6f, 1e6f), out)
        assertTrue("the tone map passes 1", out.max() <= 1f)
    }

    // -------------------------------------------------------------------------------------------
    // The mood: the halo's spread, and the same light at every mood.
    // -------------------------------------------------------------------------------------------

    /** The light [shape]'s halo adds, over the whole plane, in linear light: what the mood spends. */
    private fun haloLight(shape: SkyStarLight.Shape): Double {
        val without = SkyStarLight.Shape(shape.tint, shape.light, shape.beadPx, shape.wingPx, shape.haloRadiusPx, 0f, shape.quiet)
        val a = FloatArray(3)
        val b = FloatArray(3)
        var total = 0.0
        val step = 0.01f
        var r = step / 2f
        while (r < shape.reachPx + 1f) {
            SkyStarLight.lightAt(shape, r, a)
            SkyStarLight.lightAt(without, r, b)
            total += (a.sum() - b.sum()) * 2.0 * Math.PI * r * step
            r += step
        }
        return total
    }

    @Test
    fun `the halo adds the same light at every mood`() {
        // `SkyGlyph.haloEmittedLight` holds this of the halo's own shape; this holds it of the
        // light that is actually drawn, which is the halo times HALO_GAIN in linear light.
        val emitted = (SkyGlyph.MOOD_MIN..SkyGlyph.MOOD_MAX).map { haloLight(shape(SkyAge.NEWEST_TINT, it)) }
        println("  halo light by mood: ${emitted.map { "%.4f".format(it) }}")
        assertEquals("a hard day's halo adds a different light", emitted.min(), emitted.max(), emitted.max() * 2e-3)

        // Positive control: the halos really are different shapes, and a halo dimmed by mood, the
        // ranking SKY.md §3.4 refuses, shows up as a different light.
        assertNotEquals(shape(SkyAge.NEWEST_TINT, 1).haloRadiusPx, shape(SkyAge.NEWEST_TINT, 5).haloRadiusPx)
        val hard = shape(SkyAge.NEWEST_TINT, 1)
        val dimmed = SkyStarLight.Shape(hard.tint, hard.light, hard.beadPx, hard.wingPx, hard.haloRadiusPx, hard.haloPeak * 0.68f, false)
        assertTrue("a dimmed halo adds the same light, so the check is blind", haloLight(dimmed) < emitted.min() * 0.9)
    }

    @Test
    fun `a mood moves nothing but the halo`() {
        // Inside the heart, where the halo is a sliver of the light, every mood draws the same star.
        val radii = floatArrayOf(0f, 0.5f, 1f, 1.5f)
        val neutral = shape(SkyAge.NEWEST_TINT)
        for (mood in SkyGlyph.MOOD_MIN..SkyGlyph.MOOD_MAX) {
            val moody = shape(SkyAge.NEWEST_TINT, mood)
            assertEquals(neutral.beadPx, moody.beadPx, 0f)
            assertEquals(neutral.wingPx, moody.wingPx, 0f)
            assertEquals(neutral.light, moody.light, 0f)
            for (r in radii) {
                val a = shownAt(neutral, r)
                val b = shownAt(moody, r)
                for (c in 0..2) assertEquals("mood $mood moves the heart at $r px", a[c].toFloat(), b[c].toFloat(), 2f)
            }
        }
    }

    // -------------------------------------------------------------------------------------------
    // The landmark, and the quiet sky.
    // -------------------------------------------------------------------------------------------

    @Test
    fun `a landmark is the one bright star`() {
        val landmark = SkyStarLight.raster(shape(SkyAge.LANDMARK_TINT, kind = SkyKind.LIFE_EVENT))
        val landmarkLight = landmark.pixels.sumOf { landed(it).sum() }
        for (tint in listOf(SkyAge.NEWEST_TINT, SkyAge.OLDEST_TINT)) {
            for (mood in SkyGlyph.MOOD_MIN..SkyGlyph.MOOD_MAX) {
                val star = SkyStarLight.raster(shape(tint, mood))
                assertTrue("a star at mood $mood outshines a landmark", star.pixels.sumOf { landed(it).sum() } * 2 < landmarkLight)
                assertTrue("a star at mood $mood is as big as a landmark", star.size < landmark.size)
            }
        }
    }

    @Test
    fun `the quiet sky's star is a crisp bead and nothing round it`() {
        // §7.1: a soft gradient round a small mark is the first thing to disappear for someone with
        // low vision. So past the bead's edge and its one pixel of smoothing, nothing is drawn.
        val quiet = shape(SkyAge.NEWEST_TINT, quiet = true)
        val raster = SkyStarLight.raster(quiet)
        val n = raster.size
        val c = n / 2f
        var inside = 0
        for (y in 0 until n) {
            for (x in 0 until n) {
                val d = Math.hypot((x + 0.5 - c), (y + 0.5 - c))
                val a = alpha(raster.pixels[y * n + x])
                if (d > quiet.beadPx + 1.0) assertEquals("the quiet star has light past its bead at $d px", 0, a)
                if (d < quiet.beadPx - 1.0) {
                    inside++
                    assertTrue("the quiet bead is faint inside at $d px: $a", a > 120)
                }
            }
        }
        assertTrue("the quiet bead covers almost nothing", inside > 10)
        // And it is a sphere, a little darker toward its limb, not a flat dot.
        assertEquals(1f, SkyStarLight.limbAt(0f), 0f)
        assertEquals("the limb is 0.22 of the centre at the edge", 0.22f, SkyStarLight.limbAt(1f), 1e-6f)
        assertTrue(shownAt(quiet, 0f).max() > shownAt(quiet, quiet.beadPx * 0.9f).max())

        // The control: the ordinary star, which does have light past its bead, fails the same check.
        val ordinary = shape(SkyAge.NEWEST_TINT)
        assertTrue("an ordinary star has nothing past its bead, so the check proves nothing", shownAt(ordinary, ordinary.beadPx + 3f).max() > 0)
    }
}
