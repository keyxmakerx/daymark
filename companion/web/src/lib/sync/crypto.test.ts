import { describe, it, expect, beforeAll } from 'vitest'
import { createHash } from 'node:crypto'
import _sodium from 'libsodium-wrappers-sumo'
import {
  initCrypto,
  newSalt,
  deriveKeys,
  encryptSnapshot,
  decryptSnapshot,
  snapshotBlobLength,
  unpaddedSnapshotBlobLength,
  signManifest,
  verifyManifest,
  manifestPublicKeyB64,
  toBase64,
  fromBase64,
  MAGIC,
  FMT_PADDED,
  FMT_UNPADDED,
  type KdfParams,
  type Manifest,
} from './crypto'
import { MIN_PADDED, pad, paddedLength } from '../padding'

// Small KDF params keep the test fast; production defaults to >=256 MiB / 3 ops.
const FAST: KdfParams = { alg: 'argon2id', memMiB: 8, ops: 2 }
const enc = new TextEncoder()
const dec = new TextDecoder()
const MiB = 1 << 20

/** What a snapshot blob adds to its body: magic (4), format byte (1), nonce (24) and tag (16). */
const OVERHEAD = 4 + 1 + 24 + 16

/** A length the padding could have produced for some input: the buckets, and nothing else. */
function isBucket(len: number): boolean {
  return len >= MIN_PADDED && paddedLength(len) === len
}

/** Byte-for-byte equality; toEqual walks a 1 MiB array element by element and takes seconds. */
function sameBytes(a: Uint8Array, b: Uint8Array): boolean {
  return Buffer.from(a.buffer, a.byteOffset, a.byteLength).equals(Buffer.from(b.buffer, b.byteOffset, b.byteLength))
}

function bytes(n: number, seed = 7): Uint8Array {
  const b = new Uint8Array(n)
  for (let i = 0; i < n; i++) b[i] = (i * 31 + seed) & 0xff
  return b
}

const hex = (b: Uint8Array): string => Buffer.from(b.buffer, b.byteOffset, b.byteLength).toString('hex')
const unhex = (h: string): Uint8Array => new Uint8Array(Buffer.from(h, 'hex'))

/**
 * A snapshot envelope built from its documented parts (docs/SYNC_PROTOCOL.md §1.1), not by the
 * module: magic, format byte, nonce, then the AEAD of `body` under `aadText`. Tests use it to make
 * the format-1 envelopes nothing writes any more, and to state format 2 independently of the writer.
 */
function envelopeFromParts(format: number, body: Uint8Array, aadText: string, nonce: Uint8Array, key: Uint8Array): Uint8Array {
  const ct = _sodium.crypto_aead_xchacha20poly1305_ietf_encrypt(body, enc.encode(aadText), null, nonce, key)
  const out = new Uint8Array(OVERHEAD - 16 + ct.length)
  out.set(MAGIC, 0)
  out[4] = format
  out.set(nonce, 5)
  out.set(ct, 29)
  return out
}

