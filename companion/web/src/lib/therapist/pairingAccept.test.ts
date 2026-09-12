import { beforeEach, describe, expect, it } from 'vitest'
import {
  THERAPIST_RUN_STORAGE_KEY,
  answerPairing,
  enrolAfterApproval,
  forgetTherapistRun,
  loadTherapistRun,
  saveTherapistRun,
  waitForApproval,
  type PairingAcceptancePorts,
  type TherapistRunRecord,
  type TherapistRunStorage,
} from './pairingAccept'
import { AcceptError, findKeyRecord, saveKeyRecord, type AcceptancePorts, type KeyRecord } from './inviteAccept'
import { newPairingCode, type PairingCode } from '../pairing/pairingCode'
import { initAssignmentCrypto } from '../assignments/crypto'
import type { TherapistOffer } from '../pairing/payloads'
import type { TherapistStatusResult } from '../pairing/relay'

/*
 * The ORDER is the property. Every port records its call, so a test can assert that no keys are
 * generated before the relationship is known, that nothing is enrolled before the owner approved,
 * and that a failed run leaves this browser exactly as it found it.
 */

const REL_REF = 'rel-ref-abc'
let code: PairingCode

function memory(): TherapistRunStorage & { data: Map<string, string> } {
  const data = new Map<string, string>()
  return {
    data,
    getItem: (k) => data.get(k) ?? null,
    setItem: (k, v) => void data.set(k, v),
    removeItem: (k) => void data.delete(k),
  }
}

interface Harness {
  ports: PairingAcceptancePorts
  calls: string[]
  keyStore: ReturnType<typeof memory>
  runStore: ReturnType<typeof memory>
  offers: TherapistOffer[]
  answerFails: Error | null
  wrapFails: boolean
  reopensDifferently: boolean
  statuses: TherapistStatusResult[]
  enrolOutcome: 'enrolled' | 'refused' | 'unknown'
}

function harness(): Harness {
  const calls: string[] = []
  const keyStore = memory()
  const runStore = memory()
  const offers: TherapistOffer[] = []
  let n = 0
  const key = (fill: number) => ({ publicKey: new Uint8Array(32).fill(fill), privateKey: new Uint8Array(32).fill(fill + 100) })
  const h: Harness = {
    calls,
    keyStore,
    runStore,
    offers,
    answerFails: null,
    wrapFails: false,
    reopensDifferently: false,
    statuses: [],
    enrolOutcome: 'enrolled',
    ports: {
      async answer(args) {
        calls.push('fetch')
        const offer = await args.makeOffer(REL_REF)
        offers.push(offer)
        calls.push('respond')
        if (h.answerFails) throw h.answerFails
        return { exchangeId: 'ex-1', relRef: REL_REF }
      },
      async status() {
        calls.push('status')
        return h.statuses.shift() ?? { state: 'waiting' }
      },
      accept: {
        redeem: async () => {
          calls.push('redeem')
          throw new Error('redeem must never be called by the pairing ceremony')
        },
        enrol: async (ticket: string, credentialId: string) => {
          calls.push(`enrol:${ticket}:${credentialId}`)
          return h.enrolOutcome
        },
        login: async () => {
          calls.push('login')
          return { ok: true, session: { relRef: '', csrf: 'c', absoluteExpiry: 0, idleExpiry: 0 } } as never
        },
        register: async () => {
          calls.push('register')
          return 'registered' as never
        },
        logout: async () => void calls.push('logout'),
        newKeys: () => {
          calls.push('newKeys')
          return { box: key(1), sign: key(2) } as never
        },
        wrapKeys: async () => {
          calls.push('wrap')
          if (h.wrapFails) throw new Error('no')
          return { v: 1, salt: 'x', nonce: 'y', ct: 'z' } as never
        },
        unwrapKeys: async () => {
          calls.push('unwrap')
          return { box: key(h.reopensDifferently ? 9 : 1), sign: key(h.reopensDifferently ? 8 : 2) } as never
        },
        randomToken: () => ({ raw: new Uint8Array(16).fill(++n), b64url: `tok-${n}` }),
        toBase64: (b: Uint8Array) => Buffer.from(b).toString('base64url'),
        storage: keyStore,
        now: () => 1_700_000_000_000,
      } as unknown as AcceptancePorts,
      runStorage: runStore,
      async wait() {
        calls.push('wait')
      },
    },
  }
  return h
}

