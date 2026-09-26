package com.daymark.app.data.entity

import androidx.room.Entity

/**
 * The owner's own mark against one item of a clinician's game plan (#177).
 *
 * This is the only table in the game-plan layer the owner writes. The clinician's words are
 * [GamePlan] and [GamePlanItem], read-only and signed; where the owner is with an item is theirs,
 * and it lives here.
 *
 * ## Keyed by ([lineageId], [itemRef]), so it carries across versions
 *
 * A plan is updated by a new signed version, and an item keeps its `itemRef` from one version to
 * the next. Keying the owner's mark by lineage and item rather than by version is what makes an
 * update carry the owner's progress forward instead of starting it over
 * (`docs/COMPANION_PHONE.md` §3).
 *
 * ## No foreign key, on purpose
 *
 * A mark belongs to an item across every version of its lineage, so there is no single row it
 * could reference; and it is the owner's data, which outlives any one version of the clinician's.
 * A withdrawn or superseded plan leaves the owner's marks where they were.
 *
 * ## [state] is a text key
 *
 * The keys are [com.daymark.app.goals.GoalBoard.StepState]'s — `todo`, `doing`, `done` — so the app
 * has one vocabulary for where somebody is with something they mean to do, read through
 * `StepState.fromKey`, which never throws. Text and never an ordinal, the same reason
 * [GoalStep.state] is: a value this build does not know must read back rather than fail.
 *
 * ## What is not here
 *
 * No note: the journal is where the owner's writing goes, the rule [GoalStep] keeps. No date and no
 * count: nothing measures an item against the clock or tallies how often it was done. No score.
 * Having no row for an item is not a failure; it is an item the owner has not marked.
 */
@Entity(tableName = "game_plan_progress", primaryKeys = ["lineageId", "itemRef"])
data class GamePlanProgress(

    /** The [GamePlan.lineageId] the item belongs to. */
    val lineageId: String,

    /** The [GamePlanItem.itemRef] this mark is for, in any version of the lineage. */
    val itemRef: String,

    /** A `GoalBoard.StepState` key. Stored as text; see the note above. */
    val state: String,
)
