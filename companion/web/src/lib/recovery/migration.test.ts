import { describe, it, expect, beforeAll } from 'vitest'
import _sodium from 'libsodium-wrappers-sumo'
import {
  initCrypto,
  newSalt,
  deriveKeys,
  encryptSnapshot,
  decryptSnapshot,
  manifestPublicKeyB64,
  DEFAULT_KDF,
  type KdfParams,
} from '../sync/crypto'
import { subkeysFromMaster, masterFromPassphrase, enrolExistingOwner } from './migration'
import { unwrapWithRecoveryCode, unwrapWithPassphrase, DataKeyError } from './dataKey'

/*
 * DOES THE UPGRADE ACTUALLY LEAVE AN EXISTING ARCHIVE READABLE?
 *
 * migration.ts argues that it does, and the argument rests on one claim that is cheap to check and
 * catastrophic to get wrong: that subkeysFromMaster() reproduces sync/crypto.ts deriveKeys() exactly,
 * because the KDF context string and the two subkey ids are COPIED between the files. If those
 * copies ever drift, every snapshot an existing owner has stops opening, silently, and the only
 * symptom is somebody's journal not coming back. That is what the first test here is for, and it is
 * deliberately the cheap one so it can never be the test somebody skips.
 */

const PASSPHRASE = 'the passphrase they had before any of this existed'
const PLAINTEXT = new TextEncoder().encode(JSON.stringify({ version: 4, entries: [{ id: 9, moodLevel: 2 }] }))

/** Small parameters, used ONLY where the thing under test is not the KDF. Production floors at 256/3. */
const FAST: KdfParams = { alg: 'argon2id', memMiB: 8, ops: 2 }

beforeAll(async () => {
  await initCrypto()
})

describe('the subkeys are byte-identical to the ones the passphrase path derives', () => {
  it('reproduces both subkeys from the same master (conformance against sync/crypto.ts)', () => {
    // The Argon2id step is run here directly at FAST parameters, so this test costs nothing: what is
    // under test is the crypto_kdf half — the context string and the two subkey ids — not the
    // password hash. Feeding both paths the same master is exactly what makes the comparison sharp.
    const salt = newSalt()
    const master = _sodium.crypto_pwhash(
      32,
      PASSPHRASE,
      salt,
      FAST.ops,
      FAST.memMiB * 1024 * 1024,
      _sodium.crypto_pwhash_ALG_ARGON2ID13,
    )
    const legacy = deriveKeys(PASSPHRASE, salt, FAST)
    const fromMaster = subkeysFromMaster(master)
    expect(Buffer.from(fromMaster.syncKey)).toEqual(Buffer.from(legacy.syncKey))
    expect(Buffer.from(fromMaster.manifestSeed)).toEqual(Buffer.from(legacy.manifestSeed))
  })

  it('keeps the manifest signing identity, which a reader compares and would otherwise alarm on', () => {
    // The manifest public key is the anti-rollback trust anchor (COMPANION_SECURITY.md §8). A reader
    // re-derives it and compares; a migration that changed it would look, from the reader's side,
    // exactly like a hostile server swapping the signing identity.
    const salt = newSalt()
    const master = _sodium.crypto_pwhash(
      32,
      PASSPHRASE,
      salt,
      FAST.ops,
      FAST.memMiB * 1024 * 1024,
      _sodium.crypto_pwhash_ALG_ARGON2ID13,
    )
    expect(manifestPublicKeyB64(subkeysFromMaster(master).manifestSeed)).toBe(
      manifestPublicKeyB64(deriveKeys(PASSPHRASE, salt, FAST).manifestSeed),
    )
  })

  it('refuses a master that is not 32 bytes', () => {
    expect(() => subkeysFromMaster(new Uint8Array(16))).toThrow(DataKeyError)
  })
})

describe('enrolling an owner who already has snapshots on the server', () => {
  /*
   * The scenario, end to end and at the real KDF floor:
   *
   *   1. An owner has been syncing for a year under the old design. Their snapshot is encrypted
   *      with a key derived straight from their passphrase.
   *   2. They enrol in recovery. Nothing is re-encrypted; their existing master is wrapped twice.
   *   3. They forget the passphrase.
   *   4. They type the recovery code and their year-old snapshot opens.
   *
   * Step 2 is the one worth staring at: the snapshot written in step 1 is never touched, and the
   * bytes decrypted in step 4 are the bytes encrypted in step 1. Steps 1 and 2 are done once, in
   * the hook, because each of the four Argon2id runs they need is at the 256 MiB floor.
   */
  // Everything here is assigned in the hook, never in the describe body: a describe body runs at
  // collection time, before any beforeAll, and newSalt() needs the WASM module to be loaded.
  let legacy: ReturnType<typeof deriveKeys>
  let envelope: Uint8Array
  let enrolled: Awaited<ReturnType<typeof enrolExistingOwner>>

  beforeAll(async () => {
    const salt = newSalt()
    legacy = deriveKeys(PASSPHRASE, salt, DEFAULT_KDF)
    envelope = encryptSnapshot(PLAINTEXT, legacy.syncKey, 'devA', 41)
    enrolled = await enrolExistingOwner(PASSPHRASE, salt, DEFAULT_KDF)
  }, 180000)

  it('wraps the master the owner already had, not a new one', () => {
    // Free to assert and the load-bearing half of the whole migration: the enrolled master implies
    // the same sync key the passphrase path was already using, so the archive did not move.
    expect(Buffer.from(subkeysFromMaster(enrolled.master).syncKey)).toEqual(Buffer.from(legacy.syncKey))
  })

  it('leaves an existing snapshot readable with the recovery code alone', async () => {
    const recovered = await unwrapWithRecoveryCode(enrolled.blob, enrolled.recoveryCode.display)
    const keys = subkeysFromMaster(recovered)
    expect(Buffer.from(decryptSnapshot(envelope, keys.syncKey, 'devA', 41))).toEqual(Buffer.from(PLAINTEXT))
    // And the signing identity is the same one, so a reader's anti-rollback check does not fire.
    expect(manifestPublicKeyB64(keys.manifestSeed)).toBe(manifestPublicKeyB64(legacy.manifestSeed))
  }, 180000)

  it('also leaves the passphrase working, so enrolment is not a cutover', async () => {
    // Enrolment must not be a moment where something can break. Both routes work from the instant
    // the blob is written, which is what lets the legacy keyparams record be removed later and
    // separately — the ordering hazard migration.ts spells out.
    const viaPassphrase = await unwrapWithPassphrase(enrolled.blob, PASSPHRASE)
    expect(Buffer.from(viaPassphrase)).toEqual(Buffer.from(enrolled.master))
  }, 180000)

  it('refuses published KDF parameters below the floor', async () => {
    // The parameters come from the server's keyparams record, so they are hostile input on the one
    // path that reads them. No derivation happens here: the refusal is the point.
    await expect(masterFromPassphrase(PASSPHRASE, newSalt(), FAST)).rejects.toThrow(DataKeyError)
    await expect(
      masterFromPassphrase(PASSPHRASE, newSalt(), { alg: 'argon2id', memMiB: 255, ops: 3 }),
    ).rejects.toThrow(DataKeyError)
  })
})