describe('owner sync crypto', () => {
  beforeAll(async () => {
    await initCrypto()
  })

  it('round-trips a snapshot under the derived key', () => {
    const salt = newSalt()
    const keys = deriveKeys('correct horse battery staple', salt, FAST)
    const plaintext = enc.encode(JSON.stringify({ version: 12, entries: [{ id: 1, moodLevel: 4 }] }))
    const blob = encryptSnapshot(plaintext, keys.syncKey, 'devA', 7)
    const back = decryptSnapshot(blob, keys.syncKey, 'devA', 7)
    expect(dec.decode(back)).toBe(dec.decode(plaintext))
  })

  it('is deterministic: same passphrase+salt+params derive the same key', () => {
    const salt = newSalt()
    const a = deriveKeys('pass', salt, FAST)
    const b = deriveKeys('pass', salt, FAST)
    expect(Buffer.from(a.syncKey)).toEqual(Buffer.from(b.syncKey))
  })

  it('fails to decrypt with the wrong passphrase', () => {
    const salt = newSalt()
    const good = deriveKeys('right', salt, FAST)
    const bad = deriveKeys('wrong', salt, FAST)
    const blob = encryptSnapshot(enc.encode('secret'), good.syncKey, 'devA', 0)
    expect(() => decryptSnapshot(blob, bad.syncKey, 'devA', 0)).toThrow()
  })

  it('detects tampering (AEAD)', () => {
    const keys = deriveKeys('p', newSalt(), FAST)
    const blob = encryptSnapshot(enc.encode('hello'), keys.syncKey, 'devA', 0)
    blob[blob.length - 1] ^= 0x01 // flip a ciphertext bit
    expect(() => decryptSnapshot(blob, keys.syncKey, 'devA', 0)).toThrow()
  })

  it('binds lineage+version via AAD (wrong version fails)', () => {
    const keys = deriveKeys('p', newSalt(), FAST)
    const blob = encryptSnapshot(enc.encode('hi'), keys.syncKey, 'devA', 3)
    expect(() => decryptSnapshot(blob, keys.syncKey, 'devA', 4)).toThrow()
    expect(() => decryptSnapshot(blob, keys.syncKey, 'devB', 3)).toThrow()
  })

  it('rejects a non-Daymark envelope', () => {
    const keys = deriveKeys('p', newSalt(), FAST)
    expect(() => decryptSnapshot(new Uint8Array([9, 9, 9, 9, 1, 2, 3]), keys.syncKey, 'devA', 0)).toThrow(/magic|short/)
  })

  it('base64 is URL-safe, no padding (conformance vector for the Kotlin client)', () => {
    // bytes 0x00..0x0F → RFC 4648 §5 URL-safe, no padding.
    const bytes = new Uint8Array(Array.from({ length: 16 }, (_, i) => i))
    const b64 = toBase64(bytes)
    expect(b64).toBe('AAECAwQFBgcICQoLDA0ODw')
    expect(b64).not.toMatch(/[+/=]/) // never standard-base64 chars or padding
    expect(Buffer.from(fromBase64(b64))).toEqual(Buffer.from(bytes))
  })

  it('signs and verifies a manifest; rejects tampering', () => {
    const keys = deriveKeys('p', newSalt(), FAST)
    const m: Manifest = { lineage: 'devA', head: 2, entries: [{ version: 2, hash: 'abc' }] }
    const { signatureB64, publicKeyB64 } = signManifest(m, keys.manifestSeed)
    expect(publicKeyB64).toBe(manifestPublicKeyB64(keys.manifestSeed))
    expect(verifyManifest(m, signatureB64, publicKeyB64)).toBe(true)
    const tampered: Manifest = { ...m, entries: [{ version: 2, hash: 'EVIL' }] }
    expect(verifyManifest(tampered, signatureB64, publicKeyB64)).toBe(false)
  })
})

