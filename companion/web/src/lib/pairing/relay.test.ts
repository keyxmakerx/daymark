/*
 * The §3.7.4 test: the pairing code must not appear in any request the server can observe.
 *
 * A full pairing — owner opens, therapist answers, owner collects — is driven through a
 * RECORDING transport that also plays the server's part (the same state machine
 * PairingRelayRoutesTest proves on the real routes). Afterwards, every recorded request —
 * URL, every header, every body — is searched for the code in every encoding it could
 * plausibly wear: verbatim, base64url, standard base64, hex, and URL-encoded. The invariant
 * is structural in relay.ts (the code only ever enters cpaceStart/cpaceRespond), but a
 * structural argument is a comment until something greps the wire; this is the grep.
 *
 * The non-vacuity guard plants the code into a copy of a request and asserts the detector
 * FINDS it — a corpus scan that cannot see a planted example proves only that it is blind.
 *
 * Two more properties live here since the 2026-09-01 audit, each with a test that was red on
 * the code before it: the run is bound to the invitation whose LINK the therapist holds (not
 * to what the server says), and one run derives one key however often the owner polls.
 */
import { beforeAll, describe, expect, it } from 'vitest'
import {
  ownerOpenPairing,
  ownerCollectPairing,
  ownerApprovePairing,
  ownerCancelPairing,
  therapistAnswerPairing,
  therapistPairingStatus,
  channelIdentifier,
  PairingPausedError,
  retryAfterSeconds,
  type OwnerPairingState,
} from './relay'
import { initCpace, parseLv } from './cpace'
import { newEnrolTicketB64, type TherapistOffer } from './payloads'

/**
 * The therapist's offer: two well-formed (if fake) public keys, a name, and a ticket they chose.
 * What the owner must get back, byte for byte, iff the codes matched.
 */
const OFFER: TherapistOffer = {
  boxPubB64: Buffer.from(new Uint8Array(32).fill(0x11)).toString('base64url'),
  signPubB64: Buffer.from(new Uint8Array(32).fill(0x22)).toString('base64url'),
  displayName: 'Dr Example',
  enrolTicketB64: newEnrolTicketB64(),
}
import { checkSymbol } from '../recovery/recoveryCode'
import {
  PAIRING_ALPHABET,
  PAIRING_PAYLOAD_SYMBOLS,
  newPairingCode,
  parsePairingCode,
  type CanonicalPairingCode,
} from './pairingCode'

/**
 * Both codes are real, canonical pairing codes drawn fresh per run (relay.ts refuses anything
 * else before the first request). WRONG_CODE differs from CODE in one payload symbol with the
 * check symbol recomputed, so it is a well-formed code that is simply not the same code — the
 * case the check symbol cannot catch and the PAKE is for.
 */
let CODE: CanonicalPairingCode
let WRONG_CODE: CanonicalPairingCode

function oneSymbolOff(code: CanonicalPairingCode): CanonicalPairingCode {
  const i = PAIRING_ALPHABET.indexOf(code[0])
  const other = PAIRING_ALPHABET[(i + 1) % PAIRING_ALPHABET.length]
  const payload = other + code.slice(1, PAIRING_PAYLOAD_SYMBOLS)
  const parsed = parsePairingCode(payload + checkSymbol(payload))
  if (!parsed.ok) throw new Error('test setup: could not build a second code')
  return parsed.code.canonical
}
const REL_REF = 'rel-ref-abc123'
const INVITE_ID = 'invite-xyz789'
const OTHER_INVITE_ID = 'invite-other456'
const INVITE_SECRET = 'invite-secret-value-42'
const BEARER = 'owner-bearer-token'

interface RecordedRequest {
  url: string
  method: string
  headers: Record<string, string>
  body: string
}

/**
 * The server's part of the relay, plus a wire recorder — one instance per test. `servesTo`
 * is the list of invite ids whose link-holder this server answers fetch/respond for; an honest
 * server serves one exchange to one invitation, and a splicing server is the same code with
 * two ids in the list.
 */
