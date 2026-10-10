import { describe, it, expect, beforeAll } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { initAssignmentCrypto, newBoxKeyPair, newSignKeyPair, fingerprint, type BoxKeyPair, type SignKeyPair } from '../assignments/crypto'
import { emptyGrant } from '../assignments/grant'
import type { PinnedTherapist } from '../assignments/inbox'
import { newGamePlan, sealGamePlan, supersede, withdraw, type GamePlanItem, type GamePlanPayload } from '../therapist/gamePlan'
import {
  applyPlanDecisions,
  buildPlans,
  canDecide,
  evaluatePlan,
  planDecisionRecordFor,
  planHeading,
  PLANS_FRAMING,
} from './planInbox'

let owner: BoxKeyPair
let clinician: SignKeyPair
let stranger: SignKeyPair

beforeAll(async () => {
  await initAssignmentCrypto()
  owner = newBoxKeyPair()
  clinician = newSignKeyPair()
  stranger = newSignKeyPair()
})

function pinned(): PinnedTherapist {
  return {
    id: 't1', displayName: 'Dr. Example',
    signPub: clinician.publicKey, boxPub: newBoxKeyPair().publicKey, grant: emptyGrant(fingerprint(clinician.publicKey)),
  }
}

const items: GamePlanItem[] = [
  { itemRef: 'a', kind: 'goal', title: 'Walk', detail: 'Ten minutes', targetPerWeek: 3 },
  { itemRef: 'b', kind: 'exercise', title: 'Breathe' },
]

function plan(over: Partial<GamePlanPayload> = {}): GamePlanPayload {
  return { ...newGamePlan(fingerprint(owner.publicKey), fingerprint(clinician.publicKey), 1_000), lineageId: 'gp-1', items, ...over }
}

function blob(p: GamePlanPayload, signer: SignKeyPair = clinician, to: Uint8Array = owner.publicKey, filed = { lineage: p.lineageId, version: p.version }) {
  return { therapistId: 't1', lineage: filed.lineage, version: filed.version, bytes: sealGamePlan(p, signer, to) }
}

describe('a game plan is shown as a proposal only once every check passes (#231)', () => {
  it('opens a plan sealed to this owner by the pinned clinician', () => {
    const item = evaluatePlan(blob(plan()), pinned(), owner)
    expect(item.verdict).toBe('VERIFIED')
    expect(item.plan?.items.map((i) => i.title)).toEqual(['Walk', 'Breathe'])
    expect(canDecide(item)).toBe(true)
    expect(planHeading(item)).toBe('From Dr. Example · proposal · not yet added')
  })

  it('refuses each failure on its own, and shows nothing of the plan', () => {
    // The control for every case below is the test above: the same plan, unchanged, is VERIFIED.
    const cases: Array<[string, ReturnType<typeof blob>, string]> = [
      ['signed by another key', blob(plan(), stranger), 'UNTRUSTED_KEY'],
      ['sealed to another owner', blob(plan(), clinician, newBoxKeyPair().publicKey), 'OPEN_FAILED'],
      ['addressed to another owner', blob(plan({ recipientOwnerFp: fingerprint(newBoxKeyPair().publicKey) })), 'OPEN_FAILED'],
      ['naming another author', blob(plan({ authorFingerprint: fingerprint(stranger.publicKey) })), 'UNTRUSTED_KEY'],
      ['filed under another version', blob(plan(), clinician, owner.publicKey, { lineage: 'gp-1', version: 1 }), 'OPEN_FAILED'],
      ['filed under another lineage', blob(plan(), clinician, owner.publicKey, { lineage: 'gp-2', version: 0 }), 'OPEN_FAILED'],
      ['a withdrawal carrying items', blob({ ...withdraw(plan()), items }), 'OPEN_FAILED'],
      ['an item of an unknown kind', blob(plan({ items: [{ itemRef: 'a', kind: 'diagnosis' as GamePlanItem['kind'], title: 'x' }] })), 'OPEN_FAILED'],
      ['two items with one ref', blob(plan({ items: [items[0], { ...items[1], itemRef: 'a' }] })), 'OPEN_FAILED'],
    ]
    expect(fingerprint(stranger.publicKey)).not.toBe(fingerprint(clinician.publicKey))
    for (const [name, b, verdict] of cases) {
      const item = evaluatePlan(b, pinned(), owner)
      expect(item.verdict, name).toBe(verdict)
      expect(item.plan, name).toBeUndefined()
      expect(canDecide(item), name).toBe(false)
      expect(planDecisionRecordFor(item, 'declined'), name).toBeNull()
    }
  })

  it('shows a withdrawn plan as history, never to be accepted', () => {
    const item = evaluatePlan(blob(withdraw(plan())), pinned(), owner)
    expect(item.verdict).toBe('VERIFIED')
    expect(canDecide(item)).toBe(false)
    expect(planDecisionRecordFor(item, 'accepted')).toBeNull()
    expect(planHeading(item)).toBe('From Dr. Example · withdrawn')
  })

  it('reads plans only from a pinned clinician, newest first', () => {
    const older = blob(plan())
    const newer = blob(supersede(plan(), items.slice(0, 1), 2_000))
    const unpinned = { ...blob(plan({ lineageId: 'gp-9' })), therapistId: 'nobody' }
    const built = buildPlans([older, newer, unpinned], [pinned()], owner)
    expect(built.map((i) => i.raw.version)).toEqual([1, 0])
  })
})

