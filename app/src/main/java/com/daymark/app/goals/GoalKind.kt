package com.daymark.app.goals

/**
 * What shape a goal is — and the reason `Goal` did not grow a `steps` list.
 *
 * `docs/CLINICIAN_FEEDBACK.md` §8: *"Read a book on X" and "exercise 3× a week" are not the same
 * shape and the model only expresses the second.* Rather than replace the weekly count, which
 * people are using right now and which `docs/DECISIONS_2026-08.md` §D5 keeps ("the
 * implementation-intention part is the well-evidenced piece and should survive"), a goal now
 * declares which of the two it is, and the two coexist.
 *
 * ## Why a key and not a Room enum column
 *
 * Stored as [key] text, the same way `Tracker.type`, `Treatment.kind` and `OfferRecord.kind` are.
 * A kind this version does not recognise — an older backup, a hand-edited file, a row a later
 * version wrote — must read back as *something* rather than crash, and [fromKey] decides that here
 * where it can be tested, instead of leaving it to a type converter that would throw.
 *
 * ## Why the unknown case resolves to [HABIT]
 *
 * [HABIT] is what every row written before this change means, and the column's SQL default. An
 * unreadable kind shown as a habit displays a weekly count the row actually has; shown as a project
 * it would display an empty board and look like a goal whose steps had been lost. Reading an
 * unknown as the older meaning cannot invent an absence.
 */
enum class GoalKind(val key: String) {

    /** The original goal: do this so many times a week, optionally tied to an activity. */
    HABIT("habit"),

    /**
     * A container for concrete steps — §D5's *"a folder for steps, not a standalone aspiration"*.
     * There is no target and no rate; see [GoalBoard] for what a project is allowed to say about
     * its own progress.
     */
    PROJECT("project"),
    ;

    companion object {
        /** What a goal is when nothing says otherwise — and what the `goals.kind` column defaults to. */
        val DEFAULT: GoalKind = HABIT

        /** Never throws: an unrecognised key is [DEFAULT]. See the note above on why. */
        fun fromKey(key: String?): GoalKind = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}
