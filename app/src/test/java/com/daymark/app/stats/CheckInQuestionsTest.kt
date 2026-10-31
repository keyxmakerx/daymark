package com.daymark.app.stats

import com.daymark.app.stats.CheckInQuestions.Candidate
import com.daymark.app.stats.CheckInQuestions.Closing
import com.daymark.app.stats.CheckInQuestions.Moment
import com.daymark.app.stats.InterruptionBudget.Kind
import com.daymark.app.stats.InterruptionBudget.Offer
import com.daymark.app.stats.InterruptionBudget.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The extra questions and the closing screen's offers, held to the four rules in
 * [CheckInQuestions]' header. Ledgers are swept exhaustively up to a length rather than sampled, so
 * no draw decides a result, and every absence has a positive control beside it.
 */
class CheckInQuestionsTest {

    private val hour = 3_600_000L
    private val day = 24 * hour
    private val start = 1_700_000_000_000L - (1_700_000_000_000L % day)

    private val unknownKey = "vanished_in_a_later_version"
    private val outcomes = listOf(Outcome.ACCEPTED.key, Outcome.DISMISSED.key, Outcome.SNOOZED.key, unknownKey)

    /** A question row on day [n], mid-morning. */
    private fun row(n: Int, outcome: String) = Offer(Kind.QUESTION.key, start + n * day + 9 * hour, outcome)

    /** Every ledger up to [maxLength] rows, one a day on days 1.., oldest first. */
    private fun ledgers(maxLength: Int): List<List<Offer>> {
        var level = listOf(emptyList<Offer>())
        val all = mutableListOf<List<Offer>>()
        all += level
        for (n in 1..maxLength) {
            level = level.flatMap { ledger -> outcomes.map { ledger + row(n, it) } }
            all += level
        }
        return all
    }

    /** A check-in on day [n] at six in the evening. */
    private fun moment(n: Int, mood: Int = 3, hardTime: Boolean = false, allowed: Int = 3) =
        Moment(mood, hardTime, allowed, start + n * day + 18 * hour, start + n * day)

    private fun fresh(subject: Long) = Candidate(subject, answeredToday = false, rows = emptyList())

    @Test
    fun `no input puts more questions in a check-in than the person allowed`() {
        val some = ledgers(3)
        val candidates = (1L..5L).map { s -> Candidate(s, false, some[(s * 7 % some.size).toInt()]) }
        for (allowed in -2..6) for (mood in 1..5) for (hard in listOf(false, true)) {
            val asked = CheckInQuestions.plan(moment(10, mood, hard, allowed), candidates)
            assertTrue("allowed $allowed, mood $mood, hard $hard: $asked", asked.size <= allowed.coerceIn(CheckInQuestions.ALLOWED))
        }
        // Positive control: five fresh questions and the most the person can allow gives three.
        assertEquals(listOf(1L, 2L, 3L), CheckInQuestions.plan(moment(10), (1L..5L).map(::fresh)))
        assertEquals(listOf(1L), CheckInQuestions.plan(moment(10, allowed = CheckInQuestions.DEFAULT_ALLOWED), (1L..5L).map(::fresh)))
    }

    @Test
    fun `a lower mood never adds a question, and the hard-time switch never adds one`() {
        val pool = ledgers(3)
        for ((i, ledger) in pool.withIndex()) {
            val candidates = listOf(Candidate(1L, false, ledger), fresh(2L), Candidate(3L, i % 2 == 0, ledger))
            for (lower in 1..5) for (higher in lower..5) {
                val atLower = CheckInQuestions.plan(moment(10, lower), candidates)
                val atHigher = CheckInQuestions.plan(moment(10, higher), candidates)
                assertTrue("mood $lower asked $atLower, mood $higher asked $atHigher", atHigher.containsAll(atLower))
            }
            for (mood in 1..5) {
                val hard = CheckInQuestions.plan(moment(10, mood, hardTime = true), candidates)
                assertTrue("hard time asked $hard at mood $mood", hard.isEmpty())
            }
        }
        // Positive controls: Meh and better ask; Awful and Bad end the check-in at once.
        val one = listOf(fresh(1L))
        for (mood in 3..5) assertEquals(listOf(1L), CheckInQuestions.plan(moment(10, mood), one))
        for (mood in 1..CheckInQuestions.SHORT_PATH_MOOD_MAX) assertEquals(emptyList<Long>(), CheckInQuestions.plan(moment(10, mood), one))
    }

