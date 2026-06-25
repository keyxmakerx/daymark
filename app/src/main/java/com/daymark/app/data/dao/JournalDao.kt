package com.daymark.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.daymark.app.data.entity.JournalEntry
import kotlinx.coroutines.flow.Flow

@Dao
interface JournalDao {

    @Query("SELECT * FROM journal_entries ORDER BY dateTime DESC")
    fun observeAll(): Flow<List<JournalEntry>>

    /** Case-insensitive search over title and body. */
    @Query(
        "SELECT * FROM journal_entries WHERE title LIKE '%' || :query || '%' " +
            "OR body LIKE '%' || :query || '%' ORDER BY dateTime DESC",
    )
    fun search(query: String): Flow<List<JournalEntry>>

    @Query("SELECT * FROM journal_entries WHERE id = :id")
    suspend fun getById(id: Long): JournalEntry?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: JournalEntry): Long

    @Update
    suspend fun update(entry: JournalEntry)

    @Delete
    suspend fun delete(entry: JournalEntry)

    @Query("SELECT COUNT(*) FROM journal_entries")
    suspend fun count(): Int

    // --- Backup / restore ---

    @Query("SELECT * FROM journal_entries")
    suspend fun getAll(): List<JournalEntry>

    @Query("DELETE FROM journal_entries")
    suspend fun deleteAll()
}
