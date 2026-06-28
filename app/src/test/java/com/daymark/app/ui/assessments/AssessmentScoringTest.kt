package com.daymark.app.ui.assessments

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssessmentScoringTest {

    private fun maxScore(key: String): Int =
        Assessments.byKey(key)!!.questions.sumOf { q -> q.options.maxOf { it.points } }

    @Test
    fun maxScores_matchStandardRanges() {
        assertEquals(27, maxScore("phq9")) // 9 items × 3
        assertEquals(21, maxScore("gad7")) // 7 items × 3
        assertEquals(25, maxScore("who5")) // 5 items × 5
    }

    @Test
    fun phq9_bandBoundaries() {
        val s = Assessments.PHQ9
        assertEquals("Minimal", s.bandFor(4).label)
        assertEquals("Mild", s.bandFor(5).label)
        assertEquals("Moderate", s.bandFor(10).label)
        assertEquals("Moderately high", s.bandFor(15).label)
        assertEquals("High", s.bandFor(20).label)
    }

    @Test
    fun who5_displaysAsPercentage() {
        assertEquals("100 / 100", Assessments.displayScore("who5", 25))
        assertEquals("0 / 100", Assessments.displayScore("who5", 0))
        assertEquals("7", Assessments.displayScore("phq9", 7))
    }

    @Test
    fun phq9_selfHarmItemIsLast() {
        assertEquals(9, Assessments.PHQ9.questions.size)
        assertEquals(8, Assessments.PHQ9_SELF_HARM_INDEX)
        assertTrue(Assessments.PHQ9.questions[Assessments.PHQ9_SELF_HARM_INDEX].text.contains("better off dead"))
    }

    @Test
    fun all_haveCitations() {
        Assessments.ALL.forEach { assertTrue(it.title, it.citation.isNotBlank()) }
    }
}