function relayServer(servesTo: string[] = [INVITE_ID]) {
  const recorded: RecordedRequest[] = []
  /** Every response body the server wrote, so a test can assert what NEVER comes back. */
  const responses: string[] = []
  const exchange: {
    id: string
    sidB64?: string
    msgAB64?: string
    msgBB64?: string
    envB64?: string
    approvedTicket?: string
    state: 'NONE' | 'OPEN' | 'RESPONDED' | 'CLOSED' | 'CANCELLED' | 'SUPERSEDED'
  } = { id: 'exchange-1', state: 'NONE' }

  const doFetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    const headers: Record<string, string> = {}
    for (const [k, v] of Object.entries((init?.headers ?? {}) as Record<string, string>)) {
      headers[k] = v
    }
    const body = typeof init?.body === 'string' ? init.body : ''
    recorded.push({ url, method: init?.method ?? 'GET', headers, body })

    const respond = (status: number, payload?: unknown) => {
      const text = payload === undefined ? '' : JSON.stringify(payload)
      responses.push(text)
      return new Response(text === '' ? null : text, { status })
    }

    if (url === `/v1/relations/${REL_REF}/pairing` && init?.method === 'POST') {
      const req = JSON.parse(body) as { inviteId: string; sidB64: string; msgAB64: string }
      if (req.inviteId !== INVITE_ID) return respond(404, { error: 'no open invite' })
      exchange.sidB64 = req.sidB64
      exchange.msgAB64 = req.msgAB64
      exchange.state = 'OPEN'
      return respond(201, { exchangeId: exchange.id })
    }
    const therapistInvite = servesTo.find((id) => url.startsWith(`/v1/invite/${id}/pairing/`))
    if (therapistInvite && url === `/v1/invite/${therapistInvite}/pairing/fetch` && init?.method === 'POST') {
      const req = JSON.parse(body) as { secret: string }
      if (req.secret !== INVITE_SECRET) return respond(401, { error: 'unauthorized' })
      if (exchange.state !== 'OPEN') return respond(410, { error: 'invite unavailable' })
      return respond(200, {
        exchangeId: exchange.id,
        relRef: REL_REF,
        sidB64: exchange.sidB64,
        msgAB64: exchange.msgAB64,
      })
    }
    if (
      therapistInvite &&
      url === `/v1/invite/${therapistInvite}/pairing/${exchange.id}/respond` &&
      init?.method === 'POST'
    ) {
      const req = JSON.parse(body) as { secret: string; msgBB64: string; envB64?: string }
      if (req.secret !== INVITE_SECRET) return respond(401, { error: 'unauthorized' })
      if (typeof req.envB64 !== 'string') return respond(400, { error: 'implausible envelope size' })
      if (exchange.state !== 'OPEN') return respond(410, { error: 'exchange unavailable' })
      exchange.msgBB64 = req.msgBB64
      exchange.envB64 = req.envB64
      exchange.state = 'RESPONDED'
      return respond(204)
    }
    if (
      therapistInvite &&
      url === `/v1/invite/${therapistInvite}/pairing/${exchange.id}/status` &&
      init?.method === 'POST'
    ) {
      const req = JSON.parse(body) as { secret: string }
      if (req.secret !== INVITE_SECRET) return respond(401, { error: 'unauthorized' })
      if (exchange.state === 'RESPONDED') return respond(200, { state: 'WAITING' })
      if (exchange.state === 'CLOSED') return respond(200, { state: 'APPROVED', scope: ['read.share'] })
      return respond(410, { error: 'exchange unavailable' })
    }
    if (url === `/v1/relations/${REL_REF}/pairing/${exchange.id}` && (init?.method ?? 'GET') === 'GET') {
      if (exchange.state === 'NONE') return respond(404, { error: 'no such exchange' })
      return respond(200, { exchangeId: exchange.id, state: exchange.state, msgBB64: exchange.msgBB64, envB64: exchange.envB64 })
    }
    if (url === `/v1/relations/${REL_REF}/pairing/${exchange.id}/approve` && init?.method === 'POST') {
      if (exchange.state !== 'RESPONDED') return respond(410, { error: 'exchange unavailable' })
      const req = JSON.parse(body) as { enrolTicketB64: string }
      exchange.approvedTicket = req.enrolTicketB64
      exchange.state = 'CLOSED'
      return respond(204)
    }
    if (url === `/v1/relations/${REL_REF}/pairing/${exchange.id}/cancel` && init?.method === 'POST') {
      if (exchange.state === 'OPEN' || exchange.state === 'RESPONDED' || exchange.state === 'CLOSED') {
        exchange.state = 'CANCELLED'
        return respond(204)
      }
      return respond(410, { error: 'exchange unavailable' })
    }
    throw new Error(`unexpected request: ${init?.method} ${url}`)
  }) as typeof fetch

  return { doFetch, recorded, responses, exchange }
}

