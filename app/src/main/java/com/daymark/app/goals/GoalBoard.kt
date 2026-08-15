package com.daymark.app.goals

/**
 * The three-column board a [GoalKind.PROJECT] goal's steps live on: what states exist, which moves
 * are legal, and the one sentence a project is allowed to say about its own progress.
 *
 * Import-free on purpose, the same rule `stats/InterruptionBudget.kt` and `export/ReportLayout.kt`
 * keep, so every rule below is decided in a file a plain-JVM unit test can reach. The board never
 * touches Room, a clock or a `Context`: [Step] is a plain mirror of the `goal_steps` row (exactly
 * as `InterruptionBudget.Offer` mirrors an `offer_records` row), and the caller passes the clock in.
 *
 * ## What the board deliberately does not have
 *
 * **No due dates, and no field that could become one.** The brief was to justify one or omit it, and
 * there is no version of a due date that cannot become a stick: the moment a date exists, "past it"
 * exists, and a step that has been sitting for six weeks acquires a state it did not have before.
 * A person can write "by Friday" into a step's own title if a date matters to *them* — that is their
 * sentence, not a field the app compares the clock against.
 *
 * **No rate, percentage or fraction.** [Progress] carries two integers and renders as *"3 of 7 steps
 * done"*. `docs/DECISIONS_2026-08.md` §D6 rules out "velocity, burndown, percentage rings, or count
 * badges on pending columns", and a percentage is the shape that most easily turns into a target.
 * Note which half that leaves out: `done` and `total` are stated, "4 still to do" is not, because a
 * count of undone things is the one number on this screen that reads as an accusation.
 *
 * **No ordering of the columns by virtue.** [StepState.DONE] is last because left-to-right is how
 * the board is read, not because it is the good end. Every move between columns is legal in both
 * directions — see [isLegalMove].
 *
 * ## The invariant the tests exist for
 *
 * > Every board operation conserves the set of steps, and leaves every column numbered `0..n-1`.
 *
 * Steps are a person's own writing about what they mean to do. An operation that drops one, or that
 * leaves two steps claiming the same slot, loses or scrambles that — so both halves are asserted
 * over every (step × target column) pair rather than at a chosen point, in `GoalBoardTest`.
 */
object GoalBoard {

    /**
     * Where a step is right now.
     *
     * [key] is what the row stores, so an unrecognised value read back later is a string comparison
     * and not a crash — same reasoning as [GoalKind]. [emptyNote] is the column's copy when it holds
     * nothing: an empty column is an ordinary state of a board, not a gap to be filled, so none of
     * these three lines asks for anything.
     */
    enum class StepState(val key: String, val columnTitle: String, val emptyNote: String) {
        TO_DO("todo", "To do", "Nothing here."),
        DOING("doing", "Doing", "Nothing here."),
        DONE("done", "Done", "Nothing here yet."),
        ;

        companion object {
            /** Never throws: an unrecognised key reads as [TO_DO], the state a new step starts in. */
            fun fromKey(key: String?): StepState = entries.firstOrNull { it.key == key } ?: TO_DO
        }
    }

    /** The columns left to right. Board order, and the order [move] and [reorder] return steps in. */
    val COLUMNS: List<StepState> = listOf(StepState.TO_DO, StepState.DOING, StepState.DONE)

    /**
     * One step, as this file needs it — a plain mirror of the `goal_steps` row.
     *
     * [createdAt] is carried and never read here. It exists so that a round-trip through the board
     * (load → move → save) cannot quietly reset when the person wrote the step down; leaving it out
     * of this type is exactly how that would happen, since the editor holds the whole step in memory
     * between opening and saving.
     *
     * [id] identifies a step within one board only. The editor uses negative ids for steps typed but
     * not yet saved, so that a new project's board works before its goal has a row — see
     * `ui/goals/GoalEditorViewModel.kt`.
     */
    data class Step(
        val id: Long,
        val title: String,
        val state: StepState,
        val position: Int,
        val createdAt: Long = 0,
        val completedAt: Long? = null,
    )

    /**
     * Whether a step may go from [from] to [to]. Every pair of *different* states is legal, in both
     * directions, and that is the rule rather than an omission.
     *
     * Moving a card back out of "done", or out of "doing" and into "to do", is a person correcting
     * the board to match what is true. A board that only moved one way would make an honest
     * correction cost something, and would quietly turn the columns into a ratchet — which is the
     * shape §D6 rules out under "throughput metrics". Nothing here counts a backwards move, and
     * nothing records that one happened.
     *
     * A move to the state a step is already in is not illegal, it is nothing: [move] returns the
     * board unchanged, so a double-tap cannot re-stamp [Step.completedAt].
     */
    fun isLegalMove(from: StepState, to: StepState): Boolean = from != to

