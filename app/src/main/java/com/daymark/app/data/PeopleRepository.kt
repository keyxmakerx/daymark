package com.daymark.app.data

import com.daymark.app.data.dao.EntryPersonDao
import com.daymark.app.data.dao.PersonDao
import com.daymark.app.data.dao.PersonNoteDao
import com.daymark.app.data.entity.EntryPersonCrossRef
import com.daymark.app.data.entity.Person
import com.daymark.app.data.entity.PersonGroup
import com.daymark.app.data.entity.PersonGroupShare
import com.daymark.app.data.entity.PersonNote
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one door onto people and communities: their rows, their notes, the entries that name them,
 * and whether each is shared with a clinician.
 *
 * ## The rule this class is built around
 *
 * `docs/PLAN_2026-09-SKY-PEOPLE-TIMING.md` §2: *"**Never in any rule that reads mood.**
 * Correlations, patterns and the cards they produce cannot receive a person or a community, groups
 * included, enforced by signature the way the Sky's field is kept blind to data."*
 *
 * **Nothing on this class returns a mood, and nothing takes one.** `observeEntryIds` hands back
 * entry ids; the caller that wants to draw those entries asks `EntryRepository` for them and does
 * the join itself, in a ViewModel, for a list it is about to render. There is no
 * `moodWithPerson`, no `entriesFor(person): List<MoodEntry>`, and no aggregate of any kind keyed
 * by a person — not a mean, not a count-by-mood, not a "most often with".
 *
 * That is the half of the guard this layer can hold. The other half is `stats/`'s own signatures:
 * a correlation factor is a `MoodCorrelations.FactorId`, which can only be made from an activity or
 * a tracker, so a person id does not compile into a correlation at all.
 *
 * ## The one prompt this layer is allowed to feed
 *
 * §2 allows exactly two: an entry names somebody with no page yet, so offer one, once; and somebody
 * has come up several times with no page, offer once. Both read tags and dates and never mood.
 * [findByName] and [observeEntryCount] are what they need, and neither returns anything else.
 *
 * The prompt that is forbidden — *"you haven't written about X in a while"* — has no query behind
 * it anywhere in this layer, and `PersonNoteDao` says why that absence is deliberate rather than an
 * omission somebody should helpfully fill in.
 */
