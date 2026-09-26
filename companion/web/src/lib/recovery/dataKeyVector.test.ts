import { describe, it, expect, beforeAll, afterEach, vi } from 'vitest'
import { createHash } from 'node:crypto'
import _sodium from 'libsodium-wrappers-sumo'
import { initCrypto, deriveKeys, fromBase64, type KdfParams } from '../sync/crypto'
import { wrapDataKey, unwrapWithPassphrase, unwrapWithRecoveryCode, DataKeyError, type RecoverableDataKey } from './dataKey'
import { masterFromPassphrase, subkeysFromMaster } from './migration'
import { checkSymbol, normalizeRecoveryInput, parseRecoveryCode, RecoveryCodeError } from './recoveryCode'
import { SUBKEY_OWNER_BOX, SUBKEY_OWNER_SIGN } from '../owner/identity'

/*
 * THE KEY DOCUMENTS THE PHONE IS HELD TO (#403).
 *
 * `GET /v1/keydoc` serves the key parameters until the owner has a wrapped key, and the wrapped key
 * from then on (docs/SYNC_PROTOCOL.md §1.2). The phone reads both, in sync-crypto's KeyDocument.kt,
 * and sync-crypto's KeyDocumentVectorTest.kt holds it to every constant below: the same two
 * documents, the same passphrase, the same recovery code as a person might type it, the same master
 * and the same four subkeys. The two documents are an enrolment: the wrapped key locks the master
 * that the key parameters derive.
 *
 * The wrapped key here is made by this module's own writer, wrapDataKey, with only its random draws
 * pinned, and the Kotlin writer makes the identical bytes from the same inputs. So a document the
 * web made opens on the phone, and one the phone made opens here: they are this one document.
 *
 * The refusals at the end are the ones both sides make before any key is derived, listed by the same
 * names in KeyDocumentVectorTest.kt. A change to any constant below is a change to the wire format,
 * never a refactor.
 */

const hex = (b: Uint8Array) => Buffer.from(b).toString('hex')
const seq = (start: number, n: number) => Uint8Array.from({ length: n }, (_, i) => start + i)

/** Two-, three- and four-byte UTF-8 sequences, so the vector pins how a passphrase becomes bytes. */
const PASSPHRASE = 'wrapped-key vector: caf\u00e9, \u65e5\u8a18, \u{1F33F}'
const PASSPHRASE_UTF8 =
  '777261707065642d6b657920766563746f723a20636166c3a92c20e697a5e8a8982c20f09f8cbf'

const PAYLOAD = 'K7M2QXR9CT4HWAZP3NE8GUV6DYJF5'
const CODE = 'K7M2QXR9CT4HWAZP3NE8GUV6DYJF59'
/** The code as a person might type it back: lower case, an en dash, a no-break space, a spaced em dash, a tab, a newline. */
const TYPED_CODE = ' k7m2q\u2013xr9ct\u00a04hwaz \u2014 p3ne8\tguv6d\nyjf59 '

const KEY_PARAMS =
  '{"v":1,"alg":"xchacha20poly1305","kdf":{"alg":"argon2id","memMiB":256,"ops":3},"saltB64":"EBESExQVFhcYGRobHB0eHw"}'

const PASSPHRASE_SALT = seq(0x20, 16)
const PASSPHRASE_NONCE = seq(0x30, 24)
const RECOVERY_SALT = seq(0x50, 16)
const RECOVERY_NONCE = seq(0x60, 24)

const WRAPPED =
  '{"v":1,"slots":[' +
  '{"kind":"passphrase","kdf":{"alg":"argon2id","memMiB":256,"ops":3},"saltB64":"ICEiIyQlJicoKSorLC0uLw",' +
  '"nonceB64":"MDEyMzQ1Njc4OTo7PD0-P0BBQkNERUZH","ctB64":"RATPiu0OXZXeHyv_qf9sywj4klVNv2V0bfJatAFESi0ULES6wAX9gxbYETbgJ_hV"},' +
  '{"kind":"recovery","kdf":{"alg":"argon2id","memMiB":256,"ops":3},"saltB64":"UFFSU1RVVldYWVpbXF1eXw",' +
  '"nonceB64":"YGFiY2RlZmdoaWprbG1ub3BxcnN0dXZ3","ctB64":"EKWynx8Jr3QkiB8YRyYoeVzI6reqpmygeRV0lO9MPU-wYBq9zdqyKCgf-wkR-nC9"}]}'
const WRAPPED_SHA256 = '8f47f8f8f875deabc23a45c54fda979a1faf4d5a1a2fdebdb688a08df6f3b2c1'

