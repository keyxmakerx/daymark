/*
 * The negotiation — what the pairing ceremony is actually FOR (plan §3.7.3, steps 5 and 6).
 *
 * CPace produces a key; the relay carries opaque blobs; the envelope seals things under that
 * key. This module is the one that knows what those things ARE: each side's two public keys and
 * the capability set the relationship is being opened with. It composes the three layers into
 * the two calls the interface makes — ownerRunPairing and therapistRunPairing.
 *
 * WHAT THIS REPLACES, AND WHY IT IS BETTER RATHER THAN JUST NEWER. Today the clinician's public
 * keys travel over a plain route and the OWNER authenticates them by reading a fingerprint
 * aloud and comparing it with what the clinician reads back — four groups of characters, twice,
 * in both directions, and a ceremony that is skipped or fumbled the moment anybody is busy. The
 * fingerprint was doing real work: the server relays those keys and vouches for none of them,
 * so without an out-of-band comparison a hostile or compromised server could substitute a key
 * and read everything sealed to it afterwards.
 *
 * A PAKE moves that work into the protocol. Both people share ONE short code, spoken once. The
 * key derived from it exists only if both sides had the same code, so an envelope that opens is
 * proof the other end is the person who was told the code — and the keys inside it arrive
 * authenticated by that fact, not by anyone's eyesight. Six characters, once, replacing
 * fingerprint comparison in both directions.
 *
 * WHAT IT DOES NOT REPLACE, said plainly because the temptation to over-claim here is strong. A
 * PAKE excludes the server from the PROTOCOL. It does not exclude a fully compromised server
 * from the CODE IT SERVES to the owner's browser (plan §3.7.5) — a Companion that wanted to
 * could ship JavaScript that reads the code out of the input box. That is why the phone
 * eventually holds the owner's half (§3.6.4 condition 2, stage 4.0b), and why the fingerprint
 * is DEMOTED rather than deleted: it remains on the connections surface for anyone who wants to
 * verify out of band later.
 *
 * THE PAYLOAD SCHEMA IS VERSIONED AND STRICT. Everything crossing this boundary arrives from a
 * machine with both the means and the position to lie — the envelope proves it came from
 * someone holding the code, not that what is inside is well-formed — so every field is checked
 * for shape before it is used, and an unknown version is refused rather than best-guessed.
 */
import { initEnvelope, openEnvelope, sealEnvelope, type Direction } from './envelope'
import {
  ownerCollectPairing,
  ownerOpenPairing,
  therapistAnswerPairing,
  therapistCollectOwnerHalf,
  type OwnerPairingState,
} from './relay'

type FetchLike = typeof fetch

/** Both keys are raw 32-byte public keys — X25519 for sealing, Ed25519 for signing. */
export const PUBLIC_KEY_BYTES = 32

/** Bumped whenever the shape below changes in a way an older peer would misread. */
export const NEGOTIATION_VERSION = 1

/** One side's half of the negotiation, as it travels inside a sealed envelope. */
export interface NegotiationHalf {
  v: number
  /** X25519 public key, base64url — what the other side seals to. */
  boxPubB64: string
  /** Ed25519 public key, base64url — what the other side verifies signatures against. */
  signPubB64: string
  /**
   * What this relationship is being opened with. Carried so the two sides agree on it inside
   * the channel rather than trusting a server-held copy — connecting and sharing stay separate
   * acts (§3.10), so this is the shape of the connection, never a grant of anybody's journal.
   */
  scope: string[]
}

const b64 = {
  encode(bytes: Uint8Array): string {
    let s = ''
    for (const b of bytes) s += String.fromCharCode(b)
    return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
  },
  decode(text: string): Uint8Array {
    const raw = atob(text.replace(/-/g, '+').replace(/_/g, '/'))
    const out = new Uint8Array(raw.length)
    for (let i = 0; i < raw.length; i++) out[i] = raw.charCodeAt(i)
    return out
  },
}

function isKey(value: unknown): value is string {
  if (typeof value !== 'string' || value.length === 0 || value.length > 64) return false
  if (!/^[A-Za-z0-9_-]+$/.test(value)) return false
  try {
    return b64.decode(value).length === PUBLIC_KEY_BYTES
  } catch {
    return false
  }
}

