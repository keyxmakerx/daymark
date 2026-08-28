/*
 * The whole ceremony, both sides, through a server that behaves like the real one.
 *
 * The fake below implements the relay's state machine exactly as PairingRelayRoutesTest pins it
 * on the real routes — four touches, one reply per exchange, closing publishes, collect gives
 * back only what a closed exchange holds. What is being tested here is the layer above that:
 * that two people with the SAME code end up holding each other's real public keys, and that two
 * people with DIFFERENT codes end up holding nothing and being told to try again.
 *
 * The hostile cases are the point of the file. A relay that swaps a sealed half, or splices two
 * exchanges together, or hands back its own envelope, must produce a refusal rather than a key
 * anybody trusts — and it must do so WITHOUT anyone reading a fingerprint aloud, because
 * replacing that ceremony is the entire justification for this code existing.
 */
import { beforeAll, describe, expect, it } from 'vitest'
import {
  decodeHalf,
  encodeHalf,
  ownerBeginPairing,
  ownerCompletePairing,
  therapistCollect,
  therapistRunPairing,
  type NegotiationHalf,
} from './negotiation'
import { initEnvelope } from './envelope'

const CODE = 'K7M2Q9'
const WRONG_CODE = 'K7M2Q8'
const REL_REF = 'rel-ref-abc123'
const INVITE_ID = 'invite-xyz789'
const INVITE_SECRET = 'invite-secret-value-42'
const BEARER = 'owner-bearer-token'

const key = (seed: number): string => {
  const bytes = new Uint8Array(32).map((_, i) => (i * 31 + seed) & 0xff)
  let s = ''
  for (const b of bytes) s += String.fromCharCode(b)
  return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

const OWNER_HALF = { boxPubB64: key(1), signPubB64: key(2), scope: ['read.share'] }
const THERAPIST_HALF = { boxPubB64: key(3), signPubB64: key(4), scope: ['read.share'] }

/** The relay's state machine, and a seam for a hostile operator to reach into it. */
function relayServer() {
  const ex: {
    id: string
    sidB64?: string
    msgAB64?: string
    msgBB64?: string
    payloadA?: string
    payloadB?: string
    state: 'NONE' | 'OPEN' | 'RESPONDED' | 'CLOSED' | 'CANCELLED'
  } = { id: 'exchange-1', state: 'NONE' }

  const doFetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    const body = typeof init?.body === 'string' ? init.body : ''
    const method = init?.method ?? 'GET'
    const reply = (status: number, payload?: unknown) =>
      new Response(payload === undefined ? null : JSON.stringify(payload), { status })

    if (url === `/v1/relations/${REL_REF}/pairing` && method === 'POST') {
      const req = JSON.parse(body)
      if (req.inviteId !== INVITE_ID) return reply(404, { error: 'no open invite' })
      ex.sidB64 = req.sidB64
      ex.msgAB64 = req.msgAB64
      ex.state = 'OPEN'
      return reply(201, { exchangeId: ex.id })
    }
    if (url === `/v1/invite/${INVITE_ID}/pairing/fetch` && method === 'POST') {
      if (JSON.parse(body).secret !== INVITE_SECRET) return reply(401, { error: 'unauthorized' })
      if (ex.state !== 'OPEN') return reply(410, { error: 'invite unavailable' })
      return reply(200, { exchangeId: ex.id, relRef: REL_REF, sidB64: ex.sidB64, msgAB64: ex.msgAB64 })
    }
    if (url === `/v1/invite/${INVITE_ID}/pairing/${ex.id}/respond` && method === 'POST') {
      const req = JSON.parse(body)
      if (req.secret !== INVITE_SECRET) return reply(401, { error: 'unauthorized' })
      if (ex.state !== 'OPEN') return reply(410, { error: 'exchange unavailable' })
      ex.msgBB64 = req.msgBB64
      ex.payloadB = req.payloadB64
      ex.state = 'RESPONDED'
      return reply(204)
    }
    if (url === `/v1/relations/${REL_REF}/pairing/${ex.id}` && method === 'GET') {
      if (ex.state === 'NONE') return reply(404, { error: 'no such exchange' })
      return reply(200, { exchangeId: ex.id, state: ex.state, msgBB64: ex.msgBB64, payloadBB64: ex.payloadB })
    }
    if (url === `/v1/relations/${REL_REF}/pairing/${ex.id}/close` && method === 'POST') {
      if (ex.state !== 'RESPONDED') return reply(410, { error: 'exchange unavailable' })
      ex.payloadA = JSON.parse(body || '{}').payloadB64
      ex.state = 'CLOSED'
      return reply(204)
    }
    if (url === `/v1/invite/${INVITE_ID}/pairing/${ex.id}/collect` && method === 'POST') {
      if (JSON.parse(body).secret !== INVITE_SECRET) return reply(401, { error: 'unauthorized' })
      if (ex.state !== 'CLOSED' || !ex.payloadA) return reply(410, { error: 'exchange unavailable' })
      return reply(200, { payloadB64: ex.payloadA })
    }
    throw new Error(`unexpected request: ${method} ${url}`)
  }) as typeof fetch

  return { doFetch, ex }
}

