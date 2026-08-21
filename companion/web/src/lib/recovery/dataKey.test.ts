import { describe, it, expect, beforeAll } from 'vitest'
import { initCrypto, encryptSnapshot, decryptSnapshot, type KdfParams } from '../sync/crypto'
import {
  createRecoverableDataKey,
  unwrapWithPassphrase,
  unwrapWithRecoveryCode,
  replacePassphrase,
  rotateRecoveryCode,
  wrapDataKey,
  zeroizeDataKey,
  DataKeyError,
  DATA_KEY_BYTES,
  type RecoverableDataKey,
} from './dataKey'
import { newRecoveryCode, RecoveryCodeError, type RecoveryCode } from './recoveryCode'
import { subkeysFromMaster } from './migration'

/*
 * EVERY TEST HERE COSTS ARGON2ID AT THE 256 MiB / 3-PASS FLOOR, which is the point: the floor is a
 * security property and a test suite that quietly ran below it would be testing a different
 * system. therapist/keyStore.test.ts made the same trade and states it the same way. The mitigation
 * is the same too — derive once in beforeAll, reuse everywhere a derivation is not itself the thing
 * under test.
 *
 * The cases that need NO derivation at all are the interesting half: a mistyped recovery code and a
 * downgraded KDF are both refused before any key is derived, and the tests below prove it by
 * asserting WHICH error came back.
 */

const PASSPHRASE = 'correct horse battery staple sync'
const PLAINTEXT = new TextEncoder().encode(JSON.stringify({ version: 12, entries: [{ id: 1, moodLevel: 4 }] }))

let created: { blob: RecoverableDataKey; recoveryCode: RecoveryCode; dataKey: Uint8Array }
let viaPassphrase: Uint8Array
let viaRecoveryCode: Uint8Array

beforeAll(async () => {
  await initCrypto()
  created = await createRecoverableDataKey(PASSPHRASE)
  viaPassphrase = await unwrapWithPassphrase(created.blob, PASSPHRASE)
  // Deliberately the DISPLAY form, hyphens and all — that is what a person types back, and the
  // round trip is only real if the grouping the product printed is the grouping it accepts.
  viaRecoveryCode = await unwrapWithRecoveryCode(created.blob, created.recoveryCode.display)
}, 120000)

describe('either secret opens the same data key', () => {
  it('the passphrase and the recovery code both return the identical 32 bytes', () => {
    expect(viaPassphrase).toHaveLength(DATA_KEY_BYTES)
    expect(Buffer.from(viaPassphrase)).toEqual(Buffer.from(created.dataKey))
    expect(Buffer.from(viaRecoveryCode)).toEqual(Buffer.from(created.dataKey))
  })

  it('both routes produce byte-identical plaintext out of the same snapshot', () => {
    // The claim that matters is not "the two 32-byte buffers match" but "the person gets their
    // journal back either way", so this goes all the way through the real snapshot envelope: encrypt
    // once under the key the passphrase opened, decrypt under the key the recovery code opened.
    const fromPassphrase = subkeysFromMaster(viaPassphrase)
    const fromCode = subkeysFromMaster(viaRecoveryCode)
    const envelope = encryptSnapshot(PLAINTEXT, fromPassphrase.syncKey, 'devA', 7)
    expect(Buffer.from(decryptSnapshot(envelope, fromCode.syncKey, 'devA', 7))).toEqual(Buffer.from(PLAINTEXT))
    expect(Buffer.from(decryptSnapshot(envelope, fromPassphrase.syncKey, 'devA', 7))).toEqual(Buffer.from(PLAINTEXT))
  })

  it('the two slots are independently wrapped — separate salt, separate nonce, separate ciphertext', () => {
    const [passphraseSlot, recoverySlot] = created.blob.slots
    expect(passphraseSlot.kind).toBe('passphrase')
    expect(recoverySlot.kind).toBe('recovery')
    expect(passphraseSlot.saltB64).not.toBe(recoverySlot.saltB64)
    expect(passphraseSlot.nonceB64).not.toBe(recoverySlot.nonceB64)
    expect(passphraseSlot.ctB64).not.toBe(recoverySlot.ctB64)
  })
})

