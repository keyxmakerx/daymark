/*
 * THE OWNER'S GAME PLANS (#231): a clinician's plan, opened, checked and shown as a proposal, and the
 * owner's accept or decline kept in the owner's lane.
 *
 * WHAT A PLAN MUST BE TO BE SHOWN AS ONE. Each of these is checked before anything of the plan is
 * shown, and a plan that fails one is a refused item that can never be accepted:
 *   - it opens with the owner's own box key (sealed to this owner, and not altered);
 *   - it is signed by the clinician's PINNED key, and names that key's fingerprint as its author;
 *   - it is addressed to this owner (therapist/gamePlan.ts checks recipientOwnerFp);
 *   - it is filed under the lineage and version it was signed with, which the server does not sign;
 *   - it is well formed: a withdrawal carries no items, and every item is one of the four kinds.
 *
 * WHAT THE OWNER CAN DO WITH IT. Accept or decline a VERIFIED, active plan. A withdrawn plan is
 * history: shown, and never accepted. A newer version of a plan is a new proposal, because a
 * decision answers exactly the signed plan it was made about.
 *
 * WHERE A DECISION IS KEPT. In the owner's lane, as a gamePlanDecision record carrying the plan as
 * signed (lane/record.ts), the way the assignment inbox keeps its own (assignments/inboxLane.ts).
 * A decline of a plan that did not verify is not kept: there is no plan a decision could be about.
 *
 * A plan is never written into `treatments`. Ports and transitions only; the screen holds no rule.
 */
import { fingerprint, type BoxKeyPair } from '../assignments/crypto'
import type { PinnedTherapist, RawAssignmentBlob } from '../assignments/inbox'
import { gamePlanDecisionRecord, readRecord, type KnownRecord, type LaneRecord, type SignedEnvelope } from '../lane/record'
import { openGamePlanSigned, type GamePlanItem, type GamePlanPayload } from '../therapist/gamePlan'

export type PlanVerdict = 'VERIFIED' | 'UNTRUSTED_KEY' | 'OPEN_FAILED'
export type PlanDecision = 'accepted' | 'declined'

export interface PlanItem {
  therapistId: string
  therapistName: string
  raw: { lineage: string; version: number }
  verdict: PlanVerdict
  /** The plan, for a VERIFIED item only: nothing of a plan that failed a check is shown. */
  plan?: GamePlanPayload
  /** The plan as signed, verbatim, for a VERIFIED item: what a decision carries and is matched by. */
  signed?: SignedEnvelope
  decision?: PlanDecision
}

const KINDS: ReadonlySet<string> = new Set(['goal', 'exercise', 'task', 'note'])

/** Whether every item of a plan is one this console can show, and a withdrawal carries none. */
function wellFormed(p: GamePlanPayload): boolean {
  if (p.status !== 'active' && p.status !== 'withdrawn') return false
  if (!Array.isArray(p.items)) return false
  if (p.status === 'withdrawn' && p.items.length > 0) return false
  const refs = new Set<string>()
  for (const it of p.items as unknown[]) {
    const item = it as Partial<GamePlanItem> | null
    if (typeof item !== 'object' || item === null) return false
    if (typeof item.itemRef !== 'string' || refs.has(item.itemRef)) return false
    if (typeof item.kind !== 'string' || !KINDS.has(item.kind)) return false
    if (typeof item.title !== 'string') return false
    if (item.detail !== undefined && typeof item.detail !== 'string') return false
    refs.add(item.itemRef)
  }
  return true
}

/** Open and check one fetched plan. Never throws: every failure is an item that cannot be accepted. */
export function evaluatePlan(raw: RawAssignmentBlob, therapist: PinnedTherapist, ownerBox: BoxKeyPair): PlanItem {
  const base = { therapistId: therapist.id, therapistName: therapist.displayName, raw: { lineage: raw.lineage, version: raw.version } }
  let opened: ReturnType<typeof openGamePlanSigned>
  try {
    opened = openGamePlanSigned(raw.bytes, ownerBox, therapist.signPub)
  } catch (e) {
    const untrusted = e instanceof Error && /signature/i.test(e.message)
    return { ...base, verdict: untrusted ? 'UNTRUSTED_KEY' : 'OPEN_FAILED' }
  }
  const plan = opened.plan
  // The signed name must be the pinned key's own: it decides whose plan this is shown as.
  if (plan.authorFingerprint !== fingerprint(therapist.signPub)) return { ...base, verdict: 'UNTRUSTED_KEY' }
  if (plan.lineageId !== raw.lineage || plan.version !== raw.version) return { ...base, verdict: 'OPEN_FAILED' }
  if (!wellFormed(plan)) return { ...base, verdict: 'OPEN_FAILED' }
  return { ...base, verdict: 'VERIFIED', plan, signed: { payloadJson: opened.payloadJson, sigB64: opened.sigB64 } }
}

