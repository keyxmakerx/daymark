package com.daymark.app.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.daymark.app.goals.GoalKind

/**
 * A goal, of one of the two shapes [com.daymark.app.goals.GoalKind] names.
 *
 * A **habit** is what this row has always been: "Exercise 5× per week", optionally linked to an
 * [activityId], where progress is the number of distinct days this week whose entries include that
 * activity. A **project** ignores [activityId] and [targetPerWeek] entirely and is a container for
 * `goal_steps` rows instead — `docs/CLINICIAN_FEEDBACK.md` §8, `docs/DECISIONS_2026-08.md` §D5.
 *
 * The two coexist rather than one replacing the other. §D5 reads as a replacement, and it was not
 * built as one: people are mid-way through using the weekly count right now, [cue] and [routine] are
 * the best-evidenced part of the feature, and a migration that reinterpreted existing rows would
 * change what someone's own goal means without asking them.
 */
@Entity(tableName = "goals")
data class Goal(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val activityId: Long? = null,
    val targetPerWeek: Int = 3,
    val createdAt: Long = 0,
    val archived: Boolean = false,
    /**
     * Optional implementation intention ("when [cue], I will [routine]") — a simple, well-evidenced
     * way to turn an intention into action. Empty when unset.
     */
    @ColumnInfo(defaultValue = "") val cue: String = "",
    @ColumnInfo(defaultValue = "") val routine: String = "",
    /**
     * A [com.daymark.app.goals.GoalKind.key]. Text rather than an enum column, and defaulted in SQL
     * as well as in Kotlin, so every row written before v15 keeps meaning exactly what it meant.
     *
     * **Appended, never inserted.** `backup/BackupManager.kt` builds a [Goal] positionally in two
     * places; appending is the only position that leaves those call sites correct, and a new field
     * inserted in the middle would have silently shifted `archived` into `targetPerWeek`. Every
     * field added after this one follows the same rule, for the same reason.
     */
    @ColumnInfo(defaultValue = "habit") val kind: String = GoalKind.DEFAULT.key,
    /**
     * When the person said this goal was reached, in epoch millis. Null means they have not said so.
     *
     * **This is not [archived], and the difference is the whole reason the column exists.** Archiving
     * is giving up on something, putting it aside, or losing interest —
     * `docs/DECISIONS_2026-08.md` §D5 keeps that act neutral and uncounted. Reading an archived row as
     * a reached one would draw a "goal reached" star for someone abandoning a goal, congratulating
     * them for it on the surface that promises not to judge. The two are independent in both
     * directions: archiving never sets this, and a goal marked reached and then archived keeps the
     * mark, because tidying a list is not a retraction.
     *
     * **Nothing computes it.** A habit hitting `targetPerWeek`, or a project with every step in
     * *Done*, does not set this — see [com.daymark.app.goals.GoalReached] for why a threshold here
     * would be the app deciding something about a person and acting on it.
     *
     * Nullable with no SQL default, like `mood_entries.photoPath`: `ALTER TABLE ... ADD COLUMN
     * reachedAt INTEGER` gives every existing row NULL, which is the truth about them — nobody has
     * been asked yet.
     */
    val reachedAt: Long? = null,
)
