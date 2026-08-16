package com.daymark.app.goals

import com.daymark.app.goals.GoalBoard.Step
import com.daymark.app.goals.GoalBoard.StepState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The board's state machine, and the two things it is not allowed to do to a person's steps.
 *
 * Steps are a person's own writing about what they mean to do next, so the invariant at the top of
 * [GoalBoard] is what most of this file is:
 *
 * > Every board operation conserves the set of steps, and leaves every column numbered `0..n-1`.
 *
 * Both halves are swept over **every (step × target column) pair** rather than asserted at a chosen
 * point, because the two ways of breaking it are a step dropped on a path nobody tried and a hole
 * left in the column a step moved out of — neither of which shows up at the point you would pick by
 * hand. [conserves] and [contiguous] state the invariant independently of the implementation, and
 * [proves the detectors work][detectors_fire_on_a_board_that_actually_breaks_the_invariant] before
 * anything relies on them staying silent.
 *
 * The wording tests do the same thing for the product rule. Asserting that a string does *not* shame
 * is worthless unless the thing doing the asserting can recognise shaming, so [shames] is tried
 * against copy this product would never ship *first*, and only then against every summary the board
 * can produce.
 */
class GoalBoardTest {

    private val now = 1_700_000_000_000L

    /**
     * A board with three columns in use, an already-completed step, and a middle step in "to do" —
     * so that a move out of the middle has a gap to leave behind and a full column to join.
     */
    private fun board() = listOf(
        Step(1, "a", StepState.TO_DO, 0),
        Step(2, "b", StepState.TO_DO, 1),
        Step(3, "c", StepState.TO_DO, 2),
        Step(4, "d", StepState.DOING, 0),
        Step(5, "e", StepState.DONE, 0, completedAt = 111L),
    )

    /** The board's shape as one readable string, so a failure names what moved where. */
    private fun shape(steps: List<Step>): String = GoalBoard.COLUMNS.joinToString(" | ") { state ->
        "${state.key}=" + GoalBoard.columnOf(steps, state).joinToString(",") { it.title }
    }

    /** Half of the invariant, stated without reference to how the board does it. */
    private fun conserves(before: List<Step>, after: List<Step>): Boolean =
        before.map { it.id }.sorted() == after.map { it.id }.sorted()

    /** The other half: every column numbered 0..n-1, no gap and no repeat. */
    private fun contiguous(steps: List<Step>): Boolean = GoalBoard.COLUMNS.all { state ->
        val positions = steps.filter { it.state == state }.map { it.position }.sorted()
        positions == positions.indices.toList()
    }

    // --- the detectors, proved before they are trusted ---

    @Test
    fun detectors_fire_on_a_board_that_actually_breaks_the_invariant() {
        val good = board()

        val stepDropped = good.drop(1)
        assertFalse("a lost step must be detected", conserves(good, stepDropped))

        val stepInvented = good + Step(99, "z", StepState.TO_DO, 3)
        assertFalse("an invented step must be detected", conserves(good, stepInvented))

        val holeLeft = good.filterNot { it.id == 2L }
        assertFalse("a gap in a column must be detected", contiguous(holeLeft))

        val twoInOneSlot = good.map { if (it.id == 3L) it.copy(position = 1) else it }
        assertFalse("two steps in one slot must be detected", contiguous(twoInOneSlot))

        assertTrue("the untouched board must pass both", conserves(good, good) && contiguous(good))
    }

    // --- what moves are legal ---

    @Test
    fun every_pair_of_different_states_is_a_legal_move_in_both_directions() {
        var pairs = 0
        for (from in StepState.entries) {
            for (to in StepState.entries) {
                if (from == to) {
                    assertFalse("$from -> $to is not a move", GoalBoard.isLegalMove(from, to))
                } else {
                    pairs++
                    assertTrue(
                        "$from -> $to must be legal: the columns are not a ratchet",
                        GoalBoard.isLegalMove(from, to),
                    )
                }
            }
        }
        // Guards the sweep against an enum member being added and silently not tested.
        assertEquals(StepState.entries.size * (StepState.entries.size - 1), pairs)
    }

    @Test
    fun a_finished_step_can_be_moved_back_out_of_done() {
        val after = GoalBoard.move(board(), 5, StepState.TO_DO, now)
        assertEquals("todo=a,b,c,e | doing=d | done=", shape(after))
    }

    // --- the invariant, swept ---

    @Test
    fun no_move_of_any_step_to_any_column_loses_a_step_or_leaves_a_hole() {
        val before = board()
        var cases = 0
        for (step in before) {
            for (target in GoalBoard.COLUMNS) {
                val after = GoalBoard.move(before, step.id, target, now)
                cases++
                assertTrue(
                    "moving ${step.title} to $target lost or invented a step: ${shape(after)}",
                    conserves(before, after),
                )
                assertTrue(
                    "moving ${step.title} to $target left a hole: ${shape(after)}",
                    contiguous(after),
                )
            }
        }
        assertEquals(board().size * GoalBoard.COLUMNS.size, cases)
    }

