import { beforeEach, describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import {
  MAX_EXCHANGES_PER_INVITE,
  abandonApproval,
  approve,
  checkForReply,
  keepInvitation,
  newCode,
  openRun,
  restore,
  startInvitation,
  stopInvitation,
  type InviteSummary,
  type OwnerCeremony,
  type OwnerCeremonyPorts,
} from './ownerCeremony'
import { OWNER_RUN_STORAGE_KEY, type OwnerRunStorage } from './ownerRunStore'
import type { TherapistOffer } from './payloads'
import type { OwnerPairingState } from './relay'
import type { InviteResponse } from '../sync/portal'
import { type CanonicalPairingCode } from './pairingCode'
import type { OfferCheck } from '../owner/therapistKeys'
import type { PinWrite } from '../therapist/pinStore'
import { OWNER_COPY } from './copy'

/*
 * The ORDER of effects is what this file is for. Every port records its call, so a test can assert
 * that a ticket is never forwarded before keys are pinned, that nothing opens a second run on its
 * own, and that the code never reaches storage.
 */

const OFFER: TherapistOffer = {
  boxPubB64: Buffer.from(new Uint8Array(32).fill(1)).toString('base64url'),
  signPubB64: Buffer.from(new Uint8Array(32).fill(2)).toString('base64url'),
  displayName: 'Dr Example',
  enrolTicketB64: Buffer.from(new Uint8Array(32).fill(3)).toString('base64url'),
}

const INVITE: InviteResponse = { inviteId: 'inv-1', link: 'https://example/portal/invite#id=inv-1&s=x', expiresAt: 9_000_000 }

function memoryStorage(): OwnerRunStorage & { data: Map<string, string> } {
  const data = new Map<string, string>()
  return {
    data,
    getItem: (k) => data.get(k) ?? null,
    setItem: (k, v) => void data.set(k, v),
    removeItem: (k) => void data.delete(k),
  }
}

function runFor(exchangeId: string): OwnerPairingState {
  return {
    exchangeId,
    inviteId: INVITE.inviteId,
    sidB64: Buffer.from(new Uint8Array(16).fill(9)).toString('base64url'),
    start: { ya: new Uint8Array(32).fill(4), msgA: new Uint8Array(35).fill(5) },
  }
}

interface Harness {
  ports: OwnerCeremonyPorts
  calls: string[]
  storage: ReturnType<typeof memoryStorage>
  codes: string[]
  reply: { state: 'waiting' } | { state: 'cancelled' } | { state: 'superseded' } | { state: 'complete'; isk: Uint8Array; offer: TherapistOffer | null }
  invites: InviteSummary[]
  check: OfferCheck
  pin: PinWrite | 'unreadable'
  approveThrows: boolean
}

function harness(): Harness {
  const calls: string[] = []
  const storage = memoryStorage()
  const codes: string[] = []
  let n = 0
  const h: Harness = {
    calls,
    storage,
    codes,
    reply: { state: 'waiting' },
    invites: [],
    check: { kind: 'first' },
    pin: 'pinned-now',
    approveThrows: false,
    ports: {
      async mintInvite() {
        calls.push('mint')
        return INVITE
      },
      async listInvites() {
        calls.push('list')
        return h.invites
      },
      async openRun(inviteId, code) {
        calls.push(`open:${inviteId}:${code.canonical}`)
        codes.push(code.canonical)
        return runFor(`ex-${++n}`)
      },
      async readRun(run) {
        calls.push(`read:${run.exchangeId}`)
        return h.reply
      },
      async approveRun(exchangeId, ticket) {
        calls.push(`approve:${exchangeId}:${ticket}`)
        if (h.approveThrows) throw new Error('server said no')
      },
      async cancelRun(exchangeId) {
        calls.push(`cancel:${exchangeId}`)
        return true
      },
      async reportInvite(inviteId) {
        calls.push(`report:${inviteId}`)
      },
      inspectOffer() {
        calls.push('inspect')
        return h.check
      },
      pinOffer() {
        calls.push('pin')
        return h.pin
      },
      displayName: 'Sam Reed',
      async freshCode() {
        const canonical = `CODE${String(codes.length).padStart(4, '0')}` as CanonicalPairingCode
        return { canonical, display: `${canonical.slice(0, 4)}-${canonical.slice(4)}` }
      },
      storage,
      now: () => 1_700_000_000_000,
    },
  }
  return h
}

let h: Harness
beforeEach(() => {
  h = harness()
})

async function toWaiting(): Promise<OwnerCeremony> {
  return openRun(h.ports, await startInvitation(h.ports))
}

describe('opening a run', () => {
  it('mints without making a code, then makes one only when the owner opens a run', async () => {
    const invited = await startInvitation(h.ports)
    expect(invited.phase).toBe('invited')
    expect(h.codes).toHaveLength(0)
    expect(h.calls).toEqual(['mint'])

    const waiting = await openRun(h.ports, invited)
    expect(waiting.phase).toBe('waiting')
    expect(h.codes).toHaveLength(1)
    if (waiting.phase !== 'waiting') return
    expect(waiting.code.display).toMatch(/^.{4}-.{4}$/)
    expect(waiting.attemptsLeft).toBe(MAX_EXCHANGES_PER_INVITE - 1)
  })

  it('persists the run, and what it persists has no field for the code', async () => {
    const waiting = await toWaiting()
    if (waiting.phase !== 'waiting') return
    const stored = h.storage.data.get(OWNER_RUN_STORAGE_KEY)!
    expect(stored).toContain(waiting.run.exchangeId)
    expect(stored).not.toContain(waiting.code.canonical)
    expect(stored).not.toContain(waiting.code.display)
    // Control: the detector would see the code if it were there.
    expect((stored + waiting.code.canonical).includes(waiting.code.canonical)).toBe(true)
  })
})

describe('checking for a reply', () => {
  it('stays put while nobody has answered, and never opens a second run on its own', async () => {
    const waiting = await toWaiting()
    const still = await checkForReply(h.ports, waiting)
    expect(still).toBe(waiting)
    await checkForReply(h.ports, still)
    expect(h.calls.filter((c) => c.startsWith('open:'))).toHaveLength(1)
  })

  it('an offer that opens is the answered phase, with the name to show', async () => {
    const waiting = await toWaiting()
    h.reply = { state: 'complete', isk: new Uint8Array(64), offer: OFFER }
    const answered = await checkForReply(h.ports, waiting)
    expect(answered.phase).toBe('answered')
    if (answered.phase !== 'answered') return
    expect(answered.offer.displayName).toBe('Dr Example')
  })

  it('an offer that does not open is a mismatch with no diagnosis and no automatic retry', async () => {
    const waiting = await toWaiting()
    h.reply = { state: 'complete', isk: new Uint8Array(64), offer: null }
    const mismatch = await checkForReply(h.ports, waiting)
    expect(mismatch.phase).toBe('mismatch')
    // Nothing was opened, cancelled, approved or reported off the back of it.
    expect(h.calls.filter((c) => c.startsWith('open:'))).toHaveLength(1)
    expect(h.calls.some((c) => c.startsWith('cancel:') || c.startsWith('approve:') || c.startsWith('report:'))).toBe(false)
  })

  it('cancelled and superseded end the run and forget it', async () => {
    for (const [reply, reason] of [
      [{ state: 'cancelled' } as const, 'cancelled'],
      [{ state: 'superseded' } as const, 'superseded'],
    ] as const) {
      h = harness()
      const waiting = await toWaiting()
      h.reply = reply
      const ended = await checkForReply(h.ports, waiting)
      expect(ended.phase).toBe('ended')
      expect(ended.phase === 'ended' && ended.reason).toBe(reason)
      expect(h.storage.data.get(OWNER_RUN_STORAGE_KEY)).toBeUndefined()
    }
  })
})

describe('approval', () => {
  async function toAnswered(): Promise<OwnerCeremony> {
    const waiting = await toWaiting()
    h.reply = { state: 'complete', isk: new Uint8Array(64), offer: OFFER }
    return checkForReply(h.ports, waiting)
  }

  it('pins before it forwards the ticket, and forwards the ticket the offer carried', async () => {
    const answered = await toAnswered()
    const approved = await approve(h.ports, answered)
    expect(approved.phase).toBe('approved')
    const pinAt = h.calls.indexOf('pin')
    const approveAt = h.calls.findIndex((c) => c.startsWith('approve:'))
    expect(pinAt).toBeGreaterThanOrEqual(0)
    expect(approveAt).toBeGreaterThan(pinAt)
    expect(h.calls[approveAt]).toBe(`approve:${answered.phase === 'answered' ? answered.run.exchangeId : ''}:${OFFER.enrolTicketB64}`)
    expect(h.storage.data.get(OWNER_RUN_STORAGE_KEY)).toBeUndefined()
  })

  it('unreadable keys forward nothing either', async () => {
    const answered = await toAnswered()
    h.pin = 'unreadable'
    await expect(approve(h.ports, answered)).rejects.toThrow(/could not be read/)
    expect(h.calls.some((c) => c.startsWith('approve:'))).toBe(false)
    // Control: the same run approves when the keys read, so the refusal above is about the keys.
    h.pin = 'pinned-now'
    expect((await approve(h.ports, answered)).phase).toBe('approved')
  })

  it('refuses to approve anything that is not an answered run', async () => {
    const waiting = await toWaiting()
    await expect(approve(h.ports, waiting)).rejects.toThrow(/no answered pairing run/)
    h.reply = { state: 'complete', isk: new Uint8Array(64), offer: null }
    const mismatch = await checkForReply(h.ports, waiting)
    await expect(approve(h.ports, mismatch)).rejects.toThrow(/no answered pairing run/)
  })

  it('an approval can be taken back, which returns to the invitation with its counts refreshed', async () => {
    const approved = await approve(h.ports, await toAnswered())
    h.invites = [{ inviteId: INVITE.inviteId, status: 'PENDING', createdAt: 1, expiresAt: 9_000_000, failCount: 2, exchangeCount: 3 }]
    const back = await abandonApproval(h.ports, approved)
    expect(back.phase).toBe('invited')
    if (back.phase !== 'invited') return
    expect(back.attemptsLeft).toBe(MAX_EXCHANGES_PER_INVITE - 3)
    expect(back.failCount).toBe(2)
    expect(h.calls.filter((c) => c.startsWith('cancel:'))).toHaveLength(1)
  })
})

/*
 * ISSUE #111. A matching code on a FRESH invitation replaces the keys this console holds. The pin
 * was recorded on exactly one proof — the code — so demanding a stronger one to replace it than to
 * create it is incoherent, and buys nothing: a code-holder can already pair fresh and be sent
 * shares. What the module owes in exchange is that nobody reaches the button without the screen
 * having said what it does and does not reach, and that the ONE genuine ambiguity still refuses.
 */
describe('replacing keys this console already holds', () => {
  async function answeredWith(check: OfferCheck): Promise<OwnerCeremony> {
    const waiting = await toWaiting()
    h.check = check
    h.reply = { state: 'complete', isk: new Uint8Array(64), offer: OFFER }
    return checkForReply(h.ports, waiting)
  }

  it('reads the offer against the record before anything is written or forwarded', async () => {
    const answered = await answeredWith({ kind: 'supersedes' })
    expect(answered.phase).toBe('answered')
    if (answered.phase !== 'answered') return
    // The screen can say what approving would do, because the answer is already in the state…
    expect(answered.keys.kind).toBe('supersedes')
    // …and getting it cost no write and no forward. This is the ordering the whole screen rests on.
    expect(h.calls).toContain('inspect')
    expect(h.calls).not.toContain('pin')
    expect(h.calls.some((c) => c.startsWith('approve:'))).toBe(false)
    // Control: 'pin' and 'approve:' DO appear once an approval happens, so the absences can fail.
    await approve(h.ports, answered)
    expect(h.calls).toContain('pin')
    expect(h.calls.some((c) => c.startsWith('approve:'))).toBe(true)
  })

  it('approves, records the new keys, and says a replacement happened', async () => {
    const answered = await answeredWith({ kind: 'supersedes' })
    h.pin = 'superseded'
    const approved = await approve(h.ports, answered)
    expect(approved.phase).toBe('approved')
    if (approved.phase !== 'approved') return
    expect(approved.pin).toBe('superseded')
    expect(approved.replaced).toBe(true)
    // Still pin-then-forward, unchanged: the local, reversible act precedes the one that is neither.
    expect(h.calls.indexOf('pin')).toBeLessThan(h.calls.findIndex((c) => c.startsWith('approve:')))
  })

  it('a clinician who re-keyed completely is a replacement to the owner, whatever the record calls it', async () => {
    // Both keys new, so the pin record has never seen this identity and reports 'pinned-now'. To
    // the OWNER it is still the same person's keys changing, and the screen must say so — which is
    // why `replaced` comes from the check made against the relationship, not from the write.
    const answered = await answeredWith({ kind: 'supersedes' })
    h.pin = 'pinned-now'
    const approved = await approve(h.ports, answered)
    expect(approved.phase === 'approved' && approved.replaced).toBe(true)
    // Control: an ordinary first pairing, same 'pinned-now' write, is NOT reported as a
    // replacement — so the flag is following the check and not the write.
    h = harness()
    const first = await answeredWith({ kind: 'first' })
    h.pin = 'pinned-now'
    const plain = await approve(h.ports, first)
    expect(plain.phase === 'approved' && plain.replaced).toBe(false)
  })

  it('keys already recorded for a DIFFERENT person are refused, and nothing is written or forwarded', async () => {
    const answered = await answeredWith({ kind: 'other-person', displayName: 'Dr Okafor' })
    await expect(approve(h.ports, answered)).rejects.toThrow(/has recorded for/)
    await expect(approve(h.ports, answered)).rejects.toThrow(/Nothing was approved and nothing was recorded/)
    // The refusal names both people and a consequence, and neither name is the one the offer chose.
    await expect(approve(h.ports, answered)).rejects.toThrow(/Dr Okafor/)
    await expect(approve(h.ports, answered)).rejects.toThrow(/Sam Reed/)
    expect(h.calls).not.toContain('pin')
    expect(h.calls.some((c) => c.startsWith('approve:'))).toBe(false)
    // And it is not a burn: the invitation was neither reported nor cancelled by the refusal.
    expect(h.calls.some((c) => c.startsWith('report:') || c.startsWith('cancel:'))).toBe(false)
  })

  it('the refusal is the copy module’s sentence, not one written at the throw site', async () => {
    const answered = await answeredWith({ kind: 'other-person', displayName: 'Dr Okafor' })
    await expect(approve(h.ports, answered)).rejects.toThrow(OWNER_COPY.sameKeysAsOther('Sam Reed', 'Dr Okafor'))
  })

  it('the name in the replacement sentences is the owner’s, never the one the offer carried', () => {
    // OFFER.displayName is typed by whoever answered. If it reached these sentences, the party
    // being checked would be choosing the words of the check.
    expect(OFFER.displayName).toBe('Dr Example')
    const words = [
      OWNER_COPY.replaceAtMint('Sam Reed'),
      OWNER_COPY.replaceTitle('Sam Reed'),
      ...OWNER_COPY.replaceBody('Sam Reed'),
      OWNER_COPY.sameKeysAsOther('Sam Reed', 'Dr Okafor'),
    ].join(' ')
    expect(words).toContain('Sam Reed')
    expect(words).not.toContain('Dr Example')
    // Control: the detector finds the offer's name when it is planted.
    expect(`${words} ${OFFER.displayName}`).toContain('Dr Example')
  })

  it('says what a replacement does not reach, in the same terms as every other take-back', () => {
    const body = OWNER_COPY.replaceBody('Sam Reed').join(' ')
    expect(body).toMatch(/does not reach what was already sealed/i)
    expect(body).toMatch(/can still open every share sent to Sam Reed before now/i)
    // Not a contradiction of the standing sentence about revoking; the same fact, at a new click.
    expect(body).not.toMatch(/un-send|undo|recall/i)
    expect(body).toMatch(/do not approve/i)
    expect(OWNER_COPY.replaceDeclineLabel).toBe('Not now')
  })
})

/*
 * ISSUE #112. A reply that did not open is one sentence on the owner's screen, and the device asks
 * the server for nothing differently afterwards.
 *
 * THE CADENCE IS THE SECURITY PROPERTY, not a performance detail. The server must not be able to
 * tell a wrong code from a right one, and traffic is a channel like any other: a client that starts
 * polling faster — or slower, or once more, or not at all — after an envelope fails to open has
 * told the server what happened inside the owner's browser. So the assertion is that the calls a
 * poll makes are IDENTICAL before and after, and that nothing in the module schedules anything.
 */
describe('a reply that did not open changes nothing about how the device behaves', () => {
  async function toMismatch(): Promise<OwnerCeremony> {
    const waiting = await toWaiting()
    h.reply = { state: 'complete', isk: new Uint8Array(64), offer: null }
    return checkForReply(h.ports, waiting)
  }

  it('polls exactly the same way after a failed open as before it', async () => {
    const waiting = await toWaiting()
    const runId = waiting.phase === 'waiting' ? waiting.run.exchangeId : ''

    // Three polls with nobody having answered.
    let state = waiting
    for (let i = 0; i < 3; i++) state = await checkForReply(h.ports, state)
    const before = h.calls.filter((c) => c.startsWith('read:'))
    const otherBefore = h.calls.filter((c) => !c.startsWith('read:'))

    // The reply lands and does not open.
    h.reply = { state: 'complete', isk: new Uint8Array(64), offer: null }
    const mismatch = await checkForReply(h.ports, state)
    expect(mismatch.phase).toBe('mismatch')

    // Three more polls from the mismatch state, with nothing further arriving.
    h.reply = { state: 'waiting' }
    h.calls.length = 0
    let after = mismatch
    for (let i = 0; i < 3; i++) after = await checkForReply(h.ports, after)

    // One read per poll, against the same run, and NOTHING else — no extra read, no re-open, no
    // cancel, no report. Byte for byte the same sequence of calls as the three polls before.
    expect(h.calls).toEqual([`read:${runId}`, `read:${runId}`, `read:${runId}`])
    expect(before).toEqual([`read:${runId}`, `read:${runId}`, `read:${runId}`])
    expect(otherBefore.filter((c) => c !== 'mint' && !c.startsWith('open:'))).toEqual([])
    // Control: the comparison can fail — an extra call would show up in exactly this list.
    await newCode(h.ports, after)
    expect(h.calls.length).toBeGreaterThan(3)
  })

  it('the mismatch state carries nothing a caller could read a new cadence out of', async () => {
    const mismatch = await toMismatch()
    expect(Object.keys(mismatch).sort()).toEqual(
      ['attemptsLeft', 'failCount', 'invite', 'phase', 'run'].sort(),
    )
    for (const key of Object.keys(mismatch)) {
      expect(key, 'a timing field leaked into the mismatch state').not.toMatch(
        /interval|backoff|delay|retry|after|next|deadline|poll/i,
      )
    }
    // Control: the pattern does fire on the names it is looking for.
    expect('retryAfter').toMatch(/interval|backoff|delay|retry|after|next|deadline|poll/i)
  })

  it('nothing in the module schedules anything, before a failure or after one', () => {
    const src = readFileSync(fileURLToPath(new URL('./ownerCeremony.ts', import.meta.url)), 'utf8')
    expect(src.length).toBeGreaterThan(2000)
    for (const scheduler of ['setTimeout', 'setInterval', 'requestAnimationFrame', 'queueMicrotask', 'requestIdleCallback']) {
      expect(src.includes(scheduler), `the module schedules with ${scheduler}`).toBe(false)
    }
    // Control: the detector sees a planted one.
    expect('setTimeout(check, 1000)'.includes('setTimeout')).toBe(true)
  })

  it('keeping the invitation asks the server for nothing and ends nothing', async () => {
    const mismatch = await toMismatch()
    h.calls.length = 0
    const kept = keepInvitation(mismatch)
    expect(kept.phase).toBe('invited')
    if (kept.phase !== 'invited') return
    // The count is exactly what it was: a reply that did not open spends no try.
    expect(kept.attemptsLeft).toBe(mismatch.phase === 'mismatch' ? mismatch.attemptsLeft : -1)
    expect(kept.failCount).toBe(mismatch.phase === 'mismatch' ? mismatch.failCount : -1)
    // And not one request was made — no cancel, no report, no new run, no read.
    expect(h.calls).toEqual([])
    // Control: the recorder is working; the other option on this screen does make calls.
    await stopInvitation(h.ports, mismatch)
    expect(h.calls).toEqual([`report:${INVITE.inviteId}`])
  })

  it('says what did not happen and asks a question, naming the person', () => {
    expect(OWNER_COPY.mismatchTitle).toBe('A reply did not open with your code')
    expect(OWNER_COPY.mismatchBody('Sam Reed')).toBe(
      'Keep this invitation open and ask Sam Reed whether they answered, or stop it and send a new link?',
    )
    // A question, never a verdict, and never an accusation.
    expect(OWNER_COPY.mismatchBody('Sam Reed')).toContain('?')
    expect(`${OWNER_COPY.mismatchTitle} ${OWNER_COPY.mismatchBody('Sam Reed')}`).not.toMatch(
      /attack|intrud|breach|suspicious|threat|danger|wrong code|you typed/i,
    )
    expect('you typed it wrong').toMatch(/attack|intrud|breach|suspicious|threat|danger|wrong code|you typed/i)
  })
})

describe('a new code is a human action and costs an attempt', () => {
  it('cancels the old run, opens exactly one new one, and counts down', async () => {
    const waiting = await toWaiting()
    h.reply = { state: 'complete', isk: new Uint8Array(64), offer: null }
    const mismatch = await checkForReply(h.ports, waiting)
    const again = await newCode(h.ports, mismatch)
    expect(again.phase).toBe('waiting')
    if (again.phase !== 'waiting') return
    expect(again.attemptsLeft).toBe(MAX_EXCHANGES_PER_INVITE - 2)
    expect(h.codes).toHaveLength(2)
    expect(h.codes[0]).not.toBe(h.codes[1])
    expect(h.calls.filter((c) => c.startsWith('cancel:'))).toHaveLength(1)
    expect(h.calls.filter((c) => c.startsWith('open:'))).toHaveLength(2)
  })
})

describe('stopping an invitation', () => {
  it('reports it, forgets the run, and is the only thing here that kills one', async () => {
    const waiting = await toWaiting()
    const ended = await stopInvitation(h.ports, waiting)
    expect(ended.phase).toBe('ended')
    expect(ended.phase === 'ended' && ended.reason).toBe('reported')
    expect(h.calls).toContain(`report:${INVITE.inviteId}`)
    expect(h.storage.data.get(OWNER_RUN_STORAGE_KEY)).toBeUndefined()
    // Nothing else in this module reports.
    h = harness()
    const w = await toWaiting()
    h.reply = { state: 'complete', isk: new Uint8Array(64), offer: null }
    await checkForReply(h.ports, w)
    await newCode(h.ports, await checkForReply(h.ports, w))
    expect(h.calls.some((c) => c.startsWith('report:'))).toBe(false)
  })
})

describe('coming back to the console', () => {
  it('with no live invitation, it is idle and the stale run is forgotten', async () => {
    await toWaiting()
    h.invites = [{ inviteId: INVITE.inviteId, status: 'CONSUMED', createdAt: 1, expiresAt: 9_000_000, failCount: 0, exchangeCount: 1 }]
    const state = await restore(h.ports)
    expect(state.phase).toBe('idle')
    expect(h.storage.data.get(OWNER_RUN_STORAGE_KEY)).toBeUndefined()
  })

  it('a run this tab still holds resumes, finishable but with no code to show', async () => {
    const waiting = await toWaiting()
    if (waiting.phase !== 'waiting') return
    h.invites = [
      {
        inviteId: INVITE.inviteId,
        status: 'PENDING',
        createdAt: 1,
        expiresAt: 9_000_000,
        failCount: 1,
        exchangeCount: 1,
        latestExchange: { exchangeId: waiting.run.exchangeId, state: 'OPEN' },
      },
    ]
    const state = await restore(h.ports)
    expect(state.phase).toBe('resumed')
    if (state.phase !== 'resumed') return
    expect(state.run.exchangeId).toBe(waiting.run.exchangeId)
    expect(state.failCount).toBe(1)
    expect(Object.keys(state)).not.toContain('code')
    // And it can still be collected: the scalar came back, which is all cpaceFinish needs.
    h.reply = { state: 'complete', isk: new Uint8Array(64), offer: OFFER }
    expect((await checkForReply(h.ports, state)).phase).toBe('answered')
  })

  it('a stored run that is not the invitation’s newest is dropped rather than shown', async () => {
    await toWaiting()
    h.invites = [
      {
        inviteId: INVITE.inviteId,
        status: 'PENDING',
        createdAt: 1,
        expiresAt: 9_000_000,
        failCount: 0,
        exchangeCount: 2,
        latestExchange: { exchangeId: 'someone-elses-run', state: 'OPEN' },
      },
    ]
    const state = await restore(h.ports)
    expect(state.phase).toBe('invited')
    expect(h.storage.data.get(OWNER_RUN_STORAGE_KEY)).toBeUndefined()
  })

  it('an invitation mid-redeem with no run here reads as needing a decision, not as waiting', async () => {
    h.invites = [
      {
        inviteId: INVITE.inviteId,
        status: 'REDEEMING',
        createdAt: 1,
        expiresAt: 9_000_000,
        failCount: 0,
        exchangeCount: 1,
        latestExchange: { exchangeId: 'ex-approved', state: 'CLOSED' },
      },
    ]
    const state = await restore(h.ports)
    expect(state.phase).toBe('ended')
  })
})
