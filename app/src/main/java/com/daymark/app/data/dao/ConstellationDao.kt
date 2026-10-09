package com.daymark.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.daymark.app.data.entity.Constellation
import kotlinx.coroutines.flow.Flow

@Dao
interface ConstellationDao {

    /** Oldest first: the order they were drawn in. */
    @Query("SELECT * FROM sky_constellations ORDER BY madeEpochDay, id")
    fun observeConstellations(): Flow<List<Constellation>>

    @Insert
    suspend fun insert(constellation: Constellation): Long

    @Query("DELETE FROM sky_constellations WHERE id = :id")
    suspend fun delete(id: Long)

    // --- Backup / restore ---

    @Query("SELECT * FROM sky_constellations")
    suspend fun getAll(): List<Constellation>

    @Query("DELETE FROM sky_constellations")
    suspend fun deleteAll()
}
