package com.daymark.app.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One number from one run of a Companion self-check: a scale's score and the band it fell in
 * (#177).
 *
 * The Companion's self-checks are its own catalogue (`docs/COMPANION_FEATURES.md` §4), separate
 * from the phone's check-ins in [AssessmentResult]. A run of one is identified by [instrumentId],
 * [instrumentVersion] and [takenAt], and writes one row per scale it scores — the shape
 * [AssessmentResult] has, with the scale named, because a Companion instrument may score more than
 * one scale.
 *
 * ## Scores and bands, never answers
 *
 * No item answer is stored, and no column could hold one. The phone's own check-ins keep only a
 * total and a band for the same reason ([AssessmentResult]), and a result reaches a clinician only
 * as scores and bands (`docs/COMPANION_FEATURES.md` §0, rule 6). A guided exercise that scores
 * nothing leaves no row: there is no number to keep, and nothing here counts how often anything was
 * done.
 *
 * ## Descriptive, within one person
 *
 * [bandLabel] is the definition's own label for the band, or `—` when a score falls in none, as the
 * web writes it. It is a self-check band, never a diagnosis, a screen or a clinical threshold
 * (`docs/COMPANION_FEATURES.md` §0, rule 1). The band's tone is not stored: it is how a definition
 * says to draw a band, not part of the result.
 *
 * ## No foreign key, no index, no DEFAULT
 *
 * A result belongs to no other row. It has no index beyond its key: an index comes with the reader
 * that needs it, when the question it answers is known.
 * No field has an `@ColumnInfo(defaultValue = …)`, so the migration writes no `DEFAULT`.
 */
@Entity(tableName = "instrument_results")
data class InstrumentResult(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** The catalogue instrument's id, as its definition names it. */
    val instrumentId: String,

    /** The definition's version, so a result is always attributable to what produced it. */
    val instrumentVersion: String,

    /** When the run was taken, in epoch millis. Every row of one run carries the same value. */
    val takenAt: Long,

    /** The scale this row scores, as the definition names it. */
    val scaleId: String,

    /** The scale's score, rounded to two places as the web rounds it. Scores need not be whole. */
    val score: Double,

    /** The band's label, or `—` when the score falls in no band. */
    val bandLabel: String,
)
