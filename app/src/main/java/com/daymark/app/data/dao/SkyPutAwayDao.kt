package com.daymark.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.daymark.app.data.entity.SkyPutAway
import kotlinx.coroutines.flow.Flow

/** The memories put away from the sky (`DECISIONS.md` §D11). */
@Dao
interface SkyPutAwayDao {

    @Query("SELECT * FROM sky_put_away")
    fun observePutAway(): Flow<List<SkyPutAway>>

    /** Putting away what is already put away keeps the first day it was. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(rows: List<SkyPutAway>)

    @Query("DELETE FROM sky_put_away WHERE kind = :kind AND recordId = :recordId")
    suspend fun delete(kind: String, recordId: Long)

    // --- Backup / restore, and "Bring them all back" ---

    @Query("SELECT * FROM sky_put_away")
    suspend fun getAll(): List<SkyPutAway>

    @Query("DELETE FROM sky_put_away")
    suspend fun deleteAll()
}
