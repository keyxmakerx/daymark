package com.daymark.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.daymark.app.data.entity.ActivityEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ActivityDao {

    @Query("SELECT * FROM activities WHERE archived = 0 ORDER BY sortOrder, name")
    fun observeActive(): Flow<List<ActivityEntity>>

    @Query("SELECT * FROM activities ORDER BY sortOrder, name")
    fun observeAll(): Flow<List<ActivityEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(activity: ActivityEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(activities: List<ActivityEntity>)

    @Update
    suspend fun update(activity: ActivityEntity)

    @Delete
    suspend fun delete(activity: ActivityEntity)

    @Query("SELECT COUNT(*) FROM activities")
    suspend fun count(): Int

    @Query("SELECT * FROM activities")
    suspend fun getAllOnce(): List<ActivityEntity>

    @Query("DELETE FROM activities")
    suspend fun deleteAll()
}