describe('neither secret reveals the other', () => {
  /*
   * A test cannot prove a cryptographic non-implication, and this file does not pretend to. What it
   * CAN prove is the structural fact the non-implication rests on: the only thing either slot
   * contains is the data key, and the data key is compatible with any passphrase and any recovery
   * code. If the same data key and the same recovery code can coexist with a DIFFERENT passphrase,
   * then nothing reachable from the recovery code can determine which passphrase is in use — the
   * information is not there to be extracted. The reverse argument is the same one, mirrored.
   */
  it('the same recovery code opens the key after the passphrase has been changed', async () => {
    const rewrapped = await replacePassphrase(created.blob, created.dataKey, 'a completely different passphrase')
    const stillWorks = await unwrapWithRecoveryCode(rewrapped, created.recoveryCode.display)
    expect(Buffer.from(stillWorks)).toEqual(Buffer.from(created.dataKey))

    const underNewPassphrase = await unwrapWithPassphrase(rewrapped, 'a completely different passphrase')
    expect(Buffer.from(underNewPassphrase)).toEqual(Buffer.from(created.dataKey))

    // And the old passphrase is genuinely out of this blob. (migration.ts documents the one case
    // where that is NOT the end of the story: a migrated owner whose legacy keyparams record still
    // stands can still reach the master the old way until it is deleted.)
    await expect(unwrapWithPassphrase(rewrapped, PASSPHRASE)).rejects.toThrow(DataKeyError)
  }, 120000)

  it('the same passphrase opens the key after the recovery code has been rotated', async () => {
    const rotated = await rotateRecoveryCode(created.blob, created.dataKey)
    expect(rotated.recoveryCode.canonical).not.toBe(created.recoveryCode.canonical)

    const stillWorks = await unwrapWithPassphrase(rotated.blob, PASSPHRASE)
    expect(Buffer.from(stillWorks)).toEqual(Buffer.from(created.dataKey))

    const underNewCode = await unwrapWithRecoveryCode(rotated.blob, rotated.recoveryCode.display)
    expect(Buffer.from(underNewCode)).toEqual(Buffer.from(created.dataKey))

    // Rotation replaces rather than appends, because the reason to rotate is almost always that the
    // old piece of paper is no longer trusted.
    await expect(unwrapWithRecoveryCode(rotated.blob, created.recoveryCode.display)).rejects.toThrow(DataKeyError)
  }, 120000)

  it('the stored blob contains neither secret, in any encoding', () => {
    const serialized = JSON.stringify(created.blob)
    const hex = Buffer.from(serialized).toString('hex')
    for (const secret of [PASSPHRASE, created.recoveryCode.canonical, created.recoveryCode.display]) {
      expect(serialized).not.toContain(secret)
      expect(hex).not.toContain(Buffer.from(secret).toString('hex'))
      expect(serialized).not.toContain(Buffer.from(secret).toString('base64url'))
    }
  })
})

