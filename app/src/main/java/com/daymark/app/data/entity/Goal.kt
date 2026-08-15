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
     * **Last in the constructor on purpose.** `backup/BackupManager.kt` builds a [Goal] positionally
     * in two places; appending is the only position that leaves those call sites correct, and a
     * new field inserted in the middle would have silently shifted `archived` into `targetPerWeek`.
     */
    @ColumnInfo(defaultValue = "habit") val kind: String = GoalKind.DEFAULT.key,
)
