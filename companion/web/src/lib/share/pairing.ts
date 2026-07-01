/*
 * Owner ↔ therapist PAIRING: mutual out-of-band SAS (short authentication string) and a
 * bidirectional TOFU (trust-on-first-use) pin store. See docs/COMPANION_THERAPIST.md §5.2.
 *
 * The SAS is a 4–6 word BLAKE2b code derived from BOTH parties' four public keys, computed in an
 * ORDER-INDEPENDENT way so the two clients render the IDENTICAL phrase to compare aloud. The SAS
 * is DISPLAY-ONLY and never a key input — trust is bound when each side PINS the other's
 * fingerprints (`PinStore`). No payload is ever sealed to, or accepted from, an unpinned key.
 *
 * Reuses the single libsodium instance + fingerprint()/keypair primitives from
 * ../assignments/crypto.ts — there is NO second sodium init path.
 */
import {
  fingerprint,
  newSignKeyPair,
  newBoxKeyPair,
  initAssignmentCrypto,
  type SignKeyPair,
  type BoxKeyPair,
} from '../assignments/crypto'
import { SAS_WORDLIST } from './wordlist'
import _sodium from 'libsodium-wrappers-sumo'

/** Single shared sodium init, reused from the assignments crypto (no second WASM load). */
export const initShareCrypto = initAssignmentCrypto

export class PairingError extends Error {}

/** A party's secret-holding identity: an X25519 (encryption) + Ed25519 (signing) keypair. */
export interface Identity {
  x25519: BoxKeyPair
  ed25519: SignKeyPair
}

/** The published form of an identity — only the two public keys. */
export interface PublicIdentity {
  x25519Pub: Uint8Array
  ed25519Pub: Uint8Array
}

/** The pinned form — only fingerprints (no key bytes) plus when it was pinned. */
export interface PinnedIdentity {
  x25519Fp: string
  ed25519Fp: string
  pinnedAt: number
}

export function newIdentity(): Identity {
  return { x25519: newBoxKeyPair(), ed25519: newSignKeyPair() }
}

export function publicOf(id: Identity): PublicIdentity {
  return { x25519Pub: id.x25519.publicKey, ed25519Pub: id.ed25519.publicKey }
}

export function fingerprints(p: PublicIdentity): { x25519Fp: string; ed25519Fp: string } {
  return { x25519Fp: fingerprint(p.x25519Pub), ed25519Fp: fingerprint(p.ed25519Pub) }
}

/**
 * Order-independent SAS. Both parties feed the SAME four public keys (their own + the peer's);
 * we sort the four keys byte-lexicographically before hashing so the phrase is identical
 * regardless of who is "self". Returns `words` distinct-position words from SAS_WORDLIST.
 */
export function sasWords(a: PublicIdentity, b: PublicIdentity, words = 6): string[] {
  if (words < 4 || words > 8) throw new PairingError('SAS length must be 4–8 words')
  const keys = [a.x25519Pub, a.ed25519Pub, b.x25519Pub, b.ed25519Pub]
  keys.sort(compareBytes)
  const total = keys.reduce((n, k) => n + k.length, 0)
  const buf = new Uint8Array(total)
  let off = 0
  for (const k of keys) {
    buf.set(k, off)
    off += k.length
  }
  // BLAKE2b digest, `words` bytes → one word each (index = byte value into a 256-word list).
  const digest = _sodium.crypto_generichash(words, buf)
  return Array.from(digest, (byte) => SAS_WORDLIST[byte])
}

function compareBytes(x: Uint8Array, y: Uint8Array): number {
  const n = Math.min(x.length, y.length)
  for (let i = 0; i < n; i++) {
    if (x[i] !== y[i]) return x[i] - y[i]
  }
  return x.length - y.length
}

/**
 * Bidirectional TOFU pin store, keyed by the peer's Ed25519 fingerprint (the stable identity).
 * Holds only fingerprints — never secret or even public key bytes — so it is safe to persist.
 */
export class PinStore {
  private readonly pins = new Map<string, PinnedIdentity>()

  /** Record the peer's X25519 + Ed25519 fingerprints (TOFU). Idempotent per Ed25519 fp. */
  pin(peer: PublicIdentity, now: number = Date.now()): PinnedIdentity {
    const { x25519Fp, ed25519Fp } = fingerprints(peer)
    const pinned: PinnedIdentity = { x25519Fp, ed25519Fp, pinnedAt: now }
    this.pins.set(ed25519Fp, pinned)
    return pinned
  }

  isPinned(ed25519Fp: string): boolean {
    return this.pins.has(ed25519Fp)
  }

  pinnedX25519Fp(ed25519Fp: string): string | null {
    return this.pins.get(ed25519Fp)?.x25519Fp ?? null
  }

  assertPinned(ed25519Fp: string): PinnedIdentity {
    const p = this.pins.get(ed25519Fp)
    if (!p) throw new PairingError(`peer ${ed25519Fp} is not pinned — verify the OOB code first`)
    return p
  }

  /** Deterministic, secret-free serialization for persistence. */
  serialize(): string {
    const entries = Array.from(this.pins.values()).sort((x, y) => (x.ed25519Fp < y.ed25519Fp ? -1 : 1))
    return JSON.stringify(entries)
  }

  static load(json: string): PinStore {
    const store = new PinStore()
    const entries = JSON.parse(json) as PinnedIdentity[]
    for (const e of entries) {
      if (typeof e.ed25519Fp !== 'string' || typeof e.x25519Fp !== 'string') {
        throw new PairingError('malformed pin store entry')
      }
      store.pins.set(e.ed25519Fp, { x25519Fp: e.x25519Fp, ed25519Fp: e.ed25519Fp, pinnedAt: e.pinnedAt ?? 0 })
    }
    return store
  }
}

export type { SignKeyPair, BoxKeyPair }
export { fingerprint, newSignKeyPair, newBoxKeyPair }
