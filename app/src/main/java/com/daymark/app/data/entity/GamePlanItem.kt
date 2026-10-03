package com.daymark.app.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey

/**
 * One item of an accepted [GamePlan] version — a goal, an exercise, a task or a note, in the
 * clinician's words and in the clinician's order (#177).
 *
 * Every column is what the signed payload says about the item, written once when the owner accepts
 * the plan. The owner never edits a row here; what the owner does with an item is recorded in
 * [GamePlanProgress], which is keyed by ([lineageId], [itemRef]) so it carries across versions. The
 * signed bytes are [GamePlan.payloadJson], and this table is a reading of them, never a way to
 * rebuild them.
 *
 * ## The key, and the one invariant it holds
 *
 * ([lineageId], [version], [itemRef]): an item is named by its `itemRef` within one version of one
 * lineage. Progress is keyed by `itemRef`, so two items in one version sharing an `itemRef` would
 * share the owner's marks; the key makes that plan impossible to store rather than ambiguous to
 * read. [position] is the item's index in the signed list, and it is what a plan is shown in.
 *
 * ## The foreign key, and why there is no separate index
 *
 * ([lineageId], [version]) references `game_plans` with `ON DELETE CASCADE`, the shape
 * [PersonNote] and [GoalStep] established: an item is part of one plan version and means nothing
 * without it, so it goes when that version goes. The two referencing columns are the leading
 * columns of the primary key, so SQLite's own primary-key index already serves the cascade's
 * lookup — the arrangement [EntryPersonCrossRef] relies on for `entryId` — and a second index on
 * the same columns would be one more thing for a later schema to disagree about.
 *
 * Foreign keys are enforced only while `PRAGMA foreign_keys` is on. Room sets it; a raw
 * `SupportSQLiteDatabase` does not have to. `CompanionDao.deleteAll` therefore empties this table
 * before `game_plans` rather than relying on the cascade.
 *
 * ## Nullable where the payload's field is optional, and no DEFAULT
 *
 * [detail], [targetPerWeek], [dueAt] and [recurrence] are optional in the signed item, so their
 * columns are nullable, and null means the plan did not say. [targetPerWeek] is an `Int`, the type
 * `Goal.targetPerWeek` has, because it is a count of times in a week. No field has an
 * `@ColumnInfo(defaultValue = …)`, so the migration writes no `DEFAULT`.
 */
@Entity(
    tableName = "game_plan_items",
    primaryKeys = ["lineageId", "version", "itemRef"],
    foreignKeys = [
        ForeignKey(
            entity = GamePlan::class,
            parentColumns = ["lineageId", "version"],
            childColumns = ["lineageId", "version"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class GamePlanItem(

    /** The [GamePlan.lineageId] this item belongs to. */
    val lineageId: String,

    /** The [GamePlan.version] this item belongs to. */
    val version: Long,

    /** The clinician's name for this item, stable across the plan's versions, as signed. */
    val itemRef: String,

    /** The item's index in the signed list, `0..n-1`. The order the clinician wrote them in. */
    val position: Int,

    /**
     * `goal`, `exercise`, `task` or `note`, as signed. Text, so a kind this build does not know reads
     * back as itself.
     */
    val kind: String,

    /** The item's title, as the clinician wrote it. Never parsed, never rewritten. */
    val title: String,

    /** The item's longer text, as the clinician wrote it, or null when there is none. */
    val detail: String?,

    /** How many times a week the clinician suggests, or null when the item names no number. */
    val targetPerWeek: Int?,

    /**
     * A date the clinician attached, in epoch millis, or null when there is none. Shown as the
     * clinician's words; never compared against the clock to say anything was missed.
     */
    val dueAt: Long?,

    /** The clinician's recurrence text, or null when there is none. */
    val recurrence: String?,
)