/** Every encoding the code could wear on the wire. */
function encodingsOf(code: string): string[] {
  const bytes = new TextEncoder().encode(code)
  let raw = ''
  for (const b of bytes) raw += String.fromCharCode(b)
  const std = btoa(raw)
  return [
    code,
    encodeURIComponent(code),
    std,
    std.replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, ''),
    Array.from(bytes)
      .map((b) => b.toString(16).padStart(2, '0'))
      .join(''),
  ]
}

function wireTextOf(requests: RecordedRequest[]): string {
  return requests
    .map((r) => [r.method, r.url, ...Object.entries(r.headers).flat(), r.body].join('\n'))
    .join('\n')
}

const hex = (bytes: Uint8Array): string => Buffer.from(bytes).toString('hex')

beforeAll(async () => {
  await initCpace()
  CODE = (await newPairingCode()).canonical
  WRONG_CODE = oneSymbolOff(CODE)
})

describe('the code is canonical before it is bytes', () => {
  it('a typed, un-canonicalised code is refused before any request is made', async () => {
    const { doFetch, recorded } = relayServer()
    const typed = `${CODE.slice(0, 4)}-${CODE.slice(4)}`.toLowerCase() as CanonicalPairingCode
    await expect(
      ownerOpenPairing({ relRef: REL_REF, inviteId: INVITE_ID, code: typed, bearerToken: BEARER }, doFetch),
    ).rejects.toThrow(/canonical/)
    await expect(
      therapistAnswerPairing({ inviteId: INVITE_ID, secret: INVITE_SECRET, makeOffer: async () => OFFER, code: typed }, doFetch),
    ).rejects.toThrow(/canonical/)
    expect(recorded).toHaveLength(0)
  })

  it('however the therapist typed it, the canonical form keys the same as the owner', async () => {
    const { doFetch } = relayServer()
    const opened = await ownerOpenPairing(
      { relRef: REL_REF, inviteId: INVITE_ID, code: CODE, bearerToken: BEARER },
      doFetch,
    )
    const typed = parsePairingCode(` ${CODE.slice(0, 4).toLowerCase()} — ${CODE.slice(4)} `)
    expect(typed.ok).toBe(true)
    const therapist = await therapistAnswerPairing(
      { inviteId: INVITE_ID, secret: INVITE_SECRET, makeOffer: async () => OFFER, code: typed.ok ? typed.code.canonical : CODE },
      doFetch,
    )
    const collected = await ownerCollectPairing({ relRef: REL_REF, bearerToken: BEARER, pairing: opened }, doFetch)
    expect(collected.state).toBe('complete')
    expect(collected.state === 'complete' && hex(collected.isk)).toBe(hex(therapist.isk))
  })

  it('a restored run (scalar and pin only, no code) finishes and keeps its pin', async () => {
    const { doFetch, exchange } = relayServer()
    const opened = await ownerOpenPairing(
      { relRef: REL_REF, inviteId: INVITE_ID, code: CODE, bearerToken: BEARER },
      doFetch,
    )
    const therapist = await therapistAnswerPairing({ inviteId: INVITE_ID, secret: INVITE_SECRET, makeOffer: async () => OFFER, code: CODE }, doFetch)
    // What a reload leaves: the persisted fields, and nothing derived.
    const restored: OwnerPairingState = {
      exchangeId: opened.exchangeId,
      inviteId: opened.inviteId,
      sidB64: opened.sidB64,
      start: opened.start,
    }
    const first = await ownerCollectPairing({ relRef: REL_REF, bearerToken: BEARER, pairing: restored }, doFetch)
    expect(first.state === 'complete' && hex(first.isk)).toBe(hex(therapist.isk))
    expect(restored.pinnedMsgBB64).toBe(exchange.msgBB64)
    // A second reload keeps only the pin; a swapped reply is refused, never re-keyed.
    const again: OwnerPairingState = {
      exchangeId: opened.exchangeId,
      inviteId: opened.inviteId,
      sidB64: opened.sidB64,
      start: opened.start,
      pinnedMsgBB64: restored.pinnedMsgBB64,
    }
    exchange.msgBB64 = exchange.msgBB64!.slice(0, -2) + 'AA'
    await expect(
      ownerCollectPairing({ relRef: REL_REF, bearerToken: BEARER, pairing: again }, doFetch),
    ).rejects.toThrow(/changed after the key/)
  })
})

