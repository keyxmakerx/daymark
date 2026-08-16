package com.daymark.app.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.daymark.app.goals.GoalBoard

/**
 * One concrete step of a project goal ([com.daymark.app.goals.GoalKind.PROJECT]) — the "folder for
 * steps" `docs/DECISIONS_2026-08.md` §D5 asks for.
 *
 * ## Why the cascade, and why the repository also deletes explicitly
 *
 * This is the **first foreign key in the schema**, and it is here because a step is a person's own
 * writing about what they mean to do. A goal deleted with its steps left behind is that writing kept
 * after they asked for it to go — invisible, since nothing would ever query it again.
 *
 * `ON DELETE CASCADE` does the work, but it only works while `PRAGMA foreign_keys` is on, which Room
 * sets and a raw `SupportSQLiteDatabase` (a migration, a restore path) does not have to. So
 * `GoalRepository.deleteById` deletes the steps itself first as well. That is deliberate
 * belt-and-braces on a deletion, not an oversight: the redundant statement costs one query, and the
 * failure it guards against is silent.
 *
 * ## Why `state` is text and not an enum column
 *
 * The same reason `OfferRecord.kind` is: a value this version does not recognise must read back
 * rather than throw inside a type converter. [GoalBoard.StepState.fromKey] decides that, in a file
 * a unit test can reach.
 *
 * ## What is not here
 *
 * **No due date, and no field a due date could hide in.** [completedAt] is a record that something
 * happened, never a deadline something is measured against — see [GoalBoard]'s header for why any
 * date on a step becomes a stick. **No note or body.** A step is a line, and the journal is where
 * writing goes; a second free-text surface here would split a person's writing across two places
 * with different rules.
 */
@Entity(
    tableName = "goal_steps",
    foreignKeys = [
        ForeignKey(
            entity = Goal::class,
            parentColumns = ["id"],
            childColumns = ["goalId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("goalId")],
)
data class GoalStep(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val goalId: Long,
    val title: String,
    /** A [GoalBoard.StepState.key]. Stored as text; see the note above. */
    val state: String = GoalBoard.StepState.TO_DO.key,
    /** Order within this step's own column, `0..n-1`. [GoalBoard] owns the numbering. */
    val position: Int = 0,
    val createdAt: Long = 0,
    /** When it was last moved into "done", or null. Never compared against the clock. */
    val completedAt: Long? = null,
)

/** The row as [GoalBoard] needs it. The board is import-free, so the mapping lives on this side. */
fun GoalStep.toStep(): GoalBoard.Step = GoalBoard.Step(
    id = id,
    title = title,
    state = GoalBoard.StepState.fromKey(state),
    position = position,
    createdAt = createdAt,
    completedAt = completedAt,
)

/**
 * A board step as a row belonging to [goalId].
 *
 * [GoalBoard.Step.id] is dropped rather than carried: the editor gives unsaved steps negative ids,
 * and `GoalRepository.replaceSteps` writes the board back as a fresh set, so an id from the board is
 * never a row id to reuse.
 */
fun GoalBoard.Step.toRow(goalId: Long): GoalStep = GoalStep(
    id = 0,
    goalId = goalId,
    title = title,
    state = state.key,
    position = position,
    createdAt = createdAt,
    completedAt = completedAt,
)
