/*
 * The CPace exchange, carried through the Companion as opaque parcels (COMPANION_PAIRING.md §4),
 * and the approval that turns a matched code into an enrolment.
 *
 * Four touches, none simultaneous — this is store-and-forward, not a phone call:
 *
 *   owner:      ownerOpenPairing()       — derive MSGa from the code, post it, keep the scalar
 *   therapist:  therapistAnswerPairing() — fetch MSGa, derive MSGb + the key, seal their OFFER (E1)
 *                                          under the key, post both
 *   owner:      ownerCollectPairing()    — fetch MSGb, derive the same key, open the offer
 *   owner:      ownerApprovePairing()    — seal their OWN keys back (E2) and hand the server that
 *                                          envelope plus the enrol ticket the offer carried
 *   therapist:  therapistPairingStatus() — learn that it happened, take E2, then enrol
 *
 * BOTH DIRECTIONS REST ON THE CODE, since issue #101. E1 is what lets the owner pin the clinician;
 * E2 is what lets the clinician pin the owner, and it replaced a form where the owner's keys were
 * pasted in as base64 from wherever they happened to arrive. The two envelopes are sealed under
 * different keys derived from the same ISK (envelope.ts's direction labels), so neither can be
 * replayed as the other, and the server relays both without a key for either.
 *
 * THE CODE NEVER LEAVES THE DEVICE IT WAS TYPED ON. That is the hard invariant of
 * COMPANION_PAIRING.md §5 — a server holding the code could run the exchange itself and sit in the
 * middle — and in this module it is structural, not behavioral: the code goes into
 * cpaceStart/cpaceRespond as the PRS and nothing else reads it; every request body is built from
 * the OUTPUTS of those calls, which are uniform group elements the code cannot be recovered from,
 * or from ciphertext under the key they produce. relay.test.ts drives a whole pairing through a
 * recording transport and then greps every request — URL, headers, body — for the code in every
 * encoding it could wear; that test is the proof §5 there rests on, and removing this property
 * fails it.
 *
 * THE CODE IS CANONICAL BEFORE IT IS BYTES. Two people typing "the same code" produce the same
 * key only if both sides feed the PAKE the same bytes, and a trailing space, a lower-case letter
 * or a chat client's em dash would otherwise make two different keys with no signal at all —
 * indistinguishable from a wrong code, by design. So the code arrives here as a
 * CanonicalPairingCode (pairingCode.ts: eight upper-case symbols, check symbol verified) and is
 * checked AGAIN at runtime before it becomes bytes, because a cast is one keystroke and the bug
 * this repo keeps producing is a check that assumes its input. The collecting touch does not take
 * the code at all: cpaceFinish needs only the scalar, which is what lets the owner's persisted
 * half of a run (ownerRunStore.ts) omit the code by construction.
 *
 * WRONG CODE ≠ ERROR, here as everywhere in the pairing design (COMPANION_PAIRING.md §6):
 * mismatched codes produce two different keys and no signal on the wire. The mismatch surfaces
 * here, once, in the only place it can: the therapist's sealed offer fails to open on the owner's
 * side and comes back as `offer: null` — one bit, no diagnosis — and a person decides what that
 * means (the burn rule: only a human report kills an invite).
 *
 * WHAT THE OFFER IS, AND WHY IT IS ENOUGH. The therapist seals, under the key only a right code
 * derives, their two public keys, a name, and an enrol ticket THEY chose (payloads.ts). If the
 * owner opens it, the keys are the keys of whoever typed the code — pinned on that authority,
 * no read-aloud — and the ticket is a secret shared by exactly the two people who should hold
 * it. The owner then hands the ticket to the server on Approve. The server never sees the code
 * or the key; a link-holder who answered the run first produced an envelope the owner could not
 * open, so no ticket of theirs is ever forwarded; and the ticket appears on the wire in exactly
 * one request, the owner's approve, and in no response (relay.test.ts proves that too).
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
 * left to how often a caller polls. The pin survives a reload (ownerRunStore.ts carries it), so
 * the property holds across tabs as well as within one. The envelope needs no pin of its own:
 * a swapped envelope does not open, and one that opens was sealed under this run's key.
 */