describe('the relay carries a pairing end to end', () => {
  it('both sides derive the same key through the three touches', async () => {
    const { doFetch } = relayServer()
    const opened = await ownerOpenPairing(
      { relRef: REL_REF, inviteId: INVITE_ID, code: CODE, bearerToken: BEARER },
      doFetch,
    )
    const therapist = await therapistAnswerPairing(
      { inviteId: INVITE_ID, secret: INVITE_SECRET, makeOffer: async () => OFFER, code: CODE },
      doFetch,
    )
    const collected = await ownerCollectPairing(
      { relRef: REL_REF, bearerToken: BEARER, pairing: opened },
      doFetch,
    )
    expect(collected.state).toBe('complete')
    if (collected.state !== 'complete') return
    expect(hex(collected.isk)).toBe(hex(therapist.isk))
    expect(collected.isk.length).toBe(64)
    // And the offer came through the envelope intact: keys, name, ticket, exactly as sealed.
    expect(collected.offer).toEqual(OFFER)
  })

  it('a wrong code completes the wire protocol and silently diverges — no error, no signal', async () => {
    const { doFetch } = relayServer()
    const opened = await ownerOpenPairing(
      { relRef: REL_REF, inviteId: INVITE_ID, code: CODE, bearerToken: BEARER },
      doFetch,
    )
    const therapist = await therapistAnswerPairing(
      { inviteId: INVITE_ID, secret: INVITE_SECRET, makeOffer: async () => OFFER, code: WRONG_CODE },
      doFetch,
    )
    const collected = await ownerCollectPairing(
      { relRef: REL_REF, bearerToken: BEARER, pairing: opened },
      doFetch,
    )
    expect(collected.state).toBe('complete')
    if (collected.state !== 'complete') return
    expect(hex(collected.isk)).not.toBe(hex(therapist.isk))
    // The only place the mismatch shows: the offer does not open. One bit, no throw.
    expect(collected.offer).toBeNull()
  })

  it('the owner sees waiting before the reply, cancelled after a cancel, superseded after a re-open', async () => {
    const { doFetch, exchange } = relayServer()
    const opened = await ownerOpenPairing(
      { relRef: REL_REF, inviteId: INVITE_ID, code: CODE, bearerToken: BEARER },
      doFetch,
    )
    const waiting = await ownerCollectPairing(
      { relRef: REL_REF, bearerToken: BEARER, pairing: opened },
      doFetch,
    )
    expect(waiting.state).toBe('waiting')
    exchange.state = 'CANCELLED'
    const cancelled = await ownerCollectPairing(
      { relRef: REL_REF, bearerToken: BEARER, pairing: opened },
      doFetch,
    )
    expect(cancelled.state).toBe('cancelled')
    // The server retires a run when the owner opens a fresh one; the client must name that
    // state rather than throw at it, because a console polling an old run will meet it.
    exchange.state = 'SUPERSEDED'
    const superseded = await ownerCollectPairing(
      { relRef: REL_REF, bearerToken: BEARER, pairing: opened },
      doFetch,
    )
    expect(superseded.state).toBe('superseded')
  })
})

