package com.daymark.app.goals

/**
 * What "reached" means for a goal, and the copy that says it out loud.
 *
 * Import-free on purpose, the same rule [GoalBoard] keeps, so every rule below is decided in a file
 * a plain-JVM unit test can reach. No clock is read here — the caller passes [nowMillis] in.
 *
 * ## The one thing this file exists to keep apart
 *
 * `Goal.archived` is not this. Archiving is what a person does when they give up on something, put
 * it aside, or lose interest, and `docs/DECISIONS_2026-08.md` §D5 protects that act: *"Abandoning is
 * one tap, neutrally worded, never counted as failure."* Reading an archived goal as a reached one
 * would file that act under achievement and, on the Sky, draw a star for it — the app congratulating
 * someone for letting go of something, on the one surface whose whole claim is that it does not
 * judge. That is why [isReached] is given the timestamp and nothing else: `archived` is not a
 * parameter here, so no amount of later editing can quietly route it in.
 *
 * ## Why nothing computes this
 *
 * A habit goal that hits `targetPerWeek` this week is **not** reached, and a project whose steps are
 * all in *Done* is **not** reached. Both are thresholds, and a threshold that flips a goal to reached
 * is the app deciding something about a person and then acting on it — the exact shape §D1a and §D6
 * exist to block, and one tick away from a streak. It would also be wrong on its own terms: someone
 * can hit 3× a week for a month and know the goal is nowhere near reached, and someone can decide a
 * goal is reached in a week they logged nothing.
 *
 * So the only inputs are the stored timestamp and a person's tap. Nothing in this file takes a
 * count, a target, a fraction or a date range, and `GoalReachedTest` asserts that none appears.
 */
object GoalReached {

    /** Whether the person has marked this goal reached. Null means they have not; that is all. */
    fun isReached(reachedAt: Long?): Boolean = reachedAt != null

    /**
     * The `reachedAt` to store when the person sets the mark to [reached].
     *
     * Setting it to `true` on a goal that is **already** marked keeps [current] rather than
     * re-stamping. The date is what the Sky places the star on, so a second `true` — a re-composition
     * delivering the switch's state again, or an editor saved twice — would otherwise move a star the
     * person placed weeks ago to today, and nothing anywhere would report that it had moved. Same
     * reasoning as [GoalBoard.move] refusing to re-stamp `completedAt` on a no-op move.
     *
     * Setting it to `false` clears it outright. A person who ticked this by accident, or who decided
     * they were wrong, gets the goal back exactly as it was — no "was reached on…" residue, because a
     * record of a retracted claim is a record of a mistake, kept about them, for nothing.
     */
    fun stamp(current: Long?, reached: Boolean, nowMillis: Long): Long? =
        if (reached) current ?: nowMillis else null

    /** [stamp] driven from the current value: mark an unmarked goal, unmark a marked one. */
    fun toggled(current: Long?, nowMillis: Long): Long? =
        stamp(current, !isReached(current), nowMillis)

    /**
     * The label on the control that sets the mark.
     *
     * Both halves name the act and neither grades it. "Mark as reached" is not "Complete!" and
     * "Unmark as reached" is not "Give up" or "Reopen" — an undo must cost a person nothing, and a
     * word with a verdict in it is a cost. There is no exclamation mark in either, for the reason
     * `sky/Sky.kt` gives about its own introductions: congratulation is evaluation.
     */
    fun actionLabel(reachedAt: Long?): String =
        if (isReached(reachedAt)) UNMARK_ACTION else MARK_ACTION

    const val MARK_ACTION: String = "Mark as reached"
    const val UNMARK_ACTION: String = "Unmark as reached"

    /**
     * The name of the control itself, next to the switch.
     *
     * A noun and not a verb, because it labels a state a person can see the truth of at a glance,
     * while [actionLabel] — which says what a tap would do — goes to the switch's content
     * description, where a screen reader needs the direction spelled out.
     */
    const val LABEL: String = "Reached"

    /**
     * Under the control, so the person knows whose judgement this is before they use it.
     *
     * "Yours to say" answers the question someone will actually have — *does the app decide this?* —
     * and "yours to take back" is the reassurance that makes an accidental tap survivable. Neither
     * sentence asks for anything.
     */
    const val HINT: String = "Yours to say, and yours to take back."

    /**
     * What a goals-list card says instead of a progress line once the mark is set.
     *
     * Names who did it, in the person's own past tense, the way `SkyKind.introduction` names a star's
     * form. Not "Goal reached" — which reads as the app's verdict — and not "Well done", which is
     * praise, which is evaluation.
     */
    const val REACHED_NOTE: String = "You marked this reached."
}