/** Every fetched plan from a pinned clinician, opened and checked, newest issued first. */
export function buildPlans(blobs: readonly RawAssignmentBlob[], therapists: readonly PinnedTherapist[], ownerBox: BoxKeyPair): PlanItem[] {
  const byId = new Map(therapists.map((t) => [t.id, t]))
  const items: PlanItem[] = []
  for (const b of blobs) {
    const t = byId.get(b.therapistId)
    if (t) items.push(evaluatePlan(b, t, ownerBox))
  }
  return items.sort((a, b) => (b.plan?.issuedAt ?? 0) - (a.plan?.issuedAt ?? 0))
}

/** Whether the owner may accept or decline this plan: a VERIFIED one that is not withdrawn. */
export function canDecide(item: PlanItem): boolean {
  return item.verdict === 'VERIFIED' && item.plan?.status === 'active'
}

/** The record a decision adds to the owner's lane, or null for a plan no decision can be about. */
export function planDecisionRecordFor(item: PlanItem, decision: PlanDecision, createdAt: number = Date.now()): KnownRecord | null {
  if (!canDecide(item) || !item.signed) return null
  return gamePlanDecisionRecord(item.signed, decision, createdAt)
}

/** Later first: by when it was made, then by id, as the assignment inbox orders its own. */
function later(a: LaneRecord, b: LaneRecord): boolean {
  return a.createdAt !== b.createdAt ? a.createdAt > b.createdAt : a.id > b.id
}

/** The plans, each with the latest decision the lane holds for exactly its signed plan. */
export function applyPlanDecisions(items: readonly PlanItem[], records: readonly LaneRecord[]): PlanItem[] {
  const latest = new Map<string, LaneRecord>()
  for (const r of records) {
    const read = readRecord(r)
    if (read?.kind !== 'gamePlanDecision') continue
    const key = `${read.payload.sigB64}\n${read.payload.payloadJson}`
    const held = latest.get(key)
    if (!held || later(r, held)) latest.set(key, r)
  }
  return items.map((item) => {
    if (!item.signed) return item
    const found = latest.get(`${item.signed.sigB64}\n${item.signed.payloadJson}`)
    const read = found && readRecord(found)
    return read?.kind === 'gamePlanDecision' ? { ...item, decision: read.payload.decision } : item
  })
}

/* ── Fixed words. Every sentence the plans section says, with the clinician's name slotted in. ── */

export const PLANS_HEADING = 'Game plans'
export const PLANS_FRAMING =
  'This is guidance from your real clinician. Daymark is not diagnosing you. You can accept it or decline it.'
export const PLANS_EMPTY_BEFORE = 'Refresh to fetch game plans from your clinicians.'
export const PLANS_EMPTY = 'No game plans to review.'

export function planHeading(item: PlanItem): string {
  if (item.verdict !== 'VERIFIED') return `From ${item.therapistName}`
  if (item.plan?.status === 'withdrawn') return `From ${item.therapistName} · withdrawn`
  return `From ${item.therapistName} · proposal · ${item.decision === 'accepted' ? 'accepted' : 'not yet added'}`
}

export function planRefusal(item: PlanItem): string {
  return item.verdict === 'UNTRUSTED_KEY'
    ? `This plan is not signed by the key you recorded for ${item.therapistName}. It cannot be accepted.`
    : `This plan could not be opened: it was not sealed to you, or it was changed on the way. It cannot be accepted.`
}

export const PLAN_WITHDRAWN = 'Your clinician has withdrawn this plan. It is kept here as history.'
export const PLAN_DECLINED = 'Declined. Your clinician is not told.'
export const PLAN_ACCEPTED = 'Accepted. The plan is kept as your clinician wrote it.'

export function kindLabel(kind: GamePlanItem['kind']): string {
  return kind === 'goal' ? 'Goal' : kind === 'exercise' ? 'Exercise' : kind === 'task' ? 'Task' : 'Note'
}

export function perWeek(n: number): string {
  return n === 1 ? 'Once a week' : `${n} times a week`
}
