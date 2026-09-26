package com.daymark.app.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One number from one run of a Companion timed task, such as Steady Attention (#177).
 *
 * A run is identified by [taskId], [taskVersion] and [takenAt], and writes one row per number it
 * produced, named as the web's result names it — the counts, the reaction-time figures, and the
 * clock's own measurements (`frameJitterMs`, `droppedFrames`, `refreshMs`). A task added to the
 * catalogue later produces different numbers and needs no new column.
 *
 * ## The precision flag is on every row
 *
 * [timingFlag] is the run's own caveat — `ok`, or `lower-precision` when the device clock was too
 * uneven to trust its spread (`docs/COMPANION_FEATURES.md` §3.4). It is repeated on each row so that
 * no number from a run can be read without it.
 *
 * ## Descriptive, within one person
 *
 * No norms, no percentiles, no cutoffs (`docs/COMPANION_FEATURES.md` §3.4). This table holds what a
 * run measured and says nothing about what it means.
 *
 * ## No foreign key, no index, no DEFAULT
 *
 * A result belongs to no other row. It has no index beyond its key: an index comes with the reader
 * that needs it, when the question it answers is known.
 * No field has an `@ColumnInfo(defaultValue = …)`, so the migration writes no `DEFAULT`.
 */
@Entity(tableName = "task_results")
data class TaskResult(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** The catalogue task's id. */
    val taskId: String,

    /** The task's version, so a result is always attributable to what produced it. */
    val taskVersion: String,

    /** When the run was taken, in epoch millis. Every row of one run carries the same value. */
    val takenAt: Long,

    /** The run's timing flag: `ok` or `lower-precision`, as the web writes it. */
    val timingFlag: String,

    /** The number's name, as the web's result names it. */
    val metric: String,

    /** The number. Some are whole counts and some are not, so it is stored as a real. */
    val value: Double,
)
