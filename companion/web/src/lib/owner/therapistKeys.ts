/*
 * OWNER SIDE of the therapist public-key exchange: read the two keys a therapist published for a
 * relationship, refuse anything that is not a key, and pin only what a person has confirmed with
 * their own ears.
 *
 * WHY THIS FILE EXISTS. Everything around it was already built and none of it was connected.
 * ShareBuilder.svelte seals a bundle to `therapist.boxPub`; assignments/share.ts refuses to seal to
 * a key that is not pinned; therapist/pinStore.ts records the pin across sessions so that refusal
 * is a real check rather than a value compared with itself; PinnedTherapistPicker.svelte lists the
 * therapists that have been pinned. And nothing in the repository ever fetched a therapist's public
 * keys — a grep for `therapistBoxPub` found the field being consumed everywhere and produced
 * nowhere. The only way a therapist's key had ever reached the console was an owner pasting
 * base64url into OwnerUnlock.svelte that a clinician had spelled out to them, which is a ceremony
 * almost nobody completes correctly and which several people would simply skip. So the owner had a
 * picker with nothing to pick, and the seal had no target.
 *
 * WHAT THE SERVER IS IN THIS EXCHANGE, AND WHAT IT IS NOT. It is a courier. It accepted two strings
 * from whoever held a valid therapist session, it stored them without being able to read anything
 * with them, and it hands the same two strings back to the owner's bearer token. It is not a
 * witness: it cannot tell a clinician's real key from one a hostile operator, a stolen session or a
 * tampered page substituted, and no property of this module should ever be written as though it
 * could. THE RESPONSE IS HOSTILE INPUT. Every value below arrives from a machine with both the
 * means and the position to lie, and the only reason it is safe to ask it for a key at all is that
 * a person checks the fingerprint on a channel the server is not on before anything is pinned to
 * it. That check is `acceptTherapistKeys` at the bottom of this file, and it is the whole point of
 * the file; the fetching above it is plumbing.
 *
 * WHY THE VALIDATION IS ABOUT SHAPE AND NEVER ABOUT TRUST. `parseTherapistKeyRecord` refuses
 * anything that does not decode to exactly 32 bytes, and that is a check on the STORE, not on the
 * key: 32 bytes an attacker chose passes it, and is meant to. What it buys is that a malformed
 * value cannot get as far as being pinned, sealed to, or written into a fingerprint the owner then
 * reads out loud — a key that is 31 bytes long surfaces here as a refusal with a sentence attached,
 * rather than three screens later as a seal that fails with nothing to explain it.
 *
 * WHY THERE IS NO relRef DERIVATION IN THIS FILE. Callers pass the relRef; they compute it from the
 * relationship's inbox token with `relRefOf` (sync/portal.ts), which is how InvitePanel already does
 * it. This module deliberately never sees the inbox token: it has no request that needs one — the
 * read below is authorized by the owner's bearer token alone — and a module that accepts a secret it
 * does not use is a module that will eventually send it somewhere.
 *
 * NOTHING HERE LOGS. Not the keys, not the fingerprints, not the relRef, not a length. The rest of
 * src/lib contains no console call either; this file does not start the practice.
 */
import _sodium from 'libsodium-wrappers-sumo'
import { fingerprint } from '../assignments/crypto'
import { initShareCrypto, type PublicIdentity, type PinStore } from '../share/pairing'
import { fromBase64 } from '../share/sharecrypto'
import { pinOnFirstUse, pendingRotation } from '../therapist/pinStore'

/** Both keys are raw 32-byte public keys — X25519 for sealing, Ed25519 for signing. */
export const PUBLIC_KEY_BYTES = 32

/**
 * The longest base64url string this module will even attempt to decode.
 *
 * An unpadded 32-byte key is 43 characters, so this is generous room and still refuses an
 * obviously-wrong input without allocating for it. It is NOT a bound on what the transport already
 * buffered — `fetch` has read and parsed the whole body before this code runs, and pretending
 * otherwise would be the kind of half-control this codebase keeps arguing against. It bounds what
 * gets decoded, which is the part this file is responsible for.
 */
const MAX_KEY_B64_CHARS = 64

/** Anything this module refuses, in one type the caller can render straight to the person. */
export class TherapistKeyError extends Error {
  constructor(message: string, readonly status?: number) {
    super(message)
  }
}

