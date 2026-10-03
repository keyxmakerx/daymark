import { describe, it, expect, beforeAll, afterEach, vi } from 'vitest'
import { createHash } from 'node:crypto'
import _sodium from 'libsodium-wrappers-sumo'
import { initCrypto, deriveKeys, fromBase64, kdfRange, type KdfParams } from '../sync/crypto'
import {
  wrapDataKey,
  unwrapWithPassphrase,
  unwrapWithRecoveryCode,
  DataKeyError,
  KDF_ABOVE_CEILING,
  KDF_BELOW_FLOOR,
  type RecoverableDataKey,
} from './dataKey'
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
 * names in KeyDocumentVectorTest.kt: among them KDF parameters under the floor or over the ceiling
 * (sync/crypto.ts kdfRange) on a slot of either kind, on a slot of a kind neither side opens, and on
 * the key parameters, with the edges of the range shown to reach the derivation. A change to any
 * constant below is a change to the wire format, never a refactor.
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
  type Kdf = { alg: string; memMiB: number; ops: number }
  type Slot = {
    kind: string
    kdf?: Kdf
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

  /**
   * Argon2id replaced by a refusal of its own, so nothing here spends 256 MiB or more: a row that is
   * refused never reaches it, and one at an edge of the range shows it got there, and with which
   * parameters, by this error and the call's arguments.
   */
  const REACHED_THE_DERIVATION = 'reached the derivation'
  const stubDerivation = () =>
    vi.spyOn(so, 'crypto_pwhash').mockImplementation(() => {
      throw new Error(REACHED_THE_DERIVATION)
    })
  /** The ops and memory limit (bytes) Argon2id was asked for, call by call. */
  const askedFor = (pwhash: ReturnType<typeof stubDerivation>) => pwhash.mock.calls.map((c) => [c[3], c[4]])
  const MIB = 1024 * 1024

  // Each mutation is derived from the vector's own value, never written as a literal, and each is
  // checked to change the document before its refusal counts (CLAUDE.md §5). A row about the KDF
  // parameters also names its refusal, as KeyDocumentVectorTest names its reason.
  const REFUSED: Array<[string, (b: Blob) => void, string?]> = [
    ['version 2', (b) => { b.v = 2 }],
    ['no slots', (b) => { b.slots = [] }],
    ['the recovery slot at 255 MiB', (b) => { recoverySlot(b).kdf!.memMiB = 255 }, KDF_BELOW_FLOOR],
    ['the recovery slot at 2 passes', (b) => { recoverySlot(b).kdf!.ops = 2 }, KDF_BELOW_FLOOR],
    ['the passphrase slot on argon2i', (b) => { passphraseSlot(b).kdf!.alg = 'argon2i' }, KDF_BELOW_FLOOR],
    ['the passphrase slot with no kdf', (b) => { delete passphraseSlot(b).kdf }, KDF_BELOW_FLOOR],
    ['the passphrase slot at 513 MiB', (b) => { passphraseSlot(b).kdf!.memMiB = 513 }, KDF_ABOVE_CEILING],
    ['the passphrase slot at 9 passes', (b) => { passphraseSlot(b).kdf!.ops = 9 }, KDF_ABOVE_CEILING],
    ['the recovery slot at 513 MiB', (b) => { recoverySlot(b).kdf!.memMiB = 513 }, KDF_ABOVE_CEILING],
    ['the recovery slot at 9 passes', (b) => { recoverySlot(b).kdf!.ops = 9 }, KDF_ABOVE_CEILING],
    ['the passphrase slot at 1024 MiB and 2 passes', (b) => { Object.assign(passphraseSlot(b).kdf!, { memMiB: 1024, ops: 2 }) }, KDF_BELOW_FLOOR],
    ['a padded salt', (b) => { passphraseSlot(b).saltB64 += '==' }],
    ['a nonce in the standard alphabet', (b) => { passphraseSlot(b).nonceB64 = passphraseSlot(b).nonceB64.replace(/-/g, '+') }],
    ['a ciphertext in the standard alphabet', (b) => { passphraseSlot(b).ctB64 = passphraseSlot(b).ctB64.replace(/_/g, '/') }],
    ['a salt with bits after its last byte', (b) => { passphraseSlot(b).saltB64 = strayBits(passphraseSlot(b).saltB64) }],
    ['a salt of 12 bytes', (b) => { passphraseSlot(b).saltB64 = passphraseSlot(b).saltB64.slice(0, 16) }],
    ['a ciphertext one byte longer', (b) => { passphraseSlot(b).ctB64 += 'AA' }],
  ]

  for (const [name, mutate, words] of REFUSED) {
    it(`refuses ${name}, with nothing derived`, async () => {
      const mutated = fresh()
      mutate(mutated)
      expect(JSON.stringify(mutated)).not.toBe(WRAPPED)
      const pwhash = stubDerivation()
      const refused = await unwrapWithPassphrase(mutated as unknown as RecoverableDataKey, PASSPHRASE).catch((e: unknown) => e)
      expect(refused).toBeInstanceOf(DataKeyError)
      if (words) expect((refused as Error).message).toBe(words)
      expect(pwhash).not.toHaveBeenCalled()
    })
  }

  it('the mutations are a real list: the unmutated vector passes every check they trip', () => {
    // The positive control for the table above, without an Argon2id: the vector clears the same
    // structural checks (the version, the slots, the range, base64 and lengths) that each row breaks.
    const b = fresh()
    expect(b.v).toBe(1)
    expect(b.slots).toHaveLength(2)
    for (const slot of b.slots) {
      expect(slot.kdf).toEqual({ alg: 'argon2id', memMiB: 256, ops: 3 })
      expect(kdfRange(slot.kdf as KdfParams)).toBe('inRange')
      expect(fromBase64(slot.saltB64)).toHaveLength(16)
      expect(fromBase64(slot.nonceB64)).toHaveLength(24)
      expect(fromBase64(slot.ctB64)).toHaveLength(48)
    }
  })

  it('both ends of the range are in it: 256 MiB and 3 passes, and 512 MiB and 8 passes', () => {
    // The validator itself, at the edges the rows around it sit just outside of.
    expect(kdfRange({ alg: 'argon2id', memMiB: 256, ops: 3 })).toBe('inRange')
    expect(kdfRange({ alg: 'argon2id', memMiB: 512, ops: 8 })).toBe('inRange')
    expect(kdfRange({ alg: 'argon2id', memMiB: 513, ops: 8 })).toBe('aboveCeiling')
    expect(kdfRange({ alg: 'argon2id', memMiB: 512, ops: 9 })).toBe('aboveCeiling')
  })

  const EDGE_ROWS: Array<[string, (b: Blob) => Slot, (b: RecoverableDataKey) => Promise<Uint8Array>]> = [
    ['the passphrase slot at 512 MiB and 8 passes', passphraseSlot, (b) => unwrapWithPassphrase(b, PASSPHRASE)],
    ['the recovery slot at 512 MiB and 8 passes', recoverySlot, (b) => unwrapWithRecoveryCode(b, TYPED_CODE)],
  ]
  for (const [name, slotOf, open] of EDGE_ROWS) {
    it(`${name} is not refused: Argon2id is asked for exactly that`, async () => {
      const b = fresh()
      Object.assign(slotOf(b).kdf!, { memMiB: 512, ops: 8 })
      expect(JSON.stringify(b)).not.toBe(WRAPPED)
      const pwhash = stubDerivation()
      await expect(open(b as unknown as RecoverableDataKey)).rejects.toThrow(REACHED_THE_DERIVATION)
      expect(askedFor(pwhash)).toEqual([[8, 512 * MIB]])
    })
  }

  describe('a slot of a kind neither side opens is skipped, and held to the same range', () => {
    // KeyDocumentVectorTest.aSlotOfAnUnknownKindIsSkipped_andStillHeldToTheRange, slot for slot: a
    // passkey's slot before the vector's two, a slot with no kind between them, and a slot whose kind
    // is not a string after them. Each carries only KDF parameters, at the floor.
    type Unknown = { kind?: unknown; kdf?: Kdf; credentialId?: string }
    type Mixed = { v: number; slots: Array<Slot | Unknown> }
    const floorKdf = (): Kdf => ({ alg: 'argon2id', memMiB: 256, ops: 3 })
    const withUnknownSlots = (): Mixed => {
      const b: Mixed = fresh()
      b.slots.splice(0, 0, { kind: 'webauthn-prf', kdf: floorKdf(), credentialId: 'not base64 !!' })
      b.slots.splice(2, 0, { kdf: floorKdf() })
      b.slots.push({ kind: 1, kdf: floorKdf() })
      return b
    }
    const unknownSlot = (b: Mixed) => b.slots[0] as Unknown
    const kindlessSlot = (b: Mixed) => b.slots[2] as Unknown
    const TEXT = JSON.stringify(withUnknownSlots())

    it('is skipped: the passphrase beside it opens the vector to its master', async () => {
      expect(TEXT).not.toBe(WRAPPED)
      expect(hex(await unwrapWithPassphrase(JSON.parse(TEXT), PASSPHRASE))).toBe(MASTER)
    }, 120000)

    const ROWS: Array<[string, (b: Mixed) => void, string]> = [
      ['the unknown slot at 255 MiB', (b) => { unknownSlot(b).kdf!.memMiB = 255 }, KDF_BELOW_FLOOR],
      ['the unknown slot at 2 passes', (b) => { unknownSlot(b).kdf!.ops = 2 }, KDF_BELOW_FLOOR],
      ['the unknown slot on argon2i', (b) => { unknownSlot(b).kdf!.alg = 'argon2i' }, KDF_BELOW_FLOOR],
      ['the unknown slot with no kdf', (b) => { delete unknownSlot(b).kdf }, KDF_BELOW_FLOOR],
      ['the kindless slot at 255 MiB', (b) => { kindlessSlot(b).kdf!.memMiB = 255 }, KDF_BELOW_FLOOR],
      ['the unknown slot at 513 MiB', (b) => { unknownSlot(b).kdf!.memMiB = 513 }, KDF_ABOVE_CEILING],
      ['the unknown slot at 9 passes', (b) => { unknownSlot(b).kdf!.ops = 9 }, KDF_ABOVE_CEILING],
    ]
    for (const [name, mutate, words] of ROWS) {
      it(`refuses ${name}, with nothing derived`, async () => {
        const b = withUnknownSlots()
        mutate(b)
        expect(JSON.stringify(b)).not.toBe(TEXT)
        const pwhash = stubDerivation()
        const refused = await unwrapWithPassphrase(b as unknown as RecoverableDataKey, PASSPHRASE).catch((e: unknown) => e)
        expect(refused).toBeInstanceOf(DataKeyError)
        expect((refused as Error).message).toBe(words)
        expect(pwhash).not.toHaveBeenCalled()
      })
    }

    it('the unknown slot at 512 MiB and 8 passes is not refused: the passphrase slot is derived as it always is', async () => {
      const b = withUnknownSlots()
      Object.assign(unknownSlot(b).kdf!, { memMiB: 512, ops: 8 })
      expect(JSON.stringify(b)).not.toBe(TEXT)
      const pwhash = stubDerivation()
      await expect(unwrapWithPassphrase(b as unknown as RecoverableDataKey, PASSPHRASE)).rejects.toThrow(REACHED_THE_DERIVATION)
      expect(askedFor(pwhash)).toEqual([[3, 256 * MIB]])
    })
  })

  describe('the key parameters are held to the same range', () => {
    // Read here by migration.ts, which derives an existing owner's master from them; sync/client.ts,
    // the other reader, is held to the same rows in its own words in sync/keyDocument.test.ts.
    type KeyParamsDocument = { v: number; alg: string; kdf: Kdf; saltB64: string }
    const keyParams = (): KeyParamsDocument => JSON.parse(KEY_PARAMS)
    const master = (p: KeyParamsDocument) => masterFromPassphrase(PASSPHRASE, fromBase64(p.saltB64), p.kdf as KdfParams)

    const ROWS: Array<[string, (p: KeyParamsDocument) => void]> = [
      ['the key parameters at 513 MiB', (p) => { p.kdf.memMiB = 513 }],
      ['the key parameters at 9 passes', (p) => { p.kdf.ops = 9 }],
    ]
    for (const [name, mutate] of ROWS) {
      it(`refuses ${name}, with nothing derived`, async () => {
        const p = keyParams()
        mutate(p)
        expect(JSON.stringify(p)).not.toBe(KEY_PARAMS)
        const pwhash = stubDerivation()
        const refused = await master(p).catch((e: unknown) => e)
        expect(refused).toBeInstanceOf(DataKeyError)
        expect((refused as Error).message).toBe('published KDF parameters are above the ceiling — refusing to derive')
        expect(pwhash).not.toHaveBeenCalled()
      })
    }

    it('the key parameters at 512 MiB and 8 passes are not refused: Argon2id is asked for exactly that', async () => {
      const p = keyParams()
      Object.assign(p.kdf, { memMiB: 512, ops: 8 })
      expect(JSON.stringify(p)).not.toBe(KEY_PARAMS)
      const pwhash = stubDerivation()
      await expect(master(p)).rejects.toThrow(REACHED_THE_DERIVATION)
      expect(askedFor(pwhash)).toEqual([[8, 512 * MIB]])
    })
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
