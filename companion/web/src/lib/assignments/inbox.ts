/*
 * Assignment ACCEPTANCE INBOX (owner side).
 *
 * The owner fetches opaque assignment blobs, opens+verifies each with openAssignment() against
 * the OOB-PINNED therapist key, then validates it against the current Grant. The result is an
 * InboxItem with a verdict the UI renders; a REJECTED / UNTRUSTED_KEY / OPEN_FAILED item can
 * NEVER be applied. suggest.setting ALWAYS requires the owner to accept (never auto), even when
 * the grant marks the capability 'auto'.
 *
 * This module is the machine-checkable security boundary: the tests assert that a forged /
 * tampered / ungranted / off-allowlist item is never applyable.
 */
import type { Assignment } from './types'
import { openAssignment, AssignmentOpenError, type BoxKeyPair } from './crypto'
import { validateAssignment, shouldAutoApply, type AssignmentCheck } from './validate'
import { describeAssignment } from './describe'
import type { Grant } from './types'

export type Verdict = 'VERIFIED' | 'REJECTED' | 'UNTRUSTED_KEY' | 'OPEN_FAILED'
export type Decision = 'accepted' | 'declined' | 'snoozed'

/** A pinned therapist, as consumed by the owner console (keys are OOB-verified, root of trust). */
export interface PinnedTherapist {
  id: string
  displayName: string
  signPub: Uint8Array // Ed25519 — VERIFY against this, never a key from the blob
  boxPub: Uint8Array // X25519 — seal shares/grants TO the therapist
  grant: Grant // the owner's current signed grant for this therapist
}

export interface RawAssignmentBlob {
  therapistId: string // which pinned therapist this lineage belongs to
  lineage: string
  version: number
  bytes: Uint8Array
}

export interface InboxItem {
  assignment?: Assignment
  therapistId: string
  therapistName: string
  verdict: Verdict
  check?: AssignmentCheck
  requiresAccept: boolean
  preview: string
  errors: string[]
  raw: { lineage: string; version: number }
  decision?: Decision
}

/**
 * Open + verify + validate a single raw blob into an InboxItem. Never throws — every failure
 * mode becomes a non-applyable verdict.
 */
export function evaluateBlob(raw: RawAssignmentBlob, therapist: PinnedTherapist, ownerBox: BoxKeyPair): InboxItem {
  const base = {
    therapistId: therapist.id,
    therapistName: therapist.displayName,
    raw: { lineage: raw.lineage, version: raw.version },
  }

  let assignment: Assignment
  try {
    assignment = openAssignment(raw.bytes, ownerBox, therapist.signPub)
  } catch (e) {
    // A signature mismatch means the author is not the pinned therapist (forged / substituted
    // key) — call that out distinctly from a sealed-box failure (not-for-us / tampered).
    const untrusted = e instanceof AssignmentOpenError && /signature/i.test(e.message)
    return {
      ...base,
      verdict: untrusted ? 'UNTRUSTED_KEY' : 'OPEN_FAILED',
      requiresAccept: false,
      preview: untrusted
        ? 'Could not verify authorship against the pinned therapist key — refused.'
        : 'Could not open this item (not addressed to you, or tampered) — refused.',
      errors: [e instanceof Error ? e.message : 'open failed'],
    }
  }

  const check = validateAssignment(assignment, therapist.grant)
  const preview = describeAssignment(assignment)

  if (!check.ok) {
    return {
      ...base,
      assignment,
      verdict: 'REJECTED',
      check,
      requiresAccept: false,
      preview,
      errors: check.errors,
    }
  }

  // Verified + validated. suggest.setting is ALWAYS accept-required; otherwise a non-setting
  // capability marked 'auto' may auto-apply, so it does not strictly require acceptance.
  const auto = shouldAutoApply(assignment, check.applyMode!)
  const requiresAccept = assignment.capability === 'suggest.setting' ? true : !auto
  return {
    ...base,
    assignment,
    verdict: 'VERIFIED',
    check,
    requiresAccept,
    preview,
    errors: [],
  }
}

/** Fold many raw blobs (across therapists) into a sorted inbox (newest issuedAt first). */
export function buildInbox(blobs: RawAssignmentBlob[], therapists: PinnedTherapist[], ownerBox: BoxKeyPair): InboxItem[] {
  const byId = new Map(therapists.map((t) => [t.id, t]))
  const items: InboxItem[] = []
  for (const b of blobs) {
    const t = byId.get(b.therapistId)
    if (!t) continue // no pinned therapist for this lineage — skip (never trust an unpinned author)
    items.push(evaluateBlob(b, t, ownerBox))
  }
  items.sort((a, b) => (b.assignment?.issuedAt ?? 0) - (a.assignment?.issuedAt ?? 0))
  return items
}

/** Only VERIFIED items may be applied; a decision is recorded for the item's lineage/version. */
export function canApply(item: InboxItem): boolean {
  return item.verdict === 'VERIFIED'
}