/** The two public keys a therapist published, decoded and length-checked. */
export interface TherapistKeyRecord {
  /** X25519 — the key shares are sealed TO. */
  boxPub: Uint8Array
  /** Ed25519 — the key assignments and game plans are verified AGAINST. */
  signPub: Uint8Array
  /**
   * When the server says it recorded them, in epoch milliseconds.
   *
   * The server's word, carried through unchanged and labelled as such wherever it is shown. Nothing
   * in this system attests to it: a server willing to substitute a key is equally willing to
   * backdate the moment it did so. It is here because "these have been on file since March" is
   * useful context for a person deciding whether to expect a new key at all — not because it is
   * evidence of anything.
   */
  registeredAt: number
}

/** Where to send the read, and what to authorize it with. */
export interface OwnerEndpoint {
  /** Base URL of the companion server. Empty string means the origin that served this page. */
  baseUrl: string
  /** The owner's bearer access token — the same one PortalClient sends on every owner call. */
  token: string
}

type FetchLike = typeof fetch

/** The keys as a peer identity, in the vocabulary pairing.ts and pinStore.ts speak. */
export function peerOf(record: TherapistKeyRecord): PublicIdentity {
  return { x25519Pub: record.boxPub, ed25519Pub: record.signPub }
}

/** The two fingerprints a person compares: the seal target first, the identity key second. */
export function keyFingerprints(record: TherapistKeyRecord): { boxFp: string; signFp: string } {
  return { boxFp: fingerprint(record.boxPub), signFp: fingerprint(record.signPub) }
}

/**
 * Read the keys registered for [relRef]. Null means nothing has been registered yet.
 *
 * WHY 404 IS A VALUE AND NOT AN ERROR. "The therapist has not published their keys yet" is the
 * ordinary state of every relationship between the invite being minted and the clinician finishing
 * their side, and it is the answer this route gives most often in the days after pairing. Throwing
 * for it would put a red message in front of an owner who has done nothing wrong and has nothing to
 * fix, and — worse — would make waiting look like the same kind of event as a server refusing the
 * token, which it is not. The caller has to distinguish "not yet" from "something is wrong" because
 * the two have opposite next steps: wait, versus stop.
 *
 * Bearer token only, no X-Rel-Token: this route is gated exactly as POST /v1/invite is. The thing
 * being read is two public keys, which open nothing.
 */
export async function fetchTherapistKeys(
  endpoint: OwnerEndpoint,
  relRef: string,
  doFetch: FetchLike = fetch,
): Promise<TherapistKeyRecord | null> {
  await initShareCrypto()
  const base = endpoint.baseUrl.replace(/\/+$/, '')
  const res = await doFetch(`${base}/v1/relations/${encodeURIComponent(relRef)}/therapist-keys`, {
    headers: { Authorization: `Bearer ${endpoint.token}` },
  })
  if (res.status === 404) return null
  if (res.status === 401 || res.status === 403) {
    // Deliberately one sentence for both. The server answers this surface non-enumeratingly and
    // this module is not going to invent a distinction it was not given.
    throw new TherapistKeyError(
      'the server did not accept this owner access token for that relationship — nothing was read',
      res.status,
    )
  }
  if (!res.ok) {
    throw new TherapistKeyError('the server would not hand over the published keys', res.status)
  }
  let body: unknown
  try {
    body = await res.json()
  } catch {
    // A 200 that is not JSON is not a key, and there is nothing to salvage from it. Note that the
    // body is not quoted into the message: whatever a hostile server puts in it, an owner should
    // never be reading it in this console's voice.
    throw new TherapistKeyError('the server answered with something that is not a key record')
  }
  return parseTherapistKeyRecord(body)
}

/**
 * Turn a parsed JSON body into a record, or refuse it.
 *
 * PRECONDITION: libsodium is initialized (`initShareCrypto()`), because the decode and the
 * fingerprints below are its. `fetchTherapistKeys` awaits it; a direct caller must too. The
 * readiness check is explicit rather than left to fall through the decoder, so an uninitialized
 * console reports itself as an uninitialized console instead of blaming the server for a bad key.
 *
 * Unknown fields are ignored rather than refused. A later server version that adds one is not
 * lying, and a parser that rejects what it has not been told about turns every additive change into
 * a coordinated release.
 */