    /**
     * [steps] with the step [id] moved into column [to], appended at that column's end.
     *
     * Returns [steps] unchanged when the id is absent or the move is not one ([isLegalMove]).
     * [nowMillis] is the caller's clock: entering [StepState.DONE] stamps [Step.completedAt] with
     * it, and leaving [StepState.DONE] clears it, so the field can never claim a completion that was
     * undone. Nothing else reads that timestamp — it is not compared against the clock anywhere, by
     * the rule at the top of this file.
     *
     * Both the column left and the column joined come back numbered from 0, so a gap or a repeated
     * position in the input — an interrupted save, a hand-edited backup — is normalised rather than
     * trusted.
     */
    fun move(steps: List<Step>, id: Long, to: StepState, nowMillis: Long): List<Step> {
        val target = steps.firstOrNull { it.id == id } ?: return steps
        if (!isLegalMove(target.state, to)) return steps
        val moved = target.copy(
            state = to,
            completedAt = if (to == StepState.DONE) nowMillis else null,
        )
        val rest = steps.filter { it.id != id }
        return assemble(
            COLUMNS.map { state ->
                val existing = columnOf(rest, state)
                if (state == to) existing + moved else existing
            },
        )
    }

    /**
     * [steps] with the step [id] shifted [delta] places within its own column — negative is towards
     * the top.
     *
     * A step at either end stays there: the shift is clamped rather than wrapped, so holding "up"
     * cannot teleport a step to the bottom. Returns [steps] unchanged when the id is absent, when
     * [delta] is 0, or when the shift would leave the column. A step never changes state here; that
     * is [move]'s job, and keeping them separate means a mis-aimed tap cannot silently complete
     * something.
     */
    fun reorder(steps: List<Step>, id: Long, delta: Int): List<Step> {
        val target = steps.firstOrNull { it.id == id } ?: return steps
        if (delta == 0) return steps
        val shifted = columnOf(steps, target.state).toMutableList()
        val from = shifted.indexOfFirst { it.id == id }
        val to = from + delta
        if (from < 0 || to < 0 || to >= shifted.size) return steps
        shifted.removeAt(from)
        shifted.add(to, target)
        return assemble(
            COLUMNS.map { state -> if (state == target.state) shifted else columnOf(steps, state) },
        )
    }

    /**
     * [steps] without the step [id], with the column it left renumbered.
     *
     * Here rather than a `filterNot` at the call site so that removal keeps the same contiguity
     * invariant as [move] and [reorder] — a caller that filtered would leave a hole, and the hole
     * would only show up later as a step that will not reorder past the gap.
     */
    fun remove(steps: List<Step>, id: Long): List<Step> {
        if (steps.none { it.id == id }) return steps
        val remaining = steps.filterNot { it.id == id }
        return assemble(COLUMNS.map { state -> columnOf(remaining, state) })
    }

    /** The steps in one column, top to bottom. Equal positions keep their existing order. */
    fun columnOf(steps: List<Step>, state: StepState): List<Step> =
        steps.filter { it.state == state }.sortedBy { it.position }

    /**
     * What a project may say about itself: two counts and nothing derived from them.
     *
     * There is no percentage, no fraction and no "remaining" — see the file header. [total] is every
     * step regardless of column, so the sentence stays true while steps are being added.
     */
    data class Progress(val done: Int, val total: Int) {

        /**
         * The one line the UI shows. A fact, in the person's own units.
         *
         * Nothing here congratulates, warns or compares to last week. "3 of 7 steps done" reads the
         * same on the day a project is finished and on the day it has been untouched for a month,
         * which is the point: a project with no movement is not a state the app has an opinion
         * about.
         */
        val summary: String
            get() = when {
                total == 0 -> "No steps yet"
                total == 1 -> "$done of 1 step done"
                else -> "$done of $total steps done"
            }
    }

    fun progressOf(steps: List<Step>): Progress =
        Progress(done = steps.count { it.state == StepState.DONE }, total = steps.size)

    /**
     * Whether a goal is worth saving yet.
     *
     * A habit needs a title, as it always has. A project needs a title **and at least one step**,
     * which is §D5 verbatim: *"A project is a folder for steps, not a standalone aspiration. It
     * should not be savable in a useful state with only a title — abstract goals are the shape
     * people with depression already over-produce, and abstractness is implicated in rumination."*
     *
     * This is the one place in the feature that refuses something, so it is worth being clear about
     * what it is not: it is a gate on *creating* an empty container, not a standard a project has to
     * keep meeting. Deleting every step of a saved project is allowed, and nothing chases the person
     * to put one back.
     */
    fun canSave(kind: GoalKind, title: String, stepCount: Int): Boolean = when {
        title.isBlank() -> false
        kind == GoalKind.PROJECT -> stepCount >= 1
        else -> true
    }

    /**
     * The position a newly typed step takes in [state]: the end of that column.
     *
     * Reads the column rather than counting the list, so adding to "to do" is unaffected by what is
     * in the other two columns.
     */
    fun nextPosition(steps: List<Step>, state: StepState): Int = columnOf(steps, state).size

    /**
     * Flattens columns back into one list, renumbering each from 0.
     *
     * The single definition of "the positions are correct" in this file — every operation above ends
     * here, so none of them can invent its own numbering, and the contiguity property the tests
     * assert holds by construction rather than by each function remembering to.
     */
    private fun assemble(columns: List<List<Step>>): List<Step> =
        columns.flatMap { column -> column.mapIndexed { index, step -> step.copy(position = index) } }
}
