package com.daymark.app.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A mark the person placed on their own history: a date and a short line saying what happened.
 *
 * This is the record behind `sky/Sky.kt`'s `SkyKind.LIFE_EVENT`, and `docs/SKY.md` §2.2 is its
 * design. Four columns, and every one of the fields that is *not* here was left out on purpose.
 *
 * ## The rule the table exists to keep
 *
 * **A life event exists because a person said it mattered, and for no other reason.** Nothing in
 * this app may ever write a row here on someone's behalf, and nothing may ever propose one. "Five
 * hard days in a row, that must have been something" is a clinical judgement about a person's life
 * made from their logging behaviour — it is inference with a question mark on the end, and the
 * thing it would most reliably detect is a stretch of not coping. There is no writer for this table
 * except the person's own tap on "Add".
 *
 * That rule is why the table has no `source`, no `confidence`, no `suggested` flag and no
 * `autoDetected` column: there is no shape here for a machine-written row to take, so a later change
 * that wanted to add one would have to add a column and answer for it.
 *
 * ## Why [epochDay] and not epoch millis
 *
 * A life event is a **day**, not a moment. Nobody records the minute a relationship ended, and
 * storing millis would invite the app to display one — a precision the person never gave it.
 *
 * It also matches what the Sky already speaks: `SkyCalendar` and `SkyRecord.epochDay` are both in
 * `LocalDate.toEpochDay()`, so a life-event star needs no conversion and no `ZoneId` at the layout
 * boundary. [SleepLog.night] set this convention first and for the same reason (one row per night,
 * not per instant); this follows it rather than inventing a second currency for dates.
 *
 * The consequence is deliberate: a row cannot drift across a date boundary when the device's zone
 * changes or when the timezone database is updated. The day the person chose is the day that is
 * stored.
 *
 * ## Dates in the past are the normal case, and dates in the future are allowed
 *
 * Nothing here, in the DAO, or in `ui/lifeevents/` rejects a past date, however far back. That is
 * how this feature is actually used: people add a life event when they finally decide it mattered,
 * which is often years afterwards. A floor of any kind would be the app deciding which parts of a
 * life are in scope — the same reason `SkyCalendar` does not clamp its range.
 *
 * Future dates are accepted too, and this was the one place a validation rule was tempting. It was
 * rejected: a person may well mark a date they know is coming — a surgery, a court date, a move,
 * the first anniversary — and a mark placed ahead of time is still the person's own decision that
 * it matters. Refusing it would be the software overruling the one author this table has. The cost
 * is stated rather than hidden: the Sky's rows run from the earliest record to the latest, so a
 * future life event extends the sky past the current month. That is a mark the person placed, not
 * the empty forward slots `docs/SKY.md` §4.2 rules out.
 *
 * ## What is not here
 *
 * - **No category, type or tag.** No "job / relationship / loss / move" picker. A taxonomy is the
 *   software deciding what counts as a life, and a bereavement chosen from a dropdown is worse than
 *   one typed. [label] is free text, the person's own words.
 * - **No mood and no valence.** A life event is not good or bad, and nothing asks how it felt. The
 *   Sky draws it with no mood colour for exactly this reason.
 * - **No body text.** If someone wants to write about it, that is a journal entry, which has its own
 *   surface and its own rules. This is the pin in the map, not the page. A second free-text field
 *   here would split a person's writing across two places that treat it differently.
 * - **No end date or range.** §2.2 allows a range; one date is the smaller honest thing, and a
 *   nullable `endEpochDay` can be added later without rewriting a row.
 *
 * ## The index
 *
 * [epochDay] is indexed because every read of this table is by date: the Sky asks for events in a
 * span, and the screen lists them newest-first. It is not unique — two things can happen on one day.
 */
@Entity(tableName = "life_events", indices = [Index("epochDay")])
data class LifeEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** The day it happened, as `LocalDate.toEpochDay()`. Not a timestamp — see the note above. */
    val epochDay: Long,
    /** The person's own short line. Never parsed, never matched against a vocabulary. */
    val label: String,
    /**
     * When the row was written, in epoch millis.
     *
     * Distinct from [epochDay] and never shown next to it as a correction: "added 3 years later" is
     * a remark about how someone keeps their own history. It is here so a restore can rebuild the
     * order rows were created in, and so two events on the same day have a stable tiebreak that is
     * not the row id alone.
     */
    val createdAt: Long = 0,
)
