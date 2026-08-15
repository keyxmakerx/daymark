package com.daymark.app.sky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decorative field: dense everywhere, identical everywhere, and blind to the person.
 *
 * The property that matters most — that the field cannot see data — is not tested here, because it
 * cannot be tested here. [SkyField.tile] takes a `Long` and two `Int`s; there is no data type in the
 * signature for a test to vary. That is the stronger guarantee: a runtime test would show that the
 * field *happens* not to depend on data, while the signature shows it *cannot*, and making it
 * depend on data would require a visible change to a public function that a reviewer reads.
 *
 * What is testable is the other half. A field that compensated — denser where the data is sparse —
 * would encode the gaps it exists to erase, so uniformity is checked over arbitrary regions, with a
 * detector that is first shown to catch a field that is *not* uniform.
 */
class SkyFieldTest {

    private val seed = 0x5EEDL

    @Test
    fun `the same tile is the same specks, every time`() {
        val a = SkyField.tile(seed, 3, -2)
        val b = SkyField.tile(seed, 3, -2)
        assertTrue(a.x.contentEquals(b.x))
        assertTrue(a.y.contentEquals(b.y))
        assertTrue(a.alpha.contentEquals(b.alpha))
    }

    @Test
    fun `neighbouring tiles are different, so the field does not visibly repeat`() {
        val origin = SkyField.tile(seed, 0, 0)
        for (tx in -2..2) {
            for (ty in -2..2) {
                if (tx == 0 && ty == 0) continue
                assertNotEquals(
                    "tile ($tx, $ty) repeats the origin tile",
                    origin.x.toList(),
                    SkyField.tile(seed, tx, ty).x.toList(),
                )
            }
        }
    }

    @Test
    fun `a different seed is a different sky`() {
        assertNotEquals(
            SkyField.tile(seed, 0, 0).x.toList(),
            SkyField.tile(seed + 1, 0, 0).x.toList(),
        )
    }

    @Test
    fun `every speck lands inside its own tile`() {
        for (tx in -3..3) {
            for (ty in -3..3) {
                val tile = SkyField.tile(seed, tx, ty)
                assertEquals(SkyField.SPECKS_PER_TILE, tile.size)
                for (i in 0 until tile.size) {
                    assertTrue("x escaped: ${tile.x[i]}", tile.x[i] >= 0f && tile.x[i] < 1f)
                    assertTrue("y escaped: ${tile.y[i]}", tile.y[i] >= 0f && tile.y[i] < 1f)
                }
            }
        }
    }

    @Test
    fun `no speck is bright enough to be mistaken for a star`() {
        for (tx in -3..3) {
            val tile = SkyField.tile(seed, tx, 0)
            for (i in 0 until tile.size) {
                assertTrue(
                    "speck at alpha ${tile.alpha[i]} is competing with the data",
                    tile.alpha[i] >= SkyField.ALPHA_MIN && tile.alpha[i] <= SkyField.ALPHA_MAX,
                )
            }
        }
        assertTrue(
            "the field is as loud as a star core",
            SkyField.ALPHA_MAX < SkyGlyph.CORE_ALPHA / 3f,
        )
    }

    // -------------------------------------------------------------------------------------------
    // Uniformity, and the detector that is entitled to claim it.
    // -------------------------------------------------------------------------------------------

    /**
     * Largest relative departure from the expected count over a set of equal-area windows.
     * `0.0` is perfectly uniform. Returned rather than asserted so it can be pointed at a field
     * that is known to be lumpy and shown to notice.
     */
    private fun worstDensityError(
        xs: FloatArray,
        ys: FloatArray,
        windows: Int,
    ): Double {
        val counts = IntArray(windows * windows)
        for (i in xs.indices) {
            val cx = (xs[i] * windows).toInt().coerceIn(0, windows - 1)
            val cy = (ys[i] * windows).toInt().coerceIn(0, windows - 1)
            counts[cy * windows + cx]++
        }
        val expected = xs.size.toDouble() / (windows * windows)
        var worst = 0.0
        for (c in counts) {
            val error = Math.abs(c - expected) / expected
            if (error > worst) worst = error
        }
        return worst
    }

    /**
     * The departure a window of [expected] specks is allowed, before the field counts as lumpy.
     *
     * Derived rather than picked. Even a perfectly uniform generator does not put exactly the same
     * number of specks in every window — the jitter moves them across window boundaries — and the
     * smaller the window, the larger that wobble is relative to the count. `1.6 / sqrt(expected)` is
     * 1.6 standard deviations of a Poisson sample, which is a generous bound here because a jittered
     * grid is substantially *less* variable than Poisson. A flat threshold instead of this is what
     * made the first version of this test fail on 11×11 windows holding ten specks each, which was
     * noise being reported as a defect.
     */
    private fun allowedDeparture(expected: Double): Double = 1.6 / Math.sqrt(expected)

    @Test
    fun `the density detector notices a field that fills in the gaps`() {
        // The failure being guarded against, built on purpose: a field that puts more specks in the
        // left half — as a "helpful" field would over a stretch with little data. If the detector
        // cannot see this, it is not entitled to certify anything.
        val n = SkyField.SPECKS_PER_TILE * 9
        val xs = FloatArray(n)
        val ys = FloatArray(n)
        val stream = SkyStream(1L)
        for (i in 0 until n) {
            // Two thirds of the specks are crowded into the left half.
            xs[i] = if (i % 3 == 0) stream.nextBetween(0.5f, 1f) else stream.nextBetween(0f, 0.5f)
            ys[i] = stream.nextUnit()
        }
        // Judged by the same rule the real field is judged by, so the two tests cannot drift apart.
        val expected = n.toDouble() / 16
        assertTrue(
            "the detector cannot see a 2:1 density difference",
            worstDensityError(xs, ys, 4) > allowedDeparture(expected),
        )
    }

    @Test
    fun `field density is the same everywhere`() {
        // A 6x6 block of tiles, mapped into one unit square, so the windows deliberately do not
        // line up with tile boundaries or with the generator's own cells.
        val tiles = 6
        val n = SkyField.SPECKS_PER_TILE * tiles * tiles
        val xs = FloatArray(n)
        val ys = FloatArray(n)
        var i = 0
        for (tx in 0 until tiles) {
            for (ty in 0 until tiles) {
                val tile = SkyField.tile(seed, tx, ty)
                for (k in 0 until tile.size) {
                    xs[i] = (tx + tile.x[k]) / tiles
                    ys[i] = (ty + tile.y[k]) / tiles
                    i++
                }
            }
        }
        // Window counts chosen so none of them divides the 72 cells across the block. Windows that
        // lined up with the generator's own grid would count one cell each and report a perfect
        // 0.000 whatever the jitter did — a uniformity test that had accidentally been handed the
        // answer. These cut cells in half, so the jitter has somewhere to show up.
        var sawSomeVariation = false
        for (windows in intArrayOf(5, 7, 11, 13)) {
            val error = worstDensityError(xs, ys, windows)
            val allowed = allowedDeparture(n.toDouble() / (windows * windows))
            println("  ${windows}x$windows windows: worst departure ${"%.3f".format(error)}, " +
                "allowed ${"%.3f".format(allowed)}")
            if (error > 0.0) sawSomeVariation = true
            assertTrue("field is lumpy at ${windows}x$windows: $error", error < allowed)
        }
        assertTrue("every window landed exactly on target; the windows are aligned with the cells", sawSomeVariation)
    }
}
