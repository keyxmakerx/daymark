package com.daymark.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.daymark.app.data.SkyStampPoint
import com.daymark.app.data.entity.Goal
import kotlinx.coroutines.flow.Flow

@Dao
interface GoalDao {

    @Query("SELECT * FROM goals WHERE archived = 0 ORDER BY createdAt DESC")
    fun observeActive(): Flow<List<Goal>>

    /**
     * The dates of the goals the person has marked reached — the read the Sky's `goal_reached` star
     * comes from, and two columns wide because that is all a star is.
     *
     * **`archived` is not in the WHERE clause, in either direction.** It is not `archived = 0`,
     * because archiving a goal you reached is tidying a list and must not delete a star you placed;
     * and it is emphatically not `archived = 1`, which is the misreading this whole column exists to
     * prevent — see [com.daymark.app.data.entity.Goal.reachedAt]. The only condition is the mark
     * itself.
     *
     * No `title` column. A goal's title is the person's own sentence about what they wanted, and
     * `docs/SKY.md` §8.2 rule 7 asks for that to be un-selectable here rather than merely unused by
     * the caller: every one of the six kinds reads a projection that *cannot* return text, so there
     * is no kind for which "widen the SELECT" is the cheap way to get a label onto the Sky.
     *
     * [SkyStampPoint] keeps `epochMillis` nullable because `reachedAt` is. A non-null field over a
     * nullable column reads a null back as 0, which on this surface is a star in January 1970.
     */
    @Query("SELECT id, reachedAt AS epochMillis FROM goals WHERE reachedAt IS NOT NULL")
    fun observeSkyPoints(): Flow<List<SkyStampPoint>>

    @Query("SELECT * FROM goals WHERE id = :id")
    suspend fun getById(id: Long): Goal?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(goal: Goal): Long

    @Update
    suspend fun update(goal: Goal)

    @Delete
    suspend fun delete(goal: Goal)

    /**
     * Deletes by id, so a caller never has to rebuild a whole [Goal] out of screen state to throw
     * one away. `GoalEditorViewModel` used to do exactly that from five of its eight fields, which
     * worked only because `@Delete` matches on the primary key alone.
     */
    @Query("DELETE FROM goals WHERE id = :id")
    suspend fun deleteById(id: Long)

    // --- Backup / restore ---

    @Query("SELECT * FROM goals")
    suspend fun getAll(): List<Goal>

    @Query("DELETE FROM goals")
    suspend fun deleteAll()
}
