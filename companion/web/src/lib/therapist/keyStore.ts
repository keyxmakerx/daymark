/*
 * Therapist TOTP-path KEY CUSTODY (the honestly-weaker path — see LowerAssuranceBanner).
 *
 * The therapist's long-term X25519 (box) + Ed25519 (sign) SECRET keys are stored server-side as an
 * Argon2id-wrapped blob and unwrapped IN THE BROWSER under a client-set reading passphrase that is
 * DISTINCT from the TOTP login secret. The server never sees the passphrase, the keys, or any
 * plaintext — it holds only the opaque wrapped blob.
 *
 *   master     = Argon2id(passphrase, salt, mem≥256MiB, ops≥3)          // floor reused from sync/crypto
 *   plaintext  = box.priv(32) || box.pub(32) || sign.priv(64) || sign.pub(32)   // 160 bytes, fixed layout
 *   ct         = XChaCha20-Poly1305(plaintext, aad="daymark.tkeys.v1", nonce, master)
 *   blob       = { v:1, kdf, saltB64, nonceB64, ctB64 }
 *
 * Keys are held in memory ONLY and wiped by zeroize() on logout/idle. Never persisted to disk.
 *
 * HONEST LIMITATION: the passphrase and the unwrapped keys are entered/derived in a page the server
 * serves, so a hostile-server tampered page could capture them. This is a convenience path; the
 * banner states it plainly. WebAuthn-PRF wrapping (which would defeat that) is a separate slice.
 */
import _sodium from 'libsodium-wrappers-sumo'
import { initAssignmentCrypto, type BoxKeyPair, type SignKeyPair } from '../assignments/crypto'
import type { KdfParams } from '../sync/crypto'

const URLSAFE = () => _sodium.base64_variants.URLSAFE_NO_PADDING
const enc = new TextEncoder()

const WRAP_AAD = 'daymark.tkeys.v1'
const BOX_SK = 32
const BOX_PK = 32
const SIGN_SK = 64
const SIGN_PK = 32
const KEYS_LEN = BOX_SK + BOX_PK + SIGN_SK + SIGN_PK // 160

export interface WrappedKeyBlob {
  v: 1
  kdf: KdfParams
  saltB64: string
  nonceB64: string
  ctB64: string
}

export interface TherapistKeys {
  box: BoxKeyPair // X25519 — opens sealed shares (crypto_box_seal_open)
  sign: SignKeyPair // Ed25519 — signs assignments + game plans
}

export class KeyUnwrapError extends Error {}

/** Reject server-supplied KDF params below the security floor (downgrade defense). */
function validateKdf(params: KdfParams) {
  if (params.alg !== 'argon2id' || params.memMiB < 256 || params.ops < 3) {
    throw new KeyUnwrapError('wrapped-key KDF parameters are below the security floor — refusing to derive')
  }
}

/** Wrap a keypair set under a reading passphrase (used by tests + a future enrol UI). */
export async function wrap(keys: TherapistKeys, passphrase: string, params: KdfParams = { alg: 'argon2id', memMiB: 256, ops: 3 }): Promise<WrappedKeyBlob> {
  const so = await initAssignmentCrypto()
  validateKdf(params)
  const salt = so.randombytes_buf(so.crypto_pwhash_SALTBYTES)
  const master = so.crypto_pwhash(32, passphrase, salt, params.ops, params.memMiB * 1024 * 1024, so.crypto_pwhash_ALG_ARGON2ID13)
  const plain = new Uint8Array(KEYS_LEN)
  let o = 0
  plain.set(keys.box.privateKey.subarray(0, BOX_SK), o); o += BOX_SK
  plain.set(keys.box.publicKey.subarray(0, BOX_PK), o); o += BOX_PK
  plain.set(keys.sign.privateKey.subarray(0, SIGN_SK), o); o += SIGN_SK
  plain.set(keys.sign.publicKey.subarray(0, SIGN_PK), o)
  const nonce = so.randombytes_buf(so.crypto_aead_xchacha20poly1305_ietf_NPUBBYTES)
  const ct = so.crypto_aead_xchacha20poly1305_ietf_encrypt(plain, enc.encode(WRAP_AAD), null, nonce, master)
  // `plain` is our own concatenation of the caller's secret keys and `master` unlocks the blob;
  // neither is needed past this line, and both used to stay live for as long as the tab did.
  // Wiping them does not touch the caller's own key buffers — those are theirs to zeroize().
  plain.fill(0)
  master.fill(0)
  const blob: WrappedKeyBlob = {
    v: 1,
    kdf: params,
    saltB64: so.to_base64(salt, URLSAFE()),
    nonceB64: so.to_base64(nonce, URLSAFE()),
    ctB64: so.to_base64(ct, URLSAFE()),
  }
  return blob
}

