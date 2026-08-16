package com.daymark.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.daymark.app.data.SkyDayPoint
import com.daymark.app.data.entity.LifeEvent
import kotlinx.coroutines.flow.Flow

/**
 * Reads and writes `life_events`.
 *
 * Every write here is one the person asked for. There is no upsert-by-date, no "record if absent"
 * and no bulk import from anywhere but a backup the person restored — see [LifeEvent] for why this
 * table has exactly one author.
 *
 * **The Sky needs a different query from these, and it must not be added by widening one.** A
 * star's data is `(id, epochDay)` and no text: `docs/SKY.md` §8.2 rule 7 puts that property in the
 * DAO signature rather than in discipline at the call site, so the projection the `SkyRepository`
 * needs is its own `@Query` returning a two-field class, not [observeAll] with the label ignored by
 * the caller. That query is [observeSkyPoints], and it is now the one the Sky reads.
 */
@Dao
interface LifeEventDao {

    /**
     * Newest day first, and within a day the most recently added first.
     *
     * `id DESC` is the tiebreak rather than `createdAt DESC` so the order is total even for rows
     * restored from a backup written before `createdAt` was populated.
     */
    @Query("SELECT * FROM life_events ORDER BY epochDay DESC, id DESC")
    fun observeAll(): Flow<List<LifeEvent>>

    /**
     * The Sky's projection, written the way the header above says it has to be: `(id, epochDay)`,
     * its own `@Query`, and no [LifeEvent.label].
     *
     * A life event is the one star whose text the person composed for the Sky specifically, which
     * makes it the one most tempting to draw a label next to. It is not drawn: the star is a mark,
     * and the words are on the life-events screen the star hands off to. The same rule as every
     * other kind, and for the same reason — this surface is shown to other people.
     *
     * No conversion on the way out. `life_events` already stores `LocalDate.toEpochDay()` because a
     * life event is a day and not a moment, so this is the one kind that reaches `SkyRecord`
     * without a zone touching it.
     */
    @Query("SELECT id, epochDay FROM life_events")
    fun observeSkyPoints(): Flow<List<SkyDayPoint>>

    @Insert
    suspend fun insert(event: LifeEvent): Long

    /**
     * Kept though nothing calls it yet: a person must be able to fix a date they mis-tapped or a
     * word they mistyped, and the alternative — delete and re-add — throws away [LifeEvent.createdAt]
     * and the row id. The editing screen is the next piece of this feature.
     */
    @Update
    suspend fun update(event: LifeEvent)

    @Delete
    suspend fun delete(event: LifeEvent)

    // --- Backup / restore ---

    @Query("SELECT * FROM life_events")
    suspend fun getAll(): List<LifeEvent>

    @Query("DELETE FROM life_events")
    suspend fun deleteAll()
}