let h: Harness
beforeEach(async () => {
  // enrolWithTicket fingerprints the public keys, which needs sodium; the rest of the ceremony
  // does not, which is why only these two tests used to notice.
  await initAssignmentCrypto()
  h = harness()
  code ??= await newPairingCode()
})

const input = () => ({
  inviteId: 'inv-1',
  secret: 'invite-secret',
  typedCode: code.display,
  passphrase: 'a long reading passphrase',
  displayName: 'Dr Example',
})

describe('answering a run', () => {
  it('learns the relationship before it makes any keys, and seals them with a ticket it chose', async () => {
    const run = await h.ports.answer ? await answerPairing(h.ports, input()) : null
    expect(run).not.toBeNull()
    // Keys are made inside makeOffer, which runs after the fetch and before the respond.
    expect(h.calls.slice(0, 3)).toEqual(['fetch', 'newKeys', 'wrap'])
    expect(h.calls).toContain('respond')
    expect(h.calls.indexOf('newKeys')).toBeGreaterThan(h.calls.indexOf('fetch'))
    expect(h.calls.indexOf('respond')).toBeGreaterThan(h.calls.indexOf('newKeys'))
    // Nothing enrolled, nothing signed in: the owner has not decided yet.
    expect(h.calls.some((c) => c.startsWith('enrol:') || c === 'login' || c === 'register')).toBe(false)
    // The invite secret is never redeemed. That route is gone; calling it would throw.
    expect(h.calls).not.toContain('redeem')

    const offer = h.offers[0]
    expect(offer.displayName).toBe('Dr Example')
    expect(Buffer.from(offer.enrolTicketB64, 'base64url')).toHaveLength(32)
    expect(offer.boxPubB64).toBe(Buffer.from(new Uint8Array(32).fill(1)).toString('base64url'))
  })

  it('keeps the run and the record, and the run holds no code and no passphrase', async () => {
    const run = await answerPairing(h.ports, input())
    expect(findKeyRecord(REL_REF, h.keyStore)).not.toBeNull()
    const stored = h.runStore.data.get(THERAPIST_RUN_STORAGE_KEY)!
    expect(stored).toContain(run.record.enrolTicketB64)
    for (const secret of [code.canonical, code.display, 'a long reading passphrase']) {
      expect(stored.includes(secret), `run record leaked: ${secret}`).toBe(false)
    }
    expect((stored + code.canonical).includes(code.canonical)).toBe(true)
  })

  it('a mistyped code is refused on this device, before anything is sent', async () => {
    /*
     * The wrong check symbol is derived from the right one, never fixed. This line used to append a
     * literal 'Z' to the seven payload symbols — which IS the correct code one time in thirty-one,
     * because 'Z' is one of the thirty-one symbols the check character can be. The test then
     * asserted that a valid code is refused, and failed for no reason anyone could reproduce.
     * Measured elsewhere in this repository at 97 in 3000, which is 1/31 to two decimal places.
     */
    const wrongCheck = code.canonical[7] === 'Z' ? 'Y' : 'Z'
    await expect(
      answerPairing(h.ports, { ...input(), typedCode: code.canonical.slice(0, 7) + wrongCheck }),
    ).rejects.toThrow(/does not match the rest of the code/)
    expect(h.calls).toHaveLength(0)
    await expect(answerPairing(h.ports, { ...input(), typedCode: 'nope' })).rejects.toThrow()
    expect(h.calls).toHaveLength(0)
  })

  it('a name that is empty or not plain text is refused before anything is sent', async () => {
    for (const displayName of ['', '   ', 'Dr‮Example', 'x'.repeat(65)]) {
      await expect(answerPairing(h.ports, { ...input(), displayName })).rejects.toThrow(AcceptError)
    }
    expect(h.calls).toHaveLength(0)
  })

  it('refuses a relationship this browser already holds keys for, without making a second set', async () => {
    const existing: KeyRecord = { v: 1, relRef: REL_REF, credentialId: 'old', wrapped: {} as never, createdAt: 1 }
    saveKeyRecord(existing, h.keyStore)
    await expect(answerPairing(h.ports, input())).rejects.toThrow(/already holds keys/)
    expect(h.calls).toEqual(['fetch'])
    expect(findKeyRecord(REL_REF, h.keyStore)?.credentialId).toBe('old')
  })

  it('a run that cannot be answered leaves no record behind, so a new code can be tried', async () => {
    h.answerFails = new Error('pairing respond refused (410)')
    await expect(answerPairing(h.ports, input())).rejects.toThrow(/no longer open/)
    expect(findKeyRecord(REL_REF, h.keyStore)).toBeNull()
    expect(loadTherapistRun(h.runStore)).toBeNull()
    // And the second attempt is not blocked by the first.
    h.answerFails = null
    await expect(answerPairing(h.ports, input())).resolves.toBeTruthy()
  })

  it('a wrap that will not reopen sends nothing and keeps nothing', async () => {
    h.reopensDifferently = true
    await expect(answerPairing(h.ports, input())).rejects.toThrow(/opened to something other than/)
    expect(h.calls).not.toContain('respond')
    expect(findKeyRecord(REL_REF, h.keyStore)).toBeNull()
  })
})

