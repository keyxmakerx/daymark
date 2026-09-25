/*
 * Curated SHARE bundle build/seal/open (owner → therapist). See docs/COMPANION_THERAPIST.md §5.3/§5.4.
 *
 *   plaintext  = pad(serialize(ShareBundle))                 // curated subset, padded (../padding.ts)
 *   CEK        = randombytes_buf(32)                         // fresh random key per share
 *   nonce      = randombytes_buf(24)
 *   aad        = transcript(shareId, version, recipientFp, createdAt, expiry, ownerSigningFp)
 *   body       = nonce || xchacha20poly1305_encrypt(plaintext, aad, nonce, CEK)
 *   wrappedCEK = crypto_box_seal(CEK, therapistX25519Pub)    // anonymity only, NO forward secrecy
 *   ownerSig   = crypto_sign_detached(signedMessage(aad, body, wrappedCEK), ownerEd25519Sk)
 *
 * THE SIGNATURE COVERS EVERY BYTE THE THERAPIST ACTS ON: the transcript, the body and the wrapped
 * key. A signature over the transcript alone is not enough. crypto_box_seal is anonymous, so
 * anyone holding a signed share — the server, first of all — can seal a CEK of their own to the
 * therapist's public key, encrypt new content under it with the same transcript as associated
 * data, and keep the owner's signature: every check would pass and the therapist would read words
 * the owner never wrote. Binding the body and the wrapped key into the signed message closes
 * that. `sharecrypto.test.ts` builds exactly that forgery and asserts it is refused.
 *
 * The therapist verifies the owner signature against the OOB-PINNED owner Ed25519 key FIRST,
 * before the CEK is unsealed or anything is decrypted, and refuses to render on any failure. The
 * server sees only the envelope: nonce||ciphertext, the sealed CEK, the signature, and metadata —
 * it can decrypt none of it, and the padding means the body's length says only which size bucket
 * the share falls in.
 *
 * WHEN IT WAS SEALED IS SIGNED TOO. `createdAt` is in the transcript, so the portal can refuse a
 * copy sealed before the newest one it has already opened (therapist/shareSeen.ts) — by the
 * owner's own clock, not by version numbers, which an honest server restored from a backup could
 * hand out again.
 *
 * FORMAT 1 IS REFUSED, not read. A format-1 share was signed over its transcript only, so its
 * contents cannot be checked, and accepting it would keep the forgery above open for as long as
 * any format-1 share exists. The format is named in the transcript and in the signed message, so
 * a server cannot relabel a format-2 share as format 1 or the other way round.
 *
 * NO forward secrecy (sealed box is a long-term-key primitive) — this is a documented limitation
 * (docs/COMPANION_THERAPIST.md §4); this module does NOT claim PFS.
 */
import { fingerprint, type BoxKeyPair, type SignKeyPair } from '../assignments/crypto'
import { PinStore, PairingError } from './pairing'
import { pad, unpad, PaddingError } from '../padding'
import _sodium from 'libsodium-wrappers-sumo'

const URLSAFE = () => _sodium.base64_variants.URLSAFE_NO_PADDING
const enc = new TextEncoder()
const dec = new TextDecoder()

export const SHARE_FORMAT = 2 as const
export const SHARE_CONTEXT = 'daymark.share.v2' as const
const SIG_DOMAIN = enc.encode('daymark.share.v2/owner-signature')

export interface ShareMeta {
  context: typeof SHARE_CONTEXT
  shareId: string
  version: number
  recipientFp: string // therapist X25519 fingerprint
  createdAt: number // epoch ms, when the owner sealed it
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
  fmt: typeof SHARE_FORMAT
  shareId: string
  version: number
  createdAt: number
  expiry: number
  recipientFp: string
  ownerSigningFp: string
  body: Uint8Array // nonce(24) || XChaCha20-Poly1305 ciphertext of the padded bundle
  wrappedCEK: Uint8Array // crypto_box_seal(CEK, therapistX25519Pub)
  ownerSig: Uint8Array // Ed25519 detached over signedMessage(transcript, body, wrappedCEK)
}

export class ShareOpenError extends Error {}
export class ShareExpiredError extends ShareOpenError {}
/** A share in a format this console does not open (format 1 cannot be checked, see the header). */
export class ShareFormatError extends ShareOpenError {}
/** A share sealed before the newest one this browser has already opened for the relationship. */
export class ShareOlderError extends ShareOpenError {}
export class ShareUnpinnedError extends PairingError {}

