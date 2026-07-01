/*
 * Therapist ASSIGN client: build → validate (pre-flight) → sign+seal → PUT an Assignment.
 *
 * The therapist signs the assignment JSON with their Ed25519 key, then seals it to the owner's
 * pinned X25519 key (sealAssignment). validateAssignment runs LOCALLY first so the therapist sees
 * the SAME rejection the owner will apply — but this is UX only: the owner's device is the real
 * gate. The blob is opaque to the server.
 *
 * Append-only versioning: a new assignment on an existing lineage bumps to max(version)+1; a fresh
 * lineage starts at version 0. The therapist never mutates a published version.
 */
import type { Assignment, AssignmentType, AssignmentPayload, Cadence, Grant } from '../assignments/types'
import { TYPE_CAPABILITY } from '../assignments/types'
import { validateAssignment, type AssignmentCheck } from '../assignments/validate'
import { sealAssignment, type SignKeyPair } from '../assignments/crypto'
import type { PortalClient, SessionInfo } from './session'

export interface AssignmentInput {
  type: AssignmentType
  payload: AssignmentPayload
  cadence?: Cadence
  note?: string
  lineageId?: string // reuse to supersede a prior lineage; omit for a new one
}

/** Build an Assignment from the therapist's input, stamping the capability + author fingerprint. */
export function buildAssignment(input: AssignmentInput, authorFp: string, now: number = Date.now(), lineageId?: string): Assignment {
  const lin = lineageId ?? input.lineageId ?? `asg-${now.toString(36)}-${Math.random().toString(36).slice(2, 8)}`
  return {
    assignmentId: `${lin}#${now}`,
    lineageId: lin,
    version: 0, // real wire version is chosen at PUT time from the lineage head
    type: input.type,
    capability: TYPE_CAPABILITY[input.type],
    payload: input.payload,
    ...(input.cadence ? { cadence: input.cadence } : {}),
    ...(input.note ? { note: input.note } : {}),
    issuedAt: now,
    authorFingerprint: authorFp,
  }
}

/** Pre-flight check against the (verified) local grant. */
export function preflight(a: Assignment, grant: Grant): AssignmentCheck {
  return validateAssignment(a, grant)
}

export interface PublishResult {
  ok: boolean
  version?: number
  error?: string
  check?: AssignmentCheck
}

/**
 * Validate → seal → PUT. Refuses to publish an assignment that fails the local pre-flight (so a
 * therapist cannot even attempt an ungranted/off-catalog assignment). `stepUp` is an optional
 * client-side confirmation the caller resolves before the write (fresh sign-off ceremony).
 */
export async function publishAssignment(
  a: Assignment,
  grant: Grant,
  keys: { sign: SignKeyPair },
  ownerBoxPub: Uint8Array,
  client: PortalClient,
  session: SessionInfo,
  stepUp?: () => Promise<boolean>,
): Promise<PublishResult> {
  const check = validateAssignment(a, grant)
  if (!check.ok) return { ok: false, error: check.errors.join('; '), check }

  if (stepUp) {
    const okStep = await stepUp()
    if (!okStep) return { ok: false, error: 'Sign-off cancelled.', check }
  }

  // Choose the next append-only version from the lineage head.
  const existing = await client.listVersions(session, 'assignments', a.lineageId).catch(() => [])
  const version = existing.reduce((m, v) => Math.max(m, v.version), -1) + 1
  const wire: Assignment = { ...a, version }

  const blob = sealAssignment(wire, keys.sign as SignKeyPair, ownerBoxPub)
  await client.putBlob(session, 'assignments', a.lineageId, version, blob)
  return { ok: true, version, check }
}