describe('a decision is kept in the lane and answers exactly the plan it was made about', () => {
  it('finds its plan again after a Refresh, and not a newer version of it', () => {
    const first = evaluatePlan(blob(plan()), pinned(), owner)
    const next = evaluatePlan(blob(supersede(plan(), items.slice(0, 1), 2_000)), pinned(), owner)
    const record = planDecisionRecordFor(first, 'accepted', 5_000)!
    expect(record.kind).toBe('gamePlanDecision')
    const [a, b] = applyPlanDecisions([first, next], [record])
    expect(a.decision).toBe('accepted')
    expect(planHeading(a)).toBe('From Dr. Example · proposal · accepted')
    // A newer version is a new proposal: the decision was about the plan as it was signed then.
    expect(b.decision).toBeUndefined()
  })

  it('keeps the latest of two decisions about one plan', () => {
    const item = evaluatePlan(blob(plan()), pinned(), owner)
    const accepted = planDecisionRecordFor(item, 'accepted', 5_000)!
    const declined = planDecisionRecordFor(item, 'declined', 6_000)!
    expect(applyPlanDecisions([item], [declined, accepted])[0].decision).toBe('declined')
    expect(applyPlanDecisions([item], [accepted])[0].decision).toBe('accepted')
  })
})

describe('the plans section', () => {
  const source = readFileSync(fileURLToPath(new URL('../components/owner/PlanInbox.svelte', import.meta.url)), 'utf8')

  it('says it is guidance from a real clinician, and that Daymark is not diagnosing', () => {
    expect(PLANS_FRAMING).toContain('Daymark is not diagnosing you')
    expect(source).toContain('{PLANS_FRAMING}')
  })

  it('draws no tick, no green and no raw colour', () => {
    const tick = /✓|✔|check-?mark|\bgreen\b/i
    const raw = /#[0-9a-fA-F]{3,8}\b|rgba?\(|hsla?\(/
    const style = source.slice(source.indexOf('<style>'))
    // The controls: the scanners see what they look for, and the style block is really read.
    expect(tick.test('<span>✓ Accepted</span>')).toBe(true)
    expect(raw.test('color: #00ff00')).toBe(true)
    expect(style).toContain('var(--clay)')
    expect(source.match(tick)).toBeNull()
    expect(style.match(raw)).toBeNull()
  })

  it('writes a decision only from a button the owner presses', () => {
    expect(source.match(/lane\.add\(/g)).toHaveLength(1)
    expect(source).toContain("onclick={() => decide(item, 'accepted')}")
    expect(source).not.toMatch(/\$effect|setInterval|setTimeout/)
  })
})
