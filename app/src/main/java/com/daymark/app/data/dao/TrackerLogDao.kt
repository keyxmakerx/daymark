package com.daymark.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import com.daymark.app.data.entity.TrackerLog
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackerLogDao {
    @Query("SELECT * FROM tracker_logs WHERE trackerId = :trackerId ORDER BY dateTime DESC")
    fun observeForTracker(trackerId: Long): Flow<List<TrackerLog>>

    @Insert
    suspend fun insert(log: TrackerLog): Long

    @Delete
    suspend fun delete(log: TrackerLog)

    @Query("SELECT * FROM tracker_logs")
    suspend fun getAll(): List<TrackerLog>

    @Query("DELETE FROM tracker_logs")
    suspend fun deleteAll()
}