describe('what binds a run, and to what', () => {
  it('the run is bound to the invitation whose LINK the therapist holds, not to what the server says', async () => {
    // A splicing server: it serves the exchange the owner opened for INVITE_ID to whoever
    // holds OTHER_INVITE_ID's link, reporting the true relRef, and both people typed the same
    // code (reuse happens). The therapist's channel identifier takes the invite id from the
    // link, so the keys must diverge — the server cannot marry two invitations into one run.
    const { doFetch } = relayServer([INVITE_ID, OTHER_INVITE_ID])
    const opened = await ownerOpenPairing(
      { relRef: REL_REF, inviteId: INVITE_ID, code: CODE, bearerToken: BEARER },
      doFetch,
    )
    const spliced = await therapistAnswerPairing(
      { inviteId: OTHER_INVITE_ID, secret: INVITE_SECRET, makeOffer: async () => OFFER, code: CODE },
      doFetch,
    )
    const collected = await ownerCollectPairing(
      { relRef: REL_REF, bearerToken: BEARER, pairing: opened },
      doFetch,
    )
    expect(collected.state).toBe('complete')
    if (collected.state !== 'complete') return
    expect(hex(collected.isk)).not.toBe(hex(spliced.isk))
    // The spliced party's offer, sealed under the wrong key, is nobody's therapist.
    expect(collected.offer).toBeNull()
  })

  it('the channel identifier is versioned, length-prefixed, and differs per invitation and per relationship', () => {
    const ci = channelIdentifier('rel-a', 'inv-1')
    const parts = parseLv(ci, 3).map((p) => new TextDecoder().decode(p))
    expect(parts).toEqual(['daymark/cpace/v2', 'rel-a', 'inv-1'])
    expect(hex(ci)).not.toBe(hex(channelIdentifier('rel-a', 'inv-2')))
    expect(hex(ci)).not.toBe(hex(channelIdentifier('rel-b', 'inv-1')))
    // Length-prefixing means a boundary shift is not the same bytes: 'rel-a' + 'inv-1' is
    // not 'rel-' + 'ainv-1', which a joined string could confuse.
    expect(hex(ci)).not.toBe(hex(channelIdentifier('rel-', 'ainv-1')))
  })

  it('one run derives one key: a changed reply on a later read is refused, never re-derived', async () => {
    const { doFetch, exchange } = relayServer()
    const opened = await ownerOpenPairing(
      { relRef: REL_REF, inviteId: INVITE_ID, code: CODE, bearerToken: BEARER },
      doFetch,
    )
    await therapistAnswerPairing({ inviteId: INVITE_ID, secret: INVITE_SECRET, makeOffer: async () => OFFER, code: CODE }, doFetch)
    const first = await ownerCollectPairing(
      { relRef: REL_REF, bearerToken: BEARER, pairing: opened },
      doFetch,
    )
    expect(first.state).toBe('complete')
    if (first.state !== 'complete') return

    // Polling again with the same reply is the same key, byte for byte.
    const again = await ownerCollectPairing(
      { relRef: REL_REF, bearerToken: BEARER, pairing: opened },
      doFetch,
    )
    expect(again.state).toBe('complete')
    if (again.state !== 'complete') return
    expect(hex(again.isk)).toBe(hex(first.isk))

    // A run that has produced a key cannot read as waiting or retired again: the store only
    // moves an answered run forward, so a server saying otherwise is contradicting itself.
    exchange.state = 'OPEN'
    await expect(
      ownerCollectPairing({ relRef: REL_REF, bearerToken: BEARER, pairing: opened }, doFetch),
    ).rejects.toThrow(/regressed/)
    exchange.state = 'CLOSED'

    // A server that swaps in a different — perfectly well-formed — reply is trying for a
    // second guess against the owner's scalar. It gets a refusal, not a second key.
    const other = relayServer()
    await ownerOpenPairing({ relRef: REL_REF, inviteId: INVITE_ID, code: CODE, bearerToken: BEARER }, other.doFetch)
    await therapistAnswerPairing({ inviteId: INVITE_ID, secret: INVITE_SECRET, makeOffer: async () => OFFER, code: CODE }, other.doFetch)
    expect(other.exchange.msgBB64).toBeTypeOf('string')
    expect(other.exchange.msgBB64).not.toBe(exchange.msgBB64)
    exchange.msgBB64 = other.exchange.msgBB64
    await expect(
      ownerCollectPairing({ relRef: REL_REF, bearerToken: BEARER, pairing: opened }, doFetch),
    ).rejects.toThrow(/reply changed/)
  })
})