export function parseTherapistKeyRecord(body: unknown): TherapistKeyRecord {
  if (typeof _sodium.from_base64 !== 'function') {
    throw new TherapistKeyError('this console has not finished starting its crypto — nothing was read')
  }
  if (typeof body !== 'object' || body === null || Array.isArray(body)) {
    throw new TherapistKeyError('the server answered with something that is not a key record')
  }
  const fields = body as Record<string, unknown>
  const boxPub = decodePublicKey(fields.boxPubB64)
  const signPub = decodePublicKey(fields.signPubB64)
  if (boxPub === null || signPub === null) {
    // One message for both keys and for every way either can be wrong — a wrong length, a
    // character outside the alphabet, a number where a string belongs, a field that is absent.
    // Which one it was tells an owner nothing they can act on, and the honest thing to say is the
    // consequence: this is not something to pin, and nothing has been.
    throw new TherapistKeyError(
      'the keys the server returned are not the right shape for public keys, so nothing was recorded — a key must be exactly 32 bytes and these are not',
    )
  }
  const registeredAt = fields.registeredAt
  if (typeof registeredAt !== 'number' || !Number.isFinite(registeredAt) || registeredAt < 0) {
    // Refusing the whole record over the decorative field is deliberate. The alternative is
    // displaying two thirds of a record and silently dropping the third, which means the screen is
    // showing something this module could not account for — and the one habit worth keeping when
    // reading from a machine that may be lying is that a value is either understood or refused.
    throw new TherapistKeyError('the server returned a registration time that is not a time, so the record was refused')
  }
  return { boxPub, signPub, registeredAt }
}

/**
 * Decode a base64url public key, returning null unless it is EXACTLY [PUBLIC_KEY_BYTES] long.
 *
 * Every rejection collapses to null and the caller turns all of them into one refusal, mirroring
 * the server's own decoder. libsodium's URLSAFE_NO_PADDING decoder throws on a character outside
 * the alphabet and on padding it was not expecting, which is why the whole call sits in a catch:
 * a throw here means "not base64url", not "something went wrong".
 */
function decodePublicKey(value: unknown): Uint8Array | null {
  if (typeof value !== 'string' || value.length === 0 || value.length > MAX_KEY_B64_CHARS) return null
  let bytes: Uint8Array
  try {
    bytes = fromBase64(value)
  } catch {
    return null
  }
  return bytes.length === PUBLIC_KEY_BYTES ? bytes : null
}

/**
 * What happened when a fetched key was offered to the pin store.
 *
 * Three values rather than a boolean, and none of them is a failure, because the three have
 * genuinely different next steps and a screen that collapses them would have to lie about at least
 * one. Returned rather than thrown for the same reason therapist/session.ts returns
 * `already-registered`: a caller cannot retry its way out of any of these, and the surface showing
 * the outcome has to say which one it is.
 */
export type KeyAcceptance =
  /** Nothing was on file for this therapist; the fetched keys are now the record. Save the store. */
  | 'pinned-now'
  /** These exact keys were already on file. Nothing changed, and nothing needed to. */
  | 'already-pinned'
  /**
   * This therapist is on file under a DIFFERENT encryption key. Nothing was changed and nothing
   * will be from here: replacing a pinned key is the one thing the pin exists to make expensive,
   * and it belongs to PinRecord.svelte's rotation ceremony, which costs its own out-of-band check.
   */
  | 'differs-from-pin'

/** What the owner typed back after hearing each fingerprint read out to them. */
export interface TypedFingerprints {
  /** The X25519 fingerprint — the key shares are sealed to. */
  boxFp: string
  /** The Ed25519 fingerprint — the key this console files the therapist under. */
  signFp: string
}

/**
 * The shortest a computed fingerprint may plausibly be. `fingerprint()` emits 22 characters of
 * base64url, so anything under this is a broken crypto layer rather than a short answer — and the
 * check has to fail on it, because an expected value of '' would otherwise equal the '' of an owner
 * who typed nothing and the confirmation would be confirming itself. That is the exact tautology
 * therapist/pinStore.ts was written to remove from the seal path.
 */
const MIN_FINGERPRINT_CHARS = 16

/**
 * The confirmation predicate, exported so a button's enabled state and the refusal below are the
 * same test rather than two that can drift apart.
 *
 * BOTH FINGERPRINTS, AND NEITHER IS OPTIONAL. It is tempting to gate on the encryption key alone —
 * it is the one shares are sealed to, and it is the one a prior pin nails down — but the two keys
 * arrive in ONE JSON body from a machine that can compose it freely, so a server that keeps the
 * clinician's real signing key and swaps only the encryption key would sail through a signing-only
 * check, and one that keeps the real encryption key and swaps the signing key would sail through an
 * encryption-only check. The first of those hands an attacker every share the owner ever seals; the
 * second lets them author assignments and game plans the owner opens as their therapist's. A pin
 * records the PAIR, so the confirmation has to cover the pair.
 *
 * It also happens to close the gap therapist/pinStore.ts documents at the bottom of its header: a
 * therapist's identity in this console is derived from their signing fingerprint, so a swapped
 * signing key reads as a new therapist and gets trust-on-first-use like any newcomer, with nothing
 * to compare it against. Hearing that fingerprint from the person themselves is the comparison.
 *
 * WHITESPACE IS NOT PART OF A FINGERPRINT AND CASE IS. Spaces come from how the characters were
 * grouped on screen — the therapist's acceptance page reads them out in fours — and from how the
 * owner typed them back, so they are stripped everywhere rather than only at the ends. Case is left
 * alone: base64url distinguishes `k` from `K`, and folding them would be this console deciding that
 * a difference between the fingerprint it computed and the one the clinician read out does not
 * matter. It is not in a position to decide that about any part of a fingerprint.
 */
