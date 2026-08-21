/*
 * THE RECOVERABLE DATA KEY — one random key, wrapped twice, so that EITHER the passphrase or the
 * user's recovery code opens it and NEITHER of them is the key.
 *
 * ─── THE PROBLEM THIS REPLACES ──────────────────────────────────────────────────────────────────
 *
 * sync/crypto.ts derives the snapshot key from the passphrase and from nothing else:
 *
 *     passphrase --Argon2id(salt)--> master --crypto_kdf--> SYNC_KEY / MANIFEST_SEED
 *
 * That single arrow is what makes the server ignorant, and it is also a cliff. The key is not
 * STORED anywhere, so there is nothing to unlock a second way: the passphrase is not a credential
 * that opens the key, it IS the key, run through a KDF. One forgotten passphrase and years of
 * somebody's journal are unreachable by them, by us, and by anyone. docs/COMPANION_SECURITY.md §4
 * says so in as many words ("Lose the passphrase -> snapshots unrecoverable"), and
 * docs/COMPANION_ACCESS_CONTROL.md § Key recovery is the decision to fix it without adding a
 * backdoor: "We keep no-escrow and add user-held recovery... The server never holds a recovery
 * secret. The user can recover; the server still can't read."
 *
 * ─── THE FIX, WHICH IS THE STANDARD ONE ─────────────────────────────────────────────────────────
 *
 * Stop deriving the data key FROM a secret. Generate it at random, then wrap it once per secret:
 *
 *     dataKey (32 random bytes)
 *        |
 *        +-- XChaCha20-Poly1305 under Argon2id(passphrase,    salt_p) --> slot "passphrase"
 *        +-- XChaCha20-Poly1305 under Argon2id(recovery code, salt_r) --> slot "recovery"
 *
 * Both slots are inert without one of the two secrets, so both can sit on the server next to each
 * other and the server learns nothing from holding them — which is exactly why this design can add
 * recovery WITHOUT adding escrow. The server stores a second locked box, not a second key. Whoever
 * holds either secret opens the same data key and therefore reads the same bytes; whoever holds
 * neither holds two 48-byte ciphertexts and a pair of public salts. zeroKnowledge.test.ts states
 * that as an adversary who has both slots and gets nothing, because it is the claim the whole
 * feature rests on and a claim in prose is not a claim that anything checks.
 *
 * WHY WRAP A KEY RATHER THAN KEEP TWO KEYS. The alternative — encrypt everything twice, once under
 * each secret — doubles every snapshot and makes a passphrase change rewrite the entire archive.
 * Wrapping means a passphrase change rewrites 48 bytes (replacePassphrase() below), a new recovery
 * code rewrites 48 bytes (rotateRecoveryCode()), and neither one touches a single stored snapshot.
 *
 * WHY THE SAME KDF FLOOR ON BOTH SLOTS. The recovery code carries ~143 bits (recoveryCode.ts), so
 * Argon2id adds nothing to it that matters. It is used anyway, at the identical 256 MiB / 3-pass
 * floor, for three reasons: one derivation path means one validateKdf() and one place to get it
 * wrong; the cost of a rare recovery is a few seconds, which is nothing next to what recovery is
 * worth; and if the entropy argument ever turns out to be weaker than believed, the memory-hard
 * KDF is the layer that was quietly holding the line anyway.
 *
 * WHY THE FLOOR IS RE-CHECKED ON EVERY SLOT OF EVERY BLOB. The blob arrives from the server, and
 * the KDF parameters travel inside it — so the parameters are attacker-controlled input, exactly as
 * they are in therapist/keyStore.ts and sync/client.ts. A blob claiming 8 MiB / 1 pass is a
 * downgrade attempt and is refused before a single byte is derived. Every slot is checked, not just
 * the one being opened, so that a weakened sibling slot cannot survive a round trip through an
 * honest client and end up re-uploaded next to a strong one.
 *
 * WHAT THIS MODULE DELIBERATELY DOES NOT DO. It does not talk to the server: no fetch, no storage,
 * no transport. It hands back plain data structures and lets the caller decide where they live,
 * because the same blob has to be storable by a browser, by the phone, and by a file on a USB stick,
 * and a crypto module that knows about HTTP is a crypto module that cannot be used by two of those
 * three. It also does not log, at all — the server is a zero-knowledge relay and this is the one
 * module in the tree whose stray console call would be a live key.
 *
 * MIGRATION of the existing passphrase-derived snapshots is a real question with a real answer;
 * it is written down in migration.ts rather than hand-waved here.
 */