describe('the offer and the approval', () => {
  it('approve forwards the ticket the offer carried, and the therapist sees it happen', async () => {
    const { doFetch, exchange } = relayServer()
    const opened = await ownerOpenPairing(
      { relRef: REL_REF, inviteId: INVITE_ID, code: CODE, bearerToken: BEARER },
      doFetch,
    )
    await therapistAnswerPairing({ inviteId: INVITE_ID, secret: INVITE_SECRET, makeOffer: async () => OFFER, code: CODE }, doFetch)
    const status = { inviteId: INVITE_ID, secret: INVITE_SECRET, exchangeId: opened.exchangeId }
    expect(await therapistPairingStatus(status, doFetch)).toEqual({ state: 'waiting' })

    const collected = await ownerCollectPairing({ relRef: REL_REF, bearerToken: BEARER, pairing: opened }, doFetch)
    expect(collected.state === 'complete' && collected.offer?.enrolTicketB64).toBe(OFFER.enrolTicketB64)
    await ownerApprovePairing(
      { relRef: REL_REF, bearerToken: BEARER, exchangeId: opened.exchangeId, enrolTicketB64: OFFER.enrolTicketB64 },
      doFetch,
    )
    expect(exchange.state).toBe('CLOSED')
    expect(exchange.approvedTicket).toBe(OFFER.enrolTicketB64)
    expect(await therapistPairingStatus(status, doFetch)).toEqual({ state: 'approved', scope: ['read.share'] })

    // Collecting again after approval is the same key and the same offer, not a new anything.
    const again = await ownerCollectPairing({ relRef: REL_REF, bearerToken: BEARER, pairing: opened }, doFetch)
    expect(again.state === 'complete' && again.offer).toEqual(OFFER)

    // Abandon: the run is gone to the therapist, and approving it again is refused.
    expect(await ownerCancelPairing({ relRef: REL_REF, bearerToken: BEARER, exchangeId: opened.exchangeId }, doFetch)).toBe(true)
    expect(await therapistPairingStatus(status, doFetch)).toEqual({ state: 'gone' })
    await expect(
      ownerApprovePairing(
        { relRef: REL_REF, bearerToken: BEARER, exchangeId: opened.exchangeId, enrolTicketB64: OFFER.enrolTicketB64 },
        doFetch,
      ),
    ).rejects.toThrow(/refused \(410\)/)
    expect(await ownerCancelPairing({ relRef: REL_REF, bearerToken: BEARER, exchangeId: opened.exchangeId }, doFetch)).toBe(false)
  })

  it('a swapped envelope, or none, is a null offer over the same key — never a different therapist', async () => {
    const { doFetch, exchange } = relayServer()
    const opened = await ownerOpenPairing(
      { relRef: REL_REF, inviteId: INVITE_ID, code: CODE, bearerToken: BEARER },
      doFetch,
    )
    const therapist = await therapistAnswerPairing({ inviteId: INVITE_ID, secret: INVITE_SECRET, makeOffer: async () => OFFER, code: CODE }, doFetch)
    // One byte of ciphertext turned: the AEAD refuses, and the key is untouched.
    const sealed = exchange.envB64!
    exchange.envB64 = sealed.slice(0, -3) + (sealed.endsWith('A') ? 'B' : 'A') + sealed.slice(-2)
    const tampered = await ownerCollectPairing({ relRef: REL_REF, bearerToken: BEARER, pairing: opened }, doFetch)
    expect(tampered.state === 'complete' && tampered.offer).toBeNull()
    expect(tampered.state === 'complete' && hex(tampered.isk)).toBe(hex(therapist.isk))
    // A reply from before envelopes existed: complete, no offer, no throw.
    exchange.envB64 = undefined
    const bare = await ownerCollectPairing({ relRef: REL_REF, bearerToken: BEARER, pairing: opened }, doFetch)
    expect(bare.state === 'complete' && bare.offer).toBeNull()
    // Garbage that is not even base64url: the same null.
    exchange.envB64 = '!!not-base64!!'
    const garbage = await ownerCollectPairing({ relRef: REL_REF, bearerToken: BEARER, pairing: opened }, doFetch)
    expect(garbage.state === 'complete' && garbage.offer).toBeNull()
    // And the real one, restored, still opens under the pinned key.
    exchange.envB64 = sealed
    const real = await ownerCollectPairing({ relRef: REL_REF, bearerToken: BEARER, pairing: opened }, doFetch)
    expect(real.state === 'complete' && real.offer).toEqual(OFFER)
  })

  it('an invalid offer never reaches the wire: the run is fetched but never answered', async () => {
    const { doFetch, recorded } = relayServer()
    await ownerOpenPairing({ relRef: REL_REF, inviteId: INVITE_ID, code: CODE, bearerToken: BEARER }, doFetch)
    recorded.length = 0
    await expect(
      therapistAnswerPairing(
        { inviteId: INVITE_ID, secret: INVITE_SECRET, makeOffer: async () => ({ ...OFFER, enrolTicketB64: 'short' }), code: CODE },
        doFetch,
      ),
    ).rejects.toThrow(/invalid offer/)
    // The fetch happened (the offer needs the relRef it returns); the RESPOND did not, so no run
    // was spent and nothing malformed reached the wire.
    expect(recorded.map((r) => r.url)).toEqual([`/v1/invite/${INVITE_ID}/pairing/fetch`])
  })

  it('a 429 that names a time is the connection being paused, and it carries the time', async () => {
    /*
     * Two different 429s arrive on these routes and the screen has to tell them apart, because
     * they are two different sentences: the CONNECTION's budget (this one, which says when it
     * heals) and the INVITATION's lockout (which does not, and is somebody else's guessing). The
     * header is the whole distinction, so it is what the client keys on.
     */
    const { doFetch } = relayServer()
    const paused = (headers: Record<string, string>) =>
      (async (input: RequestInfo | URL, init?: RequestInit) => {
        if (String(input).endsWith('/fetch')) {
          return new Response(JSON.stringify({ error: 'rate limited' }), { status: 429, headers })
        }
        return doFetch(input, init)
      }) as typeof fetch

    const answer = (f: typeof fetch) =>
      therapistAnswerPairing({ inviteId: INVITE_ID, secret: INVITE_SECRET, makeOffer: async () => OFFER, code: CODE }, f)

    await expect(answer(paused({ 'retry-after': '137' }))).rejects.toThrow(PairingPausedError)
    await answer(paused({ 'retry-after': '137' })).catch((e) => {
      expect(e).toBeInstanceOf(PairingPausedError)
      expect((e as PairingPausedError).retryAfterSeconds).toBe(137)
    })

    // The control: the SAME status code without the header is not a pause. A lockout must not be
    // rendered as "this connection is busy, come back at four" — it is not about the connection.
    await expect(answer(paused({}))).rejects.toThrow(/refused \(429\)/)
    await expect(answer(paused({}))).rejects.not.toThrow(PairingPausedError)
  })

  it('the respond touch reads a pause the same way the fetch does', async () => {
    const { doFetch } = relayServer()
    const throttleRespond = (async (input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input).endsWith('/respond')) {
        return new Response(JSON.stringify({ error: 'rate limited' }), { status: 429, headers: { 'retry-after': '42' } })
      }
      return doFetch(input, init)
    }) as typeof fetch
    await ownerOpenPairing({ relRef: REL_REF, inviteId: INVITE_ID, code: CODE, bearerToken: BEARER }, doFetch)
    await therapistAnswerPairing(
      { inviteId: INVITE_ID, secret: INVITE_SECRET, makeOffer: async () => OFFER, code: CODE },
      throttleRespond,
    ).catch((e) => {
      expect(e).toBeInstanceOf(PairingPausedError)
      expect((e as PairingPausedError).retryAfterSeconds).toBe(42)
    })
  })

  it('only a plain number of seconds is trusted to build a sentence out of', () => {
    // The screen says "paused until {time}". Anything this function cannot vouch for has to come
    // back null so the caller falls back to a sentence with no time in it, rather than showing a
    // person a clock time derived from a header nobody parsed.
    expect(retryAfterSeconds('137')).toBe(137)
    expect(retryAfterSeconds(' 137 ')).toBe(137)
    expect(retryAfterSeconds(null)).toBeNull()
    expect(retryAfterSeconds('')).toBeNull()
    expect(retryAfterSeconds('0')).toBeNull()
    expect(retryAfterSeconds('-5')).toBeNull()
    expect(retryAfterSeconds('12.5')).toBeNull()
    // The HTTP-date form, deliberately unread: it would have the client trusting its own clock.
    expect(retryAfterSeconds('Wed, 21 Oct 2026 07:28:00 GMT')).toBeNull()
  })

  it('a 429 on the status poll is waiting, not an error', async () => {
    const { doFetch } = relayServer()
    const throttled = (async (input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input).endsWith('/status')) return new Response(JSON.stringify({ error: 'rate limited' }), { status: 429 })
      return doFetch(input, init)
    }) as typeof fetch
    const opened = await ownerOpenPairing({ relRef: REL_REF, inviteId: INVITE_ID, code: CODE, bearerToken: BEARER }, doFetch)
    expect(
      await therapistPairingStatus({ inviteId: INVITE_ID, secret: INVITE_SECRET, exchangeId: opened.exchangeId }, throttled),
    ).toEqual({ state: 'waiting' })
  })
})

