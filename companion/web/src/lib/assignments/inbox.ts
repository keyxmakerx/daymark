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
import { PortalError, type RelMeta } from '../sync/portal'

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

  // The lineage and version the server files an item under are not signed; the ones inside it
  // are. An item served under another label is a signed assignment re-presented as something it
  // is not (an old one shown as new, or one shown twice), so it is refused like a tampered one.
  if (assignment.lineageId !== raw.lineage || assignment.version !== raw.version) {
    return {
      ...base,
      verdict: 'OPEN_FAILED',
      requiresAccept: false,
      preview: 'Could not open this item (not addressed to you, or tampered) — refused.',
      errors: ['assignment is filed under a different lineage or version than it was signed with'],
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

/* ── Fetching, item by item (#339) ────────────────────────────────────────────────────────── */

/** What the inbox needs from the server: the listings and single items. PortalClient has all three. */
export interface InboxSource {
  listLineages(inboxToken: string, channel: 'assignments'): Promise<string[]>
  listVersions(inboxToken: string, channel: 'assignments', lineage: string): Promise<RelMeta[]>
  getBlob(inboxToken: string, channel: 'assignments', lineage: string, version: number): Promise<Uint8Array>
}

/** A clinician whose items are fetched: the pinned entry's id and name, and its inbox token. */
export interface InboxSender {
  id: string
  displayName: string
  inboxToken: string
}

/** An item the server no longer keeps. Its contents are gone, so only who sent it and when remain. */
export interface GoneItem {
  therapistName: string
  sentAt: number
  lineage: string
  version: number
}

/**
 * Fetch the head of every assignment lineage, one item at a time.
 *
 * The server keeps an assignment for 90 days and then answers 410 for it (#332, #338). That is the
 * normal end of an item, not a failure, so it becomes a GoneItem and the rest of the inbox still
 * loads; before this, one ended item stopped the whole load. Any other failure still stops it,
 * because a transient error is not a fact about the item and Refresh is the honest answer to it.
 */
export async function fetchInbox(
  source: InboxSource,
  senders: readonly InboxSender[],
): Promise<{ blobs: RawAssignmentBlob[]; gone: GoneItem[] }> {
  const blobs: RawAssignmentBlob[] = []
  const gone: GoneItem[] = []
  for (const t of senders) {
    const lineages = await source.listLineages(t.inboxToken, 'assignments').catch(() => [])
    for (const lineage of lineages) {
      const versions = await source.listVersions(t.inboxToken, 'assignments', lineage)
      // Only the head of each lineage is surfaced (append-only supersede).
      const head = versions.reduce((a, b) => (b.version > a.version ? b : a), versions[0])
      if (!head) continue
      try {
        const bytes = await source.getBlob(t.inboxToken, 'assignments', lineage, head.version)
        blobs.push({ therapistId: t.id, lineage, version: head.version, bytes })
      } catch (e) {
        if (!(e instanceof PortalError && e.status === 410)) throw e
        gone.push({ therapistName: t.displayName, sentAt: head.createdAt, lineage, version: head.version })
      }
    }
  }
  return { blobs, gone }
}

/**
 * The one line an item the server no longer keeps gets. Two facts the console knows and nothing
 * else: it cannot say what the item was, and "expired" or "missed" would read as a lapse on the
 * owner's part. Same ink as the live items, no alarm colour, nothing to click.
 */
export function goneItemLine(name: string, date: string): string {
  return `Sent by ${name} on ${date}. The server keeps items for 90 days.`
}

