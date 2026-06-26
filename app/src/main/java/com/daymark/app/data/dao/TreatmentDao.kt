package com.daymark.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import com.daymark.app.data.entity.Treatment
import kotlinx.coroutines.flow.Flow

@Dao
interface TreatmentDao {
    @Query("SELECT * FROM treatments ORDER BY startedAt DESC")
    fun observeAll(): Flow<List<Treatment>>

    @Query("SELECT * FROM treatments WHERE id = :id")
    fun observeById(id: Long): Flow<Treatment?>

    @Insert
    suspend fun insert(treatment: Treatment): Long

    @Delete
    suspend fun delete(treatment: Treatment)
}
