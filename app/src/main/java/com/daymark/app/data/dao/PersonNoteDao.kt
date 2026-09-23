package com.daymark.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.daymark.app.data.entity.PersonNote
import kotlinx.coroutines.flow.Flow

/**
 * Reads and writes `person_notes`.
 *
 * Every write here is one the person asked for. There is no upsert-by-date, no "record if absent",
 * and no writer but a tap on Save — the rule
 * [com.daymark.app.data.entity.PersonNote] states and [com.daymark.app.data.entity.LifeEvent]
 * states before it.
 *
 * ## The query that is missing on purpose
 *
 * There is no `lastNoteBefore`, no `daysSinceLastNote` and no "people with no note since" query.
 * `docs/FEATURES.md` §11.3: **"Never: 'you haven't written about X in a while.' A gap is never a
 * prompt."** A page may state *last note: June* when the person opens it, which is
 * [observeForPerson] read newest-first and the first row's date — a fact on a screen somebody
 * chose to look at. A query shaped as *who has gone quiet* is the prompt itself, one refactor from
 * being spoken, so it does not exist.
 */
@Dao
interface PersonNoteDao {

    /**
     * One person's notes, newest first.
     *
     * `id DESC` is the tiebreak rather than a second time field, so the order is total even for two
     * notes written in the same millisecond or restored from a file that had none.
     */
    @Query("SELECT * FROM person_notes WHERE personId = :personId ORDER BY dateTime DESC, id DESC")
    fun observeForPerson(personId: Long): Flow<List<PersonNote>>

    @Query("SELECT * FROM person_notes WHERE id = :id")
    suspend fun getById(id: Long): PersonNote?

    @Insert
    suspend fun insert(note: PersonNote): Long

    /**
     * Used by the restore path, which writes a whole file's notes at once.
     *
     * A list insert rather than a loop because `person_notes.personId` is a live foreign key: one
     * statement inside the import's transaction either lands whole or not at all, and there is no
     * half-restored page in between.
     */
    @Insert
    suspend fun insertAll(notes: List<PersonNote>)

    @Update
    suspend fun update(note: PersonNote)

    @Delete
    suspend fun delete(note: PersonNote)

    /**
     * Kept for `PeopleRepository.delete`, which clears a person's notes by hand before deleting
     * them.
     *
     * The `ON DELETE CASCADE` would do it — but only while `PRAGMA foreign_keys` is on, which Room
     * sets and a raw `SupportSQLiteDatabase` does not have to. `GoalRepository.deleteById` deletes
     * its steps by hand for exactly this reason, and orphaned rows here would be somebody's writing
     * about a person, attached to whatever row id gets reused next.
     */
    @Query("DELETE FROM person_notes WHERE personId = :personId")
    suspend fun deleteForPerson(personId: Long)

    // --- Backup / restore ---

    @Query("SELECT * FROM person_notes")
    suspend fun getAll(): List<PersonNote>

    @Query("DELETE FROM person_notes")
    suspend fun deleteAll()
}