import {
  initCpace,
  cpaceStart,
  cpaceRespond,
  cpaceFinish,
  cpaceResumeRespond,
  lvCat,
  type CpaceStartResult,
} from './cpace'
import { initEnvelope, openEnvelope, sealEnvelope } from './envelope'
import { isCanonicalPairingCode, type CanonicalPairingCode } from './pairingCode'
import {
  decodeOwnerKeys,
  decodeTherapistOffer,
  encodeOwnerKeys,
  encodeTherapistOffer,
  type OwnerKeysPayload,
  type TherapistOffer,
} from './payloads'

type FetchLike = typeof fetch

const utf8 = (s: string): Uint8Array => new TextEncoder().encode(s)

/**
 * How often the therapist's side asks whether the owner has decided. The server meters this poll
 * per PAIRING RUN rather than per address — a bucket that refills one poll every ten seconds — so
 * forty-five seconds is never refused however long the owner takes, and one person's waiting can
 * no longer spend another's on the same clinic connection. A 429 is still treated as "still
 * waiting", because that is all a throttled poll means. Mirrors PAIRING_STATUS_POLL_SECONDS on
 * the server.
 */
export const PAIRING_STATUS_POLL_MS = 45_000

/**
 * The pairing surface refused this connection for a while, and said until when.
 *
 * It carries the `Retry-After` the server sent, in seconds, because the screen's sentence names a
 * TIME — "paused until {time}. Your invitation is unchanged and will still open then" — and a
 * sentence like that is only worth saying if the number behind it came from the thing doing the
 * refusing.
 *
 * Only the CONNECTION's budget produces this. The other 429 on these routes is the invitation's
 * own lockout, which carries no Retry-After on purpose: that one is a fact about the invitation
 * and a different sentence, so a missing header is the signal rather than a gap to paper over.
 */
export class PairingPausedError extends Error {
  constructor(readonly retryAfterSeconds: number) {
    super('pairing paused')
    this.name = 'PairingPausedError'
  }
}

/**
 * The `Retry-After` as a positive whole number of seconds, or null when the header is absent or
 * is anything this code will not vouch for. Only the delta-seconds form is read: the HTTP-date
 * form would have the client trusting its own clock against the server's, which is exactly the
 * disagreement that makes a "come back at 4:05" message wrong for the person reading it.
 */
export function retryAfterSeconds(header: string | null): number | null {
  if (header === null) return null
  const trimmed = header.trim()
  if (!/^\d+$/.test(trimmed)) return null
  const seconds = Number(trimmed)
  if (!Number.isFinite(seconds) || seconds <= 0) return null
  return seconds
}

/** A 429 that named a time is the connection's pause; anything else stays what it was. */
function throwIfPaused(res: Response, what: string): void {
  if (res.status !== 429) return
  const seconds = retryAfterSeconds(res.headers.get('retry-after'))
  if (seconds !== null) throw new PairingPausedError(seconds)
  throw new Error(`pairing ${what} refused (429)`)
}

/**
 * The code as PAKE bytes. Takes the branded type and STILL checks it: the brand keeps a typed
 * string out at compile time, this keeps a cast out at run time. Throws before any request is
 * made, so a mistake here costs nothing on the wire.
 */
function prsBytes(code: CanonicalPairingCode): Uint8Array {
  if (!isCanonicalPairingCode(code)) {
    throw new Error('pairing: the code must be canonical (parsePairingCode) before it enters the ceremony')
  }
  return utf8(code)
}

/** The server origin plus any deployment base path, without a trailing slash; '' means same-origin root. */
const baseOf = (baseUrl: string | undefined): string => (baseUrl ?? '').replace(/\/+$/, '')

