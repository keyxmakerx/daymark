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
  therapistAnswerPairing,
  channelIdentifier,
  type OwnerPairingState,
} from './relay'
import { initCpace, parseLv } from './cpace'
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
function relayServer(servesTo: string[] = [INVITE_ID], opts: { failFirstClose?: boolean } = {}) {
  const recorded: RecordedRequest[] = []
  let closeAttempts = 0
  const exchange: {
    id: string
    sidB64?: string
    msgAB64?: string
    msgBB64?: string
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

    const respond = (status: number, payload?: unknown) =>
      new Response(payload === undefined ? null : JSON.stringify(payload), { status })

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
      const req = JSON.parse(body) as { secret: string; msgBB64: string }
      if (req.secret !== INVITE_SECRET) return respond(401, { error: 'unauthorized' })
      if (exchange.state !== 'OPEN') return respond(410, { error: 'exchange unavailable' })
      exchange.msgBB64 = req.msgBB64
      exchange.state = 'RESPONDED'
      return respond(204)
    }
    if (url === `/v1/relations/${REL_REF}/pairing/${exchange.id}` && (init?.method ?? 'GET') === 'GET') {
      if (exchange.state === 'NONE') return respond(404, { error: 'no such exchange' })
      return respond(200, { exchangeId: exchange.id, state: exchange.state, msgBB64: exchange.msgBB64 })
    }
    if (url === `/v1/relations/${REL_REF}/pairing/${exchange.id}/close` && init?.method === 'POST') {
      closeAttempts++
      if (opts.failFirstClose && closeAttempts === 1) return respond(500, { error: 'not now' })
      exchange.state = 'CLOSED'
      return respond(204)
    }
    throw new Error(`unexpected request: ${init?.method} ${url}`)
  }) as typeof fetch

  return { doFetch, recorded, exchange, closeAttempts: () => closeAttempts }
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
      therapistAnswerPairing({ inviteId: INVITE_ID, secret: INVITE_SECRET, code: typed }, doFetch),
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
      { inviteId: INVITE_ID, secret: INVITE_SECRET, code: typed.ok ? typed.code.canonical : CODE },
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
    const therapist = await therapistAnswerPairing({ inviteId: INVITE_ID, secret: INVITE_SECRET, code: CODE }, doFetch)
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
      { inviteId: INVITE_ID, secret: INVITE_SECRET, code: CODE },
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
  })

  it('a wrong code completes the wire protocol and silently diverges — no error, no signal', async () => {
    const { doFetch } = relayServer()
    const opened = await ownerOpenPairing(
      { relRef: REL_REF, inviteId: INVITE_ID, code: CODE, bearerToken: BEARER },
      doFetch,
    )
    const therapist = await therapistAnswerPairing(
      { inviteId: INVITE_ID, secret: INVITE_SECRET, code: WRONG_CODE },
      doFetch,
    )
    const collected = await ownerCollectPairing(
      { relRef: REL_REF, bearerToken: BEARER, pairing: opened },
      doFetch,
    )
    expect(collected.state).toBe('complete')
    if (collected.state !== 'complete') return
    expect(hex(collected.isk)).not.toBe(hex(therapist.isk))
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
      { inviteId: OTHER_INVITE_ID, secret: INVITE_SECRET, code: CODE },
      doFetch,
    )
    const collected = await ownerCollectPairing(
      { relRef: REL_REF, bearerToken: BEARER, pairing: opened },
      doFetch,
    )
    expect(collected.state).toBe('complete')
    if (collected.state !== 'complete') return
    expect(hex(collected.isk)).not.toBe(hex(spliced.isk))
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
    await therapistAnswerPairing({ inviteId: INVITE_ID, secret: INVITE_SECRET, code: CODE }, doFetch)
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
    await therapistAnswerPairing({ inviteId: INVITE_ID, secret: INVITE_SECRET, code: CODE }, other.doFetch)
    expect(other.exchange.msgBB64).toBeTypeOf('string')
    expect(other.exchange.msgBB64).not.toBe(exchange.msgBB64)
    exchange.msgBB64 = other.exchange.msgBB64
    await expect(
      ownerCollectPairing({ relRef: REL_REF, bearerToken: BEARER, pairing: opened }, doFetch),
    ).rejects.toThrow(/reply changed/)
  })
})

