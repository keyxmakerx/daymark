/*
 * Daymark Companion — owner sync crypto (the reference implementation).
 *
 * This is the single source of truth for the wire format. The future phone (Kotlin)
 * client MUST produce byte-identical envelopes; see ../../../../docs/SYNC_PROTOCOL.md.
 *
 * Contract (per docs/COMPANION_SECURITY.md §4):
 *   passphrase ──Argon2id(salt, mem≥256MiB, ops≥3)──▶ master(32)
 *   master ──crypto_kdf(ctx="dmsync01")──┬─ id 1 ▶ SYNC_KEY        (XChaCha20-Poly1305)
 *                                        └─ id 2 ▶ MANIFEST_SEED   (Ed25519 signing seed)
 *   snapshot blob = MAGIC("DMS1") | FMT(1) | nonce(24) | XChaCha20Poly1305(plaintext, AAD, nonce, SYNC_KEY)
 *   AAD = utf8("daymark.snapshot.v1|" + lineage + "|" + version)
 *
 * The server never sees the passphrase, the keys, or the plaintext — only opaque blobs.
 */
import _sodium from 'libsodium-wrappers-sumo'

export type Sodium = typeof _sodium
let sodium: Sodium | null = null

/** Must be awaited once before any crypto call (loads the WASM). */
export async function initCrypto(): Promise<Sodium> {
  if (sodium) return sodium
  await _sodium.ready
  sodium = _sodium
  return sodium
}

function s(): Sodium {
  if (!sodium) throw new Error('crypto not initialized — await initCrypto() first')
  return sodium
}

/** KDF parameters. Defaults meet the security doc's floor (≥256 MiB, ≥3 ops). */
export interface KdfParams {
  alg: 'argon2id'
  memMiB: number
  ops: number
}

export const DEFAULT_KDF: KdfParams = { alg: 'argon2id', memMiB: 256, ops: 3 }

export const MAGIC = new Uint8Array([0x44, 0x4d, 0x53, 0x31]) // "DMS1"
export const FMT = 0x01
const KDF_CONTEXT = 'dmsync01' // exactly 8 bytes, per crypto_kdf
const SUBKEY_SYNC = 1
const SUBKEY_MANIFEST = 2

export interface OwnerKeys {
  syncKey: Uint8Array // 32 bytes — XChaCha20-Poly1305
  manifestSeed: Uint8Array // 32 bytes — Ed25519 seed
}

/** 16-byte random KDF salt (non-secret; published in keyparams). */
export function newSalt(): Uint8Array {
  return s().randombytes_buf(s().crypto_pwhash_SALTBYTES)
}

/** passphrase + salt + params → master → purpose-separated subkeys. */
export function deriveKeys(passphrase: string, salt: Uint8Array, params: KdfParams = DEFAULT_KDF): OwnerKeys {
  const so = s()
  const master = so.crypto_pwhash(
    32,
    passphrase,
    salt,
    params.ops,
    params.memMiB * 1024 * 1024,
    so.crypto_pwhash_ALG_ARGON2ID13,
  )
  const syncKey = so.crypto_kdf_derive_from_key(32, SUBKEY_SYNC, KDF_CONTEXT, master)
  const manifestSeed = so.crypto_kdf_derive_from_key(32, SUBKEY_MANIFEST, KDF_CONTEXT, master)
  return { syncKey, manifestSeed }
}

function aad(lineage: string, version: number | bigint): Uint8Array {
  return s().from_string(`daymark.snapshot.v1|${lineage}|${version}`)
}

/** plaintext (e.g. a BackupData JSON, UTF-8) → opaque envelope bytes for the server. */
export function encryptSnapshot(plaintext: Uint8Array, syncKey: Uint8Array, lineage: string, version: number | bigint): Uint8Array {
  const so = s()
  const nonce = so.randombytes_buf(so.crypto_aead_xchacha20poly1305_ietf_NPUBBYTES)
  const ct = so.crypto_aead_xchacha20poly1305_ietf_encrypt(plaintext, aad(lineage, version), null, nonce, syncKey)
  const out = new Uint8Array(MAGIC.length + 1 + nonce.length + ct.length)
  out.set(MAGIC, 0)
  out[MAGIC.length] = FMT
  out.set(nonce, MAGIC.length + 1)
  out.set(ct, MAGIC.length + 1 + nonce.length)
  return out
}