describe('§3.7.4 — the code never reaches the wire, and the ticket reaches it once', () => {
  it('a full pairing, from both codes, leaves no trace of either in any request', async () => {
    const { doFetch, recorded, responses } = relayServer()
    const opened = await ownerOpenPairing(
      { relRef: REL_REF, inviteId: INVITE_ID, code: CODE, bearerToken: BEARER },
      doFetch,
    )
    await therapistAnswerPairing({ inviteId: INVITE_ID, secret: INVITE_SECRET, makeOffer: async () => OFFER, code: CODE }, doFetch)
    await therapistPairingStatus({ inviteId: INVITE_ID, secret: INVITE_SECRET, exchangeId: opened.exchangeId }, doFetch)
    await ownerCollectPairing({ relRef: REL_REF, bearerToken: BEARER, pairing: opened }, doFetch)
    await ownerApprovePairing(
      { relRef: REL_REF, bearerToken: BEARER, exchangeId: opened.exchangeId, enrolTicketB64: OFFER.enrolTicketB64 },
      doFetch,
    )
    await therapistPairingStatus({ inviteId: INVITE_ID, secret: INVITE_SECRET, exchangeId: opened.exchangeId }, doFetch)

    // Non-vacuity, both ways: the recorder saw the whole ceremony, and the detector can see
    // a planted code. A wire scan proving neither would prove nothing.
    expect(recorded.length).toBeGreaterThanOrEqual(7)
    const wire = wireTextOf(recorded)
    expect(wire.length).toBeGreaterThan(500)
    for (const planted of encodingsOf(CODE)) {
      expect((wire + planted).includes(planted)).toBe(true)
    }

    for (const encoding of encodingsOf(CODE)) {
      expect(wire.includes(encoding), `code leaked to the wire as: ${encoding}`).toBe(false)
    }

    // The offer itself never travels in the clear: keys and name are inside the envelope only.
    for (const clear of [OFFER.boxPubB64, OFFER.signPubB64, OFFER.displayName]) {
      expect(wire.includes(clear), `offer field on the wire in the clear: ${clear}`).toBe(false)
    }

    // The ticket appears in exactly ONE request — the owner's approve — and in no response.
    const carrying = recorded.filter((r) => r.body.includes(OFFER.enrolTicketB64) || r.url.includes(OFFER.enrolTicketB64))
    expect(carrying.map((r) => r.url)).toEqual([`/v1/relations/${REL_REF}/pairing/${opened.exchangeId}/approve`])
    expect(responses.some((r) => r.includes(OFFER.enrolTicketB64))).toBe(false)
    // Control: the response recorder is not blind.
    expect(responses.some((r) => r.includes('APPROVED'))).toBe(true)
  })
})
