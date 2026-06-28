package com.daymark.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.daymark.app.data.entity.Tracker
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackerDao {
    @Query("SELECT * FROM trackers WHERE archived = 0 ORDER BY sortOrder, id")
    fun observeActive(): Flow<List<Tracker>>

    @Query("SELECT * FROM trackers WHERE id = :id")
    fun observeById(id: Long): Flow<Tracker?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(tracker: Tracker): Long

    @Update
    suspend fun update(tracker: Tracker)

    @Query("SELECT * FROM trackers")
    suspend fun getAll(): List<Tracker>

    @Query("DELETE FROM trackers")
    suspend fun deleteAll()
}
