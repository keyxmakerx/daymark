package com.daymark.app.data

import android.content.SharedPreferences
import com.daymark.app.data.dao.EntryDao
import com.daymark.app.data.dao.GoalDao
import com.daymark.app.data.dao.GoalStepDao
import com.daymark.app.data.dao.JournalDao
import com.daymark.app.data.dao.LifeEventDao
import com.daymark.app.data.dao.ThoughtRecordDao
import com.daymark.app.sky.Sky
import com.daymark.app.sky.SkyKind
import com.daymark.app.sky.SkyRecord
import com.daymark.app.sky.SkySeed
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The six acts that become stars, read as narrowly as the schema allows and merged into the one
 * list `sky/` takes.
 *
 * ## Why the projections are their own queries
 *
 * All six reads below are purpose-built `@Query` projections returning two or three columns. Not
 * one of them is an existing `SELECT *` with the extra fields ignored here, and the difference is
 * the whole point:
 * `docs/SKY.md` §8.2 rule 7 puts the "no text on the Sky" property **in the DAO signature** rather
 * than in discipline at the call site. A projection that cannot return a title is a projection that
 * cannot leak one, whatever a later change to this file does. The journal query is the sharp case —
 * `(id, dateTime)` and never `title` or `body` — because §4.1's promise is that a person standing
 * behind someone looking at their sky learns *that* they wrote and never *what*.
 *
 * Six out of six matters more than it sounds. A single kind left reading a wide row would be the
 * one place where "add a column to the SELECT" is the cheap way to get a label onto this surface,
 * and a rule with one exception is a rule nobody can apply without asking which case they are in.
 *
 * ## Why the zone conversion happens here
 *
 * `sky/` has no `java.time` and no `ZoneId` on purpose: a zone is a decision about *whose day this
 * is*, and `SkyCalendar`'s header explains why that belongs at the edge of the app rather than in
 * the middle of a geometry pass. This is that edge. A stored `epochMillis` becomes a
 * `LocalDate.toEpochDay()` exactly once, here, using the device's current zone — so an entry
 * written at 23:40 is a star on the day the person experienced as that evening, and everything
 * downstream is `Long` arithmetic that cannot drift when the timezone database is updated.
 *
 * `life_events` skips the conversion entirely because it already stores an epoch day: a life event
 * is a day the person chose, not a moment, and re-deriving it through a zone would let the date
 * they picked move.
 *
 * ## What this file must never grow
 *
 * No filter that drops records, no window that limits how far back the sky goes, and no branch that
 * treats one kind as more important than another. Every record of every kind becomes a star, or the
 * surface stops being "everything you did" and starts being a selection somebody made.
 */
