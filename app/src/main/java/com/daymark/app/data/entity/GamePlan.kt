package com.daymark.app.data.entity

import androidx.room.Entity

/**
 * One version of a game plan a clinician signed, taken into the app because the owner accepted it
 * (#177).
 *
 * A game plan is guidance from the owner's clinician — goals, exercises, tasks between sessions,
 * notes, a review cadence — written in the clinician's console, signed with their key and sealed to
 * the owner (`docs/COMPANION_THERAPIST.md` §7). This table holds the versions the owner said yes to
 * and nothing else. A plan that has not been accepted has no row, and nothing in the app writes one
 * on its own: the proposal step is both the integrity boundary and the consent boundary.
 *
 * ## Never `treatments`
 *
 * [Treatment] is the owner's own sleep-treatment marker: owner-written, editable, and never a
 * measure of anything. A clinician's guidance written there could be edited by the owner without
 * trace and would lose its signature on a backup round trip, so it has its own tables and they are
 * read-only to the owner (`docs/COMPANION_ARCHITECTURE.md` §5, `docs/COMPANION_SECURITY.md`
 * Appendix R, R10). The clinician's body is this table and [GamePlanItem]; the owner's own marks
 * against it are [GamePlanProgress].
 *
 * ## Immutable and append-only, keyed by what was signed
 *
 * The key is ([lineageId], [version]), the two values the signed payload names and the server files
 * the item under. An update is a new signed version of the same lineage and a withdrawal is a
 * signed tombstone ([status] `withdrawn`); neither changes a row already here. `CompanionDao` has no
 * update for this table, and its insert aborts on a version already stored instead of replacing it.
 * The current version of a lineage is its highest.
 *
 * ## [payloadJson] and [sigB64] are the plan; every other column is a reading of them
 *
 * The signed payload is kept exactly as it was verified, with its signature, so a plan stays
 * re-verifiable against the pinned clinician key without the server (`docs/COMPANION_THERAPIST.md`
 * §7). The typed columns here and in [GamePlanItem] are what that payload says, written once when
 * the plan is accepted. They cannot rebuild the signed bytes — an absent field and a `null` one read
 * the same here and sign differently — which is why the bytes are kept.
 *
 * `context` and `recipientOwnerFp` have no column. Both are checked when the plan is opened, and
 * both are the same for every row on this phone.
 *
 * ## No foreign key to a clinician
 *
 * The phone keeps no table of pinned clinicians yet (#174), so there is nothing to reference.
 * [authorFingerprint] is the signed name of the key the plan was verified against.
 *
 * ## No DEFAULT anywhere
 *
 * Nothing here has an `@ColumnInfo(defaultValue = …)`, so the migration that creates this table
 * writes none: a new table has no existing rows to give a value to, and a `DEFAULT` present in the
 * SQL and absent from the entity fails Room's schema check on the first open.
 */
@Entity(tableName = "game_plans", primaryKeys = ["lineageId", "version"])
data class GamePlan(

    /** The clinician's name for the plan across all its versions, as signed. */
    val lineageId: String,

    /** This version's number within its lineage, as signed. `Long`, as the server files versions. */
    val version: Long,

    /** The version this one says it replaces, as signed, or null for the first in its lineage. */
    val supersedes: Long?,

    /**
     * `active` or `withdrawn`, as signed. Text, never an ordinal, so a status this build does not
     * know reads back as itself rather than as whichever value holds that position today.
     */
    val status: String,

    /** The review cadence's unit — `day`, `week` or `month` — or null when the plan names none. */
    val reviewEvery: String?,

    /** How many of [reviewEvery] between reviews, or null when the plan names none. */
    val reviewCount: Int?,

    /** The fingerprint of the clinician's Ed25519 signing key, as signed. */
    val authorFingerprint: String,

    /** When the clinician issued this version, in epoch millis, as signed. */
    val issuedAt: Long,

    /**
     * When the owner accepted this version, in epoch millis — the one column the clinician did not
     * sign, and the moment of the owner's yes.
     */
    val acceptedAt: Long,

    /** The signed payload, verbatim: the exact text the signature covers. */
    val payloadJson: String,

    /** The clinician's Ed25519 signature over [payloadJson], base64url without padding, verbatim. */
    val sigB64: String,
)
