package com.daymark.app.ui.support

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pacer's circle is driven by frame-time arithmetic rather than a tween, so that the
 * "Remove animations" setting (animator duration scale = 0) cannot collapse a five-second breath
 * into a snap. What the frame loop relies on is that [breathScale] starts on `from`, lands on `to`
 * exactly when the phase is up, and stays there for any frame that arrives late.
 */
class BreathingPacerTest {

    private val linear: (Float) -> Float = { it }
    private val fiveSeconds = 5_000L * 1_000_000L
    private val eps = 1e-6f

    @Test
    fun startsAtFrom() {
        assertEquals(0.45f, breathScale(0L, fiveSeconds, 0.45f, 1f, linear), eps)
    }

    @Test
    fun landsOnToExactlyAtDuration() {
        assertEquals(1f, breathScale(fiveSeconds, fiveSeconds, 0.45f, 1f, linear), eps)
    }

    @Test
    fun aLateFrameHoldsAtToRatherThanOvershooting() {
        assertEquals(1f, breathScale(fiveSeconds * 3, fiveSeconds, 0.45f, 1f, linear), eps)
        assertEquals(0.45f, breathScale(fiveSeconds * 3, fiveSeconds, 1f, 0.45f, linear), eps)
    }

    @Test
    fun aFrameBeforeTheStartIsClampedToFrom() {
        assertEquals(0.45f, breathScale(-1L, fiveSeconds, 0.45f, 1f, linear), eps)
    }

    @Test
    fun growsOnTheInBreathAndShrinksOnTheOutBreath() {
        var last = breathScale(0L, fiveSeconds, 0.45f, 1f, linear)
        for (frame in 1..60) {
            val next = breathScale(frame * (fiveSeconds / 60), fiveSeconds, 0.45f, 1f, linear)
            assertTrue("in-breath frame $frame moved backwards", next >= last)
            last = next
        }
        last = breathScale(0L, fiveSeconds, 1f, 0.45f, linear)
        for (frame in 1..60) {
            val next = breathScale(frame * (fiveSeconds / 60), fiveSeconds, 1f, 0.45f, linear)
            assertTrue("out-breath frame $frame moved backwards", next <= last)
            last = next
        }
    }

    @Test
    fun easingShapesTheMiddleButNotTheEnds() {
        val slowStart: (Float) -> Float = { it * it }
        assertEquals(0.45f, breathScale(0L, fiveSeconds, 0.45f, 1f, slowStart), eps)
        assertEquals(1f, breathScale(fiveSeconds, fiveSeconds, 0.45f, 1f, slowStart), eps)
        val halfway = breathScale(fiveSeconds / 2, fiveSeconds, 0.45f, 1f, slowStart)
        assertTrue("eased halfway should trail linear halfway", halfway < 0.45f + (1f - 0.45f) / 2)
    }

    @Test
    fun aZeroLengthPhaseLandsOnToImmediately() {
        assertEquals(1f, breathScale(0L, 0L, 0.45f, 1f, linear), eps)
    }
}
