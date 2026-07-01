/*
 * GAME PLAN authoring (therapist → owner). A written plan (goals/exercises/tasks/notes) the
 * therapist composes and sends to the owner. Same sign-then-seal primitive as assignments:
 *   payload ──Ed25519.sign(therapist)──▶ signed ──crypto_box_seal(owner X25519 pub)──▶ blob
 *
 * The owner opens the sealed box and verifies the signature against the PINNED therapist key. The
 * payload carries context 'daymark.gameplan.v1' + recipientOwnerFp + lineageId/version/supersedes
 * so a plan cannot be re-pointed at a different owner and still verify (recipientOwnerFp is checked
 * on open), and supersede-not-mutate keeps the history append-only.
 *
 * Game-plan bodies are FREE TEXT, so "non-diagnostic" here is by FRAMING, not by construction — the
 * authoring UI carries the fixed non-diagnostic disclaimer (see GamePlanAuthor.svelte).
 */
import _sodium from 'libsodium-wrappers-sumo'
import type { Cadence } from '../assignments/types'
import { fingerprint, type SignKeyPair } from '../assignments/crypto'

const URLSAFE = () => _sodium.base64_variants.URLSAFE_NO_PADDING
const enc = new TextEncoder()
const dec = new TextDecoder()

export const GAMEPLAN_CONTEXT = 'daymark.gameplan.v1' as const

export interface GamePlanItem {
  itemRef: string
  kind: 'goal' | 'exercise' | 'task' | 'note'
  title: string
  detail?: string
  targetPerWeek?: number
  dueAt?: number | null
  recurrence?: string | null
}

export interface GamePlanPayload {
  context: typeof GAMEPLAN_CONTEXT
  lineageId: string
  version: number
  supersedes: number | null
  recipientOwnerFp: string // owner X25519 fingerprint the plan is sealed to
  status: 'active' | 'withdrawn'
  items: GamePlanItem[]
  reviewCadence?: Cadence
  authorFingerprint: string // therapist Ed25519 fp; verified against the owner's pinned value
  issuedAt: number
}

/** Canonical bytes for signing — stable key + item ordering, only-present optional fields. */
export function canonicalize(p: GamePlanPayload): Uint8Array {
  const canonical = {
    context: p.context,
    lineageId: p.lineageId,
    version: p.version,
    supersedes: p.supersedes,
    recipientOwnerFp: p.recipientOwnerFp,
    status: p.status,
    items: p.items.map((it) => ({
      itemRef: it.itemRef,
      kind: it.kind,
      title: it.title,
      ...(it.detail !== undefined ? { detail: it.detail } : {}),
      ...(it.targetPerWeek !== undefined ? { targetPerWeek: it.targetPerWeek } : {}),
      ...(it.dueAt !== undefined ? { dueAt: it.dueAt } : {}),
      ...(it.recurrence !== undefined ? { recurrence: it.recurrence } : {}),
    })),
    ...(p.reviewCadence ? { reviewCadence: { every: p.reviewCadence.every, count: p.reviewCadence.count } } : {}),
    authorFingerprint: p.authorFingerprint,
    issuedAt: p.issuedAt,
  }
  return enc.encode(JSON.stringify(canonical))
}

export class GamePlanOpenError extends Error {}

/** Therapist signs the canonical plan, then seals the envelope to the owner's X25519 key. */
export function sealGamePlan(p: GamePlanPayload, therapistSign: SignKeyPair, ownerBoxPub: Uint8Array): Uint8Array {
  const payloadJson = dec.decode(canonicalize(p))
  const sig = _sodium.crypto_sign_detached(enc.encode(payloadJson), therapistSign.privateKey)
  const envelope = enc.encode(JSON.stringify({ payloadJson, sigB64: _sodium.to_base64(sig, URLSAFE()) }))
  return _sodium.crypto_box_seal(envelope, ownerBoxPub)
}

/**
 * Owner opens + verifies a game plan. Throws if the seal cannot be opened, the signature does not
 * match the pinned therapist key, or the plan's recipientOwnerFp is not this owner (mis-addressed
 * / spliced). This is provided here so the therapist client can round-trip / self-verify in tests.
 */
export function openGamePlan(
  blob: Uint8Array,
  ownerBox: { publicKey: Uint8Array; privateKey: Uint8Array },
  pinnedTherapistSignPub: Uint8Array,
): GamePlanPayload {
  let openedBytes: Uint8Array
  try {
    openedBytes = _sodium.crypto_box_seal_open(blob, ownerBox.publicKey, ownerBox.privateKey)
  } catch {
    throw new GamePlanOpenError('sealed game plan could not be opened (not for this owner, or tampered)')
  }
  let env: { payloadJson?: string; sigB64?: string }
  try {
    env = JSON.parse(dec.decode(openedBytes))
  } catch {
    throw new GamePlanOpenError('malformed game plan envelope')
  }
  if (typeof env.payloadJson !== 'string' || typeof env.sigB64 !== 'string') {
    throw new GamePlanOpenError('game plan envelope missing fields')
  }
  const ok = _sodium.crypto_sign_verify_detached(_sodium.from_base64(env.sigB64, URLSAFE()), enc.encode(env.payloadJson), pinnedTherapistSignPub)
  if (!ok) throw new GamePlanOpenError('game plan signature does not match the pinned therapist key')
  const p = JSON.parse(env.payloadJson) as GamePlanPayload
  if (p.context !== GAMEPLAN_CONTEXT) throw new GamePlanOpenError('unexpected game plan context')
  const ownerFp = fingerprint(ownerBox.publicKey)
  if (p.recipientOwnerFp !== ownerFp) throw new GamePlanOpenError('game plan is addressed to a different owner')
  return p
}

/** A new empty plan for an owner, with a fresh lineage. */
export function newGamePlan(recipientOwnerFp: string, authorFp: string, now: number = Date.now()): GamePlanPayload {
  return {
    context: GAMEPLAN_CONTEXT,
    lineageId: `gp-${now.toString(36)}-${Math.random().toString(36).slice(2, 8)}`,
    version: 0,
    supersedes: null,
    recipientOwnerFp,
    status: 'active',
    items: [],
    authorFingerprint: authorFp,
    issuedAt: now,
  }
}

/** A superseding revision: same lineage, next version, supersedes the current one. */
export function supersede(prev: GamePlanPayload, items: GamePlanItem[], now: number = Date.now()): GamePlanPayload {
  return {
    ...prev,
    version: prev.version + 1,
    supersedes: prev.version,
    status: 'active',
    items,
    issuedAt: now,
  }
}

/** A signed withdrawal tombstone for a lineage (no items, status 'withdrawn'). */
export function withdraw(prev: GamePlanPayload, now: number = Date.now()): GamePlanPayload {
  return {
    ...prev,
    version: prev.version + 1,
    supersedes: prev.version,
    status: 'withdrawn',
    items: [],
    issuedAt: now,
  }
}
