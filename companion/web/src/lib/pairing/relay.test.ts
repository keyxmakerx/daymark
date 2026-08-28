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
 */
import { beforeAll, describe, expect, it } from 'vitest'
import {
  ownerOpenPairing,
  ownerCollectPairing,
  therapistAnswerPairing,
  channelIdentifier,
} from './relay'
import { initCpace } from './cpace'

const CODE = 'SEKRIT-7Q4X9Z'
const WRONG_CODE = 'SEKRIT-7Q4X9A'
const REL_REF = 'rel-ref-abc123'
const INVITE_ID = 'invite-xyz789'
const INVITE_SECRET = 'invite-secret-value-42'
const BEARER = 'owner-bearer-token'

interface RecordedRequest {
  url: string
  method: string
  headers: Record<string, string>
  body: string
}

/** The server's part of the relay, plus a wire recorder — one instance per test. */
function relayServer() {
  const recorded: RecordedRequest[] = []
  const exchange: {
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
    if (url === `/v1/invite/${INVITE_ID}/pairing/fetch` && init?.method === 'POST') {
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
    if (url === `/v1/invite/${INVITE_ID}/pairing/${exchange.id}/respond` && init?.method === 'POST') {
      const req = JSON.parse(body) as { secret: string; msgBB64: string; payloadB64?: string }
      if (req.secret !== INVITE_SECRET) return respond(401, { error: 'unauthorized' })
      if (exchange.state !== 'OPEN') return respond(410, { error: 'exchange unavailable' })
      exchange.msgBB64 = req.msgBB64
      exchange.payloadB = req.payloadB64
      exchange.state = 'RESPONDED'
      return respond(204)
    }
    if (url === `/v1/relations/${REL_REF}/pairing/${exchange.id}` && (init?.method ?? 'GET') === 'GET') {
      if (exchange.state === 'NONE') return respond(404, { error: 'no such exchange' })
      return respond(200, {
        exchangeId: exchange.id,
        state: exchange.state,
        msgBB64: exchange.msgBB64,
        payloadBB64: exchange.payloadB,
      })
    }
    if (url === `/v1/relations/${REL_REF}/pairing/${exchange.id}/close` && init?.method === 'POST') {
      const req = JSON.parse(body || '{}') as { payloadB64?: string }
      exchange.payloadA = req.payloadB64
      exchange.state = 'CLOSED'
      return respond(204)
    }
    if (url === `/v1/invite/${INVITE_ID}/pairing/${exchange.id}/collect` && init?.method === 'POST') {
      const req = JSON.parse(body) as { secret: string }
      if (req.secret !== INVITE_SECRET) return respond(401, { error: 'unauthorized' })
      if (exchange.state !== 'CLOSED' || !exchange.payloadA) {
        return respond(410, { error: 'exchange unavailable' })
      }
      return respond(200, { payloadB64: exchange.payloadA })
    }
    throw new Error(`unexpected request: ${init?.method} ${url}`)
  }) as typeof fetch

  return { doFetch, recorded, exchange }
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

beforeAll(async () => {
  await initCpace()
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
      { relRef: REL_REF, code: CODE, bearerToken: BEARER, pairing: opened },
      doFetch,
    )
    expect(collected.state).toBe('complete')
    if (collected.state !== 'complete') return
    expect(Buffer.from(collected.isk).toString('hex')).toBe(Buffer.from(therapist.isk).toString('hex'))
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
      { relRef: REL_REF, code: CODE, bearerToken: BEARER, pairing: opened },
      doFetch,
    )
    expect(collected.state).toBe('complete')
    if (collected.state !== 'complete') return
    expect(Buffer.from(collected.isk).toString('hex')).not.toBe(
      Buffer.from(therapist.isk).toString('hex'),
    )
  })

  it('the owner sees waiting before the reply and cancelled after a cancel', async () => {
    const { doFetch, exchange } = relayServer()
    const opened = await ownerOpenPairing(
      { relRef: REL_REF, inviteId: INVITE_ID, code: CODE, bearerToken: BEARER },
      doFetch,
    )
    const waiting = await ownerCollectPairing(
      { relRef: REL_REF, code: CODE, bearerToken: BEARER, pairing: opened },
      doFetch,
    )
    expect(waiting.state).toBe('waiting')
    exchange.state = 'CANCELLED'
    const cancelled = await ownerCollectPairing(
      { relRef: REL_REF, code: CODE, bearerToken: BEARER, pairing: opened },
      doFetch,
    )
    expect(cancelled.state).toBe('cancelled')
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
      { relRef: REL_REF, code: CODE, bearerToken: BEARER, pairing: opened },
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

  it('the channel identifier binds relationship and version, and is public by design', () => {
    // Sanity on what IS allowed on the wire-adjacent surface: the CI is derived, not secret,
    // and changing the relationship changes it — the property that turns a spliced relay into
    // key divergence instead of a session.
    const a = channelIdentifier('rel-a')
    const b = channelIdentifier('rel-b')
    expect(new TextDecoder().decode(a)).toContain('daymark/cpace/v1')
    expect(new TextDecoder().decode(a)).not.toBe(new TextDecoder().decode(b))
  })
})
