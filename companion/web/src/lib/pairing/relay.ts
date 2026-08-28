/*
 * The CPace exchange, carried through the Companion as opaque parcels (plan §3.7.3).
 *
 * Three touches, none simultaneous — this is store-and-forward, not a phone call:
 *
 *   owner:      ownerOpenPairing()      — derive MSGa from the code, post it, keep the scalar
 *   therapist:  therapistAnswerPairing() — fetch MSGa, derive MSGb + the key, post MSGb
 *   owner:      ownerCollectPairing()    — fetch MSGb, derive the same key, close
 *
 * THE CODE NEVER LEAVES THE DEVICE IT WAS TYPED ON. That is §3.7.4's hard invariant — a server
 * holding the code could run the exchange itself and sit in the middle — and in this module it
 * is structural, not behavioral: the code goes into cpaceStart/cpaceRespond as the PRS and
 * nothing else reads it; every request body is built from the OUTPUTS of those calls, which are
 * uniform group elements the code cannot be recovered from. relay.test.ts drives a whole
 * pairing through a recording transport and then greps every request — URL, headers, body — for
 * the code in every encoding it could wear; that test is the §3.7.4 deliverable and removing
 * this property fails it.
 *
 * WRONG CODE ≠ ERROR, here as everywhere in the §3.7 design: mismatched codes produce two
 * different keys and no signal in this module. The mismatch surfaces when the encrypted
 * negotiation built ON the key fails to open, and a person decides what that means (the burn
 * rule: only a human report kills an invite).
 *
 * What binds the exchange to THIS relationship and THESE roles rather than to any relay the
 * server might splice together: the channel identifier (CI) carries the relRef, and the
 * associated data carries each side's role. Both parties derive them independently — the owner
 * from their own relationship record, the therapist from the fetch response — so a server that
 * relays messages between two DIFFERENT relationships produces key divergence, not a session.
 */
import { initCpace, cpaceStart, cpaceRespond, cpaceFinish, type CpaceStartResult } from './cpace'

type FetchLike = typeof fetch

const utf8 = (s: string): Uint8Array => new TextEncoder().encode(s)

/**
 * Version-tagged so a future change to any part of the construction (hash, encoding, roles)
 * changes the CI and cleanly refuses to key against the old one, instead of half-agreeing.
 */
export function channelIdentifier(relRef: string): Uint8Array {
  return utf8(`daymark/cpace/v1|${relRef}`)
}

export const AD_OWNER = utf8('owner')
export const AD_THERAPIST = utf8('therapist')

const b64 = {
  encode(bytes: Uint8Array): string {
    let s = ''
    for (const b of bytes) s += String.fromCharCode(b)
    return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
  },
  decode(text: string): Uint8Array {
    const std = text.replace(/-/g, '+').replace(/_/g, '/')
    const raw = atob(std)
    const out = new Uint8Array(raw.length)
    for (let i = 0; i < raw.length; i++) out[i] = raw.charCodeAt(i)
    return out
  },
}

export interface OwnerPairingState {
  exchangeId: string
  sidB64: string
  /** The CPace scalar + MSGa. Secret; lives in memory until collect, never serialized. */
  start: CpaceStartResult
}

/** Owner touch 1: derive MSGa from the code and post it for the invite. */
export async function ownerOpenPairing(
  args: { relRef: string; inviteId: string; code: string; bearerToken: string },
  doFetch: FetchLike = fetch.bind(globalThis),
): Promise<OwnerPairingState> {
  const sodium = await initCpace()
  const sid = sodium.randombytes_buf(16)
  const start = cpaceStart(
    { prs: utf8(args.code), ci: channelIdentifier(args.relRef), sid },
    AD_OWNER,
  )
  const res = await doFetch(`/v1/relations/${encodeURIComponent(args.relRef)}/pairing`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', authorization: `Bearer ${args.bearerToken}` },
    body: JSON.stringify({
      inviteId: args.inviteId,
      sidB64: b64.encode(sid),
      msgAB64: b64.encode(start.msgA),
    }),
  })
  if (res.status !== 201) throw new Error(`pairing open refused (${res.status})`)
  const body = (await res.json()) as { exchangeId?: unknown }
  if (typeof body.exchangeId !== 'string') throw new Error('pairing open: malformed response')
  return { exchangeId: body.exchangeId, sidB64: b64.encode(sid), start }
}

export interface TherapistPairingResult {
  exchangeId: string
  relRef: string
  /** The 64-byte intermediate session key — same bytes the owner derives iff the codes matched. */
  isk: Uint8Array
}

/**
 * Produce this side's sealed negotiation half, given the key that has just been derived.
 *
 * A callback rather than a payload argument because of WHEN sealing becomes possible: neither
 * side holds the key until part-way through its own touch, so a caller who wanted to pass
 * finished ciphertext would have to run the exchange twice. This keeps relay.ts transport-only
 * — it never learns what a payload means, only that there is one — while letting negotiation.ts
 * seal at the one instant the key exists. Omitted, nothing is published, and the exchange is a
 * bare key agreement.
 */
export type SealPayload = (isk: Uint8Array, sidB64: string) => Uint8Array

