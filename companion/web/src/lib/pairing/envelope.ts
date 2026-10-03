/*
 * The negotiation envelope — what the CPace key is FOR (COMPANION_PAIRING.md §4 and §13.3).
 *
 * After the relay carries the two CPace messages, each side holds a 64-byte ISK — equal on both
 * ends iff both people typed the same code. Everything the pairing still has to move (the
 * therapist's public keys, the wrapped reading key, the capability set, TOTP enrolment) travels
 * inside envelopes sealed under keys derived from that ISK, so the server relays ciphertext it
 * has no key for, ever.
 *
 * THIS MODULE IS WHERE A WRONG CODE IS FINALLY DISCOVERED. CPace itself emits no signal on a
 * mismatch — by construction, and the whole burn-rule argument rests on it — so the first
 * observable difference between "same code" and "different code" is an envelope that refuses
 * to open. That refusal is a QUESTION handed to a person ("the code didn't match — try again,
 * or stop and tell them"), never an automatic verdict: openEnvelope returns null and the
 * caller decides, because a typo and an attacker are indistinguishable here and only a human
 * report may kill an invitation (COMPANION_PAIRING.md §7).
 *
 * KEY SCHEDULE. Two directional keys, derived by keyed BLAKE2b — the 64-byte ISK is the MAC
 * key (crypto_generichash accepts up to 64 key bytes, so the ISK is used whole rather than
 * truncated), and the message is a versioned purpose label. Directional on purpose: an
 * envelope sealed owner→therapist cannot be reflected back and opened as therapist→owner,
 * which forecloses the class of attack where a relay replays a side's own words at it.
 *
 * ENVELOPE. XChaCha20-Poly1305, fresh 24-byte random nonce per seal, and the AAD binds what
 * the ciphertext cannot say for itself: format version, the exchange's sid, and the direction.
 * A ciphertext moved between exchanges, versions, or directions refuses to open even under the
 * right key. The wire shape is version(1) | nonce(24) | ciphertext — the same style as the
 * sync snapshot format, for the same reason: self-describing enough to refuse, too small to
 * negotiate.
 */
import _sodium from 'libsodium-wrappers-sumo'

export type Sodium = typeof _sodium
let sodium: Sodium | null = null

/** Must be awaited once before any envelope call (loads the WASM). */
export async function initEnvelope(): Promise<Sodium> {
  if (sodium) return sodium
  await _sodium.ready
  sodium = _sodium
  return sodium
}

function s(): Sodium {
  if (!sodium) throw new Error('envelope not initialized — await initEnvelope() first')
  return sodium
}

const utf8 = (t: string): Uint8Array => new TextEncoder().encode(t)

/** Owner → therapist, or therapist → owner. The two directions never share a key. */
export type Direction = 'owner-to-therapist' | 'therapist-to-owner'

export const ENVELOPE_VERSION = 0x01
export const ISK_BYTES = 64

/**
 * The directional sealing key: BLAKE2b keyed with the whole ISK over a versioned label.
 * Deriving rather than using the ISK directly means a future second purpose (a transcript
 * export key, say) can never collide with a sealing key by accident.
 */
export function directionKey(isk: Uint8Array, direction: Direction): Uint8Array {
  if (isk.length !== ISK_BYTES) throw new Error('envelope: ISK must be 64 bytes')
  return s().crypto_generichash(32, utf8(`daymark/pairing/key/v1|${direction}`), isk)
}

function aadFor(sidB64: string, direction: Direction): Uint8Array {
  return utf8(`daymark/pairing/env/v1|${sidB64}|${direction}`)
}

/** Seal one negotiation payload. Fresh nonce each call; never reuse a returned envelope's. */
export function sealEnvelope(
  isk: Uint8Array,
  sidB64: string,
  direction: Direction,
  payload: Uint8Array,
): Uint8Array {
  const so = s()
  const nonce = so.randombytes_buf(so.crypto_aead_xchacha20poly1305_ietf_NPUBBYTES)
  const sealed = so.crypto_aead_xchacha20poly1305_ietf_encrypt(
    payload,
    aadFor(sidB64, direction),
    null,
    nonce,
    directionKey(isk, direction),
  )
  const out = new Uint8Array(1 + nonce.length + sealed.length)
  out[0] = ENVELOPE_VERSION
  out.set(nonce, 1)
  out.set(sealed, 1 + nonce.length)
  return out
}

/**
 * Open one envelope, or return null.
 *
 * Null is the honest answer for every failure — wrong code (different ISK), tampered bytes,
 * wrong direction, wrong sid, wrong version — because distinguishing them for the caller
 * would be an invented certainty: an AEAD failure is one bit, and dressing it up as a
 * diagnosis is how "the code didn't match" becomes "an attack was detected" in a UI. The
 * caller renders a calm question either way.
 */
export function openEnvelope(
  isk: Uint8Array,
  sidB64: string,
  direction: Direction,
  envelope: Uint8Array,
): Uint8Array | null {
  const so = s()
  const nonceBytes = so.crypto_aead_xchacha20poly1305_ietf_NPUBBYTES
  if (envelope.length < 1 + nonceBytes + so.crypto_aead_xchacha20poly1305_ietf_ABYTES) return null
  if (envelope[0] !== ENVELOPE_VERSION) return null
  const nonce = envelope.subarray(1, 1 + nonceBytes)
  const sealed = envelope.subarray(1 + nonceBytes)
  try {
    return so.crypto_aead_xchacha20poly1305_ietf_decrypt(
      null,
      sealed,
      aadFor(sidB64, direction),
      nonce,
      directionKey(isk, direction),
    )
  } catch {
    return null
  }
}