/** Serialize a half for sealing. */
export function encodeHalf(half: Omit<NegotiationHalf, 'v'>): Uint8Array {
  return new TextEncoder().encode(JSON.stringify({ v: NEGOTIATION_VERSION, ...half }))
}

/**
 * Read a half that came out of an envelope, or return null.
 *
 * Null for every rejection, with no detail about which check failed: the caller renders one
 * calm sentence either way, and a peer that is malformed on purpose learns nothing from being
 * told precisely how.
 */
export function decodeHalf(bytes: Uint8Array): NegotiationHalf | null {
  let parsed: unknown
  try {
    parsed = JSON.parse(new TextDecoder().decode(bytes))
  } catch {
    return null
  }
  if (typeof parsed !== 'object' || parsed === null) return null
  const half = parsed as Record<string, unknown>
  if (half.v !== NEGOTIATION_VERSION) return null
  if (!isKey(half.boxPubB64) || !isKey(half.signPubB64)) return null
  if (!Array.isArray(half.scope) || half.scope.some((s) => typeof s !== 'string')) return null
  if (half.scope.length > 32) return null
  return {
    v: NEGOTIATION_VERSION,
    boxPubB64: half.boxPubB64,
    signPubB64: half.signPubB64,
    scope: half.scope as string[],
  }
}

/**
 * Why the two directions are named here rather than at the call sites: getting one backwards
 * would produce an envelope that never opens, at the far end, in someone else's browser — the
 * least debuggable place a constant can be wrong.
 */
const OWNER_SEALS: Direction = 'owner-to-therapist'
const THERAPIST_SEALS: Direction = 'therapist-to-owner'

export type OwnerPairingOutcome =
  /** The clinician has not answered yet. Nothing has gone wrong. */
  | { state: 'waiting'; pairing: OwnerPairingState }
  /** The owner cancelled this exchange, or a newer one superseded it. */
  | { state: 'cancelled' }
  /**
   * They answered, and the envelope did not open. The codes differed — which is a mistyped
   * character or somebody who should not be there, and this software cannot tell those apart.
   * The caller asks a person, and NOTHING is burned: a fresh exchange costs one more phone
   * call, where an automatic verdict here would hand any link-holder a veto (§3.9.1).
   */
  | { state: 'code-mismatch' }
  /** Opened, well-formed: the clinician's keys, authenticated by the fact that it opened. */
  | { state: 'complete'; half: NegotiationHalf }

/** The owner's first touch: mint the exchange. The code is generated by the CALLER and shown
 *  to a person; it is passed in here and never leaves the device. */
export async function ownerBeginPairing(
  args: { relRef: string; inviteId: string; code: string; bearerToken: string },
  doFetch: FetchLike = fetch.bind(globalThis),
): Promise<OwnerPairingState> {
  await initEnvelope()
  return ownerOpenPairing(args, doFetch)
}

/**
 * The owner's later touches: look for the reply and, when it comes, publish this side's half
 * and read theirs. Safe to call repeatedly while waiting — that is what 'waiting' is for.
 */
export async function ownerCompletePairing(
  args: {
    relRef: string
    code: string
    bearerToken: string
    pairing: OwnerPairingState
    /** This side's public halves, to seal for the clinician. */
    own: Omit<NegotiationHalf, 'v'>
  },
  doFetch: FetchLike = fetch.bind(globalThis),
): Promise<OwnerPairingOutcome> {
  await initEnvelope()
  const collected = await ownerCollectPairing(
    {
      relRef: args.relRef,
      code: args.code,
      bearerToken: args.bearerToken,
      pairing: args.pairing,
      seal: (isk, sidB64) => sealEnvelope(isk, sidB64, OWNER_SEALS, encodeHalf(args.own)),
    },
    doFetch,
  )
  if (collected.state === 'waiting') return { state: 'waiting', pairing: args.pairing }
  if (collected.state === 'cancelled') return { state: 'cancelled' }
  if (!collected.therapistHalf) return { state: 'code-mismatch' }
  const opened = openEnvelope(
    collected.isk,
    args.pairing.sidB64,
    THERAPIST_SEALS,
    collected.therapistHalf,
  )
  if (!opened) return { state: 'code-mismatch' }
  const half = decodeHalf(opened)
  // A half that opened but does not parse is a peer speaking a shape we do not know — an older
  // or newer client, not an attacker, since forging an opening envelope needs the code. It is
  // still refused, and it reads to the person as the same "try again" as a mismatch, because
  // the remedy is identical and a version lecture helps nobody mid-ceremony.
  if (!half) return { state: 'code-mismatch' }
  return { state: 'complete', half }
}