/**
 * The CPace channel identifier: version tag, relationship reference, invite id — length-
 * prefixed (the draft's lv_cat) rather than joined, so no separator can ever be mistaken for
 * structure. Version-tagged so a future change to any part of the construction changes the CI
 * and cleanly refuses to key against the old one, instead of half-agreeing. The owner's half on
 * the phone (COMPANION_PAIRING.md §14; not built: #174) must build these exact bytes.
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
   * COMPANION_PAIRING.md §3 (the owner finishes "the next time they look"), must keep it itself,
   * on the device, under its own protection (ownerRunStore.ts does, in sessionStorage). Losing it
   * makes the run unfinishable by anyone, and the remedy is a fresh run, which spends one of the
   * invite's exchanges.
   */
  start: CpaceStartResult
  /**
   * The reply that produced this run's key, once one has. A later read showing a different reply
   * is refused rather than re-derived — see ONE RUN, ONE KEY. Persistable (it is public bytes),
   * and restored by ownerRunStore.ts so the pin outlives the tab's memory.
   */
  pinnedMsgBB64?: string
  /** The derived key, cached in memory only. Never persisted; re-derived from the scalar after a reload. */
  finished?: { msgBB64: string; isk: Uint8Array }
}

/** Owner touch 1: derive MSGa from the code and post it for the invite. */
export async function ownerOpenPairing(
  args: { relRef: string; inviteId: string; code: CanonicalPairingCode; bearerToken: string; baseUrl?: string },
  doFetch: FetchLike = fetch.bind(globalThis),
): Promise<OwnerPairingState> {
  const prs = prsBytes(args.code)
  const sodium = await initCpace()
  const sid = sodium.randombytes_buf(16)
  const start = cpaceStart({ prs, ci: channelIdentifier(args.relRef, args.inviteId), sid }, AD_OWNER)
  const res = await doFetch(`${baseOf(args.baseUrl)}/v1/relations/${encodeURIComponent(args.relRef)}/pairing`, {
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
  /**
   * The run's half of the CPace transcript, so the clinician's side can survive a reload the way
   * the owner's does: the scalar is the one secret, the three messages are public bytes that were
   * already on the wire, and together they re-derive the ISK with no code and no stored key
   * (cpaceResumeRespond). A caller that persists any of this keeps it per tab and drops it when
   * the run ends; therapist/pairingAccept.ts does.
   */
  sidB64: string
  ybB64: string
  msgAB64: string
  msgBB64: string
}

/** What the clinician needs to open E2 later: the run's transcript, never the code and never the key. */
export interface TherapistRunKeying {
  sidB64: string
  ybB64: string
  msgAB64: string
  msgBB64: string
}

/**
 * The clinician's side of E2: re-derive this run's key from the transcript and open the owner's
 * envelope with it. Null for every failure — a run that is not this one, a tampered envelope, a
 * payload this build cannot read — because an AEAD failure is one bit and dressing it up would be
 * an invented diagnosis, exactly as on the owner's side.
 *
 * Re-derives rather than taking an ISK, so the reload case and the unbroken case run the same code
 * and there is one fewer place a key could be stored to save a multiplication.
 */
export function ownerKeysFromEnvelope(run: TherapistRunKeying, envB64: string): OwnerKeysPayload | null {
  let isk: Uint8Array
  let envelope: Uint8Array
  try {
    isk = cpaceResumeRespond(
      { sid: b64.decode(run.sidB64) },
      b64.decode(run.ybB64),
      b64.decode(run.msgAB64),
      b64.decode(run.msgBB64),
    )
    envelope = b64.decode(envB64)
  } catch {
    return null
  }
  const opened = openEnvelope(isk, run.sidB64, 'owner-to-therapist', envelope)
  return opened ? decodeOwnerKeys(opened) : null
}

/**
 * Therapist touches: fetch the owner's opening message, answer it, and seal the offer under the
 * key beside the reply. The offer is validated before the first request (an invalid one throws
 * here, never reaches the wire) and encrypted with the key the reply produces; the server stores
 * both and can read neither.
 */
export async function therapistAnswerPairing(
  args: {
    inviteId: string
    secret: string
    code: CanonicalPairingCode
    /**
     * Built AFTER the fetch, because the relationship reference is what the fetch is for and the
     * caller cannot make the offer without it: it is the key under which this browser's records are
     * filed, so it decides whether new keys should be generated at all (pairingAccept.ts refuses to
     * make a second set for a relationship it already holds). The offer is validated before it is
     * sealed, so a malformed one still never reaches the wire.
     */
    makeOffer: (relRef: string) => Promise<TherapistOffer>
    baseUrl?: string
  },
  doFetch: FetchLike = fetch.bind(globalThis),
): Promise<TherapistPairingResult> {
  // Before the first request: a code that is not canonical costs nothing on the wire.
  const prs = prsBytes(args.code)
  await initCpace()
  await initEnvelope()
  const invitePath = `${baseOf(args.baseUrl)}/v1/invite/${encodeURIComponent(args.inviteId)}/pairing`
  const fetchRes = await doFetch(`${invitePath}/fetch`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ secret: args.secret }),
  })
  throwIfPaused(fetchRes, 'fetch')
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
    { prs, ci: channelIdentifier(body.relRef, args.inviteId), sid: b64.decode(body.sidB64) },
    b64.decode(body.msgAB64),
    AD_THERAPIST,
  )
  // The AAD carries the sid exactly as the owner posted it, which is exactly as it came back.
  const offerBytes = encodeTherapistOffer(await args.makeOffer(body.relRef))
  const envelope = sealEnvelope(responded.isk, body.sidB64, 'therapist-to-owner', offerBytes)
  const respondRes = await doFetch(`${invitePath}/${encodeURIComponent(body.exchangeId)}/respond`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ secret: args.secret, msgBB64: b64.encode(responded.msgB), envB64: b64.encode(envelope) }),
  })
  throwIfPaused(respondRes, 'respond')
  if (respondRes.status !== 204) throw new Error(`pairing respond refused (${respondRes.status})`)
  return {
    exchangeId: body.exchangeId,
    relRef: body.relRef,
    isk: responded.isk,
    sidB64: body.sidB64,
    ybB64: b64.encode(responded.yb),
    msgAB64: body.msgAB64,
    msgBB64: b64.encode(responded.msgB),
  }
}