/** Drive the full four touches with a code for each side. */
async function runCeremony(ownerCode: string, therapistCode: string, server = relayServer()) {
  const pairing = await ownerBeginPairing(
    { relRef: REL_REF, inviteId: INVITE_ID, code: ownerCode, bearerToken: BEARER },
    server.doFetch,
  )
  // The clinician answers and immediately tries to collect; the owner has not closed yet, so
  // this is the ordinary 'awaiting-owner' half of store-and-forward.
  const therapistFirst = await therapistRunPairing(
    { inviteId: INVITE_ID, secret: INVITE_SECRET, code: therapistCode, own: THERAPIST_HALF },
    server.doFetch,
  )
  const owner = await ownerCompletePairing(
    { relRef: REL_REF, code: ownerCode, bearerToken: BEARER, pairing, own: OWNER_HALF },
    server.doFetch,
  )
  return { server, pairing, therapistFirst, owner }
}

beforeAll(async () => {
  await initEnvelope()
})

describe('one code, spoken once, replaces reading fingerprints aloud', () => {
  it('carries each side real public keys when both typed the same code', async () => {
    const { owner, therapistFirst, server } = await runCeremony(CODE, CODE)

    expect(therapistFirst.state).toBe('awaiting-owner')
    expect(owner.state).toBe('complete')
    if (owner.state !== 'complete' || therapistFirst.state !== 'awaiting-owner') return
    // The owner holds the clinician's actual keys — authenticated by the envelope opening at
    // all, which required the code. No fingerprint was compared anywhere in this test.
    expect(owner.half.boxPubB64).toBe(THERAPIST_HALF.boxPubB64)
    expect(owner.half.signPubB64).toBe(THERAPIST_HALF.signPubB64)
    expect(owner.half.scope).toEqual(['read.share'])

    // The clinician comes back after the owner closed and collects, using the key their own
    // side derived — not a value this test supplied. That distinction is the whole point: an
    // assertion against a fabricated expectation would pass over a broken decrypt.
    const resumed = await therapistCollect(
      { inviteId: INVITE_ID, secret: INVITE_SECRET, resume: therapistFirst.resume },
      server.doFetch,
    )
    expect(resumed.state).toBe('complete')
    if (resumed.state !== 'complete') return
    expect(resumed.half.boxPubB64).toBe(OWNER_HALF.boxPubB64)
    expect(resumed.half.signPubB64).toBe(OWNER_HALF.signPubB64)
    expect(resumed.relRef).toBe(REL_REF)
  })

  it('the resume key is the only way back in, and it works after a simulated reload', async () => {
    // One reply per exchange, ever — so a clinician whose tab reloaded cannot re-run the
    // ceremony. This asserts the resume state alone is sufficient, which is what makes a
    // browser refresh mid-pairing survivable rather than a request for a fresh invitation.
    const { therapistFirst, server, owner } = await runCeremony(CODE, CODE)
    expect(owner.state).toBe('complete')
    if (therapistFirst.state !== 'awaiting-owner') return
    const carried = { ...therapistFirst.resume }
    const resumed = await therapistCollect(
      { inviteId: INVITE_ID, secret: INVITE_SECRET, resume: carried },
      server.doFetch,
    )
    expect(resumed.state).toBe('complete')
    // And a resume carrying the WRONG key gets the same refusal a wrong code would.
    const wrongKey = await therapistCollect(
      {
        inviteId: INVITE_ID,
        secret: INVITE_SECRET,
        resume: { ...carried, isk: new Uint8Array(64).fill(7) },
      },
      server.doFetch,
    )
    expect(wrongKey.state).toBe('code-mismatch')
  })

  it('waiting is a state, not a failure — the owner can look before the clinician has answered', async () => {
    const server = relayServer()
    const pairing = await ownerBeginPairing(
      { relRef: REL_REF, inviteId: INVITE_ID, code: CODE, bearerToken: BEARER },
      server.doFetch,
    )
    const early = await ownerCompletePairing(
      { relRef: REL_REF, code: CODE, bearerToken: BEARER, pairing, own: OWNER_HALF },
      server.doFetch,
    )
    expect(early.state).toBe('waiting')
    // Looking again later, after they answer, completes normally — nothing was consumed.
    await therapistRunPairing(
      { inviteId: INVITE_ID, secret: INVITE_SECRET, code: CODE, own: THERAPIST_HALF },
      server.doFetch,
    )
    const later = await ownerCompletePairing(
      { relRef: REL_REF, code: CODE, bearerToken: BEARER, pairing, own: OWNER_HALF },
      server.doFetch,
    )
    expect(later.state).toBe('complete')
  })

  it('a cancelled exchange reads as cancelled, not as a mismatch', async () => {
    const server = relayServer()
    const pairing = await ownerBeginPairing(
      { relRef: REL_REF, inviteId: INVITE_ID, code: CODE, bearerToken: BEARER },
      server.doFetch,
    )
    server.ex.state = 'CANCELLED'
    const outcome = await ownerCompletePairing(
      { relRef: REL_REF, code: CODE, bearerToken: BEARER, pairing, own: OWNER_HALF },
      server.doFetch,
    )
    expect(outcome.state).toBe('cancelled')
  })
})