    @Test
    fun no_reorder_of_any_step_by_any_offset_loses_a_step_or_leaves_a_hole() {
        val before = board()
        var cases = 0
        for (step in before) {
            for (delta in -3..3) {
                val after = GoalBoard.reorder(before, step.id, delta)
                cases++
                assertTrue(conserves(before, after))
                assertTrue("reorder ${step.title} by $delta: ${shape(after)}", contiguous(after))
                assertEquals(
                    "reorder must never change a step's column",
                    before.first { it.id == step.id }.state,
                    after.first { it.id == step.id }.state,
                )
            }
        }
        assertEquals(board().size * 7, cases)
    }

    // --- move ---

    @Test
    fun moving_out_of_the_middle_of_a_column_closes_the_gap_and_appends_at_the_far_end() {
        val after = GoalBoard.move(board(), 2, StepState.DOING, now)
        assertEquals("todo=a,c | doing=d,b | done=e", shape(after))
        assertEquals(1, after.first { it.id == 2L }.position)
        assertEquals(1, after.first { it.id == 3L }.position)
    }

    @Test
    fun entering_done_stamps_the_callers_clock_and_leaving_done_clears_it() {
        val done = GoalBoard.move(board(), 1, StepState.DONE, now)
        assertEquals(now, done.first { it.id == 1L }.completedAt)

        val undone = GoalBoard.move(board(), 5, StepState.TO_DO, now)
        assertNull(
            "a completion that was undone must not survive as a timestamp",
            undone.first { it.id == 5L }.completedAt,
        )
    }

    @Test
    fun a_move_to_the_state_a_step_is_already_in_changes_nothing_at_all() {
        val before = board()
        // Identity by reference, not by value: a no-op that rebuilt the list would re-stamp
        // completedAt on a double-tap of "done".
        assertSame(before, GoalBoard.move(before, 4, StepState.DOING, now))
        assertSame(before, GoalBoard.move(before, 5, StepState.DONE, now + 5_000))
        assertEquals(111L, GoalBoard.move(before, 5, StepState.DONE, now).first { it.id == 5L }.completedAt)
    }

    @Test
    fun an_id_that_is_not_on_the_board_changes_nothing() {
        val before = board()
        assertSame(before, GoalBoard.move(before, 404, StepState.DONE, now))
        assertSame(before, GoalBoard.reorder(before, 404, -1))
        assertSame(before, GoalBoard.remove(before, 404))
    }

    @Test
    fun a_gappy_or_repeated_input_numbering_is_normalised_rather_than_trusted() {
        val handEdited = listOf(
            Step(1, "a", StepState.TO_DO, 7),
            Step(2, "b", StepState.TO_DO, 7),
            Step(3, "c", StepState.TO_DO, 90),
        )
        assertFalse("the fixture must actually be broken", contiguous(handEdited))
        val after = GoalBoard.move(handEdited, 3, StepState.DOING, now)
        assertTrue(contiguous(after))
        assertEquals("todo=a,b | doing=c | done=", shape(after))
    }

    // --- reorder ---

    @Test
    fun reorder_shifts_within_a_column_and_clamps_at_both_ends() {
        assertEquals("todo=a,c,b | doing=d | done=e", shape(GoalBoard.reorder(board(), 3, -1)))
        assertEquals("todo=b,a,c | doing=d | done=e", shape(GoalBoard.reorder(board(), 1, +1)))

        val before = board()
        assertSame("top of a column stays there", before, GoalBoard.reorder(before, 1, -1))
        assertSame("bottom of a column stays there", before, GoalBoard.reorder(before, 3, +1))
        assertSame("alone in a column", before, GoalBoard.reorder(before, 4, -1))
        assertSame("a zero shift", before, GoalBoard.reorder(before, 2, 0))
    }

    @Test
    fun reorder_does_not_wrap_around() {
        // The failure this catches is a modulo where a clamp belongs: "up" from the top landing at
        // the bottom, which on a board of one's own plans looks like the app rearranged them.
        val before = board()
        assertEquals(shape(before), shape(GoalBoard.reorder(before, 1, -1)))
        assertNotEquals("todo=b,c,a | doing=d | done=e", shape(GoalBoard.reorder(before, 1, -1)))
    }

    // --- remove ---

    @Test
    fun removing_a_step_renumbers_the_column_it_left() {
        val after = GoalBoard.remove(board(), 2)
        assertEquals("todo=a,c | doing=d | done=e", shape(after))
        assertTrue(contiguous(after))
        assertEquals(4, after.size)
        assertEquals(1, after.first { it.id == 3L }.position)
    }

    // --- progress ---

    @Test
    fun progress_counts_done_against_every_step_regardless_of_column() {
        assertEquals(GoalBoard.Progress(done = 1, total = 5), GoalBoard.progressOf(board()))
        assertEquals(GoalBoard.Progress(0, 0), GoalBoard.progressOf(emptyList()))

        val allDone = board().map { it.copy(state = StepState.DONE) }
        assertEquals(GoalBoard.Progress(5, 5), GoalBoard.progressOf(allDone))
    }

