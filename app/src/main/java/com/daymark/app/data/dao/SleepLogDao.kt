package com.daymark.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.daymark.app.data.entity.SleepLog
import kotlinx.coroutines.flow.Flow

@Dao
interface SleepLogDao {
    @Query("SELECT * FROM sleep_logs ORDER BY night DESC, id DESC")
    fun observeAll(): Flow<List<SleepLog>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: SleepLog): Long

    @Delete
    suspend fun delete(log: SleepLog)

    @Query("SELECT * FROM sleep_logs")
    suspend fun getAll(): List<SleepLog>
}