export type OwnerCollectResult =
  | { state: 'waiting' }
  | { state: 'cancelled' }
  /** The owner opened a fresh run for this invite; this one was retired by that, unanswered. */
  | { state: 'superseded' }
  /**
   * The reply is in and the key is derived. `offer` is the therapist's offer if their envelope
   * opened under this key, and null if it did not — a wrong code, a swapped envelope, a reply
   * from before envelopes existed: one bit, and the screen renders it as the question it is.
   */
  | { state: 'complete'; isk: Uint8Array; offer: TherapistOffer | null }

/**
 * Owner touch 3: look for the reply; when it is there, derive the key and open the offer.
 *
 * Takes no code. cpaceFinish needs the scalar, the sid and the two messages, and nothing else;
 * a run restored from ownerRunStore.ts after a reload finishes here without the owner having
 * to know the code any more. Writes nothing to the server: approval is a separate, human step.
 */
export async function ownerCollectPairing(
  args: { relRef: string; bearerToken: string; pairing: OwnerPairingState; baseUrl?: string },
  doFetch: FetchLike = fetch.bind(globalThis),
): Promise<OwnerCollectResult> {
  await initCpace()
  await initEnvelope()
  const base = `${baseOf(args.baseUrl)}/v1/relations/${encodeURIComponent(args.relRef)}/pairing/${encodeURIComponent(args.pairing.exchangeId)}`
  const res = await doFetch(base, {
    method: 'GET',
    headers: { authorization: `Bearer ${args.bearerToken}` },
  })
  if (res.status !== 200) throw new Error(`pairing read refused (${res.status})`)
  const body = (await res.json()) as { state?: unknown; msgBB64?: unknown; envB64?: unknown }
  const answered = body.state === 'RESPONDED' || body.state === 'CLOSED'
  const keyed = args.pairing.finished !== undefined || args.pairing.pinnedMsgBB64 !== undefined
  if (keyed && (body.state === 'OPEN' || body.state === 'SUPERSEDED')) {
    // The store only ever moves an answered run forward: RESPONDED → CLOSED, or → CANCELLED by
    // the owner's own Cancel, which is a legal step and reported below as one. A read that
    // shows the run waiting or retired AGAIN is a server contradicting its own record, and the
    // honest answer is a refusal, not a calm 'waiting' over a key already in hand. (The first
    // version of this guard refused CANCELLED too; the independent re-check caught that an
    // owner who cancels after a failed close would have been told the server lied.)
    throw new Error('pairing read: state regressed after the key was derived')
  }
  if (body.state === 'OPEN') return { state: 'waiting' }
  if (body.state === 'CANCELLED') return { state: 'cancelled' }
  if (body.state === 'SUPERSEDED') return { state: 'superseded' }
  if (!answered) throw new Error('pairing read: malformed response')
  if (typeof body.msgBB64 !== 'string') throw new Error('pairing read: reply missing')
  // ONE RUN, ONE KEY: the server does not get a second reply into this run by returning a
  // different one on a later read — in this tab or after a reload. Refusing is the honest
  // answer; nothing else is derived.
  if (args.pairing.pinnedMsgBB64 !== undefined && args.pairing.pinnedMsgBB64 !== body.msgBB64) {
    throw new Error('pairing read: the reply changed after the key was derived')
  }
  let isk: Uint8Array
  if (args.pairing.finished) {
    if (args.pairing.finished.msgBB64 !== body.msgBB64) {
      throw new Error('pairing read: the reply changed after the key was derived')
    }
    isk = args.pairing.finished.isk
  } else {
    isk = cpaceFinish({ sid: b64.decode(args.pairing.sidB64) }, args.pairing.start, b64.decode(body.msgBB64))
    args.pairing.finished = { msgBB64: body.msgBB64, isk }
    args.pairing.pinnedMsgBB64 = body.msgBB64
  }
  // The offer: opens under this key or it does not. Every failure is the same null.
  let offer: TherapistOffer | null = null
  if (typeof body.envB64 === 'string') {
    let envelope: Uint8Array | null = null
    try {
      envelope = b64.decode(body.envB64)
    } catch {
      envelope = null
    }
    const opened = envelope ? openEnvelope(isk, args.pairing.sidB64, 'therapist-to-owner', envelope) : null
    offer = opened ? decodeTherapistOffer(opened) : null
  }
  return { state: 'complete', isk, offer }
}