    @Test
    fun nextPosition_is_the_end_of_its_own_column_and_ignores_the_others() {
        assertEquals(3, GoalBoard.nextPosition(board(), StepState.TO_DO))
        assertEquals(1, GoalBoard.nextPosition(board(), StepState.DOING))
        assertEquals(0, GoalBoard.nextPosition(emptyList(), StepState.TO_DO))
    }

    // --- the wording rule, with the detector proved first ---

    /**
     * Vocabulary this product does not use about someone's own plans, from
     * `docs/DECISIONS_2026-08.md` §D6 and the no-shaming rule: anything that counts undone things,
     * anything that turns a count into a rate, and anything that frames a pause as a lapse.
     */
    private val bannedWords = listOf(
        "%", "percent", "streak", "roll", "chain", "overdue", "behind", "late",
        "remaining", "left to", "still to", "failed", "fail", "missed", "lapse", "don't break",
        "keep it up", "on track", "off track",
    )

    private fun shames(text: String): Boolean =
        bannedWords.any { text.contains(it, ignoreCase = true) }

    @Test
    fun the_wording_detector_recognises_copy_this_product_would_not_ship() {
        assertTrue(shames("You're on a 5-day roll, don't break it!"))
        assertTrue(shames("70% complete"))
        assertTrue(shames("4 steps remaining"))
        assertTrue(shames("This project is overdue"))
        assertTrue(shames("You missed a day"))
        assertFalse("the fact itself must pass", shames("3 of 7 steps done"))
        assertFalse(shames("No steps yet"))
    }

    @Test
    fun no_progress_summary_the_board_can_produce_shames_anyone() {
        var checked = 0
        for (total in 0..30) {
            for (done in 0..total) {
                val summary = GoalBoard.Progress(done, total).summary
                checked++
                assertFalse("summary shames: \"$summary\"", shames(summary))
                assertTrue(
                    "a summary must state the count it is about: \"$summary\"",
                    total == 0 || summary.contains("$done") && summary.contains("$total"),
                )
            }
        }
        assertEquals(496, checked)
    }

    @Test
    fun the_summary_reads_as_a_plain_fact_at_every_boundary() {
        assertEquals("No steps yet", GoalBoard.Progress(0, 0).summary)
        assertEquals("0 of 1 step done", GoalBoard.Progress(0, 1).summary)
        assertEquals("1 of 1 step done", GoalBoard.Progress(1, 1).summary)
        assertEquals("3 of 7 steps done", GoalBoard.Progress(3, 7).summary)
        assertEquals("7 of 7 steps done", GoalBoard.Progress(7, 7).summary)
    }

    @Test
    fun no_column_copy_shames_anyone_either() {
        for (state in StepState.entries) {
            assertFalse(state.columnTitle, shames(state.columnTitle))
            assertFalse(state.emptyNote, shames(state.emptyNote))
            assertTrue("an empty column must say something", state.emptyNote.isNotBlank())
        }
    }

    // --- canSave ---

    @Test
    fun a_project_is_not_savable_as_a_title_alone_but_a_habit_is() {
        assertFalse(GoalBoard.canSave(GoalKind.PROJECT, "Learn statistics", stepCount = 0))
        assertTrue(GoalBoard.canSave(GoalKind.PROJECT, "Learn statistics", stepCount = 1))
        assertTrue(GoalBoard.canSave(GoalKind.HABIT, "Exercise", stepCount = 0))
    }

    @Test
    fun nothing_of_either_kind_is_savable_without_a_title() {
        for (kind in GoalKind.entries) {
            for (count in 0..3) {
                assertFalse(GoalBoard.canSave(kind, "", count))
                assertFalse(GoalBoard.canSave(kind, "   ", count))
            }
        }
    }

    // --- keys read back ---

    @Test
    fun an_unrecognised_stored_key_reads_back_as_the_older_meaning_rather_than_throwing() {
        // The case: a row written by a later version, or a hand-edited backup file.
        assertEquals(GoalKind.HABIT, GoalKind.fromKey("something_a_later_version_added"))
        assertEquals(GoalKind.HABIT, GoalKind.fromKey(null))
        assertEquals(StepState.TO_DO, StepState.fromKey("blocked"))
        assertEquals(StepState.TO_DO, StepState.fromKey(null))
    }

    @Test
    fun every_key_round_trips_and_no_two_share_one() {
        for (kind in GoalKind.entries) assertEquals(kind, GoalKind.fromKey(kind.key))
        for (state in StepState.entries) assertEquals(state, StepState.fromKey(state.key))
        assertEquals(GoalKind.entries.size, GoalKind.entries.map { it.key }.toSet().size)
        assertEquals(StepState.entries.size, StepState.entries.map { it.key }.toSet().size)
    }

    @Test
    fun the_board_has_a_column_for_every_state_exactly_once() {
        assertEquals(StepState.entries.toSet(), GoalBoard.COLUMNS.toSet())
        assertEquals(StepState.entries.size, GoalBoard.COLUMNS.size)
    }
}