    /** The first day from [from] on which a question with [ledger] may be asked, or null by day 400. */
    private fun firstDayAsked(ledger: List<Offer>, from: Int): Int? =
        (from..400).firstOrNull { CheckInQuestions.mayAsk(Candidate(1L, false, ledger), moment(it)) }

    @Test
    fun `skipping never brings a question back sooner`() {
        for (ledger in ledgers(5)) {
            val next = ledger.size + 1
            val skipped = firstDayAsked(ledger + row(next, Outcome.DISMISSED.key), next)
            val answered = firstDayAsked(ledger + row(next, Outcome.ACCEPTED.key), next)
            assertTrue("$ledger: answered $answered", answered != null)
            assertTrue("$ledger: skipped never asked again", skipped != null)
            assertTrue("$ledger: skipped back on $skipped, answered on $answered", skipped!! >= answered!!)
        }
        // Positive control: two skips running rest the question past the next day; answers do not.
        val twoSkips = listOf(row(1, Outcome.DISMISSED.key), row(2, Outcome.DISMISSED.key))
        val twoAnswers = listOf(row(1, Outcome.ACCEPTED.key), row(2, Outcome.ACCEPTED.key))
        assertEquals(3, firstDayAsked(twoAnswers, 3))
        assertTrue(firstDayAsked(twoSkips, 3)!! > 3)
    }

    @Test
    fun `a question is asked at most once a day, and never for what is already answered`() {
        val askedThisMorning = listOf(row(10, Outcome.DISMISSED.key))
        assertFalse(CheckInQuestions.mayAsk(Candidate(1L, false, askedThisMorning), moment(10)))
        assertFalse(CheckInQuestions.mayAsk(Candidate(1L, answeredToday = true, rows = emptyList()), moment(10)))
        // Positive controls: asked yesterday and answered, and nothing logged today, it asks.
        assertTrue(CheckInQuestions.mayAsk(Candidate(1L, false, listOf(row(9, Outcome.ACCEPTED.key))), moment(10)))
        assertTrue(CheckInQuestions.mayAsk(fresh(1L), moment(10)))
        // Another question's rows, or another kind's, never count against this one.
        val alien = listOf(Offer(Kind.TRACKER.key, start + 10 * day + 9 * hour, Outcome.DISMISSED.key))
        assertTrue(CheckInQuestions.mayAsk(Candidate(1L, false, alien), moment(10)))
    }

    @Test
    fun `no answer, silence or setting adds a sky offer to the closing screen`() {
        val paces = (0..30).map { CheckInEngine.paceAfter(it) }.distinct() + CheckInEngine.Pace.Off
        for (mood in 1..5) for (hard in listOf(false, true)) for (pace in paces) {
            val offers = CheckInQuestions.closingOffers(mood, hard, pace)
            val ceiling = CheckInQuestions.closingOffers(mood, false, CheckInEngine.Pace.AsSet)
            assertTrue("mood $mood, hard $hard, pace $pace: $offers", ceiling.containsAll(offers))
            if (mood <= CheckInQuestions.SHORT_PATH_MOOD_MAX) assertTrue("mood $mood offered $offers", offers.isEmpty())
            if (hard) assertFalse("hard time offered See it land", Closing.SEE_IT_LAND in offers)
            if (pace != CheckInEngine.Pace.AsSet) assertTrue("eased off and still offered $offers", offers.isEmpty())
        }
        // Positive control: a neutral answer with check-ins as set offers both.
        assertEquals(setOf(Closing.SEE_IT_LAND, Closing.YOUR_SKY), CheckInQuestions.closingOffers(3, false, CheckInEngine.Pace.AsSet))
        assertEquals(setOf(Closing.YOUR_SKY), CheckInQuestions.closingOffers(4, true, CheckInEngine.Pace.AsSet))
    }
}
