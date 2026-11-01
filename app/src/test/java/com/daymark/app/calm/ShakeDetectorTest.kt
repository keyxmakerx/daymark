package com.daymark.app.calm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShakeDetectorTest {

    private val g = ShakeDetector.EARTH_G

    /** A reading of [gs] g straight down one axis. */
    private fun ShakeDetector.at(ms: Long, gs: Float) = onReading(ms, 0f, 0f, gs * g)

    @Test
    fun `resting and walking do nothing`() {
        val d = ShakeDetector()
        var t = 0L
        repeat(500) { assertFalse(d.at(t, if (it % 2 == 0) 1f else 1.4f)); t += 20 }
    }

    @Test
    fun `one or two jolts, like a pocket or a bag, do nothing`() {
        val d = ShakeDetector()
        assertFalse(d.at(0, 4f))
        assertFalse(d.at(300, 4f))
        // Long after, a third: too far apart to be one shake.
        assertFalse(d.at(2_000, 4f))
    }

    @Test
    fun `one long spike counts once`() {
        val d = ShakeDetector()
        var t = 0L
        // 100 ms of continuous hard reading, sampled every 10 ms: one peak, not ten.
        repeat(10) { assertFalse(d.at(t, 4f)); t += 10 }
        assertFalse(d.at(t, 1f))
    }

    @Test
    fun `three firm shakes within a second tumble, once`() {
        val d = ShakeDetector()
        assertFalse(d.at(0, 3f))
        assertFalse(d.at(250, 3f))
        assertTrue(d.at(500, 3f))
        // Still shaking: the cooldown holds it to one tumble.
        assertFalse(d.at(750, 3f))
        assertFalse(d.at(1_000, 3f))
        assertFalse(d.at(1_250, 3f))
        // And after the cooldown, a fresh shake works again.
        assertFalse(d.at(3_000, 3f))
        assertFalse(d.at(3_250, 3f))
        assertTrue(d.at(3_500, 3f))
    }

    @Test
    fun `just under the threshold never tumbles`() {
        val d = ShakeDetector()
        val under = ShakeDetector.THRESHOLD_G - 0.05f
        var t = 0L
        repeat(50) { assertFalse(d.at(t, under)); t += 200 }
        // Positive control: the same rhythm just over the threshold does.
        val over = ShakeDetector.THRESHOLD_G + 0.05f
        val e = ShakeDetector()
        assertFalse(e.at(0, over)); assertFalse(e.at(200, over)); assertTrue(e.at(400, over))
    }
}