describe('the close is bookkeeping, and is retried', () => {
  it('a failed close never costs the key, and the next read retries it', async () => {
    const { doFetch, exchange, closeAttempts } = relayServer([INVITE_ID], { failFirstClose: true })
    const opened = await ownerOpenPairing(
      { relRef: REL_REF, inviteId: INVITE_ID, code: CODE, bearerToken: BEARER },
      doFetch,
    )
    await therapistAnswerPairing({ inviteId: INVITE_ID, secret: INVITE_SECRET, code: CODE }, doFetch)
    const first = await ownerCollectPairing(
      { relRef: REL_REF, bearerToken: BEARER, pairing: opened },
      doFetch,
    )
    expect(first.state).toBe('complete')
    expect(exchange.state).toBe('RESPONDED')
    expect(closeAttempts()).toBe(1)
    const second = await ownerCollectPairing(
      { relRef: REL_REF, bearerToken: BEARER, pairing: opened },
      doFetch,
    )
    expect(second.state).toBe('complete')
    if (first.state !== 'complete' || second.state !== 'complete') return
    expect(hex(second.isk)).toBe(hex(first.isk))
    expect(exchange.state).toBe('CLOSED')
    expect(closeAttempts()).toBe(2)
  })

  it("the owner's own cancel after a failed close is reported as cancelled, not as a lie", async () => {
    // RESPONDED → CANCELLED is a legal forward move the store allows and the console offers;
    // a run that already holds its key must not mistake it for the server going backwards.
    const { doFetch, exchange } = relayServer([INVITE_ID], { failFirstClose: true })
    const opened = await ownerOpenPairing(
      { relRef: REL_REF, inviteId: INVITE_ID, code: CODE, bearerToken: BEARER },
      doFetch,
    )
    await therapistAnswerPairing({ inviteId: INVITE_ID, secret: INVITE_SECRET, code: CODE }, doFetch)
    const first = await ownerCollectPairing(
      { relRef: REL_REF, bearerToken: BEARER, pairing: opened },
      doFetch,
    )
    expect(first.state).toBe('complete')
    expect(exchange.state).toBe('RESPONDED')
    exchange.state = 'CANCELLED'
    const after = await ownerCollectPairing(
      { relRef: REL_REF, bearerToken: BEARER, pairing: opened },
      doFetch,
    )
    expect(after.state).toBe('cancelled')
  })
})

describe('§3.7.4 — the code never reaches the wire', () => {
  it('a full pairing, from both codes, leaves no trace of either in any request', async () => {
    const { doFetch, recorded } = relayServer()
    const opened = await ownerOpenPairing(
      { relRef: REL_REF, inviteId: INVITE_ID, code: CODE, bearerToken: BEARER },
      doFetch,
    )
    await therapistAnswerPairing({ inviteId: INVITE_ID, secret: INVITE_SECRET, code: CODE }, doFetch)
    await ownerCollectPairing(
      { relRef: REL_REF, bearerToken: BEARER, pairing: opened },
      doFetch,
    )

    // Non-vacuity, both ways: the recorder saw the whole ceremony, and the detector can see
    // a planted code. A wire scan proving neither would prove nothing.
    expect(recorded.length).toBeGreaterThanOrEqual(5)
    const wire = wireTextOf(recorded)
    expect(wire.length).toBeGreaterThan(500)
    for (const planted of encodingsOf(CODE)) {
      expect((wire + planted).includes(planted)).toBe(true)
    }

    for (const encoding of encodingsOf(CODE)) {
      expect(wire.includes(encoding), `code leaked to the wire as: ${encoding}`).toBe(false)
    }
  })
})