/**
 * Canonical AAD bytes: context|shareId|version|recipientFp|createdAt|expiry|ownerSigningFp, '|'-joined,
 * UTF-8 (a wire contract). The fields are unambiguous because openShare refuses any envelope whose
 * ids fall outside [A-Za-z0-9_-] (so none contains '|') and whose numbers are not non-negative safe
 * integers written in base 10.
 */
export function transcript(m: ShareMeta): Uint8Array {
  return enc.encode(
    `${SHARE_CONTEXT}|${m.shareId}|${m.version}|${m.recipientFp}|${m.createdAt}|${m.expiry}|${m.ownerSigningFp}`,
  )
}

/**
 * What the owner signs: a domain string, a zero byte, then BLAKE2b-256 of the transcript, of the
 * body and of the wrapped key. Everything after the domain is fixed-length, so no two different
 * envelopes produce the same message.
 */
export function signedMessage(aad: Uint8Array, body: Uint8Array, wrappedCEK: Uint8Array): Uint8Array {
  const out = new Uint8Array(SIG_DOMAIN.length + 1 + 32 * 3)
  out.set(SIG_DOMAIN, 0)
  let at = SIG_DOMAIN.length + 1
  for (const part of [aad, body, wrappedCEK]) {
    out.set(_sodium.crypto_generichash(32, part), at)
    at += 32
  }
  return out
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

const ID = /^[A-Za-z0-9_-]{1,128}$/
const isCount = (n: unknown): n is number => typeof n === 'number' && Number.isSafeInteger(n) && n >= 0

/**
 * Owner builds a sealed share for a PINNED therapist. Asserts the recipient is pinned and that
 * meta.recipientFp actually matches the given therapist X25519 key (no silent mis-address).
 * `meta.version` must be the version the share is published as: the therapist checks it against
 * the version the server serves it under.
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
  if (!ID.test(meta.shareId) || !isCount(meta.version) || !isCount(meta.createdAt) || !isCount(meta.expiry)) {
    throw new PairingError('share id, version, creation time or expiry is not in the form a therapist accepts')
  }
  if (bundle.shareId !== meta.shareId || bundle.ownerFp !== meta.ownerSigningFp) {
    throw new PairingError('the bundle names a different share or owner than its envelope')
  }
  const aad = transcript(meta)
  const cek = _sodium.randombytes_buf(_sodium.crypto_aead_xchacha20poly1305_ietf_KEYBYTES)
  try {
    const nonce = _sodium.randombytes_buf(_sodium.crypto_aead_xchacha20poly1305_ietf_NPUBBYTES)
    const ct = _sodium.crypto_aead_xchacha20poly1305_ietf_encrypt(pad(serializeBundle(bundle)), aad, null, nonce, cek)
    const body = new Uint8Array(nonce.length + ct.length)
    body.set(nonce, 0)
    body.set(ct, nonce.length)
    const wrappedCEK = _sodium.crypto_box_seal(cek, therapistX25519Pub)
    const ownerSig = _sodium.crypto_sign_detached(signedMessage(aad, body, wrappedCEK), owner.privateKey)
    return {
      fmt: SHARE_FORMAT,
      shareId: meta.shareId,
      version: meta.version,
      createdAt: meta.createdAt,
      expiry: meta.expiry,
      recipientFp: meta.recipientFp,
      ownerSigningFp: meta.ownerSigningFp,
      body,
      wrappedCEK,
      ownerSig,
    }
  } finally {
    _sodium.memzero(cek)
  }
}

/**
 * Therapist opens + verifies a sealed share. Strict order, and nothing secret is used until the
 * owner's signature has verified:
 *   1. refuse any format but 2, and any envelope whose fields are not in their canonical form
 *   2. assert fingerprint(pub) === pinnedOwnerSigningFp === envelope.ownerSigningFp, and that the
 *      envelope is addressed to this therapist's X25519 key
 *   3. verify ownerSig over signedMessage(transcript, body, wrappedCEK)
 *   4. check now < expiry
 *   5. refuse a share sealed before `notBefore`, the newest one already opened (therapist/shareSeen.ts)
 *   6. unseal the CEK, AEAD-decrypt with the transcript as associated data, unpad
 *   7. the decrypted bundle must name the same share and owner as its envelope
 * Only then return the bundle. Never renders on any failure.
 */
export function openShare(
  sealed: SealedShare,
  therapist: BoxKeyPair,
  ownerSigningPub: Uint8Array,
  pinnedOwnerSigningFp: string,
  now: number,
  notBefore?: number,
): ShareBundle {
  // 1. format and shape (the envelope's fields are attacker-settable until step 3)
  if ((sealed as { fmt: unknown }).fmt !== SHARE_FORMAT) {
    throw new ShareFormatError('share is not in format 2')
  }
  const nb = _sodium.crypto_aead_xchacha20poly1305_ietf_NPUBBYTES
  if (
    !ID.test(sealed.shareId) || !ID.test(sealed.recipientFp) || !ID.test(sealed.ownerSigningFp) ||
    !isCount(sealed.version) || !isCount(sealed.createdAt) || !isCount(sealed.expiry) ||
    sealed.body.length < nb + _sodium.crypto_aead_xchacha20poly1305_ietf_ABYTES ||
    sealed.wrappedCEK.length !== _sodium.crypto_box_SEALBYTES + _sodium.crypto_aead_xchacha20poly1305_ietf_KEYBYTES ||
    sealed.ownerSig.length !== _sodium.crypto_sign_BYTES
  ) {
    throw new ShareOpenError('malformed sealed-share envelope')
  }

  // 2. pinned owner key, and addressed to this therapist
  const ownerPubFp = fingerprint(ownerSigningPub)
  if (ownerPubFp !== pinnedOwnerSigningFp || sealed.ownerSigningFp !== pinnedOwnerSigningFp) {
    throw new ShareOpenError('owner signing key does not match the pinned owner fingerprint')
  }
  if (sealed.recipientFp !== fingerprint(therapist.publicKey)) {
    throw new ShareOpenError('share is addressed to a different therapist key')
  }

  // 3. the owner's signature over every byte used below
  const aad = transcript({
    context: SHARE_CONTEXT,
    shareId: sealed.shareId,
    version: sealed.version,
    recipientFp: sealed.recipientFp,
    createdAt: sealed.createdAt,
    expiry: sealed.expiry,
    ownerSigningFp: sealed.ownerSigningFp,
  })
  const sigOk = _sodium.crypto_sign_verify_detached(sealed.ownerSig, signedMessage(aad, sealed.body, sealed.wrappedCEK), ownerSigningPub)
  if (!sigOk) throw new ShareOpenError('owner signature invalid (forged / wrong owner / altered)')

  // 4. expiry (client re-check; the server also enforces on the honest path)
  if (!(now < sealed.expiry)) throw new ShareExpiredError('share has expired')

  // 5. not older than one already opened; decided on the signed time, before anything is decrypted
  if (notBefore !== undefined && sealed.createdAt < notBefore) {
    throw new ShareOlderError('share was sealed before one already opened')
  }

  // 6. unseal, decrypt, unpad
  let cek: Uint8Array
  try {
    cek = _sodium.crypto_box_seal_open(sealed.wrappedCEK, therapist.publicKey, therapist.privateKey)
  } catch {
    throw new ShareOpenError('sealed CEK could not be opened (not addressed to this therapist)')
  }
  let bundle: ShareBundle
  try {
    const nonce = sealed.body.subarray(0, nb)
    const ct = sealed.body.subarray(nb)
    let padded: Uint8Array
    try {
      padded = _sodium.crypto_aead_xchacha20poly1305_ietf_decrypt(null, ct, aad, nonce, cek)
    } catch {
      throw new ShareOpenError('share ciphertext failed authentication')
    }
    try {
      bundle = deserializeBundle(unpad(padded))
    } catch (e) {
      if (e instanceof PaddingError || e instanceof SyntaxError) throw new ShareOpenError('share contents are malformed')
      throw e
    }
  } finally {
    _sodium.memzero(cek)
  }

  // 7. the contents belong to this envelope
  if (bundle.shareId !== sealed.shareId || bundle.ownerFp !== sealed.ownerSigningFp) {
    throw new ShareOpenError('share contents name a different share or owner than their envelope')
  }
  return bundle
}

/** URL-safe (no padding) base64 helpers, matching sync/crypto.ts and assignments/crypto.ts. */
export function toBase64(b: Uint8Array): string {
  return _sodium.to_base64(b, URLSAFE())
}
export function fromBase64(b64: string): Uint8Array {
  return _sodium.from_base64(b64, URLSAFE())
}