describe('a wrong code, and the things a hostile relay might try', () => {
  it('one mistyped character yields no keys and a try-again — never a verdict', async () => {
    const { owner } = await runCeremony(CODE, WRONG_CODE)
    expect(owner.state).toBe('code-mismatch')
    // The distinction the burn rule rests on: this outcome cannot tell a typo from an attacker,
    // so it must not be spelled as one. It carries no keys and no accusation.
    expect(JSON.stringify(owner)).not.toMatch(/attack|intrud|hostile|malicious/i)
  })

  it('a relay that substitutes the clinician sealed half is refused', async () => {
    const server = relayServer()
    const pairing = await ownerBeginPairing(
      { relRef: REL_REF, inviteId: INVITE_ID, code: CODE, bearerToken: BEARER },
      server.doFetch,
    )
    await therapistRunPairing(
      { inviteId: INVITE_ID, secret: INVITE_SECRET, code: CODE, own: THERAPIST_HALF },
      server.doFetch,
    )
    // The operator swaps in an envelope of their own making. They do not have the code, so the
    // best they can do is garbage — and garbage is exactly what an attacker in this position
    // is limited to, which is the property worth pinning.
    server.ex.payloadB = btoa('\x01' + 'x'.repeat(60)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
    const outcome = await ownerCompletePairing(
      { relRef: REL_REF, code: CODE, bearerToken: BEARER, pairing, own: OWNER_HALF },
      server.doFetch,
    )
    expect(outcome.state).toBe('code-mismatch')
  })

  it('a half that opens but is malformed is refused rather than half-used', async () => {
    // Not an attack — a peer speaking a shape we do not know, since forging an opening
    // envelope needs the code. Refused all the same: a partial identity is not an identity.
    expect(decodeHalf(new TextEncoder().encode('not json'))).toBeNull()
    expect(decodeHalf(encodeHalf({ ...THERAPIST_HALF, boxPubB64: 'too-short' }))).toBeNull()
    expect(decodeHalf(new TextEncoder().encode(JSON.stringify({ v: 99, ...THERAPIST_HALF })))).toBeNull()
    const scopeFlood = { ...THERAPIST_HALF, scope: Array(40).fill('read.share') }
    expect(decodeHalf(encodeHalf(scopeFlood))).toBeNull()
    // Non-vacuity: the well-formed one this file uses everywhere does decode.
    const good = decodeHalf(encodeHalf(THERAPIST_HALF)) as NegotiationHalf
    expect(good.boxPubB64).toBe(THERAPIST_HALF.boxPubB64)
  })

  it('the two directions do not open each other, so a reflected half is refused', async () => {
    const server = relayServer()
    const pairing = await ownerBeginPairing(
      { relRef: REL_REF, inviteId: INVITE_ID, code: CODE, bearerToken: BEARER },
      server.doFetch,
    )
    await therapistRunPairing(
      { inviteId: INVITE_ID, secret: INVITE_SECRET, code: CODE, own: THERAPIST_HALF },
      server.doFetch,
    )
    const first = await ownerCompletePairing(
      { relRef: REL_REF, code: CODE, bearerToken: BEARER, pairing, own: OWNER_HALF },
      server.doFetch,
    )
    expect(first.state).toBe('complete')
    // Now the operator replays the OWNER's own sealed half back at the owner, as if it were the
    // clinician's. Same key, same exchange, wrong direction — and the direction is in the AEAD.
    server.ex.payloadB = server.ex.payloadA
    server.ex.state = 'RESPONDED'
    const reflected = await ownerCompletePairing(
      { relRef: REL_REF, code: CODE, bearerToken: BEARER, pairing, own: OWNER_HALF },
      server.doFetch,
    )
    expect(reflected.state).toBe('code-mismatch')
  })
})
