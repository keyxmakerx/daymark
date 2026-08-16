package com.daymark.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.daymark.app.data.SkyPoint
import com.daymark.app.data.entity.JournalEntry
import kotlinx.coroutines.flow.Flow

@Dao
interface JournalDao {

    @Query("SELECT * FROM journal_entries ORDER BY dateTime DESC")
    fun observeAll(): Flow<List<JournalEntry>>

    /**
     * The Sky's journal projection: **`id` and `dateTime`, never `title`, never `body`.**
     *
     * This is the narrowest query in the app and the one with the most riding on it.
     * `docs/SKY.md` §4.1: *the Sky never renders journal prose — it shows that you wrote, not what
     * you wrote.* The reason is the one behind the ban on note excerpts in the companion
     * (`docs/DECISIONS_2026-08.md` §D6) — what is protected is a place to write without an audience
     * — and it is stronger here, because the Sky is a surface people show to other people and
     * screenshot. A shoulder-surfer must learn that something was written on a date and nothing
     * else.
     *
     * **The way this gets broken is by widening it, not by replacing it.** Somebody will want a
     * title in a tooltip, or a first line in the star's detail, and the cheapest way to get one is
     * to add a column to this `SELECT`. That change has to be made here, in the open, against this
     * comment — which is the entire reason the Sky reads through its own query instead of through
     * [observeAll]. `SkyRecord` has no text field to put one in either; the two together are what
     * make the property structural rather than remembered.
     */
    @Query("SELECT id, dateTime AS epochMillis FROM journal_entries")
    fun observeSkyPoints(): Flow<List<SkyPoint>>

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
