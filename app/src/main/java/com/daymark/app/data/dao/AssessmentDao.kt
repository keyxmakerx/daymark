package com.daymark.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.daymark.app.data.entity.AssessmentResult
import kotlinx.coroutines.flow.Flow

@Dao
interface AssessmentDao {
    @Query("SELECT * FROM assessment_results WHERE key = :key ORDER BY dateTime")
    fun observeForKey(key: String): Flow<List<AssessmentResult>>

    @Query("SELECT * FROM assessment_results ORDER BY dateTime DESC")
    fun observeAll(): Flow<List<AssessmentResult>>

    @Insert
    suspend fun insert(result: AssessmentResult): Long

    @Query("SELECT * FROM assessment_results")
    suspend fun getAll(): List<AssessmentResult>

    @Query("DELETE FROM assessment_results")
    suspend fun deleteAll()
}
