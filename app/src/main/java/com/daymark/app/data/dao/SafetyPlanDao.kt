package com.daymark.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.daymark.app.data.entity.SafetyPlanItem
import kotlinx.coroutines.flow.Flow

@Dao
interface SafetyPlanDao {
    @Query("SELECT * FROM safety_plan_items ORDER BY section ASC, position ASC")
    fun observeAll(): Flow<List<SafetyPlanItem>>

    /** Next free slot in a section, so appends keep their order without renumbering. */
    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM safety_plan_items WHERE section = :section")
    suspend fun nextPosition(section: String): Int

    @Insert
    suspend fun insert(item: SafetyPlanItem): Long

    @Update
    suspend fun update(item: SafetyPlanItem)

    @Delete
    suspend fun delete(item: SafetyPlanItem)

    @Query("SELECT * FROM safety_plan_items")
    suspend fun getAll(): List<SafetyPlanItem>

    @Query("DELETE FROM safety_plan_items")
    suspend fun deleteAll()
}
