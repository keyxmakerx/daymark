package com.daymark.app.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One line of the **reception ledger** — the record of the app having asked for someone's attention,
 * and what became of it. Three columns and nothing else, as specified in
 * `docs/COMPANION_DIALOGUE.md`.
 *
 * This is the decision engine's own table (`docs/DECISIONS_2026-08.md` §D1a). It holds the app's
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
 * them is exactly the shape `docs/DECISIONS_2026-08.md` §D6 rules out.
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
)

/**
 * Which feature asked. Each kind carries its own budget, which is the whole of the generalisation
 * described in `docs/DECISIONS_2026-08.md` §D1 — the engine still knows nothing about what any of
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
