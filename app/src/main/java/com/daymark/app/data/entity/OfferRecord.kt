package com.daymark.app.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One line of the **reception ledger** — the record of the app having asked for someone's attention,
 * when in the week it asked, and what became of it.
 *
 * It began as the three columns `docs/COMPANION_DIALOGUE.md` specifies and now carries three more,
 * all of them facts about *the app's own behaviour at the moment it spoke* and none of them about
 * the person: which hour and weekday the ask was made in ([offeredHour], [offeredWeekday]) and
 * whether anything came back ([responded]). `docs/PLAN_2026-09-SKY-PEOPLE-TIMING.md` §4 is what
 * asks for them, and `com.daymark.app.stats.TimingGrid` is what reads them.
 *
 * This is the decision engine's own table (`docs/DECISIONS.md` §D1a). It holds the app's
 * behaviour and the reception of that behaviour — **never anything about the person**. There is no
 * note, no answer, no dialogue text, no mood, no free-text column of any kind, and there must never
 * be one: the moment this table carries content it stops being a ledger of interruptions and starts
 * being a record of a person.
 *
 * Two properties the readers of this table must preserve, recorded here because the schema is where
 * they are easiest to check:
 *
 *  - **Monotonic.** Falling reception may only ever cause the app to ask *less*. No row, and no
 *    combination of rows, may make it ask more. Escalation is a setting the person changes, never an
 *    inference drawn from this table.
 *  - **Not a clinical signal.** Being dismissed is not a symptom. Nothing here may be read as
 *    evidence about how someone is doing, surfaced to a clinician, or put in a report — a quiet week
 *    means the app was quiet, and nothing more.
 *
 * It follows that nothing here is a streak or a score. Rows are immutable facts about single
 * moments; there is no [androidx.room.Update] path in the DAO, and consecutive-run counting over
 * them is exactly the shape `docs/DECISIONS.md` §D6 rules out.
 */
