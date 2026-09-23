/*
 * What travels inside the pairing envelope (COMPANION_PAIRING.md §13.4), pinned down to bytes.
 *
 * TWO MESSAGES, ONE EACH WAY, AND THE CODE IS THE AUTHORITY FOR BOTH.
 *
 * E1, therapist → owner, sealed under the key only a matching code derives: their two public keys,
 * a name to show the owner, and the enrolment ticket they chose. The owner, on Approve, hands that
 * ticket to the server in the clear (the server is the party that will honour it; it never sees
 * the code or the key), and the therapist already holds it.
 *
 * E2, owner → therapist, sealed at Approve under the same run's key in the OTHER direction: the
 * owner's two public keys, and nothing else. This file used to say there could be no second
 * payload, because the owner had no durable keys to put in one — OwnerUnlock.svelte minted a fresh
 * pair every visit. owner/identity.ts ended that: the owner's identity is derived from their master
 * key and is the same identity every session, so the second direction can rest on the code exactly
 * as the first does. That is what retires issue #101's hand-pasted owner keys. What a clinician
 * pins is no longer as good as the channel a base64 string arrived on; it is as good as the code
 * the owner spoke.
 *
 * THE TWO NEVER MIX. The envelope's AAD carries the direction (envelope.ts), so a reflected E1 does
 * not open as an E2 under any key, and each decoder below refuses the other's shape field for
 * field. The `v` inside each payload is its own, so a future third thing is a refusal to open old
 * bytes rather than a misreading of them.
 *
 * THE OFFER IS AUTHENTICATED BY OPENING, NOT BY ANYTHING INSIDE IT. If the envelope opens, the
 * bytes were sealed by someone holding the ISK, which means someone who typed the code. The
 * public keys inside are therefore the keys of the person the owner gave the code to, and the
 * owner pins them on that authority alone (owner/therapistKeys.ts, pinFromPairing). No signature
 * inside the offer could add to that; a link-holder without the code cannot produce an envelope
 * that opens, and a server cannot alter one without it failing to open.
 *
 * STRICT ON THE WAY IN. Unknown `v`, a missing field, an extra field, a key of the wrong length,
 * a ticket of the wrong length, a name with control or bidi characters: each is refused as a
 * whole, never repaired. Forward compatibility is `v`, not leniency, because the recurring bug
 * in this repo is a check that assumed its input.
 */

export const OFFER_VERSION = 1
export const DISPLAY_NAME_MAX_CODEPOINTS = 64
const KEY_BYTES = 32
export const ENROL_TICKET_BYTES = 32

/** The therapist's offer, as the owner sees it after the envelope opens. */
export interface TherapistOffer {
  /** X25519 public key, base64url, 32 bytes. */
  boxPubB64: string
  /** Ed25519 public key, base64url, 32 bytes. */
  signPubB64: string
  /** What they typed as their name. Shown labelled as such, never trusted for anything else. */
  displayName: string
  /** 32 random bytes the therapist chose; the owner forwards it on Approve, the therapist enrols with it. */
  enrolTicketB64: string
}

const B64URL = /^[A-Za-z0-9_-]*$/

function decodedLength(b64: string): number | null {
  if (!B64URL.test(b64)) return null
  const std = b64.replace(/-/g, '+').replace(/_/g, '/')
  try {
    return atob(std).length
  } catch {
    return null
  }
}

/** A name the owner's screen can show: at most 64 code points, no control, format or bidi characters. */
export function validDisplayName(name: string): boolean {
  if (typeof name !== 'string') return false
  if (Array.from(name).length > DISPLAY_NAME_MAX_CODEPOINTS) return false
  return !/\p{C}/u.test(name)
}

/** Every field, every length; the reason a decode can return null. */
export function validTherapistOffer(offer: unknown): offer is TherapistOffer {
  if (typeof offer !== 'object' || offer === null) return false
  const o = offer as Record<string, unknown>
  const keys = Object.keys(o).sort()
  if (keys.join(',') !== 'boxPubB64,displayName,enrolTicketB64,signPubB64') return false
  if (typeof o.boxPubB64 !== 'string' || decodedLength(o.boxPubB64) !== KEY_BYTES) return false
  if (typeof o.signPubB64 !== 'string' || decodedLength(o.signPubB64) !== KEY_BYTES) return false
  if (typeof o.enrolTicketB64 !== 'string' || decodedLength(o.enrolTicketB64) !== ENROL_TICKET_BYTES) return false
  if (typeof o.displayName !== 'string' || !validDisplayName(o.displayName)) return false
  return true
}

