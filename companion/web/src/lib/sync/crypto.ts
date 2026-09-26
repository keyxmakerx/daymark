/*
 * Daymark Companion — owner sync crypto (the reference implementation).
 *
 * This is the single source of truth for the wire format. The future phone (Kotlin)
 * client MUST produce byte-identical envelopes; see ../../../../docs/SYNC_PROTOCOL.md.
 *
 * Contract (per docs/COMPANION_SECURITY.md §4):
 *   passphrase ──Argon2id(salt, 256≤mem≤512 MiB, 3≤ops≤8)──▶ master(32)
 *   master ──crypto_kdf(ctx="dmsync01")──┬─ id 1 ▶ SYNC_KEY        (XChaCha20-Poly1305)
 *                                        └─ id 2 ▶ MANIFEST_SEED   (Ed25519 signing seed)
 *   snapshot blob = MAGIC("DMS1") | FMT | nonce(24) | XChaCha20Poly1305(body, AAD, nonce, SYNC_KEY)
 *     FMT 0x02, the only format written:  body = pad(plaintext)   AAD = utf8("daymark.snapshot.v2|" + lineage + "|" + version)
 *     FMT 0x01, still read, never written: body = plaintext        AAD = utf8("daymark.snapshot.v1|" + lineage + "|" + version)
 *
 * The server never sees the passphrase, the keys, or the plaintext — only opaque blobs.
 *
 * PADDED SNAPSHOTS (#315). A snapshot is padded by ../padding.ts before it is encrypted, so the
 * stored size says which size bucket it falls in and not how much was written between two syncs.
 * Padding hides how much, never when: the server still sees when each version arrives.
 *
 * The format byte sits outside the ciphertext, where the server can change it, so each format
 * names itself in the associated data as well. A server that turns 0x02 into 0x01 to make a reader
 * skip unpadding (or 0x01 into 0x02) changes the associated data the reader authenticates against,
 * and the envelope fails to open instead of opening in the wrong form. Unpadding happens only after
 * the AEAD has authenticated the body; it is strict, so a writer that pads wrongly is refused
 * loudly rather than read loosely.
 *
 * Format 1 is opened and never written: every snapshot stored before #315 is in it, and nothing
 * about it is unsound except that it tells the server exact sizes.
 *
 * THE WEB CONSOLE'S LANE (#345; docs/SYNC_PROTOCOL.md, the lane section). What the owner's console
 * adds to the person's record travels in lineages of its own, each version sealed in exactly the
 * bytes of a format-2 snapshot — magic, 0x02, nonce, padded body — under the same sync key, and
 * told apart by its associated data alone:
 *
 *   lane version = MAGIC | 0x02 | nonce(24) | XChaCha20Poly1305(pad(plaintext), AAD, nonce, SYNC_KEY)
 *     AAD = utf8("daymark.lane.v1|" + lineage + "|" + version)
 *
 * So a lane version never opens as a snapshot and a snapshot never opens as a lane version, under
 * the same key, lineage and version: the AEAD refuses before a byte of either is read as the other.
 * The layout is shared on purpose, so that the associated data is the whole of the separation and a
 * test can show it is.
 */
import _sodium from 'libsodium-wrappers-sumo'
import { pad, paddedLength, unpad, PaddingError } from '../padding'

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

/**
 * The range every reader holds KDF parameters to before it derives anything (docs/SYNC_PROTOCOL.md
 * §1.2): Argon2id, at least KDF_FLOOR and at most KDF_CEILING, in memory and in passes alike. The
 * parameters travel inside what the server hands out, so both ends are the server's to move. Under
 * the floor, a key is cheap to guess from what the server stores. Over the ceiling, the server
 * would decide how much memory and time a reader spends, because Argon2id runs before the AEAD can
 * refuse anything. sync-crypto's SyncCrypto.KdfParams holds the phone to the same four numbers.
 */
export const KDF_FLOOR = { memMiB: 256, ops: 3 } as const
export const KDF_CEILING = { memMiB: 512, ops: 8 } as const

/** Exactly the floor, which is what every writer uses: well inside the ceiling. */
export const DEFAULT_KDF: KdfParams = { alg: 'argon2id', memMiB: KDF_FLOOR.memMiB, ops: KDF_FLOOR.ops }

/**
 * Where KDF parameters fall against the range: 'belowFloor' for anything that is not Argon2id at or
 * above the floor, 'aboveCeiling' for Argon2id past the ceiling, and 'inRange' otherwise. The floor
 * is checked first, as the phone checks it, so parameters under one bound and over the other are
 * 'belowFloor' on both sides. Every comparison is written so that a value that is not a number
 * fails it: a member that is missing is refused here, not handed to libsodium.
 */
export function kdfRange(params: KdfParams | undefined | null): 'inRange' | 'belowFloor' | 'aboveCeiling' {
  if (!params || params.alg !== 'argon2id') return 'belowFloor'
  if (!(params.memMiB >= KDF_FLOOR.memMiB && params.ops >= KDF_FLOOR.ops)) return 'belowFloor'
  if (!(params.memMiB <= KDF_CEILING.memMiB && params.ops <= KDF_CEILING.ops)) return 'aboveCeiling'
  return 'inRange'
}

