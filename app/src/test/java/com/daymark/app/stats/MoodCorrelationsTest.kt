package com.daymark.app.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MoodCorrelationsTest {

    @Test
    fun factorDeltas_computesMeanDeltaAndGatesByMinOccurrences() {
        // Factor 1 present on high-mood entries, absent on low-mood ones.
        val entries = listOf(
            5 to listOf(1L),
            4 to listOf(1L),
            5 to listOf(1L),
            2 to listOf(2L),
            1 to listOf(2L),
        )
        val deltas = MoodCorrelations.factorDeltas(entries, minOccurrences = 2)
        val f1 = deltas.first { it.id == 1L }
        assertEquals(3, f1.n)
        assertEquals(14.0 / 3, f1.meanWith, 1e-9)
        assertEquals(1.5, f1.meanWithout, 1e-9)
        assertTrue("factor 1 should lift mood", f1.delta > 0)
    }

    @Test
    fun factorDeltas_dropsFactorsBelowMinOccurrencesOrAlwaysPresent() {
        val entries = listOf(
            5 to listOf(1L, 9L),
            4 to listOf(1L, 9L),
            3 to listOf(1L, 9L),
        )
        // Factor 9 is on every entry (no "without" group) → excluded.
        // Factor 1 also always present → excluded.
        val deltas = MoodCorrelations.factorDeltas(entries, minOccurrences = 1)
        assertTrue(deltas.isEmpty())
    }

    @Test
    fun rankLifts_splitsPositiveAndNegativeDeltas() {
        val deltas = listOf(
            MoodCorrelations.FactorDelta(1, 4.5, 3.0, 1.5, 5, 0.4),
            MoodCorrelations.FactorDelta(2, 2.0, 3.5, -1.5, 5, -0.4),
            MoodCorrelations.FactorDelta(3, 4.0, 3.0, 1.0, 5, 0.3),
        )
        val (up, down) = MoodCorrelations.rankLifts(deltas, topN = 5)
        assertEquals(listOf(1L, 3L), up.map { it.id })
        assertEquals(listOf(2L), down.map { it.id })
    }

    @Test
    fun pearson_perfectPositiveAndNegativeAndConstant() {
        assertEquals(1.0, MoodCorrelations.pearson(listOf(1.0, 2.0, 3.0), listOf(2.0, 4.0, 6.0))!!, 1e-9)
        assertEquals(-1.0, MoodCorrelations.pearson(listOf(1.0, 2.0, 3.0), listOf(3.0, 2.0, 1.0))!!, 1e-9)
        // Constant series → undefined.
        assertNull(MoodCorrelations.pearson(listOf(1.0, 1.0, 1.0), listOf(1.0, 2.0, 3.0)))
        // Too few points.
        assertNull(MoodCorrelations.pearson(listOf(1.0), listOf(2.0)))
    }

    @Test
    fun trackerCorrelation_respectsMinDays() {
        val points = listOf(
            MoodCorrelations.DayPoint(2.0, 4.0),
            MoodCorrelations.DayPoint(3.0, 6.0),
            MoodCorrelations.DayPoint(5.0, 10.0),
        )
        assertNull("below minDays → null", MoodCorrelations.trackerCorrelation(points, minDays = 5))
        assertEquals(1.0, MoodCorrelations.trackerCorrelation(points, minDays = 3)!!, 1e-9)
    }

    @Test
    fun emptyInput_returnsEmpty() {
        assertTrue(MoodCorrelations.factorDeltas(emptyList(), 1).isEmpty())
    }
}
