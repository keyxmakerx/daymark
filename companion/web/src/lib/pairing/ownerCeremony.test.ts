import { beforeEach, describe, expect, it } from 'vitest'
import {
  MAX_EXCHANGES_PER_INVITE,
  abandonApproval,
  approve,
  checkForReply,
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
  pin: 'pinned-now' | 'already-pinned' | 'differs-from-pin' | 'unreadable'
  approveThrows: boolean
  /** What the E2 sealing port does: a sealed blob, or a throw standing in for a run with no key. */
  sealFails: boolean
}

/** Stands in for the owner's sealed keys. The real bytes are proved in relay.test.ts. */
const SEALED_OWNER_KEYS = 'AQ-sealed-owner-keys'

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
    pin: 'pinned-now',
    approveThrows: false,
    sealFails: false,
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
      async approveRun(exchangeId, ticket, envB64) {
        calls.push(`approve:${exchangeId}:${ticket}:${envB64}`)
        if (h.approveThrows) throw new Error('server said no')
      },
      async cancelRun(exchangeId) {
        calls.push(`cancel:${exchangeId}`)
        return true
      },
      async reportInvite(inviteId) {
        calls.push(`report:${inviteId}`)
      },
      pinOffer() {
        calls.push('pin')
        return h.pin
      },
      sealOwnerKeys(run) {
        calls.push(`seal:${run.exchangeId}`)
        if (h.sealFails) throw new Error('this run has no key yet')
        return SEALED_OWNER_KEYS
      },
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

  it('pins, then seals its own keys, then forwards both — in that order', async () => {
    const answered = await toAnswered()
    const approved = await approve(h.ports, answered)
    expect(approved.phase).toBe('approved')
    const pinAt = h.calls.indexOf('pin')
    const sealAt = h.calls.findIndex((c) => c.startsWith('seal:'))
    const approveAt = h.calls.findIndex((c) => c.startsWith('approve:'))
    expect(pinAt).toBeGreaterThanOrEqual(0)
    expect(sealAt).toBeGreaterThan(pinAt)
    expect(approveAt).toBeGreaterThan(sealAt)
    const exchangeId = answered.phase === 'answered' ? answered.run.exchangeId : ''
    // The ticket the offer carried AND the owner's sealed keys travel in the one request.
    expect(h.calls[approveAt]).toBe(`approve:${exchangeId}:${OFFER.enrolTicketB64}:${SEALED_OWNER_KEYS}`)
    // Sealed for THIS run, not some other one the tab still remembers.
    expect(h.calls[sealAt]).toBe(`seal:${exchangeId}`)
    expect(h.storage.data.get(OWNER_RUN_STORAGE_KEY)).toBeUndefined()
  })

  it('keys that cannot be sealed forward nothing, so nobody enrols unable to verify the owner', async () => {
    const answered = await toAnswered()
    h.sealFails = true
    await expect(approve(h.ports, answered)).rejects.toThrow(/could not be sealed/)
    expect(h.calls.some((c) => c.startsWith('approve:'))).toBe(false)
    // The run is still there to try again with; nothing was spent.
    expect(h.storage.data.get(OWNER_RUN_STORAGE_KEY)).toBeDefined()
  })

  it('nothing is sealed when the pin refuses, so a substitution never reaches the other side', async () => {
    const answered = await toAnswered()
    h.pin = 'differs-from-pin'
    await expect(approve(h.ports, answered)).rejects.toThrow(/already has different keys/)
    expect(h.calls.some((c) => c.startsWith('seal:'))).toBe(false)
    // Control: the same detector sees the seal on the path that does reach it.
    const ok = harness()
    const okAnswered = await (async () => {
      const waiting = await openRun(ok.ports, await startInvitation(ok.ports))
      ok.reply = { state: 'complete', isk: new Uint8Array(64), offer: OFFER }
      return checkForReply(ok.ports, waiting)
    })()
    await approve(ok.ports, okAnswered)
    expect(ok.calls.some((c) => c.startsWith('seal:'))).toBe(true)
  })

  it('a key that differs from what this console already recorded forwards nothing', async () => {
    const answered = await toAnswered()
    h.pin = 'differs-from-pin'
    await expect(approve(h.ports, answered)).rejects.toThrow(/already has different keys/)
    expect(h.calls.some((c) => c.startsWith('approve:'))).toBe(false)
  })

  it('unreadable keys forward nothing either', async () => {
    const answered = await toAnswered()
    h.pin = 'unreadable'
    await expect(approve(h.ports, answered)).rejects.toThrow(/could not be read/)
    expect(h.calls.some((c) => c.startsWith('approve:'))).toBe(false)
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
