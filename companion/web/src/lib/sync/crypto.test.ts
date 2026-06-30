import { describe, it, expect, beforeAll } from 'vitest'
import {
  initCrypto,
  newSalt,
  deriveKeys,
  encryptSnapshot,
  decryptSnapshot,
  signManifest,
  verifyManifest,
  manifestPublicKeyB64,
  toBase64,
  fromBase64,
  type KdfParams,
  type Manifest,
} from './crypto'

// Small KDF params keep the test fast; production defaults to >=256 MiB / 3 ops.
const FAST: KdfParams = { alg: 'argon2id', memMiB: 8, ops: 2 }
const enc = new TextEncoder()
const dec = new TextDecoder()

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