describe('snapshots are padded before they are encrypted (#315)', () => {
  let key: Uint8Array
  const nonce = Uint8Array.from({ length: 24 }, (_, i) => 0xa0 + i)

  beforeAll(async () => {
    await initCrypto()
    key = _sodium.randombytes_buf(32)
  })

  it('every snapshot is written in format 2, with a body that is a bucket size', () => {
    for (const n of [0, 1, 19, 4091, 4092, 4093, 8187, 8188, 8189, 70_000, MiB - 5, MiB - 4, MiB - 3]) {
      const blob = encryptSnapshot(bytes(n), key, 'devA', 3)
      expect(blob[4], `n=${n}`).toBe(FMT_PADDED)
      expect(isBucket(blob.length - OVERHEAD), `n=${n}`).toBe(true)
      expect(blob.length - OVERHEAD, `n=${n}`).toBe(paddedLength(4 + n))
      expect(blob.length, `n=${n}`).toBe(snapshotBlobLength(n))
    }
  })

  it('the same check fails on an unpadded snapshot (positive control)', () => {
    const n = 5000
    const legacy = envelopeFromParts(FMT_UNPADDED, bytes(n), 'daymark.snapshot.v1|devA|3', nonce, key)
    expect(legacy.length - OVERHEAD).toBe(n)
    expect(Number.isInteger(Math.log2(n))).toBe(false) // so its length is not already a bucket size
    expect(isBucket(legacy.length - OVERHEAD)).toBe(false)
    expect(legacy.length).toBe(unpaddedSnapshotBlobLength(n))
  })

  it('round-trips one byte under, exactly on and one byte over each boundary, including 1 MiB', () => {
    // The 4-byte length prefix counts toward the bucket, so each boundary sits 4 bytes below it.
    for (let boundary = MIN_PADDED; boundary <= MiB; boundary *= 2) {
      for (const n of [boundary - 5, boundary - 4, boundary - 3]) {
        const plain = bytes(n, n)
        const blob = encryptSnapshot(plain, key, 'devA', n)
        expect(sameBytes(decryptSnapshot(blob, key, 'devA', n), plain), `n=${n}`).toBe(true)
      }
    }
    // One byte over 1 MiB is the first Padmé size, not the next power of two.
    expect(snapshotBlobLength(MiB - 3) - OVERHEAD).toBe(1_081_344)
  })

  it('a snapshot stored unpadded, in format 1, still opens', () => {
    const plain = bytes(5000)
    const legacy = envelopeFromParts(FMT_UNPADDED, plain, 'daymark.snapshot.v1|devA|3', nonce, key)
    expect(legacy[4]).toBe(0x01)
    expect(sameBytes(decryptSnapshot(legacy, key, 'devA', 3), plain)).toBe(true)
  })

  it('a changed format byte fails to open, so it cannot make a reader skip unpadding', () => {
    const plain = bytes(100)
    const blob = encryptSnapshot(plain, key, 'devA', 3)
    expect(sameBytes(decryptSnapshot(blob, key, 'devA', 3), plain)).toBe(true) // the unchanged blob opens
    const relabelled = blob.slice()
    relabelled[4] = blob[4] === FMT_PADDED ? FMT_UNPADDED : FMT_PADDED
    expect(relabelled[4]).not.toBe(blob[4])
    expect(() => decryptSnapshot(relabelled, key, 'devA', 3)).toThrow()

    // And the other way: a format-1 envelope relabelled as format 2.
    const legacy = envelopeFromParts(FMT_UNPADDED, plain, 'daymark.snapshot.v1|devA|3', nonce, key)
    expect(sameBytes(decryptSnapshot(legacy, key, 'devA', 3), plain)).toBe(true)
    const promoted = legacy.slice()
    promoted[4] = legacy[4] === FMT_UNPADDED ? FMT_PADDED : FMT_UNPADDED
    expect(promoted[4]).not.toBe(legacy[4])
    expect(() => decryptSnapshot(promoted, key, 'devA', 3)).toThrow()
  })

  it('refuses a format byte it does not know', () => {
    const blob = encryptSnapshot(bytes(10), key, 'devA', 3)
    const unknown = blob.slice()
    unknown[4] = Math.max(FMT_PADDED, FMT_UNPADDED) + 1
    expect([FMT_PADDED, FMT_UNPADDED]).not.toContain(unknown[4])
    expect(() => decryptSnapshot(unknown, key, 'devA', 3)).toThrow(/unsupported envelope format/)
  })

  it('refuses a format-2 body whose padding pad() could not have produced', () => {
    // Only a holder of the sync key can make one. Strictness makes a writer that pads wrongly fail
    // loudly instead of writing snapshots only it can read.
    const good = pad(bytes(10))
    const opened = decryptSnapshot(envelopeFromParts(FMT_PADDED, good, 'daymark.snapshot.v2|devA|3', nonce, key), key, 'devA', 3)
    expect(sameBytes(opened, bytes(10))).toBe(true) // the same construction, well padded, opens
    const bad = good.slice()
    const last = bad.length - 1
    bad[last] = good[last] === 0 ? 1 : 0
    expect(bad[last]).not.toBe(good[last])
    const blob = envelopeFromParts(FMT_PADDED, bad, 'daymark.snapshot.v2|devA|3', nonce, key)
    expect(() => decryptSnapshot(blob, key, 'devA', 3)).toThrow(/padding/)
  })
})

