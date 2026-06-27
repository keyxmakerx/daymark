package com.daymark.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.daymark.app.data.entity.EntryActivityCrossRef
import com.daymark.app.data.entity.EntryWithActivities
import com.daymark.app.data.entity.MoodEntry
import kotlinx.coroutines.flow.Flow

@Dao
interface EntryDao {

    @Transaction
    @Query("SELECT * FROM mood_entries ORDER BY dateTime DESC")
    fun observeAll(): Flow<List<EntryWithActivities>>

    @Transaction
    @Query("SELECT * FROM mood_entries WHERE dateTime BETWEEN :from AND :to ORDER BY dateTime DESC")
    fun observeBetween(from: Long, to: Long): Flow<List<EntryWithActivities>>

    @Transaction
    @Query("SELECT * FROM mood_entries WHERE id = :id")
    suspend fun getById(id: Long): EntryWithActivities?

    @Transaction
    @Query("SELECT * FROM mood_entries WHERE note LIKE '%' || :query || '%' AND note != '' ORDER BY dateTime DESC")
    fun search(query: String): Flow<List<EntryWithActivities>>

    @Transaction
    @Query("SELECT * FROM mood_entries WHERE dateTime BETWEEN :from AND :to ORDER BY dateTime ASC")
    suspend fun getBetween(from: Long, to: Long): List<EntryWithActivities>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: MoodEntry): Long

    @Update
    suspend fun update(entry: MoodEntry)

    @Delete
    suspend fun delete(entry: MoodEntry)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCrossRefs(refs: List<EntryActivityCrossRef>)

    @Query("DELETE FROM entry_activity WHERE entryId = :entryId")
    suspend fun clearCrossRefs(entryId: Long)

    @Query("SELECT COUNT(*) FROM mood_entries")
    suspend fun count(): Int

    // --- Backup / restore ---

    @Query("SELECT * FROM mood_entries")
    suspend fun getAllEntries(): List<MoodEntry>

    @Query("SELECT * FROM entry_activity")
    suspend fun getAllCrossRefs(): List<EntryActivityCrossRef>

    @Query("DELETE FROM mood_entries")
    suspend fun deleteAllEntries()

    @Query("DELETE FROM entry_activity")
    suspend fun deleteAllCrossRefs()

    /** Replace an entry's activity links atomically. */
    @Transaction
    suspend fun setActivities(entryId: Long, activityIds: List<Long>) {
        clearCrossRefs(entryId)
        if (activityIds.isNotEmpty()) {
            insertCrossRefs(activityIds.map { EntryActivityCrossRef(entryId, it) })
        }
    }
}