import _sodium from 'libsodium-wrappers-sumo'
import { initCrypto, DEFAULT_KDF, type KdfParams } from '../sync/crypto'
import { newRecoveryCode, requireRecoveryCode, type RecoveryCode } from './recoveryCode'

const URLSAFE = () => _sodium.base64_variants.URLSAFE_NO_PADDING
const enc = new TextEncoder()

/** The wrapped secret is a 32-byte symmetric key — the same size sync/crypto.ts derives today. */
export const DATA_KEY_BYTES = 32
/** XChaCha20-Poly1305 tag length. A v1 slot's ciphertext is exactly DATA_KEY_BYTES + this. */
const MAC_BYTES = 16

/**
 * Which secret a slot is wrapped under.
 *
 * It is a label, not a capability: both kinds wrap the identical data key and neither is weaker.
 * It exists so the AAD can bind a slot to its role — see aadFor() — and so a caller can say "open
 * the one the passphrase fits" without trial-decrypting.
 */
export type SlotKind = 'passphrase' | 'recovery'

/** One wrapped copy of the data key. Opaque without its secret; safe to hand to the server. */
export interface WrappedSlot {
  kind: SlotKind
  /** The Argon2id parameters used for THIS slot. Attacker-controlled input; validated on every use. */
  kdf: KdfParams
  /** 16-byte Argon2id salt. Non-secret by construction, and per-slot so the two KEKs are unrelated. */
  saltB64: string
  nonceB64: string
  ctB64: string
}

/**
 * The whole thing the server stores: a version and a list of wrapped copies.
 *
 * WHY A LIST AND NOT TWO NAMED FIELDS. docs/COMPANION_ACCESS_CONTROL.md § Key recovery also names
 * "optional social / Shamir recovery — the user splits recovery across people or devices they
 * choose", and a future WebAuthn-PRF slot is already implied by COMPANION_SECURITY.md §4. All of
 * those are more wrapped copies of the same data key. A list takes them without a format change; a
 * pair of named fields would have to be migrated to accept the third.
 */
export interface RecoverableDataKey {
  v: 1
  slots: WrappedSlot[]
}

/** Everything wrong with a blob, a secret, or a set of KDF parameters. */
export class DataKeyError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'DataKeyError'
  }
}

/**
 * What the AEAD authenticates besides the ciphertext.
 *
 * Binding the slot kind means a recovery slot cannot be relabelled as the passphrase slot by
 * whoever is storing the blob. That swap would not leak anything by itself — both slots hold the
 * same 32 bytes — but it would turn "your passphrase is wrong" into the outcome of an edit the
 * server made, and a zero-knowledge store should not be able to influence which of a user's secrets
 * appears to have failed.
 */
function aadFor(kind: SlotKind): Uint8Array {
  return enc.encode(`daymark.datakey.v1|${kind}`)
}

/**
 * Reject KDF parameters below the security-doc floor (>=256 MiB, >=3 ops) — downgrade defence.
 *
 * Takes an optional argument on purpose: the blob is parsed from whatever the server sent, so a slot
 * with no `kdf` field at all is a thing that can actually arrive, and it must be refused here rather
 * than crash three lines later reading `.alg` off undefined.
 */
