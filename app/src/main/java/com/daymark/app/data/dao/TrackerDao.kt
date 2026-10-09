package com.daymark.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.daymark.app.data.SkyTrackerPoint
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

    @Query("SELECT * FROM trackers WHERE id = :id")
    suspend fun getById(id: Long): Tracker?

    @Query("DELETE FROM trackers")
    suspend fun deleteAll()

    /**
     * The Sky's projection of trackers (`DECISIONS.md` §D11): for each tracker the person switched
     * on and still keeps, how many logs it has and when the first was. No name, value or note, the
     * rule every sky projection keeps (`SkyRepository`'s header). A tracker with no logs has
     * nothing to draw and is not returned.
     */
    @Query(
        "SELECT t.id AS trackerId, COUNT(l.id) AS logs, MIN(l.dateTime) AS firstMillis " +
            "FROM trackers t JOIN tracker_logs l ON l.trackerId = t.id " +
            "WHERE t.showInSky = 1 AND t.archived = 0 GROUP BY t.id",
    )
    fun observeSkySources(): Flow<List<SkyTrackerPoint>>

    /** Shows a tracker in the person's sky, or takes it out. Only the person does either. */
    @Query("UPDATE trackers SET showInSky = :shown WHERE id = :id")
    suspend fun setShowInSky(id: Long, shown: Boolean)
}