const MASTER = 'fd4b977bf6032cbf030a5339c2525bc63d64a49bfb647534e64e462b6e129a14'
const SUBKEY_1 = 'c6c95a6ff06a5386d366c4fd16746850927c9115408ac3ca7ca72868a7125e63' // SYNC_KEY
const SUBKEY_2 = 'cb7c3ed93bb15409a1250c5e8c7b2b245026a90e06d8cab6e79cd80424a972e6' // MANIFEST_SEED
const SUBKEY_3 = 'ad6e05995dd77043b6f12335dd56e1765bcc1ca96d4dfec4fcf2facd9a81d907' // the owner's X25519 seed
const SUBKEY_4 = '6f706ed43396fcb040d1a78898e61eb8f35da3a3141e976146cce7e793efbf1f' // the owner's Ed25519 seed

let so: typeof _sodium
beforeAll(async () => {
  so = await initCrypto()
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('the key documents the phone is held to (#403)', () => {
  it('the recovery code: this check symbol, and this typing of it reads as the code', () => {
    expect(checkSymbol(PAYLOAD)).toBe(CODE.slice(29))
    expect(normalizeRecoveryInput(TYPED_CODE)).toBe(CODE)
    const parsed = parseRecoveryCode(TYPED_CODE)
    expect(parsed.ok && parsed.code.canonical).toBe(CODE)
  })

  it('the passphrase reaches Argon2id as these bytes, and an unpaired surrogate as U+FFFD', () => {
    expect(hex(so.from_string(PASSPHRASE))).toBe(PASSPHRASE_UTF8)
    // What the string form of crypto_pwhash hashes: the phone's secretBytes matches it, where
    // Java's own encoder would write "?" (3f) for the surrogate.
    expect(hex(so.from_string('a\uD800b'))).toBe('61efbfbd62')
    const fast = (password: string | Uint8Array) =>
      hex(so.crypto_pwhash(32, password, seq(0, 16), 2, 8 * 1024 * 1024, so.crypto_pwhash_ALG_ARGON2ID13))
    expect(fast('a\uD800b')).toBe(fast(Uint8Array.from([0x61, 0xef, 0xbf, 0xbd, 0x62])))
  })

  it('the key parameters give this master, and deriveKeys its first two subkeys', async () => {
    const params = JSON.parse(KEY_PARAMS) as { kdf: KdfParams; saltB64: string }
    const salt = fromBase64(params.saltB64)
    expect(hex(salt)).toBe(hex(seq(0x10, 16)))
    expect(hex(await masterFromPassphrase(PASSPHRASE, salt, params.kdf))).toBe(MASTER)
    const keys = deriveKeys(PASSPHRASE, salt, params.kdf)
    expect(hex(keys.syncKey)).toBe(SUBKEY_1)
    expect(hex(keys.manifestSeed)).toBe(SUBKEY_2)
  }, 120000)

  it('the web writer makes exactly this wrapped key from that master', async () => {
    // Only the draws are pinned; the derivation, the AEAD, the associated data and the encoding
    // are wrapDataKey's own.
    const draws: Record<number, Uint8Array[]> = {
      16: [PASSPHRASE_SALT, RECOVERY_SALT],
      24: [PASSPHRASE_NONCE, RECOVERY_NONCE],
    }
    const pinnedDraw = (n: number): Uint8Array => {
      const next = draws[n]?.shift()
      if (!next) throw new Error(`the vector pins no draw of ${n} bytes`)
      return next.slice()
    }
    vi.spyOn(so, 'randombytes_buf').mockImplementation(pinnedDraw as typeof so.randombytes_buf)
    const master = Uint8Array.from(Buffer.from(MASTER, 'hex'))
    const blob: RecoverableDataKey = {
      v: 1,
      slots: [await wrapDataKey(master, PASSPHRASE, 'passphrase'), await wrapDataKey(master, CODE, 'recovery')],
    }
    const text = JSON.stringify(blob)
    expect(text).toBe(WRAPPED)
    expect(createHash('sha256').update(text).digest('hex')).toBe(WRAPPED_SHA256)
    expect(draws[16]).toHaveLength(0)
    expect(draws[24]).toHaveLength(0)
  }, 120000)

  it('either secret opens it to that master, whose four subkeys are these', async () => {
    const viaPassphrase = await unwrapWithPassphrase(JSON.parse(WRAPPED), PASSPHRASE)
    const viaCode = await unwrapWithRecoveryCode(JSON.parse(WRAPPED), TYPED_CODE)
    expect(hex(viaPassphrase)).toBe(MASTER)
    expect(hex(viaCode)).toBe(MASTER)
    const { syncKey, manifestSeed } = subkeysFromMaster(viaPassphrase)
    expect(hex(syncKey)).toBe(SUBKEY_1)
    expect(hex(manifestSeed)).toBe(SUBKEY_2)
    expect(hex(so.crypto_kdf_derive_from_key(32, SUBKEY_OWNER_BOX, 'dmsync01', viaPassphrase))).toBe(SUBKEY_3)
    expect(hex(so.crypto_kdf_derive_from_key(32, SUBKEY_OWNER_SIGN, 'dmsync01', viaPassphrase))).toBe(SUBKEY_4)
  }, 120000)
})

describe('what both sides refuse before deriving anything (#403)', () => {
  type Slot = {
    kind: string
    kdf?: { alg: string; memMiB: number; ops: number }
    saltB64: string
    nonceB64: string
    ctB64: string
  }
  type Blob = { v: number; slots: Slot[] }
  const fresh = (): Blob => JSON.parse(WRAPPED)
  const passphraseSlot = (b: Blob) => b.slots[0]
  const recoverySlot = (b: Blob) => b.slots[1]
  const B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_'
  /** The same text with the lowest bit of its last symbol set: bits after the last byte. */
  const strayBits = (s: string) => s.slice(0, -1) + B64[B64.indexOf(s[s.length - 1]) ^ 1]

  // Each mutation is derived from the vector's own value, never written as a literal, and each is
  // checked to change the document before its refusal counts (CLAUDE.md §5).
  const REFUSED: Array<[string, (b: Blob) => void]> = [
    ['version 2', (b) => { b.v = 2 }],
    ['no slots', (b) => { b.slots = [] }],
    ['the recovery slot at 255 MiB', (b) => { recoverySlot(b).kdf!.memMiB = 255 }],
    ['the recovery slot at 2 passes', (b) => { recoverySlot(b).kdf!.ops = 2 }],
    ['the passphrase slot on argon2i', (b) => { passphraseSlot(b).kdf!.alg = 'argon2i' }],
    ['the passphrase slot with no kdf', (b) => { delete passphraseSlot(b).kdf }],
    ['a padded salt', (b) => { passphraseSlot(b).saltB64 += '==' }],
    ['a nonce in the standard alphabet', (b) => { passphraseSlot(b).nonceB64 = passphraseSlot(b).nonceB64.replace(/-/g, '+') }],
    ['a ciphertext in the standard alphabet', (b) => { passphraseSlot(b).ctB64 = passphraseSlot(b).ctB64.replace(/_/g, '/') }],
    ['a salt with bits after its last byte', (b) => { passphraseSlot(b).saltB64 = strayBits(passphraseSlot(b).saltB64) }],
    ['a salt of 12 bytes', (b) => { passphraseSlot(b).saltB64 = passphraseSlot(b).saltB64.slice(0, 16) }],
    ['a ciphertext one byte longer', (b) => { passphraseSlot(b).ctB64 += 'AA' }],
  ]

  for (const [name, mutate] of REFUSED) {
    it(`refuses ${name}, with nothing derived`, async () => {
      const mutated = fresh()
      mutate(mutated)
      expect(JSON.stringify(mutated)).not.toBe(WRAPPED)
      const pwhash = vi.spyOn(so, 'crypto_pwhash')
      await expect(unwrapWithPassphrase(mutated as unknown as RecoverableDataKey, PASSPHRASE)).rejects.toThrow(DataKeyError)
      expect(pwhash).not.toHaveBeenCalled()
    })
  }

  it('the mutations are a real list: the unmutated vector passes every check they trip', () => {
    // The positive control for the table above, without an Argon2id: the vector clears the same
    // structural checks (the version, the slots, the floor, base64 and lengths) that each row breaks.
    const b = fresh()
    expect(b.v).toBe(1)
    expect(b.slots).toHaveLength(2)
    for (const slot of b.slots) {
      expect(slot.kdf).toEqual({ alg: 'argon2id', memMiB: 256, ops: 3 })
      expect(fromBase64(slot.saltB64)).toHaveLength(16)
      expect(fromBase64(slot.nonceB64)).toHaveLength(24)
      expect(fromBase64(slot.ctB64)).toHaveLength(48)
    }
  })

  it('a mistyped code is refused by its check symbol before the document is opened', async () => {
    const at = 5
    const typo = CODE.slice(0, at) + (CODE[at] === 'X' ? 'Y' : 'X') + CODE.slice(at + 1)
    expect(typo).not.toBe(CODE)
    const pwhash = vi.spyOn(so, 'crypto_pwhash')
    await expect(unwrapWithRecoveryCode(JSON.parse(WRAPPED), typo)).rejects.toThrow(RecoveryCodeError)
    expect(pwhash).not.toHaveBeenCalled()
  })
})
