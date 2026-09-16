package com.daymark.app.stats

import com.daymark.app.backup.repoFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MoodCorrelationsTest {

    /** Shorthand: these tests are about the arithmetic, not about what kind of factor it is. */
    private fun f(id: Long): MoodCorrelations.FactorId = MoodCorrelations.FactorId.ofActivity(id)

    @Test
    fun factorDeltas_computesMeanDeltaAndGatesByMinOccurrences() {
        // Factor 1 present on high-mood entries, absent on low-mood ones.
        val entries = listOf(
            5 to listOf(f(1)),
            4 to listOf(f(1)),
            5 to listOf(f(1)),
            2 to listOf(f(2)),
            1 to listOf(f(2)),
        )
        val deltas = MoodCorrelations.factorDeltas(entries, minOccurrences = 2)
        val f1 = deltas.first { it.id == f(1) }
        assertEquals(3, f1.n)
        assertEquals(14.0 / 3, f1.meanWith, 1e-9)
        assertEquals(1.5, f1.meanWithout, 1e-9)
        assertTrue("factor 1 should lift mood", f1.delta > 0)
    }

    @Test
    fun factorDeltas_dropsFactorsBelowMinOccurrencesOrAlwaysPresent() {
        val entries = listOf(
            5 to listOf(f(1), f(9)),
            4 to listOf(f(1), f(9)),
            3 to listOf(f(1), f(9)),
        )
        // Factor 9 is on every entry (no "without" group) → excluded.
        // Factor 1 also always present → excluded.
        val deltas = MoodCorrelations.factorDeltas(entries, minOccurrences = 1)
        assertTrue(deltas.isEmpty())
    }

    @Test
    fun rankLifts_splitsPositiveAndNegativeDeltas() {
        val deltas = listOf(
            MoodCorrelations.FactorDelta(f(1), 4.5, 3.0, 1.5, 5, 0.4),
            MoodCorrelations.FactorDelta(f(2), 2.0, 3.5, -1.5, 5, -0.4),
            MoodCorrelations.FactorDelta(f(3), 4.0, 3.0, 1.0, 5, 0.3),
        )
        val (up, down) = MoodCorrelations.rankLifts(deltas, topN = 5)
        assertEquals(listOf(f(1), f(3)), up.map { it.id })
        assertEquals(listOf(f(2)), down.map { it.id })
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

    // ─── A person can never become a factor ──────────────────────────────────────────────────────

    /**
     * The plan asks for this to be true by SHAPE, not by convention, so the test is about the
     * shape. `FactorId`'s constructor is private and the only ways in are the two factories below;
     * a person's id cannot be turned into one without adding a third, here, in the open.
     *
     * The Kotlin compiler is the real guard — a private constructor is not a thing a source scan
     * enforces. This asserts the surface stays as small as it is, because the failure mode is
     * somebody adding a general-purpose `of(Long)` in a hurry and not noticing what it opens.
     */
    @Test
    fun `the only ways to make a factor are an activity and a tracker`() {
        val src = repoFile("app/src/main/java/com/daymark/app/stats/MoodCorrelations.kt").readText()
        val code = src.replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
            .lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")

        // The detector must be able to see a factory at all, or everything below is vacuous.
        assertTrue("cannot see the factories that exist", code.contains("fun ofActivity(") && code.contains("fun ofTracker("))

        assertTrue("the constructor stopped being private", code.contains("value class FactorId private constructor"))

        val factories = Regex("""fun of(\w+)\(""").findAll(code).map { it.groupValues[1] }.toList()
        assertEquals(
            "a third way to make a factor appeared. If it is a person, that is the one thing this " +
                "type exists to prevent — see its header. If it is not, add it to this list on purpose",
            listOf("Activity", "Tracker"),
            factories,
        )
    }

    /** The planted control for the sweep above: it would see a person factory if one were added. */
    @Test
    fun `the factory check is not blind`() {
        val planted = "fun ofPerson(id: Long): FactorId = FactorId(id)"
        val factories = Regex("""fun of(\w+)\(""").findAll(planted).map { it.groupValues[1] }.toList()
        assertEquals(listOf("Person"), factories)
    }
}
