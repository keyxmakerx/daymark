/*
 * Curated SHARE bundle build/seal/open (owner → therapist). See docs/COMPANION_THERAPIST.md §5.3/§5.4.
 *
 *   plaintext  = serialize(ShareBundle)                      // curated, non-diagnostic subset
 *   CEK        = randombytes_buf(32)                         // fresh random key per share
 *   nonce      = randombytes_buf(24)
 *   aad        = transcript(shareId, version, recipientFp, expiry, ownerSigningFp)
 *   ciphertext = xchacha20poly1305_encrypt(plaintext, aad, nonce, CEK)
 *   wrappedCEK = crypto_box_seal(CEK, therapistX25519Pub)    // anonymity only, NO forward secrecy
 *   ownerSig   = crypto_sign_detached(transcript, ownerEd25519Sk)   // MANDATORY owner signature
 *
 * The therapist unseals the CEK, then VERIFIES the owner signature against the OOB-PINNED owner
 * Ed25519 key before decrypting/rendering anything. Verify-then-decrypt, refuse-to-render on any
 * failure. The server sees only nonce||ciphertext, the sealed CEK, the signature, and metadata —
 * it can decrypt none of it.
 *
 * NO forward secrecy (sealed box is a long-term-key primitive) — this is a documented limitation
 * (docs/COMPANION_THERAPIST.md §4); this module does NOT claim PFS.
 */
import { fingerprint, type BoxKeyPair, type SignKeyPair } from '../assignments/crypto'
import { PinStore, PairingError } from './pairing'
import _sodium from 'libsodium-wrappers-sumo'

const URLSAFE = () => _sodium.base64_variants.URLSAFE_NO_PADDING
const enc = new TextEncoder()
const dec = new TextDecoder()

const CONTEXT = 'daymark.share.v1' as const

export interface ShareMeta {
  context: typeof CONTEXT
  shareId: string
  version: number
  recipientFp: string // therapist X25519 fingerprint
  expiry: number // epoch ms
  ownerSigningFp: string // owner Ed25519 fingerprint (pinned by therapist)
}

/**
 * Curated, non-diagnostic-by-construction subset. The PHQ item-9 / self-harm slot is
 * STRUCTURALLY ABSENT from this type — there is no field it could ever be materialized into.
 * Check-ins carry scores/bands only (never raw item responses); notes/journal are post-redaction.
 */
export interface ShareBundle {
  schema: 1
  shareId: string
  scope: { from: number; to: number; recordTypes: string[] }
  ownerFp: string
  checkIns: Array<{ instrumentId: string; at: number; score: number; band: string }>
  moods?: Array<{ at: number; level: number; note?: string }>
  journal?: Array<{ at: number; text: string }>
  sleep?: Array<{ at: number; bedTime: number; wakeTime: number; quality: number }>
}

/** Opaque-to-server envelope. Contains no key material, no CEK, no plaintext. */
export interface SealedShare {
  fmt: 1
  shareId: string
  version: number
  expiry: number
  recipientFp: string
  ownerSigningFp: string
  body: Uint8Array // nonce(24) || XChaCha20-Poly1305 ciphertext
  wrappedCEK: Uint8Array // crypto_box_seal(CEK, therapistX25519Pub)
  ownerSig: Uint8Array // Ed25519 detached over transcript(meta)
}

export class ShareOpenError extends Error {}
export class ShareExpiredError extends ShareOpenError {}
export class ShareUnpinnedError extends PairingError {}

/**
 * Canonical AAD / signed-transcript bytes. Used for BOTH the AEAD and the Ed25519 signature so a
 * stolen wrapped-CEK cannot be spliced onto a different ciphertext and a bundle cannot be
 * re-pointed at a different owner identity and still verify. Field order + encoding are FIXED
 * (a wire contract): context|shareId|version|recipientFp|expiry|ownerSigningFp, '|'-joined, UTF-8.
 * The '|' separators + the fixed prefix make the fields unambiguous; fps are URL-safe base64
 * strings (no '|'), version/expiry are base-10 integers.
 */
export function transcript(m: ShareMeta): Uint8Array {
  return enc.encode(
    `${CONTEXT}|${m.shareId}|${m.version}|${m.recipientFp}|${m.expiry}|${m.ownerSigningFp}`,
  )
}

/** Canonical bundle bytes (stable key order via explicit construction). */
export function serializeBundle(b: ShareBundle): Uint8Array {
  const canonical = {
    schema: b.schema,
    shareId: b.shareId,
    scope: { from: b.scope.from, to: b.scope.to, recordTypes: [...b.scope.recordTypes] },
    ownerFp: b.ownerFp,
    checkIns: b.checkIns.map((c) => ({ instrumentId: c.instrumentId, at: c.at, score: c.score, band: c.band })),
    ...(b.moods ? { moods: b.moods.map((x) => ({ at: x.at, level: x.level, ...(x.note !== undefined ? { note: x.note } : {}) })) } : {}),
    ...(b.journal ? { journal: b.journal.map((x) => ({ at: x.at, text: x.text })) } : {}),
    ...(b.sleep ? { sleep: b.sleep.map((x) => ({ at: x.at, bedTime: x.bedTime, wakeTime: x.wakeTime, quality: x.quality })) } : {}),
  }
  return enc.encode(JSON.stringify(canonical))
}

export function deserializeBundle(bytes: Uint8Array): ShareBundle {
  return JSON.parse(dec.decode(bytes)) as ShareBundle
}