describe('waiting for the owner', () => {
  it('polls until the answer changes, and never enrols while waiting', async () => {
    const run = await answerPairing(h.ports, input())
    h.statuses = [{ state: 'waiting' }, { state: 'waiting' }, { state: 'approved', scope: ['read.share'] }]
    const ticks: number[] = []
    const result = await waitForApproval(h.ports, run.record, 'invite-secret', { onTick: (n) => ticks.push(n) })
    expect(result).toEqual({ state: 'approved', scope: ['read.share'] })
    expect(ticks).toEqual([1, 2])
    expect(h.calls.filter((c) => c === 'status')).toHaveLength(3)
    expect(h.calls.some((c) => c.startsWith('enrol:'))).toBe(false)
  })

  it('a run that has gone is reported as such and the stored run is dropped', async () => {
    const run = await answerPairing(h.ports, input())
    h.statuses = [{ state: 'gone' }]
    expect(await waitForApproval(h.ports, run.record, 'invite-secret')).toEqual({ state: 'gone' })
    expect(loadTherapistRun(h.runStore)).toBeNull()
  })

  it('stops when the person leaves the page', async () => {
    const run = await answerPairing(h.ports, input())
    const signal = { aborted: true }
    expect(await waitForApproval(h.ports, run.record, 'invite-secret', { signal })).toEqual({ state: 'waiting' })
    expect(h.calls.filter((c) => c === 'status')).toHaveLength(0)
  })
})

describe('after the owner approves', () => {
  it('enrols with the ticket from the offer and then forgets the run', async () => {
    const run = await answerPairing(h.ports, input())
    const enrolment = await enrolAfterApproval(h.ports, run, ['read.share'])
    expect(h.calls).toContain(`enrol:${run.record.enrolTicketB64}:${run.record.credentialId}`)
    expect(enrolment.relRef).toBe(REL_REF)
    expect(enrolment.scope).toEqual(['read.share'])
    expect(enrolment.serverConfirmedEnrolment).toBe(true)
    expect(loadTherapistRun(h.runStore)).toBeNull()
    // The key record survives: it is the only copy of the keys.
    expect(findKeyRecord(REL_REF, h.keyStore)).not.toBeNull()
  })

  it('a refused enrolment rolls the record back; an unanswered one keeps it', async () => {
    h.enrolOutcome = 'refused'
    let run = await answerPairing(h.ports, input())
    await expect(enrolAfterApproval(h.ports, run, [])).rejects.toThrow(/would not enrol/)
    expect(findKeyRecord(REL_REF, h.keyStore)).toBeNull()

    h = harness()
    h.enrolOutcome = 'unknown'
    run = await answerPairing(h.ports, input())
    const enrolment = await enrolAfterApproval(h.ports, run, [])
    expect(enrolment.serverConfirmedEnrolment).toBe(false)
    expect(findKeyRecord(REL_REF, h.keyStore)).not.toBeNull()
  })
})

describe('the stored run', () => {
  it('round-trips and refuses anything that is not exactly a v1 record', () => {
    const store = memory()
    const good: TherapistRunRecord = {
      v: 1,
      inviteId: 'inv',
      exchangeId: 'ex',
      relRef: 'rel',
      enrolTicketB64: 'tick',
      credentialId: 'cred',
    }
    saveTherapistRun(store, good)
    expect(loadTherapistRun(store)).toEqual(good)
    for (const bad of ['nope', '[]', JSON.stringify({ ...good, v: 2 }), JSON.stringify({ ...good, relRef: '' })]) {
      const s = memory()
      s.data.set(THERAPIST_RUN_STORAGE_KEY, bad)
      expect(loadTherapistRun(s), bad).toBeNull()
    }
    forgetTherapistRun(store)
    expect(loadTherapistRun(store)).toBeNull()
    expect(loadTherapistRun(null)).toBeNull()
  })
})