describe('a mistyped recovery code is caught by the checksum, not by a failed decrypt', () => {
  it('one wrong character comes back as a RecoveryCodeError before any key is derived', async () => {
    /*
     * The distinction this asserts is the whole reason the code carries a check symbol. A typo must
     * come back as "one character does not match the rest of the code" — instantly, with no
     * Argon2id run at all — and NOT as the same opaque failure a genuinely wrong code produces. A
     * person who mistyped needs to look at their paper again; a person with the wrong code entirely
     * needs to be told something else.
     */
    const canonical = created.recoveryCode.canonical
    const wrongChar = canonical[3] === 'X' ? 'Y' : 'X'
    const typo = canonical.slice(0, 3) + wrongChar + canonical.slice(4)

    let thrown: unknown = null
    try {
      await unwrapWithRecoveryCode(created.blob, typo)
    } catch (err) {
      thrown = err
    }
    // The error TYPE is the evidence, and it is better evidence than a stopwatch would be: a
    // RecoveryCodeError can only come out of the parse gate, and the only place a key is ever
    // derived is unwrapSlot(), downstream of it. If the gate had been skipped this would be a
    // DataKeyError from a failed AEAD open instead.
    expect(thrown).toBeInstanceOf(RecoveryCodeError)
    expect(thrown).not.toBeInstanceOf(DataKeyError)
    expect((thrown as RecoveryCodeError).fault).toBe('checksum')
  })

  it('the checksum gate runs before the blob is touched at all', async () => {
    // Ordering, asserted structurally rather than by clock. The recovery slot here is unusable —
    // its ciphertext is not even base64 — so if anything reached it the failure would come from
    // there. It comes from the checksum instead.
    const canonical = created.recoveryCode.canonical
    const typo = canonical.slice(0, 3) + (canonical[3] === 'X' ? 'Y' : 'X') + canonical.slice(4)
    const unusable: RecoverableDataKey = {
      v: 1,
      slots: created.blob.slots.map((s) => (s.kind === 'recovery' ? { ...s, ctB64: 'not base64 at all' } : s)),
    }
    await expect(unwrapWithRecoveryCode(unusable, typo)).rejects.toThrow(RecoveryCodeError)
  })

  it('a well-formed but wrong code gets as far as the decrypt, and fails there', async () => {
    // The other side of the same distinction: this code passes its own checksum, so nothing can
    // rule it out until a real derivation and a real AEAD open have been attempted.
    const someoneElsesCode = await newRecoveryCode()
    await expect(unwrapWithRecoveryCode(created.blob, someoneElsesCode.display)).rejects.toThrow(DataKeyError)
  }, 120000)

  it('the wrong passphrase fails the same way, saying nothing about which part was wrong', async () => {
    await expect(unwrapWithPassphrase(created.blob, 'not the passphrase')).rejects.toThrow(DataKeyError)
  }, 120000)
})

describe('the KDF floor is enforced (downgrade defence)', () => {
  /*
   * Same shape therapist/keyStore.ts and sync/client.ts already implement, and for the same reason:
   * the parameters travel INSIDE the blob, the blob comes from the server, so the parameters are
   * attacker-controlled. A blob that claims 8 MiB / 1 pass is asking the client to derive a key that
   * is cheap to brute-force from the ciphertext sitting next to it. None of these tests derives
   * anything, because the refusal happens before the derivation — which is the point.
   */
  const WEAK: KdfParams = { alg: 'argon2id', memMiB: 8, ops: 2 }

  function withKdf(kdf: KdfParams, kind: 'passphrase' | 'recovery'): RecoverableDataKey {
    return { v: 1, slots: created.blob.slots.map((s) => (s.kind === kind ? { ...s, kdf } : s)) }
  }

  it('refuses a below-floor passphrase slot', async () => {
    await expect(unwrapWithPassphrase(withKdf(WEAK, 'passphrase'), PASSPHRASE)).rejects.toThrow(DataKeyError)
  })

  it('refuses a below-floor recovery slot', async () => {
    await expect(
      unwrapWithRecoveryCode(withKdf(WEAK, 'recovery'), created.recoveryCode.display),
    ).rejects.toThrow(DataKeyError)
  })

  it('refuses a blob that is weak in a slot it is not even opening', async () => {
    // A downgraded sibling slot must not survive a round trip through an honest client and get
    // re-uploaded next to a strong one.
    await expect(unwrapWithPassphrase(withKdf(WEAK, 'recovery'), PASSPHRASE)).rejects.toThrow(DataKeyError)
  })

  it('refuses to WRITE a below-floor slot, so a weak blob cannot originate here either', async () => {
    await expect(wrapDataKey(created.dataKey, PASSPHRASE, 'passphrase', WEAK)).rejects.toThrow(DataKeyError)
    await expect(
      wrapDataKey(created.dataKey, PASSPHRASE, 'passphrase', { alg: 'argon2id', memMiB: 256, ops: 2 }),
    ).rejects.toThrow(DataKeyError)
    await expect(
      wrapDataKey(created.dataKey, PASSPHRASE, 'passphrase', { alg: 'argon2id', memMiB: 64, ops: 3 }),
    ).rejects.toThrow(DataKeyError)
  })

  it('refuses an unknown KDF algorithm outright', async () => {
    const alien = { alg: 'pbkdf2', memMiB: 4096, ops: 10 } as unknown as KdfParams
    await expect(unwrapWithPassphrase(withKdf(alien, 'passphrase'), PASSPHRASE)).rejects.toThrow(DataKeyError)
  })
})

