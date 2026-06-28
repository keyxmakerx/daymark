package com.daymark.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.daymark.app.data.entity.Reminder
import kotlinx.coroutines.flow.Flow

@Dao
interface ReminderDao {
    @Query("SELECT * FROM reminders ORDER BY hour, minute")
    fun observeAll(): Flow<List<Reminder>>

    @Query("SELECT * FROM reminders WHERE id = :id")
    suspend fun getById(id: Long): Reminder?

    @Insert
    suspend fun insert(reminder: Reminder): Long

    @Update
    suspend fun update(reminder: Reminder)

    @Delete
    suspend fun delete(reminder: Reminder)

    @Query("SELECT * FROM reminders")
    suspend fun getAll(): List<Reminder>

    @Query("DELETE FROM reminders")
    suspend fun deleteAll()
}
