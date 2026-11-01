package com.daymark.app.calm

import com.daymark.app.calm.SandRake.Point
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SandRakeTest {

    @Test
    fun `one step leaves parallel grooves centred on the finger`() {
        val grooves = SandRake.tines(Point(0f, 0f), Point(10f, 0f), spacing = 9f)
        assertEquals(SandRake.TINES, grooves.size)
        // A horizontal step: every groove is horizontal, and they sit symmetrically above and below.
        grooves.forEach { (a, b) -> assertEquals(a.y, b.y, 0.001f); assertEquals(10f, b.x - a.x, 0.001f) }
        val ys = grooves.map { it.first.y }.sorted()
        assertEquals(0f, ys.sum(), 0.001f)
        assertEquals(9f, ys[1] - ys[0], 0.001f)
    }

    @Test
    fun `a still finger leaves nothing`() {
        assertTrue(SandRake.tines(Point(4f, 4f), Point(4f, 4f), 9f).isEmpty())
    }

    @Test
    fun `points too close together are not kept`() {
        assertTrue(SandRake.worthKeeping(null, Point(0f, 0f), 3f))
        assertFalse(SandRake.worthKeeping(Point(0f, 0f), Point(1f, 1f), 3f))
        assertTrue(SandRake.worthKeeping(Point(0f, 0f), Point(3f, 0f), 3f))
    }

    @Test
    fun `the oldest strokes are let go whole once there are too many points`() {
        val strokes = List(5) { s -> List(10) { Point(s.toFloat(), it.toFloat()) } }
        val kept = SandRake.bounded(strokes, max = 25)
        assertEquals(strokes.takeLast(2), kept)
        // Under the limit, nothing is touched — the positive control for the line above.
        assertEquals(strokes, SandRake.bounded(strokes, max = 50))
        // The newest stroke is never let go, even alone over the limit: it is under the finger.
        assertEquals(strokes.takeLast(1), SandRake.bounded(strokes, max = 3))
    }

    @Test
    fun `the natural end line says what the person can do, not how they feel`() {
        val line = CalmSession.NATURAL_END_LINE.lowercase()
        listOf("feel", "better", "calm", "well done", "great").forEach { assertFalse(it, line.contains(it)) }
        assertTrue(abs(CalmSession.NATURAL_END_MS - 600_000L) == 0L)
    }
}