@Singleton
class PeopleRepository @Inject constructor(
    private val personDao: PersonDao,
    private val personNoteDao: PersonNoteDao,
    private val entryPersonDao: EntryPersonDao,
) {

    // --- People ---

    fun observeAll(): Flow<List<Person>> = personDao.observeAll()

    /** What the *with* picker offers: everybody not archived. */
    fun observeActive(): Flow<List<Person>> = personDao.observeActive()

    fun observe(id: Long): Flow<Person?> = personDao.observeById(id)

    suspend fun getById(id: Long): Person? = personDao.getById(id)

    /**
     * Whether a name already has a page, archived included.
     *
     * Archived rows count as having one: somebody who was tidied out of the picker has a page
     * already, and offering to make them a second one would be the app forgetting them on the
     * person's behalf.
     */
    suspend fun findByName(name: String): List<Person> = personDao.findByName(name.trim())

    /**
     * Adds a person or a community.
     *
     * [nowMillis] is passed in rather than read from a clock here, the way the rest of this layer
     * takes its time from the caller, so a test can add somebody at a fixed moment.
     *
     * The new row's sharing is `null` — *follow the group* — and every group defaults to off, so a
     * name added here is not shared with anybody. See [isShared].
     */
    suspend fun add(
        name: String,
        group: PersonGroup = PersonGroup.DEFAULT,
        whoTheyAre: String = "",
        nowMillis: Long = 0,
    ): Long = personDao.insert(
        Person(
            name = name.trim(),
            groupKey = group.key,
            whoTheyAre = whoTheyAre,
            createdAt = nowMillis,
        ),
    )

    suspend fun update(person: Person) = personDao.update(person)

    /** Hides from the picker. Entries and notes are untouched — `Person.archived` says why. */
    suspend fun setArchived(person: Person, archived: Boolean) =
        personDao.update(person.copy(archived = archived))

    /**
     * Deletes a person and everything attached to them.
     *
     * The notes cascade and the entry links do not, so both are cleared by hand first. That is not
     * belt and braces: `person_notes`' cascade only fires while `PRAGMA foreign_keys` is on, which
     * Room sets and a raw `SupportSQLiteDatabase` does not have to, and `entry_people` has no
     * foreign key at all by design ([EntryPersonCrossRef]). `GoalRepository.deleteById` deletes its
     * steps the same way and for the same reason.
     *
     * Not transactional here, because this repository holds no database handle. The order is chosen
     * so that a failure part-way leaves the person's row standing with fewer links, which is
     * recoverable by deleting again — the reverse order would leave a name-less link nothing can
     * reach.
     */
    suspend fun delete(person: Person) {
        entryPersonDao.clearForPerson(person.id)
        personNoteDao.deleteForPerson(person.id)
        personDao.delete(person)
    }

    // --- Notes ---

    fun observeNotes(personId: Long): Flow<List<PersonNote>> = personNoteDao.observeForPerson(personId)

    suspend fun addNote(personId: Long, dateTime: Long, body: String): Long =
        personNoteDao.insert(PersonNote(personId = personId, dateTime = dateTime, body = body))

    suspend fun updateNote(note: PersonNote) = personNoteDao.update(note)

    suspend fun deleteNote(note: PersonNote) = personNoteDao.delete(note)

    // --- The link to entries ---

    /**
     * The entries that name this person, **as ids**.
     *
     * Ids and not entries, and that is the signature this feature is held to. A caller that needs
     * to draw the list asks `EntryRepository` for those ids; a caller that wants to correlate them
     * with mood has to write that itself and answer for it. See the class header.
     */
    fun observeEntryIds(personId: Long): Flow<List<Long>> =
        entryPersonDao.observeEntryIdsForPerson(personId)

    fun observeEntryCount(personId: Long): Flow<Int> =
        entryPersonDao.observeEntryCountForPerson(personId)

    fun observePersonIdsForEntry(entryId: Long): Flow<List<Long>> =
        entryPersonDao.observePersonIdsForEntry(entryId)

    suspend fun personIdsForEntry(entryId: Long): List<Long> =
        entryPersonDao.personIdsForEntry(entryId)

    /** Replaces one entry's *with* list, the way `EntryRepository` replaces its activities. */
    suspend fun setPeopleOnEntry(entryId: Long, personIds: List<Long>) =
        entryPersonDao.setPeople(entryId, personIds)

    suspend fun insertCrossRefs(refs: List<EntryPersonCrossRef>) =
        entryPersonDao.insertCrossRefs(refs)

    // --- Sharing ---

    fun observeGroupShares(): Flow<List<PersonGroupShare>> = personDao.observeGroupShares()

    /**
     * Sets a whole group's default.
     *
     * Writes a row even for `false`. An explicit off and an absent row mean the same thing to
     * [isShared] — `PersonGroupShare` requires that they do — but the row records that somebody
     * came to the screen and decided, which is worth keeping when the alternative is deleting the
     * evidence of a choice.
     */
    suspend fun setGroupDefault(group: PersonGroup, shared: Boolean) =
        personDao.setGroupShare(PersonGroupShare(group.key, shared))

    /** One person's own answer, or `null` to go back to following their group. */
    suspend fun setSharedOverride(person: Person, shared: Boolean?) =
        personDao.update(person.copy(sharedOverride = shared))

    companion object {

        /**
         * Sharing is off unless something says otherwise, in every direction.
         *
         * This is the value the resolution below falls back to twice: once for a person with no
         * override, once for a group with no row. It is named rather than written as a bare
         * `false` so that both fallbacks are visibly the same decision.
         */
        const val SHARING_OFF: Boolean = false

        /**
         * The whole sharing rule: **the person's own answer, or their group's, or off.**
         *
         * One expression, in a companion, so a test can call it without a database — this is the
         * line that decides what leaves the device, and it is worth being able to exercise
         * directly rather than through Room.
         *
         * `?:` and not `||`: an override of `false` must *beat* a group default of `true`, which is
         * the entire purpose of a per-item override under a group somebody turned on. Written as
         * `sharedOverride == true || groupDefault` it would read almost the same and would share
         * everybody the person had explicitly excluded.
         */
        fun isShared(sharedOverride: Boolean?, groupDefault: Boolean): Boolean =
            sharedOverride ?: groupDefault

        /**
         * The same rule against the group defaults as they are actually stored: a map of the rows
         * that exist, where a group with no row is off.
         *
         * Keyed by the stored `groupKey` string rather than by [PersonGroup], so a key this build
         * does not recognise keeps its own setting instead of being resolved to `other` first and
         * silently picking up `other`'s default.
         */
        fun isShared(person: Person, groupDefaults: Map<String, Boolean>): Boolean =
            isShared(person.sharedOverride, groupDefaults[person.groupKey] ?: SHARING_OFF)

        /** The convenient shape of [isShared]'s second argument, built from the stored rows. */
        fun groupDefaults(shares: List<PersonGroupShare>): Map<String, Boolean> =
            shares.associate { it.groupKey to it.shared }
    }
}