export const MAGIC = new Uint8Array([0x44, 0x4d, 0x53, 0x31]) // "DMS1"
/** The unpadded format of every snapshot stored before #315. Opened, never written. */
export const FMT_UNPADDED = 0x01
/** The padded format (#315): the only one encryptSnapshot writes. */
export const FMT_PADDED = 0x02
/** Each format's name in the associated data, so the format byte cannot be changed on its own. */
const AAD_CONTEXT: Readonly<Record<number, string>> = {
  [FMT_UNPADDED]: 'daymark.snapshot.v1',
  [FMT_PADDED]: 'daymark.snapshot.v2',
}
// Fixed by the algorithm (crypto_aead_xchacha20poly1305_ietf_NPUBBYTES and _ABYTES). Written out
// so a snapshot's stored size can be worked out before libsodium has loaded.
const NONCE_BYTES = 24
const TAG_BYTES = 16
const HEADER_BYTES = MAGIC.length + 1 + NONCE_BYTES
/** The u32 length prefix ../padding.ts puts in front of the plaintext (its LAYOUT). */
const PAD_PREFIX_BYTES = 4
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

/** The lane's name in the associated data (#345): never a snapshot's, so neither opens as the other. */
export const LANE_CONTEXT = 'daymark.lane.v1'

function aad(format: number, lineage: string, version: number | bigint): Uint8Array {
  return s().from_string(`${AAD_CONTEXT[format]}|${lineage}|${version}`)
}

/** MAGIC | FMT_PADDED | nonce | the AEAD of pad(plaintext) under this associated data. */
function sealPadded(plaintext: Uint8Array, associated: Uint8Array, syncKey: Uint8Array): Uint8Array {
  const so = s()
  const nonce = so.randombytes_buf(NONCE_BYTES)
  const ct = so.crypto_aead_xchacha20poly1305_ietf_encrypt(pad(plaintext), associated, null, nonce, syncKey)
  const out = new Uint8Array(HEADER_BYTES + ct.length)
  out.set(MAGIC, 0)
  out[MAGIC.length] = FMT_PADDED
  out.set(nonce, MAGIC.length + 1)
  out.set(ct, HEADER_BYTES)
  return out
}

/** The magic and the format byte of an envelope, checked before anything is decrypted. */
function envelopeFormat(envelope: Uint8Array): number {
  if (envelope.length < HEADER_BYTES) throw new Error('envelope too short')
  for (let i = 0; i < MAGIC.length; i++) if (envelope[i] !== MAGIC[i]) throw new Error('bad magic — not a Daymark snapshot envelope')
  return envelope[MAGIC.length]!
}

/** The body of an envelope under this associated data, still padded. Throws if it does not open. */
function openBody(envelope: Uint8Array, associated: Uint8Array, syncKey: Uint8Array): Uint8Array {
  const nonce = envelope.subarray(MAGIC.length + 1, HEADER_BYTES)
  const ct = envelope.subarray(HEADER_BYTES)
  return s().crypto_aead_xchacha20poly1305_ietf_decrypt(null, ct, associated, nonce, syncKey)
}

/**
 * The size of the blob encryptSnapshot writes for a plaintext of `plaintextLength` bytes: the
 * header, the padded body and the tag. Needs no libsodium, so a writer can check a snapshot
 * against a size limit before it derives a key or sends anything.
 */
export function snapshotBlobLength(plaintextLength: number): number {
  return HEADER_BYTES + paddedLength(PAD_PREFIX_BYTES + plaintextLength) + TAG_BYTES
}

/** The size the same snapshot had in format 1, unpadded. Only ever reported, never written. */
export function unpaddedSnapshotBlobLength(plaintextLength: number): number {
  return HEADER_BYTES + plaintextLength + TAG_BYTES
}

/** plaintext (e.g. a BackupData JSON, UTF-8) → opaque envelope bytes for the server, padded (format 2). */
export function encryptSnapshot(plaintext: Uint8Array, syncKey: Uint8Array, lineage: string, version: number | bigint): Uint8Array {
  return sealPadded(plaintext, aad(FMT_PADDED, lineage, version), syncKey)
}

/**
 * Opaque envelope bytes → plaintext, from either format. Throws if tampered, wrong key, wrong
 * lineage/version, or the format byte was changed (the associated data names the format).
 */
export function decryptSnapshot(envelope: Uint8Array, syncKey: Uint8Array, lineage: string, version: number | bigint): Uint8Array {
  const format = envelopeFormat(envelope)
  if (format !== FMT_PADDED && format !== FMT_UNPADDED) throw new Error(`unsupported envelope format ${format}`)
  const body = openBody(envelope, aad(format, lineage, version), syncKey)
  if (format === FMT_UNPADDED) return body
  try {
    return unpad(body)
  } catch (e) {
    if (e instanceof PaddingError) throw new Error('snapshot opened, but its padding is not in the standard form')
    throw e
  }
}

/** The associated data of one lane version: the lane's own context, its lineage and its version. */
function laneAad(lineage: string, version: number | bigint): Uint8Array {
  return s().from_string(`${LANE_CONTEXT}|${lineage}|${version}`)
}

/**
 * One version of the web console's lane (a UTF-8 JSON, lane/record.ts) → envelope bytes, padded, in
 * a format-2 snapshot's layout under the lane's associated data (see the header).
 */
export function encryptLaneVersion(plaintext: Uint8Array, syncKey: Uint8Array, lineage: string, version: number | bigint): Uint8Array {
  return sealPadded(plaintext, laneAad(lineage, version), syncKey)
}

/**
 * Envelope bytes → one lane version's plaintext. Throws for anything that is not a lane version of
 * this lineage and version under this key, a snapshot of the same lineage and version included, and
 * for padding that is not in the standard form. Only the padded format exists for a lane.
 */
export function decryptLaneVersion(envelope: Uint8Array, syncKey: Uint8Array, lineage: string, version: number | bigint): Uint8Array {
  const format = envelopeFormat(envelope)
  if (format !== FMT_PADDED) throw new Error(`unsupported lane envelope format ${format}`)
  const body = openBody(envelope, laneAad(lineage, version), syncKey)
  try {
    return unpad(body)
  } catch (e) {
    if (e instanceof PaddingError) throw new Error('lane version opened, but its padding is not in the standard form')
    throw e
  }
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
