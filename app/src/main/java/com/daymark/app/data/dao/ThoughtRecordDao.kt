package com.daymark.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import com.daymark.app.data.entity.ThoughtRecord
import kotlinx.coroutines.flow.Flow

@Dao
interface ThoughtRecordDao {
    @Query("SELECT * FROM thought_records ORDER BY dateTime DESC")
    fun observeAll(): Flow<List<ThoughtRecord>>

    @Query("SELECT * FROM thought_records WHERE id = :id")
    suspend fun getById(id: Long): ThoughtRecord?

    @Insert
    suspend fun insert(record: ThoughtRecord): Long

    @Delete
    suspend fun delete(record: ThoughtRecord)

    @Query("SELECT * FROM thought_records")
    suspend fun getAll(): List<ThoughtRecord>

    @Query("DELETE FROM thought_records")
    suspend fun deleteAll()
}