/**
 * Owner builds a sealed share for a PINNED therapist. Asserts the recipient is pinned and that
 * meta.recipientFp actually matches the given therapist X25519 key (no silent mis-address).
 */
export function buildShare(
  bundle: ShareBundle,
  meta: ShareMeta,
  therapistX25519Pub: Uint8Array,
  owner: SignKeyPair,
  therapistEd25519Fp: string,
  pins: PinStore,
): SealedShare {
  if (!pins.isPinned(therapistEd25519Fp)) {
    throw new ShareUnpinnedError('refusing to seal a share to an unpinned therapist')
  }
  const actualRecipientFp = fingerprint(therapistX25519Pub)
  if (meta.recipientFp !== actualRecipientFp) {
    throw new ShareUnpinnedError('meta.recipientFp does not match the therapist X25519 key')
  }
  const pinnedX = pins.pinnedX25519Fp(therapistEd25519Fp)
  if (pinnedX !== actualRecipientFp) {
    throw new ShareUnpinnedError('therapist X25519 key is not the pinned one for this relationship')
  }
  if (meta.ownerSigningFp !== fingerprint(owner.publicKey)) {
    throw new PairingError('meta.ownerSigningFp does not match the owner signing key')
  }
  const aad = transcript(meta)
  const cek = _sodium.randombytes_buf(_sodium.crypto_aead_xchacha20poly1305_ietf_KEYBYTES)
  const nonce = _sodium.randombytes_buf(_sodium.crypto_aead_xchacha20poly1305_ietf_NPUBBYTES)
  const ct = _sodium.crypto_aead_xchacha20poly1305_ietf_encrypt(serializeBundle(bundle), aad, null, nonce, cek)
  const body = new Uint8Array(nonce.length + ct.length)
  body.set(nonce, 0)
  body.set(ct, nonce.length)
  const wrappedCEK = _sodium.crypto_box_seal(cek, therapistX25519Pub)
  const ownerSig = _sodium.crypto_sign_detached(aad, owner.privateKey)
  return {
    fmt: 1,
    shareId: meta.shareId,
    version: meta.version,
    expiry: meta.expiry,
    recipientFp: meta.recipientFp,
    ownerSigningFp: meta.ownerSigningFp,
    body,
    wrappedCEK,
    ownerSig,
  }
}

/**
 * Therapist opens + verifies a sealed share. Strict order:
 *   1. unseal the CEK with the therapist X25519 keypair (fail ⇒ not-for-me / tampered)
 *   2. rebuild the transcript from the envelope fields
 *   3. verify ownerSig against the supplied owner Ed25519 pub AND assert
 *      fingerprint(pub) === pinnedOwnerSigningFp === envelope.ownerSigningFp
 *   4. check now < expiry
 *   5. AEAD-decrypt with the recomputed AAD (fail ⇒ tamper)
 * Only then parse + return the bundle. Never renders on any failure.
 */
export function openShare(
  sealed: SealedShare,
  therapist: BoxKeyPair,
  ownerSigningPub: Uint8Array,
  pinnedOwnerSigningFp: string,
  now: number,
): ShareBundle {
  // 1. unseal the CEK
  let cek: Uint8Array
  try {
    cek = _sodium.crypto_box_seal_open(sealed.wrappedCEK, therapist.publicKey, therapist.privateKey)
  } catch {
    throw new ShareOpenError('sealed CEK could not be opened (not addressed to this therapist, or tampered)')
  }

  // 2. rebuild transcript from the (attacker-settable) envelope fields
  const meta: ShareMeta = {
    context: CONTEXT,
    shareId: sealed.shareId,
    version: sealed.version,
    recipientFp: sealed.recipientFp,
    expiry: sealed.expiry,
    ownerSigningFp: sealed.ownerSigningFp,
  }
  const aad = transcript(meta)

  // 3. verify owner signature against the PINNED owner key (defeats key substitution)
  const ownerPubFp = fingerprint(ownerSigningPub)
  if (ownerPubFp !== pinnedOwnerSigningFp || sealed.ownerSigningFp !== pinnedOwnerSigningFp) {
    throw new ShareOpenError('owner signing key does not match the pinned owner fingerprint')
  }
  const sigOk = _sodium.crypto_sign_verify_detached(sealed.ownerSig, aad, ownerSigningPub)
  if (!sigOk) throw new ShareOpenError('owner signature invalid (forged / wrong owner / spliced)')

  // 4. expiry (client re-check; the server also enforces on the honest path)
  if (!(now < sealed.expiry)) throw new ShareExpiredError('share has expired')

  // 5. AEAD-decrypt with the recomputed AAD
  const nb = _sodium.crypto_aead_xchacha20poly1305_ietf_NPUBBYTES
  if (sealed.body.length < nb) throw new ShareOpenError('share body too short')
  const nonce = sealed.body.subarray(0, nb)
  const ct = sealed.body.subarray(nb)
  let plaintext: Uint8Array
  try {
    plaintext = _sodium.crypto_aead_xchacha20poly1305_ietf_decrypt(null, ct, aad, nonce, cek)
  } catch {
    throw new ShareOpenError('share ciphertext failed authentication (tampered)')
  }
  return deserializeBundle(plaintext)
}

/** URL-safe (no padding) base64 helpers, matching sync/crypto.ts and assignments/crypto.ts. */
export function toBase64(b: Uint8Array): string {
  return _sodium.to_base64(b, URLSAFE())
}
export function fromBase64(b64: string): Uint8Array {
  return _sodium.from_base64(b64, URLSAFE())
}