describe('the snapshot vectors the phone is held to (#316)', () => {
  /*
   * Format 1 is pinned in sync-crypto's SyncCryptoTest.kt: the sync key derived from this
   * passphrase and salt, and the ciphertext of these bytes under this nonce, lineage and version.
   * The web holds the same bytes here, as a whole envelope, so a stored unpadded snapshot keeps
   * opening. Format 2 is the same inputs, padded, under the format-2 associated data. A change to
   * any constant below is a change to the wire format, never a refactor.
   */
  const SALT_0_TO_15 = Uint8Array.from({ length: 16 }, (_, i) => i)
  const SYNC_KEY = '3d0a8d769e09df76fcb676a3e0eb792f3a5f2106c9d81759622eadd73a51fd65'
  const NONCE_1_TO_24 = Uint8Array.from({ length: 24 }, (_, i) => i + 1)
  const PLAINTEXT = '{"hello":"daymark"}'
  // As SyncCryptoTest.kt pins it: the AEAD output alone, ciphertext then tag.
  const V1_CIPHERTEXT = '02658812b667c934edd8a3ff9a50b3882b1a33621fa8f0d2f9a3fb272e12f29c387699'
  const V2_HEADER = '444d5331' + '02' + '0102030405060708090a0b0c0d0e0f101112131415161718'
  const V2_LENGTH = 4141 // 29-byte header, the 4096-byte padded body, the 16-byte tag
  const V2_CIPHERTEXT_HEAD = '7947e064a129ce73bb96a8bcd91fb69b' // the first 16 bytes after the header
  const V2_TAG = 'd55196e0a95b086092d65200787c1a49'
  const V2_SHA256 = 'ab71fc3e71bb1fff1a1e1ad1ca04c2a59e9192c686d7b6b6d0356561b40841c3'

  let key: Uint8Array
  beforeAll(async () => {
    await initCrypto()
    key = deriveKeys('conformance-vector', SALT_0_TO_15, FAST).syncKey
  })

  it('the key is the one SyncCryptoTest.kt derives from the same passphrase and salt', () => {
    expect(hex(key)).toBe(SYNC_KEY)
  })

  it('format 1: the envelope around the ciphertext SyncCryptoTest.kt pins opens to the plaintext', () => {
    const envelope = new Uint8Array([...MAGIC, 0x01, ...NONCE_1_TO_24, ...unhex(V1_CIPHERTEXT)])
    expect(envelope.length).toBe(64)
    expect(dec.decode(decryptSnapshot(envelope, key, 'devA', 7))).toBe(PLAINTEXT)
  })

  it('format 2: the same inputs, padded, give exactly these bytes, and they open', () => {
    const body = pad(enc.encode(PLAINTEXT))
    expect(body.length).toBe(4096)
    const envelope = envelopeFromParts(0x02, body, 'daymark.snapshot.v2|devA|7', NONCE_1_TO_24, key)
    expect(envelope.length).toBe(V2_LENGTH)
    expect(hex(envelope.subarray(0, 29))).toBe(V2_HEADER)
    expect(hex(envelope.subarray(29, 45))).toBe(V2_CIPHERTEXT_HEAD)
    expect(hex(envelope.subarray(V2_LENGTH - 16))).toBe(V2_TAG)
    expect(createHash('sha256').update(envelope).digest('hex')).toBe(V2_SHA256)
    expect(dec.decode(decryptSnapshot(envelope, key, 'devA', 7))).toBe(PLAINTEXT)
  })

  it('the vector pins the associated data too: the same body under the format-1 name has another tag', () => {
    const underV1Name = envelopeFromParts(0x02, pad(enc.encode(PLAINTEXT)), 'daymark.snapshot.v1|devA|7', NONCE_1_TO_24, key)
    expect(hex(underV1Name.subarray(29, 45))).toBe(V2_CIPHERTEXT_HEAD) // same key, nonce and body
    expect(hex(underV1Name.subarray(V2_LENGTH - 16))).not.toBe(V2_TAG)
  })

  it('the writer makes exactly that construction, for whatever nonce it draws', () => {
    const blob = encryptSnapshot(enc.encode(PLAINTEXT), key, 'devA', 7)
    const drawn = blob.slice(5, 29)
    const rebuilt = envelopeFromParts(0x02, pad(enc.encode(PLAINTEXT)), 'daymark.snapshot.v2|devA|7', drawn, key)
    expect(sameBytes(blob, rebuilt)).toBe(true)
  })
})