/** The bytes to seal. Throws on an offer this module would refuse to decode: nothing invalid is ever sent. */
export function encodeTherapistOffer(offer: TherapistOffer): Uint8Array {
  if (!validTherapistOffer(offer)) throw new Error('pairing: refusing to encode an invalid offer')
  return new TextEncoder().encode(
    JSON.stringify({
      v: OFFER_VERSION,
      boxPubB64: offer.boxPubB64,
      signPubB64: offer.signPubB64,
      displayName: offer.displayName,
      enrolTicketB64: offer.enrolTicketB64,
    }),
  )
}

/** The offer, or null for anything that is not exactly a v1 offer. Null is a refusal, not a diagnosis. */
export function decodeTherapistOffer(bytes: Uint8Array): TherapistOffer | null {
  let parsed: unknown
  try {
    parsed = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes))
  } catch {
    return null
  }
  if (typeof parsed !== 'object' || parsed === null) return null
  const p = parsed as Record<string, unknown>
  if (p.v !== OFFER_VERSION) return null
  const { v: _v, ...rest } = p
  if (!validTherapistOffer(rest)) return null
  return {
    boxPubB64: rest.boxPubB64,
    signPubB64: rest.signPubB64,
    displayName: rest.displayName,
    enrolTicketB64: rest.enrolTicketB64,
  }
}

/**
 * The owner's identity, as the clinician sees it after E2 opens.
 *
 * PUBLIC HALVES ONLY, and deliberately nothing else — no name, no relationship reference, no
 * timestamp. The clinician learned the relationship from the fetch, and every field that is not a
 * key is a field a later reader might be tempted to trust. What this payload is FOR is one claim:
 * these two keys belong to whoever spoke the code.
 *
 * AUTHENTICATED BY OPENING, exactly as the offer is. If the envelope opens under this run's key in
 * the owner → therapist direction, the bytes were sealed by someone holding the ISK, which means
 * the person the clinician typed the code with. No signature inside could add to that.
 */
export interface OwnerKeysPayload {
  /** X25519 public key, base64url, 32 bytes. What the owner's shares are sealed to. */
  boxPubB64: string
  /** Ed25519 public key, base64url, 32 bytes. What proves a grant or a share came from the owner. */
  signPubB64: string
}

export const OWNER_KEYS_VERSION = 1

/** Both fields, both lengths, and no others. The reason decodeOwnerKeys can return null. */
export function validOwnerKeys(keys: unknown): keys is OwnerKeysPayload {
  if (typeof keys !== 'object' || keys === null) return false
  const o = keys as Record<string, unknown>
  if (Object.keys(o).sort().join(',') !== 'boxPubB64,signPubB64') return false
  if (typeof o.boxPubB64 !== 'string' || decodedLength(o.boxPubB64) !== KEY_BYTES) return false
  if (typeof o.signPubB64 !== 'string' || decodedLength(o.signPubB64) !== KEY_BYTES) return false
  return true
}

/** The bytes to seal. Throws on anything this module would refuse to decode: nothing invalid is sent. */
export function encodeOwnerKeys(keys: OwnerKeysPayload): Uint8Array {
  if (!validOwnerKeys(keys)) throw new Error('pairing: refusing to encode invalid owner keys')
  return new TextEncoder().encode(
    JSON.stringify({
      v: OWNER_KEYS_VERSION,
      boxPubB64: keys.boxPubB64,
      signPubB64: keys.signPubB64,
    }),
  )
}

/**
 * The owner's keys, or null for anything that is not exactly a v1 owner-keys payload.
 *
 * Null is a refusal and not a diagnosis, for the same reason decodeTherapistOffer's is. By the time
 * these bytes exist the envelope has already opened, so the remaining ways to be here holding
 * something unreadable are a version this build does not know and a bug. Both mean "do not pin",
 * and the screen says that without inventing which.
 */
export function decodeOwnerKeys(bytes: Uint8Array): OwnerKeysPayload | null {
  let parsed: unknown
  try {
    parsed = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes))
  } catch {
    return null
  }
  if (typeof parsed !== 'object' || parsed === null) return null
  const p = parsed as Record<string, unknown>
  if (p.v !== OWNER_KEYS_VERSION) return null
  const { v: _v, ...rest } = p
  if (!validOwnerKeys(rest)) return null
  return { boxPubB64: rest.boxPubB64, signPubB64: rest.signPubB64 }
}

/**
 * A fresh enrolment ticket for one offer: 32 bytes from the platform CSPRNG, base64url. The
 * therapist chooses it, seals it to the owner, and later presents it to the server; the server
 * stores only its hash. Never reused across runs.
 */
export function newEnrolTicketB64(): string {
  const bytes = new Uint8Array(ENROL_TICKET_BYTES)
  globalThis.crypto.getRandomValues(bytes)
  let s = ''
  for (const b of bytes) s += String.fromCharCode(b)
  return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}