/**
 * Everything the clinician's side needs to come back to an exchange it already answered.
 *
 * The key is in here, and it has to be: one reply per exchange, ever, so a clinician whose page
 * reloaded between answering and the owner closing cannot simply run the ceremony again — the
 * relay would refuse, correctly, and the person would be stuck asking for a fresh invitation
 * because a browser tab closed. Callers that persist this are holding a live session key and
 * should treat it as one: same lifetime as the ceremony, gone when it ends.
 */
export interface TherapistResumeState {
  exchangeId: string
  relRef: string
  sidB64: string
  isk: Uint8Array
}

export type TherapistPairingOutcome =
  | { state: 'code-mismatch' }
  | { state: 'awaiting-owner'; resume: TherapistResumeState }
  | { state: 'complete'; exchangeId: string; relRef: string; half: NegotiationHalf }

/**
 * The clinician's whole side: fetch the owner's opening message, answer it with this side's
 * sealed half, then collect theirs.
 *
 * The 'awaiting-owner' outcome is not a failure — the owner has to open their console for the
 * fourth touch to exist, and store-and-forward means that can be minutes or hours later. The
 * caller shows what is happening and offers to look again.
 */
export async function therapistRunPairing(
  args: {
    inviteId: string
    secret: string
    code: string
    /** This side's freshly generated public halves, to seal for the owner. */
    own: Omit<NegotiationHalf, 'v'>
  },
  doFetch: FetchLike = fetch.bind(globalThis),
): Promise<TherapistPairingOutcome> {
  await initEnvelope()
  let sidB64 = ''
  const answered = await therapistAnswerPairing(
    {
      inviteId: args.inviteId,
      secret: args.secret,
      code: args.code,
      seal: (isk, sid) => {
        sidB64 = sid
        return sealEnvelope(isk, sid, THERAPIST_SEALS, encodeHalf(args.own))
      },
    },
    doFetch,
  )
  const resume: TherapistResumeState = {
    exchangeId: answered.exchangeId,
    relRef: answered.relRef,
    sidB64,
    isk: answered.isk,
  }
  return therapistCollect({ inviteId: args.inviteId, secret: args.secret, resume }, doFetch)
}

/**
 * Collect the owner's half for an exchange this side already answered — the fourth touch, and
 * the resume path for a clinician who came back later.
 *
 * Shared by therapistRunPairing rather than duplicated, so the first attempt and the tenth take
 * exactly the same code path; a resume that drifted from the original would be a bug nobody
 * sees until somebody's browser reloads at the wrong moment.
 */
export async function therapistCollect(
  args: { inviteId: string; secret: string; resume: TherapistResumeState },
  doFetch: FetchLike = fetch.bind(globalThis),
): Promise<TherapistPairingOutcome> {
  await initEnvelope()
  const { resume } = args
  const ownerHalf = await therapistCollectOwnerHalf(
    { inviteId: args.inviteId, secret: args.secret, exchangeId: resume.exchangeId },
    doFetch,
  )
  if (!ownerHalf) return { state: 'awaiting-owner', resume }
  const opened = openEnvelope(resume.isk, resume.sidB64, OWNER_SEALS, ownerHalf)
  if (!opened) return { state: 'code-mismatch' }
  const half = decodeHalf(opened)
  if (!half) return { state: 'code-mismatch' }
  return { state: 'complete', exchangeId: resume.exchangeId, relRef: resume.relRef, half }
}
