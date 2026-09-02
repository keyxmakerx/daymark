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
 * WHAT BINDS THE RUN TO THIS INVITATION, and what does not. The channel identifier carries the
 * invite id, which is the one value each side holds WITHOUT the server's help — the owner
 * chose which invitation to open a run for, and the therapist has it from the link itself. The
 * relationship reference is in the CI too, but on the therapist's side it arrives in the fetch
 * response, so it binds nothing against the party that sent it (the audit of 2026-09-01 caught
 * an earlier version of this comment crediting it with exactly that). The associated data
 * carries each side's role. A server that serves one invitation's opening message to the
 * holder of another's link therefore produces key divergence, not a session — even if, by
 * reuse or bad luck, both invitations were given the same code.
 *
 * ONE RUN, ONE KEY. The owner's state pins the reply that produced its key; a later read that
 * shows a different reply is refused, never re-derived. CPace gives an online attacker one
 * guess per protocol run only if a run derives one key, and that is enforced here rather than
 * left to how often a caller polls.
 */
import {
  initCpace,
  cpaceStart,
  cpaceRespond,
  cpaceFinish,
  lvCat,
  type CpaceStartResult,
} from './cpace'

type FetchLike = typeof fetch

const utf8 = (s: string): Uint8Array => new TextEncoder().encode(s)

/**
 * The CPace channel identifier: version tag, relationship reference, invite id — length-
 * prefixed (the draft's lv_cat) rather than joined, so no separator can ever be mistaken for
 * structure. Version-tagged so a future change to any part of the construction changes the CI
 * and cleanly refuses to key against the old one, instead of half-agreeing. The owner's half on
 * the phone (plan 4.0b) must build these exact bytes.
 */
export function channelIdentifier(relRef: string, inviteId: string): Uint8Array {
  return lvCat(utf8('daymark/cpace/v2'), utf8(relRef), utf8(inviteId))
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
  inviteId: string
  sidB64: string
  /**
   * The CPace scalar + MSGa. Secret, and the ONLY copy of the owner's half of the run — the
   * server holds nothing that can finish it. This module keeps it in memory and never
   * serializes it; a caller that has to survive a reload, or the store-and-forward gap of
   * §3.7.3 (the owner finishes "next time they open the app"), must keep it itself, on the
   * device, under its own protection. Losing it makes the run unfinishable by anyone, and the
   * remedy is a fresh run, which spends one of the invite's exchanges.
   */
  start: CpaceStartResult
  /**
   * Set by the first successful collect: the reply that produced the key, and the key. A later
   * read showing a different reply is refused rather than re-derived — see ONE RUN, ONE KEY.
   */
  finished?: { msgBB64: string; isk: Uint8Array }
}

/** Owner touch 1: derive MSGa from the code and post it for the invite. */
export async function ownerOpenPairing(
  args: { relRef: string; inviteId: string; code: string; bearerToken: string },
  doFetch: FetchLike = fetch.bind(globalThis),
): Promise<OwnerPairingState> {
  const sodium = await initCpace()
  const sid = sodium.randombytes_buf(16)
  const start = cpaceStart(
    { prs: utf8(args.code), ci: channelIdentifier(args.relRef, args.inviteId), sid },
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
  return { exchangeId: body.exchangeId, inviteId: args.inviteId, sidB64: b64.encode(sid), start }
}

export interface TherapistPairingResult {
  exchangeId: string
  relRef: string
  /** The 64-byte intermediate session key — same bytes the owner derives iff the codes matched. */
  isk: Uint8Array
}

/** Therapist touches: fetch the owner's opening message, answer it, keep the key. */
export async function therapistAnswerPairing(
  args: { inviteId: string; secret: string; code: string },
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
  // The invite id in the CI is args.inviteId — from the link, never from the response.
  const responded = cpaceRespond(
    {
      prs: utf8(args.code),
      ci: channelIdentifier(body.relRef, args.inviteId),
      sid: b64.decode(body.sidB64),
    },
    b64.decode(body.msgAB64),
    AD_THERAPIST,
  )
  const respondRes = await doFetch(`${invitePath}/${encodeURIComponent(body.exchangeId)}/respond`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ secret: args.secret, msgBB64: b64.encode(responded.msgB) }),
  })
  if (respondRes.status !== 204) throw new Error(`pairing respond refused (${respondRes.status})`)
  return { exchangeId: body.exchangeId, relRef: body.relRef, isk: responded.isk }
}

export type OwnerCollectResult =
  | { state: 'waiting' }
  | { state: 'cancelled' }
  /** The owner opened a fresh run for this invite; this one was retired by that, unanswered. */
  | { state: 'superseded' }
  | { state: 'complete'; isk: Uint8Array }

/** Owner touch 3: look for the reply; when it is there, derive the key and close the exchange. */
export async function ownerCollectPairing(
  args: { relRef: string; code: string; bearerToken: string; pairing: OwnerPairingState },
  doFetch: FetchLike = fetch.bind(globalThis),
): Promise<OwnerCollectResult> {
  await initCpace()
  const base = `/v1/relations/${encodeURIComponent(args.relRef)}/pairing/${encodeURIComponent(args.pairing.exchangeId)}`
  const res = await doFetch(base, {
    method: 'GET',
    headers: { authorization: `Bearer ${args.bearerToken}` },
  })
  if (res.status !== 200) throw new Error(`pairing read refused (${res.status})`)
  const body = (await res.json()) as { state?: unknown; msgBB64?: unknown }
  const answered = body.state === 'RESPONDED' || body.state === 'CLOSED'
  if (args.pairing.finished && !answered) {
    // The store only ever moves an answered run forward (RESPONDED → CLOSED, or → CANCELLED);
    // a read that shows it waiting or retired again is a server contradicting its own record,
    // and the honest answer is a refusal, not a calm 'waiting' over a key already in hand.
    throw new Error('pairing read: state regressed after the key was derived')
  }
  if (body.state === 'OPEN') return { state: 'waiting' }
  if (body.state === 'CANCELLED') return { state: 'cancelled' }
  if (body.state === 'SUPERSEDED') return { state: 'superseded' }
  if (!answered) throw new Error('pairing read: malformed response')
  if (typeof body.msgBB64 !== 'string') throw new Error('pairing read: reply missing')
  let isk: Uint8Array
  if (args.pairing.finished) {
    // ONE RUN, ONE KEY: the server does not get a second reply into this run by returning a
    // different one on a later read. Refusing is the honest answer; nothing else is derived.
    if (args.pairing.finished.msgBB64 !== body.msgBB64) {
      throw new Error('pairing read: the reply changed after the key was derived')
    }
    isk = args.pairing.finished.isk
  } else {
    isk = cpaceFinish(
      {
        prs: utf8(args.code),
        ci: channelIdentifier(args.relRef, args.pairing.inviteId),
        sid: b64.decode(args.pairing.sidB64),
      },
      args.pairing.start,
      b64.decode(body.msgBB64),
    )
    args.pairing.finished = { msgBB64: body.msgBB64, isk }
  }
  if (body.state === 'RESPONDED') {
    // Bookkeeping only; the key is already derived, so a failed close costs a later retry of
    // an idempotent-shaped call, never the ceremony.
    await doFetch(`${base}/close`, {
      method: 'POST',
      headers: { authorization: `Bearer ${args.bearerToken}` },
    }).catch(() => undefined)
  }
  return { state: 'complete', isk }
}