/** Opaque envelope bytes → plaintext. Throws if tampered, wrong key, or wrong lineage/version. */
export function decryptSnapshot(envelope: Uint8Array, syncKey: Uint8Array, lineage: string, version: number | bigint): Uint8Array {
  const so = s()
  const nb = so.crypto_aead_xchacha20poly1305_ietf_NPUBBYTES
  if (envelope.length < MAGIC.length + 1 + nb) throw new Error('envelope too short')
  for (let i = 0; i < MAGIC.length; i++) if (envelope[i] !== MAGIC[i]) throw new Error('bad magic — not a Daymark snapshot envelope')
  if (envelope[MAGIC.length] !== FMT) throw new Error(`unsupported envelope format ${envelope[MAGIC.length]}`)
  const nonce = envelope.subarray(MAGIC.length + 1, MAGIC.length + 1 + nb)
  const ct = envelope.subarray(MAGIC.length + 1 + nb)
  return so.crypto_aead_xchacha20poly1305_ietf_decrypt(null, ct, aad(lineage, version), nonce, syncKey)
}

/** SHA-256 hex over arbitrary bytes (matches the server's X-Content-Hash). */
export function sha256Hex(bytes: Uint8Array): string {
  // libsodium exposes crypto_hash_sha256.
  const d = s().crypto_hash_sha256(bytes)
  return s().to_hex(d)
}

// --- Signed manifest (client-anchored integrity; see SYNC_PROTOCOL.md) ---

export interface ManifestEntry {
  version: number
  hash: string // SHA-256 hex of the stored envelope
}
export interface Manifest {
  lineage: string
  head: number
  entries: ManifestEntry[]
}

/** Canonical bytes a manifest is signed over (stable key order). */
export function manifestBytes(m: Manifest): Uint8Array {
  const canonical = JSON.stringify({
    lineage: m.lineage,
    head: m.head,
    entries: m.entries.map((e) => ({ version: e.version, hash: e.hash })),
  })
  return s().from_string(canonical)
}

export function signManifest(m: Manifest, manifestSeed: Uint8Array): { signatureB64: string; publicKeyB64: string } {
  const so = s()
  const kp = so.crypto_sign_seed_keypair(manifestSeed)
  const sig = so.crypto_sign_detached(manifestBytes(m), kp.privateKey)
  return { signatureB64: toBase64(sig), publicKeyB64: toBase64(kp.publicKey) }
}

export function verifyManifest(m: Manifest, signatureB64: string, publicKeyB64: string): boolean {
  const so = s()
  return so.crypto_sign_verify_detached(fromBase64(signatureB64), manifestBytes(m), fromBase64(publicKeyB64))
}

/** The Ed25519 public key the owner's passphrase implies (readers re-derive + compare). */
export function manifestPublicKeyB64(manifestSeed: Uint8Array): string {
  return toBase64(s().crypto_sign_seed_keypair(manifestSeed).publicKey)
}

/*
 * All base64 in the protocol is RFC 4648 §5 URL-safe, NO padding (libsodium
 * `URLSAFE_NO_PADDING`). The variant is stated EXPLICITLY here and in SYNC_PROTOCOL.md
 * so the future Kotlin client cannot accidentally use standard base64 (which this reader
 * would reject). See the conformance vector in crypto.test.ts.
 */
export function toBase64(b: Uint8Array): string {
  return s().to_base64(b, s().base64_variants.URLSAFE_NO_PADDING)
}
export function fromBase64(b64: string): Uint8Array {
  return s().from_base64(b64, s().base64_variants.URLSAFE_NO_PADDING)
}