/**
 * Seal E2: the owner's own public keys, back down the same run, in the other direction.
 *
 * Sealed at APPROVE and nowhere earlier, because approving is the moment the owner has decided
 * this is the person they meant. A run they abandon after a mismatch never carries their keys
 * anywhere, which keeps "the owner said yes" and "the clinician learned the owner's keys" the same
 * event rather than two.
 *
 * Takes the ISK the collect step derived — the same one that opened E1 — so a clinician who can
 * open this envelope is by construction the clinician whose offer the owner just read.
 */
export function sealOwnerKeys(isk: Uint8Array, sidB64: string, keys: OwnerKeysPayload): string {
  return b64.encode(sealEnvelope(isk, sidB64, 'owner-to-therapist', encodeOwnerKeys(keys)))
}

/**
 * Owner touch 4: approve the reply, forwarding the enrol ticket the offer carried and the owner's
 * own keys sealed for the clinician. The server puts the invitation into REDEEMING and makes that
 * ticket the one it will honour, and holds the envelope until the clinician's next poll.
 *
 * The one request in the whole ceremony that carries the ticket; no response ever does. E2 travels
 * here once and comes back out once, on a status poll of a CLOSED run, and the server has no key
 * for it in between.
 */
export async function ownerApprovePairing(
  args: {
    relRef: string
    bearerToken: string
    exchangeId: string
    enrolTicketB64: string
    /** E2, sealed by sealOwnerKeys under this run's key. Required: the server refuses an approval without it. */
    envB64: string
    baseUrl?: string
  },
  doFetch: FetchLike = fetch.bind(globalThis),
): Promise<void> {
  const res = await doFetch(
    `${baseOf(args.baseUrl)}/v1/relations/${encodeURIComponent(args.relRef)}/pairing/${encodeURIComponent(args.exchangeId)}/approve`,
    {
      method: 'POST',
      headers: { 'content-type': 'application/json', authorization: `Bearer ${args.bearerToken}` },
      body: JSON.stringify({ enrolTicketB64: args.enrolTicketB64, envB64: args.envB64 }),
    },
  )
  if (res.status === 204) return
  if (res.status === 409) throw new Error('pairing approve: the run changed underneath the approval; read it again')
  throw new Error(`pairing approve refused (${res.status})`)
}

