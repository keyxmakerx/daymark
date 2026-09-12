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
 * Bidirectional TOFU pin store: an INSERT-ONLY LOG of the fingerprints this console has recorded
 * for its peers. Holds only fingerprints — never secret or even public key bytes — so it is safe
 * to persist.
 *
 * WHY A LOG AND NOT A MAP. It used to be a Map keyed by the Ed25519 fingerprint, so recording a
 * peer's new encryption key OVERWROTE the row that said what the old one was. That made the one
 * question anybody asks after a key changes — what was on file before, and until when? —
 * unanswerable from the record, because answering it was what destroyed the evidence. The server's
 * key tables are insert-only for exactly this reason (CLAUDE.md §4), and a client store that is the
 * owner's only copy of the same history has no better excuse.
 *
 * SO [pin] APPENDS. Nothing in this class edits or drops a row. The only removal anywhere is a
 * person clearing their own record (therapist/pinStore.ts, forgetPeer/forgetAllPins), which drops
 * rows rather than rewriting them.
 *
 * THE NEWEST ROW IS THE ONE THAT COUNTS. [isPinned], [pinnedX25519Fp] and [assertPinned] answer
 * from the LAST row recorded for an identity, so a seal goes to the current key and a superseded
 * one is refused exactly as an unknown key is. History is readable ([rows], [history]) and is never
 * consulted by a check — "it was pinned once" must never become a way past "it is not pinned now".
 */
export class PinStore {
  /** Oldest first. Append-only; position in this list, not `pinnedAt`, is what "newest" means. */
  private readonly log: PinnedIdentity[] = []

  /**
   * Record the peer's X25519 + Ed25519 fingerprints (TOFU) as a NEW row.
   *
   * NOT idempotent, deliberately — it was, back when it overwrote. Callers that must not write a
   * second row for keys already on file ask first: `pinOnFirstUse` and `recordPin`
   * (therapist/pinStore.ts) are the two doors, and both check before they write.
   */
  pin(peer: PublicIdentity, now: number = Date.now()): PinnedIdentity {
    const { x25519Fp, ed25519Fp } = fingerprints(peer)
    const pinned: PinnedIdentity = { x25519Fp, ed25519Fp, pinnedAt: now }
    this.log.push(pinned)
    return pinned
  }

  /** Every row ever recorded, oldest first. A copy: the log is not writable from outside. */
  rows(): PinnedIdentity[] {
    return this.log.map((r) => ({ ...r }))
  }

  /** The newest row per identity, in the order each identity was first seen. What a screen lists. */
  current(): PinnedIdentity[] {
    const seen: string[] = []
    for (const r of this.log) if (!seen.includes(r.ed25519Fp)) seen.push(r.ed25519Fp)
    return seen.map((fp) => ({ ...(this.newest(fp) as PinnedIdentity) }))
  }

  /** Every row recorded for ONE identity, oldest first — what was on file, and from when. */
  history(ed25519Fp: string): PinnedIdentity[] {
    return this.log.filter((r) => r.ed25519Fp === ed25519Fp).map((r) => ({ ...r }))
  }

  private newest(ed25519Fp: string): PinnedIdentity | null {
    for (let i = this.log.length - 1; i >= 0; i--) {
      if (this.log[i].ed25519Fp === ed25519Fp) return this.log[i]
    }
    return null
  }

  isPinned(ed25519Fp: string): boolean {
    return this.newest(ed25519Fp) !== null
  }

  pinnedX25519Fp(ed25519Fp: string): string | null {
    return this.newest(ed25519Fp)?.x25519Fp ?? null
  }

  assertPinned(ed25519Fp: string): PinnedIdentity {
    const p = this.newest(ed25519Fp)
    if (!p) throw new PairingError(`peer ${ed25519Fp} is not pinned — verify the OOB code first`)
    return p
  }

  /**
   * Deterministic, secret-free serialization for persistence.
   *
   * In log order rather than sorted by fingerprint, because the order IS content now: which row
   * superseded which is the thing being kept, and a sort would scatter it.
   */
  serialize(): string {
    return JSON.stringify(this.log)
  }

  static load(json: string): PinStore {
    const store = new PinStore()
    const entries = JSON.parse(json) as PinnedIdentity[]
    for (const e of entries) {
      if (typeof e.ed25519Fp !== 'string' || typeof e.x25519Fp !== 'string') {
        throw new PairingError('malformed pin store entry')
      }
      store.log.push({ x25519Fp: e.x25519Fp, ed25519Fp: e.ed25519Fp, pinnedAt: e.pinnedAt ?? 0 })
    }
    return store
  }
}

export type { SignKeyPair, BoxKeyPair }
export { fingerprint, newSignKeyPair, newBoxKeyPair }
