package com.daymark.app.sensing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class BreathingDetectorTest {

    private val rate = 25.0 // Hz

    /** Gravity baseline + a breathing oscillation at [bpm]; optional flat pause window. */
    private fun signal(
        seconds: Int,
        bpm: Double,
        amplitude: Double = 0.05,
        pauseFromSec: Int = -1,
        pauseToSec: Int = -1,
    ): DoubleArray {
        val n = (seconds * rate).toInt()
        val freq = bpm / 60.0
        return DoubleArray(n) { i ->
            val t = i / rate
            val inPause = pauseFromSec >= 0 && t >= pauseFromSec && t < pauseToSec
            val osc = if (inPause) 0.0 else amplitude * sin(2 * PI * freq * t)
            9.8 + osc
        }
    }

    @Test
    fun detectsCleanBreathingRate() {
        val r = BreathingDetector.analyze(signal(60, bpm = 15.0), rate)
        assertEquals(15.0, r.breathingRatePerMin!!, 1.5)
        assertTrue("confidence should be high for a clean sine", r.confidence > 0.5)
        assertTrue("no pauses in steady breathing", r.pauses.isEmpty())
    }

    @Test
    fun detectsSlowerRate() {
        val r = BreathingDetector.analyze(signal(60, bpm = 10.0), rate)
        assertEquals(10.0, r.breathingRatePerMin!!, 1.5)
    }

    @Test
    fun flagsABreathingPause() {
        // 80s of breathing with a 20s flat (no chest movement) stretch in the middle.
        val r = BreathingDetector.analyze(
            signal(80, bpm = 15.0, pauseFromSec = 30, pauseToSec = 50), rate,
        )
        assertTrue("should flag at least one pause", r.pauses.isNotEmpty())
        val longest = r.pauses.maxByOrNull { it.durationSec }!!
        assertTrue("pause should be roughly the flat window", longest.durationSec in 8.0..22.0)
        assertTrue("pause should start near 30s", longest.startSec in 26.0..40.0)
    }

    @Test
    fun tooShortReturnsNoRate() {
        val r = BreathingDetector.analyze(signal(10, bpm = 15.0), rate)
        assertNull(r.breathingRatePerMin)
    }

    @Test
    fun flatSignalHasNoBreathingAndNoCrash() {
        val flat = DoubleArray((40 * rate).toInt()) { 9.8 }
        val r = BreathingDetector.analyze(flat, rate)
        // No oscillation → no rhythm; must not crash or invent pauses from a zero baseline.
        assertTrue(r.confidence < 0.2 || r.breathingRatePerMin == null)
        assertTrue(r.pauses.isEmpty())
    }
}