/**
 * The owner's Cancel — or, on an approved run nobody finished, the Abandon that puts the
 * invitation back. True when the server did it; false when there was nothing left to cancel.
 */
export async function ownerCancelPairing(
  args: { relRef: string; bearerToken: string; exchangeId: string; baseUrl?: string },
  doFetch: FetchLike = fetch.bind(globalThis),
): Promise<boolean> {
  const res = await doFetch(
    `${baseOf(args.baseUrl)}/v1/relations/${encodeURIComponent(args.relRef)}/pairing/${encodeURIComponent(args.exchangeId)}/cancel`,
    { method: 'POST', headers: { authorization: `Bearer ${args.bearerToken}` } },
  )
  if (res.status === 204) return true
  if (res.status === 410) return false
  throw new Error(`pairing cancel refused (${res.status})`)
}

export type TherapistStatusResult =
  | { state: 'waiting' }
  /**
   * The owner approved; enrol with the ticket that went into the offer. `scope` is what the
   * invitation grants. `envB64` is E2 — the owner's keys, sealed under this run's key — and it is
   * absent only when the owner's console approved before E2 existed, which reads as "no keys were
   * proved to me" rather than as an error.
   */
  | { state: 'approved'; scope: string[]; envB64?: string }
  /** Cancelled, retired, reported, expired, or never this run: one answer, so ask for a new code. */
  | { state: 'gone' }

/**
 * Therapist touch: has the owner decided? Poll at PAIRING_STATUS_POLL_MS. A 429 — rate limited,
 * or the invitation locked by somebody else's guesses — is "still waiting", not an error: the
 * ticket in the offer lives as long as the invitation, so waiting out a lockout costs nothing.
 */
export async function therapistPairingStatus(
  args: { inviteId: string; secret: string; exchangeId: string; baseUrl?: string },
  doFetch: FetchLike = fetch.bind(globalThis),
): Promise<TherapistStatusResult> {
  const res = await doFetch(
    `${baseOf(args.baseUrl)}/v1/invite/${encodeURIComponent(args.inviteId)}/pairing/${encodeURIComponent(args.exchangeId)}/status`,
    {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ secret: args.secret }),
    },
  )
  if (res.status === 429) return { state: 'waiting' }
  if (res.status === 410) return { state: 'gone' }
  if (res.status !== 200) throw new Error(`pairing status refused (${res.status})`)
  const body = (await res.json()) as { state?: unknown; scope?: unknown; envB64?: unknown }
  if (body.state === 'WAITING') return { state: 'waiting' }
  if (body.state === 'APPROVED') {
    const scope = Array.isArray(body.scope) ? body.scope.filter((s): s is string => typeof s === 'string') : []
    return typeof body.envB64 === 'string'
      ? { state: 'approved', scope, envB64: body.envB64 }
      : { state: 'approved', scope }
  }
  throw new Error('pairing status: malformed response')
}
