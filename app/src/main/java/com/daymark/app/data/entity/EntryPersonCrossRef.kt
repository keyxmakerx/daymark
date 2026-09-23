package com.daymark.app.data.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * Many-to-many link between a [MoodEntry] and the [Person]s an entry says it was *with*.
 *
 * `docs/FEATURES.md` §11.1: *"An entry gains **with**."*
 *
 * ## Deliberately the same shape as [EntryActivityCrossRef], column for column
 *
 * Composite primary key, no surrogate id, no extra columns, and one index on the far side. There
 * is nothing clever to add here and two cross-reference tables that differ for no reason are two
 * tables somebody has to hold in their head at once.
 *
 * **No foreign keys, matching [EntryActivityCrossRef].** That is not an oversight in either place:
 * the restore path inserts these rows verbatim from a backup file, and a file is untrusted input.
 * With a live foreign key, one cross-ref naming a row the file does not carry aborts the whole
 * import and the person gets nothing back; without one it is a dangling pair that no query joins
 * to. `BackupManager` drops what it can see is orphaned and lets the rest be harmless. The rows are
 * cleaned up on delete by `PeopleRepository.delete`, which is exactly how `entry_activity` is
 * handled today.
 *
 * ## What this link may and may not be used for
 *
 * It answers *which entries name this person* — the third section of a person's page, and the only
 * reason it exists. It must never answer *how does this person's mood look when Sam is there*.
 *
 * That rule is held by shape rather than by discipline. This table is read through `EntryPersonDao`
 * and nothing else, and every query on that DAO returns **ids** — never a `moodLevel`, never a
 * `MoodEntry`, never an `EntryWithActivities`. `EntryDao`, which is the one that returns mood, has
 * no method that touches this table. So there is no query anywhere in `data/` that yields a
 * (person, mood) pair: producing one takes a new query somebody has to write and answer for, rather
 * than an existing one somebody reuses. `Person`'s header and `PeopleRepository` carry the rest of
 * it, including what `stats/` has to do on its side.
 *
 * ## The index
 *
 * [Index] on `personId` and not on `entryId`: `entryId` is already the leading column of the
 * primary key, so SQLite's implicit index covers lookups by entry, and the far side needs its own —
 * the person's page reads this table by person. Exactly the arrangement [EntryActivityCrossRef]
 * has, and Room derives the name `index_entry_people_personId` from it.
 */
@Entity(
    tableName = "entry_people",
    primaryKeys = ["entryId", "personId"],
    indices = [Index("personId")],
)
data class EntryPersonCrossRef(
    val entryId: Long,
    val personId: Long,
)
