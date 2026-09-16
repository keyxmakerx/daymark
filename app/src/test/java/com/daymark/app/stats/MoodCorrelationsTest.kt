package com.daymark.app.stats

import com.daymark.app.backup.repoFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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

        // `\w*`, not `\w+`. A bare `fun of(` is the exact addition this type's header names as the
        // danger — "no `ofPerson`, no `of(Long)`, and no public constructor" — and `\w+` requires a
        // character after `of`, so the one factory the documentation warns about was the one the
        // sweep could not see. A review planted `fun of(id: Long): FactorId = FactorId(id)` into
        // MoodCorrelations.kt and all 136 tests here stayed green. It now reads as the empty name.
        val factories = factoriesIn(code)
        assertEquals(
            "a third way to make a factor appeared. If it is a person, that is the one thing this " +
                "type exists to prevent — see its header. If it is not, add it to this list on purpose",
            listOf("Activity", "Tracker"),
            factories,
        )

        // A name is not the only way in. `FactorId(...)` can only be called inside the class, so
        // counting the construction sites catches a factory that is not called `of` anything —
        // `fromPerson`, `forId`, an `invoke` operator — which no name-shaped check ever will.
        assertEquals(
            "something other than the two factories constructs a FactorId",
            2,
            Regex("""=\s*FactorId\(""").findAll(code).count(),
        )
    }

    /**
     * The planted controls for the sweep above. Three, because it has three ways to be blind.
     *
     * Each mutation is derived from the source that is really there rather than written as a
     * literal, so none of them can silently be a no-op — `CLAUDE.md` §5's second most common bug
     * shape in this repository.
     */
    @Test
    fun `the factory check is not blind`() {
        val code = strippedSource()

        // 1. A named person factory.
        assertEquals(listOf("Person"), factoriesIn("fun ofPerson(id: Long): FactorId = FactorId(id)"))

        // 2. A bare `of(Long)` — the one the type's own header names, and the one the old `\w+`
        //    sweep could not see. It reads as the empty name, which is not in the expected list.
        val bare = code.replace(
            "fun ofActivity(id: Long): FactorId = FactorId(id)",
            "fun ofActivity(id: Long): FactorId = FactorId(id)\n            fun of(id: Long): FactorId = FactorId(id)",
        )
        assertNotEquals("the mutation did not land", code, bare)
        assertTrue("the bare factory was not seen at all", factoriesIn(bare).contains(""))
        assertNotEquals("a bare of(Long) would pass the sweep", listOf("Activity", "Tracker"), factoriesIn(bare))

        // 3. A factory whose name contains no `of` at all, caught by the construction count rather
        //    than by the name sweep — so the two checks are not the same check twice.
        val renamed = code.replace(
            "fun ofTracker(id: Long): FactorId = FactorId(id)",
            "fun ofTracker(id: Long): FactorId = FactorId(id)\n            fun fromPerson(id: Long): FactorId = FactorId(id)",
        )
        assertNotEquals("the mutation did not land", code, renamed)
        assertEquals("the name sweep sees it, so this proves nothing about the counter", listOf("Activity", "Tracker"), factoriesIn(renamed))
        assertEquals(3, Regex("""=\s*FactorId\(""").findAll(renamed).count())
    }

    /** Every `fun of…(` in [source], by the part of the name after `of` — which may be empty. */
    private fun factoriesIn(source: String): List<String> =
        Regex("""fun of(\w*)\(""").findAll(source).map { it.groupValues[1] }.toList()

    private fun strippedSource(): String =
        repoFile("app/src/main/java/com/daymark/app/stats/MoodCorrelations.kt").readText()
            .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
            .lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
}
