package com.daymark.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.daymark.app.data.entity.Person
import com.daymark.app.data.entity.PersonGroupShare
import kotlinx.coroutines.flow.Flow

/**
 * Reads and writes `people` and `person_group_shares`.
 *
 * ## Why two tables on one DAO
 *
 * They are the two halves of one answer. "Is this person shared?" is
 * `person.sharedOverride ?: groupDefault`, and a caller that reaches one table without the other
 * has a wrong answer rather than a partial one — in the direction that matters, since a missing
 * override read against nothing resolves to whatever the group says. Keeping them behind one door
 * makes it awkward to read half of it, which is the point. `EntryDao` already owns `mood_entries`
 * and its cross-ref for the same reason.
 *
 * ## What is not on here, and must not be added
 *
 * **No query on this DAO joins `mood_entries`, and none may.** `docs/PLAN_2026-09-SKY-PEOPLE-TIMING.md`
 * §2: *"Never in any rule that reads mood. Correlations, patterns and the cards they produce cannot
 * receive a person or a community, groups included."* The cheapest way to break that rule is a
 * convenience query here — `SELECT p.id, e.moodLevel FROM people p JOIN entry_people ...` — written
 * to fill a screen and reused a release later by something that ranks. There is no such query, and
 * the entries that name a person are reached through `EntryPersonDao`, which returns ids.
 *
 * No ordering by anything but name and group, either: a "most tagged" or "most recent" ordering is
 * the app ranking the people in somebody's life.
 */
@Dao
interface PersonDao {

    /**
     * Everyone, archived included, grouped then alphabetical.
     *
     * `groupKey` first so the sharing screen and the manage screen read in the same order as the
     * picker. It sorts by the stored key, not by [com.daymark.app.data.entity.PersonGroup]'s
     * declaration order, so a key this build does not recognise still sorts somewhere stable
     * instead of vanishing.
     */
    @Query("SELECT * FROM people ORDER BY groupKey, name COLLATE NOCASE")
    fun observeAll(): Flow<List<Person>>

    /** What the *with* picker offers: everyone not archived. */
    @Query("SELECT * FROM people WHERE archived = 0 ORDER BY groupKey, name COLLATE NOCASE")
    fun observeActive(): Flow<List<Person>>

    /** One person's row, for their page. Null once they are deleted, so the page can close. */
    @Query("SELECT * FROM people WHERE id = :id")
    fun observeById(id: Long): Flow<Person?>

    @Query("SELECT * FROM people WHERE id = :id")
    suspend fun getById(id: Long): Person?

    /**
     * Existing rows whose name matches, ignoring case, archived included.
     *
     * For the one prompt §2 allows — *"an entry names someone who has no page yet, so offer one,
     * once"* — which needs to know whether a name is already known. It is a lookup and nothing
     * more: it counts nothing, dates nothing, and archived rows are deliberately included, because
     * somebody who was archived does already have a page and must not be offered a second one.
     */
    @Query("SELECT * FROM people WHERE name = :name COLLATE NOCASE")
    suspend fun findByName(name: String): List<Person>

    @Insert
    suspend fun insert(person: Person): Long

    @Update
    suspend fun update(person: Person)

    @Delete
    suspend fun delete(person: Person)

    @Query("SELECT COUNT(*) FROM people")
    suspend fun count(): Int

    // --- The per-group sharing default ---

    /**
     * Every group that has a row. Groups with none are not shared — see
     * [com.daymark.app.data.entity.PersonGroupShare], which explains why absence has to be the
     * off state rather than an unset one.
     */
    @Query("SELECT * FROM person_group_shares")
    fun observeGroupShares(): Flow<List<PersonGroupShare>>

    @Query("SELECT * FROM person_group_shares WHERE groupKey = :groupKey")
    suspend fun groupShare(groupKey: String): PersonGroupShare?

    /** Upsert by primary key: one row per group, the newest write wins. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setGroupShare(share: PersonGroupShare)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setGroupShares(shares: List<PersonGroupShare>)

    // --- Backup / restore ---

    @Query("SELECT * FROM people")
    suspend fun getAll(): List<Person>

    @Query("SELECT * FROM person_group_shares")
    suspend fun getAllGroupShares(): List<PersonGroupShare>

    @Query("DELETE FROM people")
    suspend fun deleteAll()

    @Query("DELETE FROM person_group_shares")
    suspend fun deleteAllGroupShares()
}