/** Unwrap the therapist keys from the wrapped blob under the reading passphrase. Throws on failure. */
export async function unwrap(blob: WrappedKeyBlob, passphrase: string): Promise<TherapistKeys> {
  const so = await initAssignmentCrypto()
  if (blob.v !== 1) throw new KeyUnwrapError('unsupported wrapped-key blob version')
  validateKdf(blob.kdf)
  const salt = so.from_base64(blob.saltB64, URLSAFE())
  const nonce = so.from_base64(blob.nonceB64, URLSAFE())
  const ct = so.from_base64(blob.ctB64, URLSAFE())
  const master = so.crypto_pwhash(32, passphrase, salt, blob.kdf.ops, blob.kdf.memMiB * 1024 * 1024, so.crypto_pwhash_ALG_ARGON2ID13)
  let plain: Uint8Array
  try {
    plain = so.crypto_aead_xchacha20poly1305_ietf_decrypt(null, ct, enc.encode(WRAP_AAD), nonce, master)
  } catch {
    throw new KeyUnwrapError('wrong reading passphrase or tampered key blob')
  } finally {
    // `master` opens this blob on its own, and nothing downstream needs it. It used to survive
    // here — including on the wrong-passphrase path, where the throw skipped straight past any
    // cleanup — so a passphrase-equivalent secret sat in the heap for the life of the tab.
    master.fill(0)
  }
  // Every exit from here on wipes `plain` first. The four keys below are slice() COPIES, so the
  // 160-byte concatenation is a second, independent home for the therapist's secret keys; before
  // this it was never cleared, and zeroize() — which only ever saw the copies — left it intact.
  // What wiping buys is narrow and worth stating plainly: it shortens the window and removes the
  // copies we made ourselves. JavaScript cannot promise more than that. The engine may have moved
  // these bytes during GC, libsodium's WASM heap holds its own working buffers, and the
  // passphrase arrived as an immutable string we cannot overwrite at all.
  if (plain.length !== KEYS_LEN) {
    plain.fill(0)
    throw new KeyUnwrapError('wrapped-key plaintext has the wrong length')
  }
  let o = 0
  const boxPriv = plain.slice(o, o + BOX_SK); o += BOX_SK
  const boxPub = plain.slice(o, o + BOX_PK); o += BOX_PK
  const signPriv = plain.slice(o, o + SIGN_SK); o += SIGN_SK
  const signPub = plain.slice(o, o + SIGN_PK)
  plain.fill(0)
  return {
    box: { publicKey: boxPub, privateKey: boxPriv },
    sign: { publicKey: signPub, privateKey: signPriv },
  }
}

/**
 * Overwrite in-memory secret key bytes. Call on logout/idle.
 *
 * This wipes the buffers this module handed out. It is not a guarantee that no copy of the keys
 * remains anywhere in the process — see the note in unwrap().
 */
export function zeroize(keys: TherapistKeys | null): void {
  if (!keys) return
  keys.box.privateKey.fill(0)
  keys.box.publicKey.fill(0)
  keys.sign.privateKey.fill(0)
  keys.sign.publicKey.fill(0)
}
