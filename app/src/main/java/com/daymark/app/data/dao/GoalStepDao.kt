package com.daymark.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.daymark.app.data.entity.GoalStep
import kotlinx.coroutines.flow.Flow

@Dao
interface GoalStepDao {

    @Query("SELECT * FROM goal_steps WHERE goalId = :goalId ORDER BY position ASC")
    fun observeForGoal(goalId: Long): Flow<List<GoalStep>>

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