export function confirmationMatches(record: TherapistKeyRecord, typed: TypedFingerprints): boolean {
  const expected = keyFingerprints(record)
  return fingerprintHeard(expected.boxFp, typed.boxFp) && fingerprintHeard(expected.signFp, typed.signFp)
}

function fingerprintHeard(expected: string, typed: string): boolean {
  const want = stripSpaces(expected)
  if (want.length < MIN_FINGERPRINT_CHARS) return false
  return want === stripSpaces(typed)
}

/**
 * A fingerprint in reading groups, for a person saying it out loud one chunk at a time.
 *
 * LOSSLESS by construction — `groupFingerprint(v).join('') === v` — so what is spoken is the whole
 * value and never a summary of it, and the check above strips the grouping back out. Four to a
 * group, matching the therapist acceptance page's own reading groups, so the two people in the
 * conversation are chunking the same characters the same way rather than one reading in fours while
 * the other follows in threes.
 *
 * Written here rather than imported from therapist/inviteAccept.ts, which has the same helper: that
 * module is the enrolment ceremony and pulls the key store, the portal client and the invite parser
 * in behind it, none of which belongs in the owner console's bundle for the sake of a slice()
 * loop. The contract that matters is the group size, and it is stated in both places.
 */
export function groupFingerprint(value: string, size = 4): string[] {
  const out: string[] = []
  for (let i = 0; i < value.length; i += size) out.push(value.slice(i, i + size))
  return out
}

function stripSpaces(s: string): string {
  return s.replace(/\s+/g, '')
}

/**
 * Pin the fetched keys — but only after a person has confirmed the fingerprint out of band.
 *
 * WHY THIS TAKES WHAT THE OWNER TYPED AND NOT A BOOLEAN. The server that relayed these keys also
 * serves this page. A confirmation it can satisfy is not a confirmation: a "yes, trust it" button
 * is something a tampered page can style, pre-select or click, and a screen-to-screen comparison
 * proves only that one machine agrees with itself. What the server cannot supply is the clinician's
 * voice on a channel this app is not on, so the check is the characters the owner heard from them.
 *
 * WHY IT COMPUTES THE EXPECTED VALUES ITSELF. A caller that could pass in the expected fingerprints
 * is a caller that could pass in the typed ones, and the gate would be back to comparing a value
 * with itself — which is precisely the bug therapist/pinStore.ts exists because of. So the only
 * thing a caller supplies here is what the owner heard, and both halves of it are checked; see
 * confirmationMatches above for why one half would not be enough.
 *
 * REFUSING COSTS NOTHING AND MUST CHANGE NOTHING. Every path that does not reach the final line
 * leaves `pins` exactly as it was found — a throw, a mismatch, an unreadable phrase, a key that
 * disagrees with the record. Only 'pinned-now' mutates, and even then nothing is persisted until
 * the caller saves the store, so a person who closes the tab has pinned nothing.
 */
export function acceptTherapistKeys(
  pins: PinStore,
  record: TherapistKeyRecord,
  typed: TypedFingerprints,
  now: number = Date.now(),
): KeyAcceptance {
  if (!confirmationMatches(record, typed)) {
    throw new TherapistKeyError(
      'those are not the fingerprints of the keys this console was handed — nothing was pinned, and nothing has been sealed to them. If they read out something different, that is the check working: stop here and reach them another way.',
    )
  }
  // Asked BEFORE pinning rather than inferred from the outcome afterwards. pinOnFirstUse leaves an
  // existing record alone, so a therapist already on file under another encryption key would come
  // back as the reassuring 'already-pinned' while the console quietly held two different keys for
  // them — which is the substitution this whole path exists to make visible.
  if (pendingRotation(pins, peerOf(record)) !== null) return 'differs-from-pin'
  return pinOnFirstUse(pins, peerOf(record), now) === 'pinned-now' ? 'pinned-now' : 'already-pinned'
}