function validateKdf(params: KdfParams | undefined | null): void {
  if (!params || params.alg !== 'argon2id' || params.memMiB < 256 || params.ops < 3) {
    throw new DataKeyError('data-key KDF parameters are below the security floor — refusing to derive')
  }
}

/**
 * Decode one base64url field of a slot, turning a malformed one into this module's own error.
 *
 * Every field of the blob is server-supplied, so "that is not base64" is ordinary hostile input and
 * not an exceptional condition. Letting libsodium's raw TypeError escape would make a caller catch
 * two unrelated error types to handle one situation, and the sentence it carries is about encodings
 * rather than about anything a person could act on.
 */
function decodeB64(value: string, field: string): Uint8Array {
  try {
    return _sodium.from_base64(value, URLSAFE())
  } catch {
    throw new DataKeyError(`wrapped slot has a malformed ${field}`)
  }
}

/** Refuse a blob that is malformed, or that is weak anywhere in it, before touching any secret. */
function validateBlob(blob: RecoverableDataKey): void {
  if (blob.v !== 1) throw new DataKeyError('unsupported data-key blob version')
  if (!Array.isArray(blob.slots) || blob.slots.length === 0) throw new DataKeyError('data-key blob has no wrapped slots')
  for (const slot of blob.slots) validateKdf(slot.kdf)
}

/** Derive the key-encryption key for one slot. The caller MUST wipe the result. */
function kekFor(secret: string, salt: Uint8Array, params: KdfParams): Uint8Array {
  return _sodium.crypto_pwhash(
    DATA_KEY_BYTES,
    secret,
    salt,
    params.ops,
    params.memMiB * 1024 * 1024,
    _sodium.crypto_pwhash_ALG_ARGON2ID13,
  )
}

/** A fresh random data key. This — not any secret — is what actually encrypts the user's data. */
export async function newDataKey(): Promise<Uint8Array> {
  await initCrypto()
  return _sodium.randombytes_buf(DATA_KEY_BYTES)
}

/**
 * Wrap one copy of a data key under one secret.
 *
 * Exported because everything that adds a way in — enrolment, a passphrase change, a rotated
 * recovery code, and the migration in migration.ts — is this function with a different secret, and
 * a private version of it would only have been re-implemented four times.
 */
export async function wrapDataKey(
  dataKey: Uint8Array,
  secret: string,
  kind: SlotKind,
  params: KdfParams = DEFAULT_KDF,
): Promise<WrappedSlot> {
  await initCrypto()
  validateKdf(params)
  if (dataKey.length !== DATA_KEY_BYTES) throw new DataKeyError('data key must be exactly 32 bytes')
  const salt = _sodium.randombytes_buf(_sodium.crypto_pwhash_SALTBYTES)
  const nonce = _sodium.randombytes_buf(_sodium.crypto_aead_xchacha20poly1305_ietf_NPUBBYTES)
  const kek = kekFor(secret, salt, params)
  try {
    const ct = _sodium.crypto_aead_xchacha20poly1305_ietf_encrypt(dataKey, aadFor(kind), null, nonce, kek)
    return {
      kind,
      kdf: params,
      saltB64: _sodium.to_base64(salt, URLSAFE()),
      nonceB64: _sodium.to_base64(nonce, URLSAFE()),
      ctB64: _sodium.to_base64(ct, URLSAFE()),
    }
  } finally {
    // The KEK opens this slot on its own, so it is passphrase-equivalent and has no reason to
    // outlive the wrap. keyStore.ts states the honest limits of doing this in JavaScript at length
    // and they apply here unchanged: this removes the copy we made, it does not promise the engine
    // kept only one, and the secret arrived as an immutable string we cannot overwrite at all.
    kek.fill(0)
  }
}

