package com.daymark.app.data.entity

import androidx.room.Entity

/**
 * A memory the person put away from their sky (`DECISIONS.md` §D11).
 *
 * Put away is hidden, never deleted: the memory itself is untouched in its own table, and keeps
 * its place in the sky, so bringing it back sends it home. One row per record, keyed by the record
 * it hides — [kind] is the sky's own key for the table it lives in (`sky/Sky.kt`, `SkyKind.key`)
 * and [recordId] its row there — so a record is put away at most once.
 *
 * It hides the memory from the sky and nowhere else. Nothing here is a privacy control, and
 * nothing says so: the entry is still in the journal, the calendar and every other place it was.
 *
 * Deleting the memory leaves a row here that matches nothing and is never drawn; it goes the next
 * time the person brings everything back, or with a full restore.
 */
@Entity(tableName = "sky_put_away", primaryKeys = ["kind", "recordId"])
data class SkyPutAway(
    val kind: String,
    val recordId: Long,
    /** The day it was put away, as `LocalDate.toEpochDay()`. The only date the list shows for it. */
    val putAwayEpochDay: Long,
)
