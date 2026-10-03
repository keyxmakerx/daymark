package com.daymark.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.daymark.app.data.entity.EntryPersonCrossRef
import kotlinx.coroutines.flow.Flow

/**
 * Reads and writes `entry_people` — the link from a mood entry to the people it says it was *with*.
 *
 * ## Why this is its own DAO and not three more methods on `EntryDao`
 *
 * `EntryDao` is the door that returns mood. It returns `MoodEntry`, `EntryWithActivities` and
 * `SkyMoodPoint`, all of which carry `moodLevel`. Putting the people link on it would put a person
 * one `@Relation` away from a row that already holds a mood, and `EntryWithActivities` shows how
 * short that distance is — a `@Relation(... Junction(EntryPersonCrossRef::class))` beside the
 * existing one is four lines, reads as symmetry, and would hand every caller of `observeAll` a
 * mood and a person in the same object.
 *
 * `docs/FEATURES.md` §11.2 forbids that: *"Never in any rule that reads mood. Correlations,
 * patterns and the cards they produce cannot receive a person or a community, groups included.
 * This is enforced by shape, not by convention."* So the link
 * is kept behind a separate door, and **every method here returns ids or counts — never a
 * `MoodEntry`, never a `moodLevel`, never an `EntryWithActivities`.** The one screen that shows a
 * person's entries takes the ids from here and asks `EntryDao` for them, which is a join the caller
 * has to write, in the open, for a list it is about to draw.
 *
 * The consequence is the property worth having: **there is no query in `data/` that returns a
 * person and a mood together.** `MoodCorrelations.factorDeltas` takes ids, so nothing stops it
 * being *handed* person ids by a determined caller — that half of the guard belongs in `stats/`
 * and `PeopleRepository` says what it should be. What this DAO removes is the accident: nobody
 * reaches a (person, mood) pair by reusing a query that was already there.
 */
@Dao
interface EntryPersonDao {

    /** The people on one entry, as ids, for the entry editor and the entry page. */
    @Query("SELECT personId FROM entry_people WHERE entryId = :entryId")
    fun observePersonIdsForEntry(entryId: Long): Flow<List<Long>>

    @Query("SELECT personId FROM entry_people WHERE entryId = :entryId")
    suspend fun personIdsForEntry(entryId: Long): List<Long>

    /**
     * The entries that name one person, as ids, newest-named first.
     *
     * Ordered by `entryId DESC` and not by date: ordering by date would mean joining
     * `mood_entries`, and a join from this table to that one is the thing this DAO does not do.
     * Entry ids are `AUTOINCREMENT`, so descending id is the order they were written in, and the
     * screen that draws the list is loading those entries anyway and can sort them properly once it
     * has them.
     */
    @Query("SELECT entryId FROM entry_people WHERE personId = :personId ORDER BY entryId DESC")
    fun observeEntryIdsForPerson(personId: Long): Flow<List<Long>>

    /**
     * How many entries name this person.
     *
     * A count of rows and nothing else. It is here so a page can say how many times somebody comes
     * up; it must never be used to rank people, and no query on this DAO orders by it.
     */
    @Query("SELECT COUNT(*) FROM entry_people WHERE personId = :personId")
    fun observeEntryCountForPerson(personId: Long): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCrossRefs(refs: List<EntryPersonCrossRef>)

    @Query("DELETE FROM entry_people WHERE entryId = :entryId")
    suspend fun clearForEntry(entryId: Long)

    /**
     * `entry_people` has no foreign keys — [EntryPersonCrossRef] says why — so a deleted person's
     * links do not go on their own. `PeopleRepository.delete` calls this.
     */
    @Query("DELETE FROM entry_people WHERE personId = :personId")
    suspend fun clearForPerson(personId: Long)

    /** Replace one entry's people atomically, the way `EntryDao.setActivities` does. */
    @Transaction
    suspend fun setPeople(entryId: Long, personIds: List<Long>) {
        clearForEntry(entryId)
        if (personIds.isNotEmpty()) {
            insertCrossRefs(personIds.map { EntryPersonCrossRef(entryId, it) })
        }
    }

    // --- Backup / restore ---

    @Query("SELECT * FROM entry_people")
    suspend fun getAll(): List<EntryPersonCrossRef>

    @Query("DELETE FROM entry_people")
    suspend fun deleteAll()
}