/** Open one slot. Returns the 32-byte data key, or throws without saying which part was wrong. */
async function unwrapSlot(slot: WrappedSlot, secret: string): Promise<Uint8Array> {
  await initCrypto()
  validateKdf(slot.kdf)
  const ct = decodeB64(slot.ctB64, 'ciphertext')
  if (ct.length !== DATA_KEY_BYTES + MAC_BYTES) {
    // A v1 slot wraps exactly one 32-byte key, so its ciphertext length is not a matter of opinion.
    // Checking it here means a slot that has been swollen with something else is refused before the
    // expensive derivation rather than after it.
    throw new DataKeyError('wrapped slot is not the size of a wrapped data key')
  }
  const salt = decodeB64(slot.saltB64, 'salt')
  const nonce = decodeB64(slot.nonceB64, 'nonce')
  // Both are fixed-width by the primitives that consume them, and libsodium would throw its own
  // error on a wrong length. Checking here keeps every refusal on this path a DataKeyError, and
  // keeps them all on the cheap side of the derivation.
  if (salt.length !== _sodium.crypto_pwhash_SALTBYTES) throw new DataKeyError('wrapped slot has a salt of the wrong length')
  if (nonce.length !== _sodium.crypto_aead_xchacha20poly1305_ietf_NPUBBYTES) {
    throw new DataKeyError('wrapped slot has a nonce of the wrong length')
  }
  const kek = kekFor(secret, salt, slot.kdf)
  let dataKey: Uint8Array
  try {
    dataKey = _sodium.crypto_aead_xchacha20poly1305_ietf_decrypt(null, ct, aadFor(slot.kind), nonce, kek)
  } catch {
    throw new DataKeyError('that secret does not open this slot')
  } finally {
    kek.fill(0)
  }
  if (dataKey.length !== DATA_KEY_BYTES) {
    dataKey.fill(0)
    throw new DataKeyError('wrapped slot did not contain a data key')
  }
  return dataKey
}

/** Every slot of a kind, in blob order. */
function slotsOfKind(blob: RecoverableDataKey, kind: SlotKind): WrappedSlot[] {
  return blob.slots.filter((s) => s.kind === kind)
}

/**
 * Set up a brand-new recoverable key: random data key, wrapped under the passphrase and under a
 * freshly generated recovery code.
 *
 * The recovery code comes back ONCE, here, and is never stored by this module or derivable from the
 * blob. Showing it, printing it, and telling the person what losing it costs are the caller's job,
 * and they are the part of this feature that actually decides whether anybody recovers anything.
 */
export async function createRecoverableDataKey(
  passphrase: string,
  params: KdfParams = DEFAULT_KDF,
): Promise<{ blob: RecoverableDataKey; recoveryCode: RecoveryCode; dataKey: Uint8Array }> {
  const dataKey = await newDataKey()
  return { ...(await wrapExistingDataKey(dataKey, passphrase, params)), dataKey }
}

/**
 * The same two wraps, over a data key the caller already has.
 *
 * This is the seam migration.ts needs: an existing user's key is not random and must not be
 * replaced, because the snapshots already on the server were encrypted under it. Enrolling them
 * into recovery is exactly "wrap the key they already have, twice".
 */
export async function wrapExistingDataKey(
  dataKey: Uint8Array,
  passphrase: string,
  params: KdfParams = DEFAULT_KDF,
): Promise<{ blob: RecoverableDataKey; recoveryCode: RecoveryCode }> {
  const recoveryCode = await newRecoveryCode()
  const passphraseSlot = await wrapDataKey(dataKey, passphrase, 'passphrase', params)
  const recoverySlot = await wrapDataKey(dataKey, recoveryCode.canonical, 'recovery', params)
  return { blob: { v: 1, slots: [passphraseSlot, recoverySlot] }, recoveryCode }
}

/** Open the data key with the passphrase. */
export async function unwrapWithPassphrase(blob: RecoverableDataKey, passphrase: string): Promise<Uint8Array> {
  validateBlob(blob)
  const slots = slotsOfKind(blob, 'passphrase')
  if (slots.length === 0) throw new DataKeyError('this blob has no passphrase slot')
  return unwrapSlot(slots[0], passphrase)
}