/** Therapist touches: fetch the owner's opening message, answer it, keep the key. */
export async function therapistAnswerPairing(
  args: { inviteId: string; secret: string; code: string; seal?: SealPayload },
  doFetch: FetchLike = fetch.bind(globalThis),
): Promise<TherapistPairingResult> {
  await initCpace()
  const invitePath = `/v1/invite/${encodeURIComponent(args.inviteId)}/pairing`
  const fetchRes = await doFetch(`${invitePath}/fetch`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ secret: args.secret }),
  })
  if (fetchRes.status !== 200) throw new Error(`pairing fetch refused (${fetchRes.status})`)
  const body = (await fetchRes.json()) as {
    exchangeId?: unknown
    relRef?: unknown
    sidB64?: unknown
    msgAB64?: unknown
  }
  if (
    typeof body.exchangeId !== 'string' ||
    typeof body.relRef !== 'string' ||
    typeof body.sidB64 !== 'string' ||
    typeof body.msgAB64 !== 'string'
  ) {
    throw new Error('pairing fetch: malformed response')
  }
  const responded = cpaceRespond(
    { prs: utf8(args.code), ci: channelIdentifier(body.relRef), sid: b64.decode(body.sidB64) },
    b64.decode(body.msgAB64),
    AD_THERAPIST,
  )
  const sealed = args.seal?.(responded.isk, body.sidB64)
  const respondRes = await doFetch(`${invitePath}/${encodeURIComponent(body.exchangeId)}/respond`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({
      secret: args.secret,
      msgBB64: b64.encode(responded.msgB),
      // One request, not two: the reply and this side's sealed half land in a single row
      // update, so the owner never sees a key it can derive beside an envelope that never came.
      ...(sealed ? { payloadB64: b64.encode(sealed) } : {}),
    }),
  })
  if (respondRes.status !== 204) throw new Error(`pairing respond refused (${respondRes.status})`)
  return { exchangeId: body.exchangeId, relRef: body.relRef, isk: responded.isk }
}

/**
 * Therapist touch 3: collect the owner's sealed half, once they have closed.
 *
 * Returns null while there is nothing yet — which is also the answer for an exchange that never
 * existed, deliberately, since the server does not distinguish them and neither should this.
 * The caller polls or asks the person to try again in a moment; there is no failure here, only
 * a ceremony the other side has not finished.
 */
export async function therapistCollectOwnerHalf(
  args: { inviteId: string; secret: string; exchangeId: string },
  doFetch: FetchLike = fetch.bind(globalThis),
): Promise<Uint8Array | null> {
  const url = `/v1/invite/${encodeURIComponent(args.inviteId)}/pairing/${encodeURIComponent(args.exchangeId)}/collect`
  const res = await doFetch(url, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ secret: args.secret }),
  })
  if (res.status === 410) return null
  if (res.status !== 200) throw new Error(`pairing collect refused (${res.status})`)
  const body = (await res.json()) as { payloadB64?: unknown }
  if (typeof body.payloadB64 !== 'string') throw new Error('pairing collect: malformed response')
  return b64.decode(body.payloadB64)
}

export type OwnerCollectResult =
  | { state: 'waiting' }
  | { state: 'cancelled' }
  | { state: 'complete'; isk: Uint8Array; therapistHalf: Uint8Array | null }

/**
 * Owner touch 3: look for the reply; when it is there, derive the key, publish this side's
 * sealed half, and close.
 */
export async function ownerCollectPairing(
  args: {
    relRef: string
    code: string
    bearerToken: string
    pairing: OwnerPairingState
    seal?: SealPayload
  },
  doFetch: FetchLike = fetch.bind(globalThis),
): Promise<OwnerCollectResult> {
  await initCpace()
  const base = `/v1/relations/${encodeURIComponent(args.relRef)}/pairing/${encodeURIComponent(args.pairing.exchangeId)}`
  const res = await doFetch(base, {
    method: 'GET',
    headers: { authorization: `Bearer ${args.bearerToken}` },
  })
  if (res.status !== 200) throw new Error(`pairing read refused (${res.status})`)
  const body = (await res.json()) as { state?: unknown; msgBB64?: unknown; payloadBB64?: unknown }
  if (body.state === 'OPEN') return { state: 'waiting' }
  if (body.state === 'CANCELLED') return { state: 'cancelled' }
  if (body.state !== 'RESPONDED' && body.state !== 'CLOSED') {
    throw new Error('pairing read: malformed response')
  }
  if (typeof body.msgBB64 !== 'string') throw new Error('pairing read: reply missing')
  const isk = cpaceFinish(
    { prs: utf8(args.code), ci: channelIdentifier(args.relRef), sid: b64.decode(args.pairing.sidB64) },
    args.pairing.start,
    b64.decode(body.msgBB64),
  )
  const therapistHalf =
    typeof body.payloadBB64 === 'string' ? b64.decode(body.payloadBB64) : null
  if (body.state === 'RESPONDED') {
    // Closing publishes this side's sealed half in the same request, which is why a failure
    // here is NOT swallowed the way a bookkeeping-only close could be: an exchange left open
    // has simply not finished, and the caller gets to say so rather than reporting a ceremony
    // complete that the clinician can never collect their half of.
    const sealed = args.seal?.(isk, args.pairing.sidB64)
    const closeRes = await doFetch(`${base}/close`, {
      method: 'POST',
      headers: { 'content-type': 'application/json', authorization: `Bearer ${args.bearerToken}` },
      body: JSON.stringify(sealed ? { payloadB64: b64.encode(sealed) } : {}),
    })
    if (closeRes.status !== 204) throw new Error(`pairing close refused (${closeRes.status})`)
  }
  return { state: 'complete', isk, therapistHalf }
}
