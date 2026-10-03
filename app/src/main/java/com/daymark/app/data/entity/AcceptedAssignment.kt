package com.daymark.app.data.entity

import androidx.room.Entity

/**
 * One assignment a clinician signed and the owner's device took in: a self-check, a task, a bundle
 * of them, a reminder cadence, a goal or a setting (#177).
 *
 * Only assignments that were accepted have a row. An item reaches this table in one of two ways and
 * no third: the owner said yes to it in the inbox, or it passed every check and its capability is
 * one the owner granted in `auto` — never a setting, which always waits for the owner
 * (`docs/COMPANION_ASSIGNMENTS.md` §1.2). A refused or declined item is never written here, and the
 * checks that decide it are `com.daymark.app.companion.AssignmentRules`.
 *
 * ## Immutable and append-only, keyed by what was signed
 *
 * ([lineageId], [version]), as for [GamePlan]: superseding an assignment is a new signed version,
 * never an edit, and `CompanionDao` inserts with abort and has no update for this table.
 *
 * ## [payloadJson] and [sigB64] are the assignment
 *
 * The signed payload is kept verbatim, with its signature, so what was accepted stays re-verifiable
 * against the pinned clinician key without the server — the same rule game plans keep
 * (`docs/COMPANION_THERAPIST.md` §7). The typed columns are only what the database finds and orders
 * rows by. An assignment's type-specific fields — which self-check, which cadence, which setting and
 * to what — are read from the payload, because they take a different shape for each of six types,
 * and six sets of mostly-empty columns would be a second copy of the payload that could disagree
 * with the one that is signed.
 *
 * `capability` has no column: an accepted assignment's capability is the one its type requires,
 * which is checked before it is accepted. `context` and `recipientOwnerFp` have none either; both
 * are checked when the item is opened, and both are the same for every row on this phone.
 *
 * ## No DEFAULT anywhere
 *
 * No field has an `@ColumnInfo(defaultValue = …)`, so the migration writes none.
 */
@Entity(tableName = "assignments", primaryKeys = ["lineageId", "version"])
data class AcceptedAssignment(

    /** The clinician's name for the assignment across its versions, as signed. */
    val lineageId: String,

    /** This version's number within its lineage, as signed. `Long`, as the server files versions. */
    val version: Long,

    /**
     * `questionnaire`, `task`, `largeAssessment`, `reminder`, `goal` or `setting`, as signed. Text,
     * so a type this build does not know reads back as itself.
     */
    val type: String,

    /** The fingerprint of the clinician's Ed25519 signing key, as signed. */
    val authorFingerprint: String,

    /** When the clinician issued this version, in epoch millis, as signed. */
    val issuedAt: Long,

    /**
     * When it was accepted, in epoch millis: the owner's yes, or for a capability the owner granted
     * in `auto`, the moment it applied under that standing yes. The one column the clinician did not
     * sign.
     */
    val acceptedAt: Long,

    /** The signed payload, verbatim: the exact text the signature covers. */
    val payloadJson: String,

    /** The clinician's Ed25519 signature over [payloadJson], base64url without padding, verbatim. */
    val sigB64: String,
)
