package com.daymark.app.goals

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GoalReached]: what the mark means, when it moves, and what the words around it are allowed to
 * say.
 *
 * The trap this feature exists to avoid — reading `Goal.archived` as "reached", and so drawing a
 * star for someone giving up on a goal — cannot be tested here, because [GoalReached] is not given
 * `archived` at all. That is the point of the signature, and it is checked as a signature in
 * `GoalReachedSchemaTest`, together with the rest of the wiring a plain-JVM test cannot execute.
 * What is tested here is everything this file does decide.
 */
class GoalReachedTest {

    private companion object {
        /** An ordinary marking time, and one much later, so "kept" and "re-stamped" cannot agree. */
        const val MARKED = 1_700_000_000_000L
        const val LATER = 1_800_000_000_000L
    }

    // ---------------------------------------------------------------------------------------------
    // isReached
    // ---------------------------------------------------------------------------------------------

    /**
     * Null and only null means unmarked.
     *
     * `0L` is the case worth naming: it is 1 January 1970, a real instant, and it is what a
     * round-trip that flattened a nullable timestamp would produce. Reading it as "unset" would make
     * a corrupted backup look like a goal nobody had marked, and reading it as reached — which is
     * what happens here — leaves the wrong date visible where someone can see it and fix it.
     */
    @Test
    fun `only a null timestamp is unmarked, and zero is a real instant`() {
        assertFalse(GoalReached.isReached(null))
        assertTrue(GoalReached.isReached(0L))
        assertTrue(GoalReached.isReached(MARKED))
        // Before the epoch, which a hand-edited backup can hold. Still a mark, not an absence.
        assertTrue(GoalReached.isReached(-1L))
    }

    // ---------------------------------------------------------------------------------------------
    // stamp
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `marking an unmarked goal stores the moment the person marked it`() {
        assertEquals(MARKED, GoalReached.stamp(current = null, reached = true, nowMillis = MARKED))
    }

    /**
     * Marking a goal that is already marked keeps the original date rather than re-stamping.
     *
     * This is the one that goes wrong silently. The date is what the Sky places the star on, and a
     * second `true` arrives without anyone tapping anything — a recomposition delivering the
     * switch's state again, an editor opened and saved for a title change. A re-stamp would move a
     * star from the week it belongs in to today, and nothing anywhere would report that it moved.
     */
    @Test
    fun `re-marking an already-marked goal does not move the date`() {
        // The detector: the two clocks must actually differ, or "kept" and "re-stamped" are the same
        // assertion and this test cannot fail.
        assertNotEquals(MARKED, LATER)

        assertEquals(MARKED, GoalReached.stamp(current = MARKED, reached = true, nowMillis = LATER))
        // And a re-stamping implementation would have returned the later clock, so the expectation
        // above distinguishes the two rather than agreeing with both.
        assertNotEquals(LATER, GoalReached.stamp(current = MARKED, reached = true, nowMillis = LATER))
    }

    /**
     * Unmarking clears the timestamp outright, from either state.
     *
     * No "was reached on…" residue is kept. Someone who ticked this by accident gets the goal back
     * exactly as it was; a stored record of a retracted claim is a record of a mistake, kept about
     * them, for nothing.
     */
    @Test
    fun `unmarking clears the date and leaves nothing behind`() {
        assertNull(GoalReached.stamp(current = MARKED, reached = false, nowMillis = LATER))
        assertNull(GoalReached.stamp(current = null, reached = false, nowMillis = LATER))
        assertNull(GoalReached.stamp(current = 0L, reached = false, nowMillis = LATER))
    }

    // ---------------------------------------------------------------------------------------------
    // toggled
    // ---------------------------------------------------------------------------------------------

    /**
     * A person who marks a goal by accident is one tap from where they started.
     *
     * The round trip has to land on `null` exactly, not on "some falsy value": `0L` would read as
     * reached at the epoch by [GoalReached.isReached] above, and would put a star on the Sky in
     * 1970.
     */
    @Test
    fun `marking and unmarking returns a goal to where it started`() {
        val marked = GoalReached.toggled(current = null, nowMillis = MARKED)
        assertEquals(MARKED, marked)
        assertTrue(GoalReached.isReached(marked))

        val unmarked = GoalReached.toggled(current = marked, nowMillis = LATER)
        assertNull(unmarked)
        assertFalse(GoalReached.isReached(unmarked))
    }

    /**
     * Marking again after an unmark takes the new time, not the old one.
     *
     * Two taps are two decisions, and the second one is about today. This is the case
     * [GoalReached.stamp]'s "keep the existing date" rule must not swallow — it keeps a date only
     * while the mark is unbroken.
     */
    @Test
    fun `re-marking after an unmark takes the new time, not the old one`() {
        val again = GoalReached.toggled(GoalReached.toggled(MARKED, LATER), LATER)
        assertEquals(LATER, again)
        assertNotEquals("the original date came back through an unmark", MARKED, again)
    }

    // ---------------------------------------------------------------------------------------------
    // The words
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `the action label names the direction a tap would go`() {
        assertEquals(GoalReached.MARK_ACTION, GoalReached.actionLabel(null))
        assertEquals(GoalReached.UNMARK_ACTION, GoalReached.actionLabel(MARKED))
        // The two must differ, or a screen reader is told the same thing in both states.
        assertNotEquals(GoalReached.MARK_ACTION, GoalReached.UNMARK_ACTION)
    }

    /**
     * Nothing in this feature's copy praises, grades or accuses.
     *
     * A "goal reached" control is where praise arrives first and looks most harmless — "Nice work!",
     * "Completed", a streak count. `docs/DECISIONS_2026-08.md` §D6 rules out the reward vocabulary,
     * and `sky/Sky.kt` states the rule these strings follow: naming, not praise, and no exclamation
     * mark, because congratulation is evaluation.
     */
    @Test
    fun `none of the copy praises, grades or accuses`() {
        val copy = listOf(
            GoalReached.MARK_ACTION,
            GoalReached.UNMARK_ACTION,
            GoalReached.LABEL,
            GoalReached.HINT,
            GoalReached.REACHED_NOTE,
        )

        // The detector must fire before its silence means anything. These are the lines this
        // feature would actually have shipped if nobody was watching.
        assertTrue("detector is broken", judges("Nice work!"))
        assertTrue("detector is broken", judges("Goal completed — 3 day streak"))
        assertTrue("detector is broken", judges("You gave up on this one"))
        assertTrue("detector is broken", judges("Overdue"))
        assertTrue("detector is broken", judges("Well done"))
        // And it must not be so greedy that it would reject any sentence at all.
        assertFalse("detector is too greedy", judges("Mark as reached"))
        assertFalse("detector is too greedy", judges("Yours to say, and yours to take back."))

        assertEquals(5, copy.size)
        for (line in copy) {
            assertTrue("copy is empty, so the check below reads nothing", line.isNotEmpty())
            assertFalse("this line judges the person: \"$line\"", judges(line))
        }
    }

    private fun judges(line: String): Boolean =
        listOf(
            "!", "nice", "well done", "congrat", "great", "amazing", "proud",
            "streak", "complete", "achiev", "unlock", "reward", "badge", "level",
            "fail", "overdue", "behind", "missed", "gave up", "give up", "abandon", "quit", "should",
        ).any { line.contains(it, ignoreCase = true) }
}