/**
 * Open the data key with a typed recovery code.
 *
 * THE CHECKSUM GATE IS THE POINT OF THIS FUNCTION'S SHAPE. It takes what the person typed, not a
 * parsed code, so that requireRecoveryCode() runs FIRST and a single mistyped character is refused
 * as "one character does not match the rest of the code" — instantly, before any Argon2id — rather
 * than as a decrypt failure three seconds later that cannot tell a typo from a wrong code. The two
 * failures are different errors (RecoveryCodeError vs DataKeyError) precisely so a caller can say
 * two different things, and so the tests can prove which one fired.
 *
 * Multiple recovery slots are tried in turn, because the format allows a person to hold more than
 * one code (a printed one at home and one in a safe, eventually a Shamir share). Each attempt costs
 * a full Argon2id derivation, which is acceptable for an operation that happens approximately never
 * and would be unacceptable for one that happened often.
 */
export async function unwrapWithRecoveryCode(blob: RecoverableDataKey, typedCode: string): Promise<Uint8Array> {
  validateBlob(blob)
  const code = requireRecoveryCode(typedCode)
  const slots = slotsOfKind(blob, 'recovery')
  if (slots.length === 0) throw new DataKeyError('this blob has no recovery slot')
  let lastError: unknown = null
  for (const slot of slots) {
    try {
      return await unwrapSlot(slot, code.canonical)
    } catch (err) {
      lastError = err
    }
  }
  throw lastError instanceof Error ? lastError : new DataKeyError('that recovery code does not open this blob')
}

/**
 * Re-wrap the passphrase slot under a new passphrase, leaving every other slot untouched.
 *
 * This is what the end of a recovery looks like: the person opened their data key with the code
 * because the passphrase was gone, and now needs a passphrase again. It is also an ordinary
 * passphrase change. Both cost 48 bytes and touch no snapshot, because the data key never moved —
 * which is the property the whole wrap-a-random-key design was bought for.
 *
 * The recovery slot is deliberately NOT reissued here. Changing a passphrase should not silently
 * invalidate the piece of paper in somebody's filing cabinet; if they want a new code, that is
 * rotateRecoveryCode(), and it is their decision to make knowingly.
 */
export async function replacePassphrase(
  blob: RecoverableDataKey,
  dataKey: Uint8Array,
  newPassphrase: string,
  params: KdfParams = DEFAULT_KDF,
): Promise<RecoverableDataKey> {
  validateBlob(blob)
  const slot = await wrapDataKey(dataKey, newPassphrase, 'passphrase', params)
  return { v: 1, slots: [slot, ...blob.slots.filter((s) => s.kind !== 'passphrase')] }
}

/**
 * Issue a new recovery code and drop every old one.
 *
 * Replacing rather than appending is the safe default: a code that has been photographed, emailed
 * to oneself, or left in a moving box is a live key to everything, and the reason a person rotates
 * is almost always that they no longer trust the old one. A caller that genuinely wants two live
 * codes can wrapDataKey() a second recovery slot and append it.
 */
export async function rotateRecoveryCode(
  blob: RecoverableDataKey,
  dataKey: Uint8Array,
  params: KdfParams = DEFAULT_KDF,
): Promise<{ blob: RecoverableDataKey; recoveryCode: RecoveryCode }> {
  validateBlob(blob)
  const recoveryCode = await newRecoveryCode()
  const slot = await wrapDataKey(dataKey, recoveryCode.canonical, 'recovery', params)
  return { blob: { v: 1, slots: [...blob.slots.filter((s) => s.kind !== 'recovery'), slot] }, recoveryCode }
}

/**
 * Overwrite an in-memory data key. Call it when the session ends.
 *
 * Same narrow promise as therapist/keyStore.ts zeroize(): it clears the buffer this module handed
 * out, and it is not a guarantee that no copy survives anywhere in the process.
 */
export function zeroizeDataKey(dataKey: Uint8Array | null): void {
  if (!dataKey) return
  dataKey.fill(0)
}