describe('malformed blobs are refused before anything expensive happens', () => {
  it('refuses an unknown blob version', async () => {
    const future = { ...created.blob, v: 2 } as unknown as RecoverableDataKey
    await expect(unwrapWithPassphrase(future, PASSPHRASE)).rejects.toThrow(DataKeyError)
  })

  it('refuses a blob with no slots', async () => {
    await expect(unwrapWithPassphrase({ v: 1, slots: [] }, PASSPHRASE)).rejects.toThrow(DataKeyError)
  })

  it('refuses a blob missing the slot being asked for', async () => {
    const noRecovery: RecoverableDataKey = { v: 1, slots: created.blob.slots.filter((s) => s.kind === 'passphrase') }
    await expect(
      unwrapWithRecoveryCode(noRecovery, created.recoveryCode.display),
    ).rejects.toThrow(DataKeyError)
  })

  it('refuses a slot whose ciphertext is not the size of a wrapped data key', async () => {
    // A v1 slot wraps exactly 32 bytes plus a 16-byte tag. Anything longer is something else
    // pretending to be a data key, and it is refused before the expensive derivation rather than
    // after it.
    const swollen: RecoverableDataKey = {
      v: 1,
      slots: created.blob.slots.map((s) => (s.kind === 'passphrase' ? { ...s, ctB64: s.ctB64 + 'AAAAAAAA' } : s)),
    }
    await expect(unwrapWithPassphrase(swollen, PASSPHRASE)).rejects.toThrow(DataKeyError)
  })

  it('refuses a slot whose fields are not base64url at all', async () => {
    // Every field of the blob is server-supplied, so "that is not base64" is ordinary hostile input.
    // It comes back as this module's own error rather than a raw decoder TypeError, so a caller
    // handles one error type instead of two.
    for (const field of ['ctB64', 'saltB64', 'nonceB64'] as const) {
      const mangled: RecoverableDataKey = {
        v: 1,
        slots: created.blob.slots.map((s) => (s.kind === 'passphrase' ? { ...s, [field]: 'not base64 !!' } : s)),
      }
      await expect(unwrapWithPassphrase(mangled, PASSPHRASE)).rejects.toThrow(DataKeyError)
    }
  })

  it('refuses a slot whose salt or nonce is the wrong length', async () => {
    for (const field of ['saltB64', 'nonceB64'] as const) {
      const short: RecoverableDataKey = {
        v: 1,
        slots: created.blob.slots.map((s) => (s.kind === 'passphrase' ? { ...s, [field]: 'AAAA' } : s)),
      }
      await expect(unwrapWithPassphrase(short, PASSPHRASE)).rejects.toThrow(DataKeyError)
    }
  })

  it('refuses a slot carrying no KDF parameters at all', async () => {
    // A missing field is a thing that arrives from a real server, and it must be refused rather than
    // crash while reading `.alg` off undefined.
    const noKdf = {
      v: 1,
      slots: created.blob.slots.map((s) => (s.kind === 'passphrase' ? { ...s, kdf: undefined } : s)),
    } as unknown as RecoverableDataKey
    await expect(unwrapWithPassphrase(noKdf, PASSPHRASE)).rejects.toThrow(DataKeyError)
  })

  it('refuses to wrap something that is not a 32-byte key', async () => {
    await expect(wrapDataKey(new Uint8Array(16), PASSPHRASE, 'passphrase')).rejects.toThrow(DataKeyError)
  })
})

describe('key hygiene', () => {
  it('zeroizeDataKey overwrites the buffer it was handed', () => {
    const k = new Uint8Array(DATA_KEY_BYTES).fill(7)
    zeroizeDataKey(k)
    expect(k.every((b) => b === 0)).toBe(true)
    zeroizeDataKey(null) // a caller with nothing unlocked must not have to check first
  })
})
