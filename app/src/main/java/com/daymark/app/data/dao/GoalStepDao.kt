package com.daymark.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.daymark.app.data.SkyStampPoint
import com.daymark.app.data.entity.GoalStep
import kotlinx.coroutines.flow.Flow

@Dao
interface GoalStepDao {

    @Query("SELECT * FROM goal_steps WHERE goalId = :goalId ORDER BY position ASC")
    fun observeForGoal(goalId: Long): Flow<List<GoalStep>>

    /**
     * The Sky's project-step projection: steps the person actually finished, by the date they
     * finished them.
     *
     * `completedAt` and not `createdAt`, because the star marks the act of completing — a step
     * written in January and done in March is a March star. The `IS NOT NULL` is not belt-and-
     * braces on top of `state = 'done'`: `GoalBoard.move` clears the stamp when a step leaves Done,
     * so a row could in principle carry the state without the timestamp, and a null here would
     * become an epoch-day of 1970 and a star two decades adrift. [SkyStampPoint] keeps the field
     * nullable anyway, so the projection promises exactly what the column does.
     *
     * **Steps of an archived project keep their stars.** There is no join to `goals` and no
     * `archived` filter, per `docs/SKY.md` §2.1: abandoning a project is not a failure and does not
     * retract the steps that were done. The step happened.
     *
     * No `title` column — a step is a line the person wrote about what they meant to do, and the
     * Sky's business with it is the date.
     */
    @Query(
        "SELECT id, completedAt AS epochMillis FROM goal_steps " +
            "WHERE state = 'done' AND completedAt IS NOT NULL",
    )
    fun observeSkyPoints(): Flow<List<SkyStampPoint>>

    /**
     * Every step of every goal, for the goals list, which shows one progress line per project.
     *
     * One flow rather than a per-goal query each: the list already combines several flows, and a
     * flow-per-row would resubscribe on every insert.
     */
    @Query("SELECT * FROM goal_steps ORDER BY goalId ASC, position ASC")
    fun observeAll(): Flow<List<GoalStep>>

    @Query("SELECT * FROM goal_steps WHERE goalId = :goalId ORDER BY position ASC")
    suspend fun getForGoal(goalId: Long): List<GoalStep>

    @Insert
    suspend fun insert(step: GoalStep): Long

    @Insert
    suspend fun insertAll(steps: List<GoalStep>)

    @Update
    suspend fun update(step: GoalStep)

    @Delete
    suspend fun delete(step: GoalStep)

    /**
     * Deletes one goal's steps.
     *
     * `goal_steps` cascades on the goal's deletion, so this is redundant on that path by design —
     * see the note on [GoalStep]. It is not redundant on the save path, which replaces a goal's
     * board wholesale.
     */
    @Query("DELETE FROM goal_steps WHERE goalId = :goalId")
    suspend fun deleteForGoal(goalId: Long)

    // --- Backup / restore ---

    @Query("SELECT * FROM goal_steps")
    suspend fun getAll(): List<GoalStep>

    @Query("DELETE FROM goal_steps")
    suspend fun deleteAll()
}
