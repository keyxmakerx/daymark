package com.daymark.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.daymark.app.data.entity.SkyPlace
import com.daymark.app.data.entity.SkyStar
import kotlinx.coroutines.flow.Flow

/** What the person chose for their stars, and the places they named (#516, #517, #518). */
@Dao
interface SkyStarDao {

    @Query("SELECT * FROM sky_stars")
    fun observeStars(): Flow<List<SkyStar>>

    @Query("SELECT * FROM sky_stars WHERE kind = :kind AND recordId = :recordId")
    suspend fun get(kind: String, recordId: Long): SkyStar?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(star: SkyStar)

    @Query("DELETE FROM sky_stars WHERE kind = :kind AND recordId = :recordId")
    suspend fun delete(kind: String, recordId: Long)

    /** Writes [star], or deletes its row when nothing is chosen in it any more. */
    @Transaction
    suspend fun save(star: SkyStar) {
        if (star.name == null && !star.shower && star.placeId == null) delete(star.kind, star.recordId) else put(star)
    }

    @Query("SELECT * FROM sky_places ORDER BY name COLLATE NOCASE")
    fun observePlaces(): Flow<List<SkyPlace>>

    @Insert
    suspend fun insertPlace(place: SkyPlace): Long

    @Query("UPDATE sky_places SET name = :name WHERE id = :id")
    suspend fun renamePlace(id: Long, name: String)

    @Query("DELETE FROM sky_places WHERE id = :id")
    suspend fun deletePlaceRow(id: Long)

    @Query("UPDATE sky_stars SET placeId = NULL WHERE placeId = :id")
    suspend fun clearPlace(id: Long)

    @Query("DELETE FROM sky_stars WHERE name IS NULL AND shower = 0 AND placeId IS NULL")
    suspend fun dropEmpty()

    /** A place goes, and every memory in it simply belongs to no place; the memories are untouched. */
    @Transaction
    suspend fun deletePlace(id: Long) {
        clearPlace(id)
        dropEmpty()
        deletePlaceRow(id)
    }

    // --- Backup / restore ---

    @Query("SELECT * FROM sky_stars")
    suspend fun getAllStars(): List<SkyStar>

    @Query("SELECT * FROM sky_places")
    suspend fun getAllPlaces(): List<SkyPlace>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStars(rows: List<SkyStar>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaces(rows: List<SkyPlace>)

    @Query("DELETE FROM sky_stars")
    suspend fun deleteAllStars()

    @Query("DELETE FROM sky_places")
    suspend fun deleteAllPlaces()
}
