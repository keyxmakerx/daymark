package com.daymark.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import com.daymark.app.data.SkyPoint
import com.daymark.app.data.entity.ThoughtRecord
import kotlinx.coroutines.flow.Flow

@Dao
interface ThoughtRecordDao {
    @Query("SELECT * FROM thought_records ORDER BY dateTime DESC")
    fun observeAll(): Flow<List<ThoughtRecord>>

    /**
     * The Sky's practice projection: an id and a date.
     *
     * A thought record is seven free-text fields about a hard moment — the situation, the thought,
     * the evidence either way. **None of them is selected**, for the reason the journal projection
     * gives at greater length: the Sky says a practice was used on a date, and the practice's own
     * screen is where its content lives.
     *
     * `docs/SKY.md` §0.4 recommends this table as the source for `SkyKind.PRACTICE` and warns off
     * `assessment_results`, which carries a score and a band — a star for a questionnaire run sits
     * close to the scoring the Sky refuses to do.
     */
    @Query("SELECT id, dateTime AS epochMillis FROM thought_records")
    fun observeSkyPoints(): Flow<List<SkyPoint>>

    @Query("SELECT * FROM thought_records WHERE id = :id")
    suspend fun getById(id: Long): ThoughtRecord?

    @Insert
    suspend fun insert(record: ThoughtRecord): Long

    @Delete
    suspend fun delete(record: ThoughtRecord)

    @Query("SELECT * FROM thought_records")
    suspend fun getAll(): List<ThoughtRecord>

    @Query("DELETE FROM thought_records")
    suspend fun deleteAll()
}