@Singleton
class SkyRepository @Inject constructor(
    private val entryDao: EntryDao,
    private val journalDao: JournalDao,
    private val thoughtRecordDao: ThoughtRecordDao,
    private val goalStepDao: GoalStepDao,
    private val goalDao: GoalDao,
    private val lifeEventDao: LifeEventDao,
    private val prefs: SharedPreferences,
) {

    /**
     * Every act the person has performed, as stars-to-be, in no particular order.
     *
     * Order is not sorted here because [Sky.layout] sorts, and a second sort would be a second
     * place for the ordering rule to live. A kind with no rows contributes an empty list rather
     * than being absent, so the merge has the same shape on a new install as on a ten-year history.
     */
    fun observeRecords(): Flow<List<SkyRecord>> {
        val sources: List<Flow<List<SkyRecord>>> = listOf(
            entryDao.observeSkyPoints().map { rows ->
                val zone = ZoneId.systemDefault()
                rows.map {
                    SkyRecord(
                        kind = SkyKind.CHECK_IN,
                        id = it.id,
                        epochDay = epochDayOf(it.epochMillis, zone),
                        moodLevel = it.moodLevel,
                    )
                }
            },
            journalDao.observeSkyPoints().map { rows ->
                val zone = ZoneId.systemDefault()
                rows.map { SkyRecord(SkyKind.JOURNAL, it.id, epochDayOf(it.epochMillis, zone)) }
            },
            thoughtRecordDao.observeSkyPoints().map { rows ->
                val zone = ZoneId.systemDefault()
                rows.map { SkyRecord(SkyKind.PRACTICE, it.id, epochDayOf(it.epochMillis, zone)) }
            },
            goalStepDao.observeSkyPoints().map { rows ->
                val zone = ZoneId.systemDefault()
                // The query already excludes null completion stamps; this is the second guard, and
                // it is here so that loosening the query cannot quietly put a 1970 star on someone's
                // sky twenty rows above everything else they have ever done.
                rows.mapNotNull { row ->
                    row.epochMillis?.let {
                        SkyRecord(SkyKind.PROJECT_STEP, row.id, epochDayOf(it, zone))
                    }
                }
            },
            goalDao.observeSkyPoints().map { rows ->
                val zone = ZoneId.systemDefault()
                // `reachedAt` is nullable and the query already excludes nulls; the filter is the
                // second guard, not the first, and it is here so a change to that query cannot
                // turn an unmarked goal into a star.
                rows.mapNotNull { row ->
                    row.epochMillis?.let {
                        SkyRecord(SkyKind.GOAL_REACHED, row.id, epochDayOf(it, zone))
                    }
                }
            },
            lifeEventDao.observeSkyPoints().map { rows ->
                rows.map { SkyRecord(SkyKind.LIFE_EVENT, it.id, it.epochDay) }
            },
        )
        return combine(sources) { parts -> parts.flatMap { it } }
    }

    /**
     * The decorative field's seed: derived once from the person's first record, then never again.
     *
     * The awkward property `SkySeed` describes is the reason this is a function with a side effect
     * rather than a value computed from [records]. A field re-derived from the current history
     * would redraw the whole background every time anything was logged, and a place whose walls
     * move is not a place. Worse, it would move on a *deletion* — and deletion on this surface
     * "leaves no shape" (§2.1), which a changed background plainly is.
     *
     * So: the first time there is anything to derive from, the value is written to prefs and every
     * later call reads it back. Deleting that first record afterwards changes nothing, because
     * nothing re-derives.
     *
     * Before there is any history at all, [SkySeed.EMPTY_SKY] is returned and **not** persisted, so
     * the person's real first record still gets to set it.
     */
    fun fieldSeed(records: List<SkyRecord>): Long {
        val stored = prefs.getLong(KEY_FIELD_SEED, 0L)
        if (stored != 0L) return stored
        // The same ordering [Sky.layout] uses, so "first record" means one thing across the app and
        // does not depend on which DAO happened to emit first.
        val first = records.minWithOrNull(
            compareBy<SkyRecord> { it.epochDay }.thenBy { it.kind.ordinal }.thenBy { it.id },
        ) ?: return SkySeed.EMPTY_SKY
        val seed = SkySeed.forFirstRecord(first)
        // A derived seed of exactly 0 would be indistinguishable from "not yet stored" and would be
        // re-derived on every open. One bit is a smaller lie than a background that moves.
        val stable = if (seed == 0L) 1L else seed
        prefs.edit().putLong(KEY_FIELD_SEED, stable).apply()
        return stable
    }

    private fun epochDayOf(epochMillis: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate().toEpochDay()

    private companion object {
        const val KEY_FIELD_SEED = "sky_field_seed"
    }
}

// -----------------------------------------------------------------------------------------------
// The projections. Four shapes, deliberately small, and deliberately not entities: a class with a
// text field on it is a class a query could be widened into.
// -----------------------------------------------------------------------------------------------

/** A dated act with a mood the person recorded. Check-ins only — no other kind carries one. */
data class SkyMoodPoint(val id: Long, val epochMillis: Long, val moodLevel: Int)

/** A dated act with no mood: a journal entry, or a practice worked through. */
data class SkyPoint(val id: Long, val epochMillis: Long)

/**
 * A dated act whose stamp column is nullable: a completed project step, or a goal marked reached.
 *
 * [epochMillis] is nullable because both source columns are. `GoalBoard.move` clears
 * `goal_steps.completedAt` when a step leaves *Done*, and `goals.reachedAt` is null on every goal
 * nobody has ticked — and in both cases un-marking is the thing the person does to take a star
 * back. The projection mirrors the column rather than promising more than the schema does: a
 * non-null field over a nullable column reads a null back as 0, which on this surface is a star in
 * January 1970, twenty years below everything else the person has ever done.
 *
 * One type for both because it is one fact — *this happened, and here is when* — and a second data
 * class with identical fields would be a second place to get the nullability wrong.
 */
data class SkyStampPoint(val id: Long, val epochMillis: Long?)

/** A record that already stores a day rather than a moment. `life_events` is the only one. */
data class SkyDayPoint(val id: Long, val epochDay: Long)