@Entity(tableName = "offer_records", indices = [Index("kind"), Index("offeredAt")])
data class OfferRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Which feature asked — an [OfferKind] key. Stored as text so an old row always reads back. */
    val kind: String,
    /** Epoch millis of the moment the offer was made. */
    val offeredAt: Long,
    /** What became of it — an [OfferOutcome] key. */
    val outcome: String,
    /**
     * The hour of the day the ask was made in, 0..23, **in the person's own time zone at that
     * moment** — or [UNRECORDED] when that is not known.
     *
     * Stored rather than derived because it **cannot be recovered afterwards**. An epoch
     * millisecond only becomes an hour of the day once a time zone is applied, and the zone
     * somebody was in last March is not knowable in October: they may have flown, moved, or had
     * their phone change it under them. [offeredAt] therefore answers "how long ago" and this
     * answers "when in the day", and neither can be computed from the other.
     *
     * Every row written before this column existed holds [UNRECORDED], and that is deliberate.
     * Back-filling it from [offeredAt] under today's zone would have manufactured an hour for every
     * historical ask and then let placement act on it — evidence about the person's day invented by
     * a migration. `TimingGrid` drops a row it cannot place rather than clamping it, so an
     * unrecorded slot costs one row of history and claims nothing.
     */
    @ColumnInfo(defaultValue = "-1") val offeredHour: Int = UNRECORDED,
    /**
     * The day of the week the ask was made on, 1..7 with **Monday as 1** (`java.time.DayOfWeek.value`),
     * in the same zone and at the same moment as [offeredHour] — or [UNRECORDED].
     *
     * Same reasoning as [offeredHour], and the same refusal to guess: a timestamp near midnight
     * changes weekday with the zone, so a back-fill would have been wrong about the boundary cases
     * most often.
     */
    @ColumnInfo(defaultValue = "-1") val offeredWeekday: Int = UNRECORDED,
    /**
     * Whether a response arrived — `true` the person was there and did something, `false` the app
     * asked and nothing came back, `null` nothing recorded either way.
     *
     * `null` covers two cases that amount to the same thing: a row written before this column
     * existed, and a row written by a feature that asks and then has no way of learning the answer
     * before the line has to go in. Both are "not known", and neither is `false`.
     *
     * **Why this is a separate column and not another [OfferOutcome].** [outcome] is what the
     * *budget* spends, and there a silence and a dismissal are worth exactly the same: both mean
     * ask less, and `InterruptionBudget` has no vocabulary for telling them apart precisely because
     * it must not acquire one. Whether anyone answered is a different question with a different
     * reader — placement — and it is the whole of placement's input. An `IGNORED` member added to
     * [OfferOutcome] would have carried the same restraint weight as [OfferOutcome.DISMISSED],
     * which is to say the budget could not have told it from a dismissal, and it would have forced
     * the mapper into `TimingGrid` to keep a list of "outcome keys that secretly mean no outcome".
     * `TimingGrid` treats **any** non-null outcome, recognised or not, as an answer on purpose, so
     * that no later schema change can make the app abandon an hour; a key denylist between the two
     * is exactly the coupling that rule exists to prevent. A boolean needs no such list — the
     * mapper never reads the outcome key at all.
     *
     * **`null` is not `false`.** "We do not know" must never be read as "nobody was there": that is
     * the direction that gives up an hour on evidence nothing recorded. Only a stored `false` says
     * a response did not arrive, and every other value reads as an answer, which keeps the app
     * asking. That is also why `OfferLedgerRepository.record` defaults this to `null` rather than
     * to `true`: the two behave identically downstream, and only one of them is honest about a
     * caller who was never told.
     *
     * It says nothing about *why* nothing came back. Asleep, at work, out of battery and having a
     * terrible week are indistinguishable here and get the same response, which is the property
     * that keeps this column from becoming a reading of a person (`docs/DECISIONS.md` §D1a).
     */
    val responded: Boolean? = null,
) {
    companion object {
        /**
         * The slot value meaning "this was not recorded", for [offeredHour] and [offeredWeekday].
         *
         * Out of range for both — an hour is 0..23 and a weekday 1..7 — so
         * `com.daymark.app.stats.TimingGrid` drops the row rather than placing it somewhere. A
         * sentinel and not a plausible default: 0 would have been a real midnight and 1 a real
         * Monday, and a row that lies quietly is worse than one that says nothing.
         *
         * The two `@ColumnInfo(defaultValue = "-1")` above and the `DEFAULT -1` in the migration
         * spell this number out as a SQL literal, because an annotation argument has to be one.
         * `TimedOfferSchemaTest` asserts all four spellings agree, so the duplication cannot drift.
         */
        const val UNRECORDED: Int = -1
    }
}

/**
 * Which feature asked. Each kind carries its own budget, which is the whole of the generalisation
 * described in `docs/DECISIONS.md` §D1 — the engine still knows nothing about what any of
 * these features *are*, only that they are different callers.
 */
enum class OfferKind(val key: String) {
    /** The companion surfacing itself unprompted. Opening it deliberately is not an offer. */
    COMPANION("companion"),

    /** A scheduled reminder firing. */
    REMINDER("reminder"),

    /** A prescribed module being put in front of the person. */
    ASSIGNMENT("assignment"),

    /** The support space offering itself after a hard day — see `stats/SupportOffer.kt`. */
    SUPPORT("support"),
    ;

    companion object {
        /** Unknown keys (an older backup, a hand-edited file) resolve to null rather than crashing. */
        fun fromKey(key: String?): OfferKind? = entries.firstOrNull { it.key == key }
    }
}

/**
 * What became of an offer.
 *
 * These are deliberately not ranked, and nothing here is a "good" outcome. [DISMISSED] is a person
 * exercising the control they were given, and treating it as a failure to be corrected is how a
 * component like this turns into an engagement optimiser.
 */
enum class OfferOutcome(val key: String) {
    /** They took it up. */
    ACCEPTED("accepted"),

    /** They waved it away this time. Says nothing about the next time. */
    DISMISSED("dismissed"),

    /** Not now — asked to be come back to. */
    SNOOZED("snoozed"),

    /**
     * Stop asking. Unlike the others this is a standing preference rather than a fact about one
     * moment: once written it holds until the person lifts it themselves.
     */
    STOP("stop"),
    ;

    companion object {
        /** Unknown keys (an older backup, a hand-edited file) resolve to null rather than crashing. */
        fun fromKey(key: String?): OfferOutcome? = entries.firstOrNull { it.key == key }
    }
}
