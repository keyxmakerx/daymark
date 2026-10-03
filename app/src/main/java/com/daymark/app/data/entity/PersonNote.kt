package com.daymark.app.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One dated note the person wrote about a [Person] — the middle section of their page.
 *
 * `docs/FEATURES.md` §11.1: *"a "who (or what) is this to you" line, dated notes the person writes
 * about them over time, then the entries that name them. It is all free text in the person's words
 * ... The app stores it and shows it; it never reads it."*
 *
 * ## Why this is a table and not a second free-text column on [Person]
 *
 * [Person.whoTheyAre] is one standing line that gets edited; this is a running record that gets
 * added to. Collapsing them would mean either losing every earlier version of the line or growing
 * one column forever with no dates in it, and the dates are what make the section worth having: a
 * person's page can say *last note: June* as a plain fact when it is opened.
 *
 * ## The one thing this table must never become
 *
 * `docs/FEATURES.md` §11.3 is explicit about the gap rule: **"Never: 'you haven't written about X
 * in a while.' A gap is never a prompt."** [dateTime] exists so a page can state when the last
 * note was, on a screen the person chose to open. It does not exist so anything can notice a
 * silence and speak about it. A quiet stretch in here is somebody living their life, or grieving,
 * or busy; it is not an omission and there is nothing to be reminded of.
 *
 * Nor is there any writer for this table but the person. Nothing summarises, nothing suggests a
 * note, nothing writes one on their behalf — the same rule [LifeEvent] holds, and for the same
 * reason. That is why there is no `source`, no `generated` flag and no `title`: a note is a date
 * and what somebody typed.
 *
 * ## No mood and no rating
 *
 * There is no valence column, and there will not be one. A note about a person is writing, not a
 * measurement, and a number beside it would be the first half of a correlation this app has ruled
 * out — see [Person]'s header and `PeopleRepository`.
 *
 * ## The foreign key and the index
 *
 * [personId] references `people(id)` with `ON DELETE CASCADE`, the shape `GoalStep` established.
 * Notes are the person's writing *about* somebody; if that somebody is deleted the writing goes
 * with them rather than becoming an orphan row that no screen can reach and no export can explain.
 * Archiving is the operation that keeps everything — see [Person.archived] — so deletion is free to
 * mean deletion.
 *
 * Foreign keys are enforced only while `PRAGMA foreign_keys` is on. Room sets it; a raw
 * `SupportSQLiteDatabase` does not have to. So the cascade is a second line of defence and not the
 * only one: `PeopleRepository.delete` clears the rows by hand first, the way
 * `GoalRepository.deleteById` does.
 *
 * `index_person_notes_personId` is the index this entity declares. Every read of this table is by
 * person, and it is also what stops Room warning that a foreign key's child column is unindexed —
 * so it is not optional, and a migration that creates the table without it fails Room's own schema
 * comparison on somebody else's change.
 */
@Entity(
    tableName = "person_notes",
    foreignKeys = [
        ForeignKey(
            entity = Person::class,
            parentColumns = ["id"],
            childColumns = ["personId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("personId")],
)
data class PersonNote(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** The [Person] this is about. Cascades on delete — see the note above. */
    val personId: Long,

    /**
     * When the note is about, in epoch millis — the same currency `journal_entries.dateTime` and
     * `thought_records.dateTime` use, because like those this is a piece of writing made at a
     * moment, not a day on a calendar. ([LifeEvent.epochDay] is a day number for the opposite
     * reason: a life event *is* a day.)
     */
    val dateTime: Long,

    /** What the person wrote. Never parsed, never summarised, never read by a rule. */
    val body: String,
)
