/*
 * THE UNLOCK HANDS BACK THE SYNC KEY OF THE MASTER IT OPENED, AND STILL NEVER THE MASTER (#345).
 *
 * The owner console reads and adds to the owner's lane (lane/lane.ts) with subkey 1 of the master,
 * so the unlock that derives the identity derives that key from the same master, before the master
 * is wiped. unlock.test.ts holds the rest of the unlock; this file holds only the key it hands on.
 */
import { describe, it, expect, beforeAll, vi } from 'vitest'
import _sodium from 'libsodium-wrappers-sumo'

const spy = vi.hoisted(() => ({ wiped: [] as Uint8Array[] }))
vi.mock('../recovery/dataKey', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../recovery/dataKey')>()
  return {
    ...actual,
    zeroizeDataKey: (key: Uint8Array | null) => {
      if (key) spy.wiped.push(key)
      actual.zeroizeDataKey(key)
    },
  }
})

import { unlockFromBlob } from './unlock'
import { createRecoverableDataKey, type RecoverableDataKey } from '../recovery/dataKey'
import { subkeysFromMaster, syncKeyFromMaster } from '../recovery/migration'
import { initCrypto } from '../sync/crypto'
import type { RecoveryCode } from '../recovery/recoveryCode'

const FAST = { alg: 'argon2id' as const, memMiB: 256, ops: 3 }
const PASSPHRASE = 'the passphrase that opens this key'
const hex = (u: Uint8Array) => Buffer.from(u).toString('hex')

let blob: RecoverableDataKey
let code: RecoveryCode
let expectedSyncKey: string

beforeAll(async () => {
  await initCrypto()
  await _sodium.ready
  const made = await createRecoverableDataKey(PASSPHRASE, FAST)
  blob = made.blob
  code = made.recoveryCode
  expectedSyncKey = hex(subkeysFromMaster(made.dataKey).syncKey)
}, 60_000)

describe('the unlock hands back subkey 1 of the master it opened', () => {
  it('the same key from the passphrase and from the recovery code', async () => {
    for (const [secret, kind] of [[PASSPHRASE, 'passphrase'], [code.canonical, 'recovery']] as const) {
      const out = await unlockFromBlob(blob, secret, kind)
      expect(out.ok, kind).toBe(true)
      if (out.ok) expect(hex(out.syncKey), kind).toBe(expectedSyncKey)
    }
  }, 60_000)

  it('derives it before the master is wiped, and still wipes the master exactly once', async () => {
    spy.wiped.length = 0
    const out = await unlockFromBlob(blob, PASSPHRASE, 'passphrase')
    expect(out.ok).toBe(true)
    if (!out.ok) return
    // A key derived after the wipe would be subkey 1 of thirty-two zeros.
    expect(hex(out.syncKey)).not.toBe(hex(syncKeyFromMaster(new Uint8Array(32))))
    expect(spy.wiped).toHaveLength(1)
    expect(spy.wiped[0]!.every((b) => b === 0)).toBe(true)
    // And the key handed back is not the master it came from.
    expect(hex(out.syncKey)).not.toBe(hex(spy.wiped[0]!))
  }, 60_000)

  it('hands back nothing but a fault when the secret does not open the key', async () => {
    const out = await unlockFromBlob(blob, `${PASSPHRASE}.`, 'passphrase')
    expect(out).toEqual({ ok: false, fault: 'didNotOpen' })
  }, 60_000)
})

describe('syncKeyFromMaster is subkey 1, as every client derives it', () => {
  it('equals the sync key subkeysFromMaster derives, for a master of 32 bytes only', () => {
    const master = _sodium.randombytes_buf(32)
    expect(hex(syncKeyFromMaster(master))).toBe(hex(subkeysFromMaster(master).syncKey))
    expect(hex(syncKeyFromMaster(master))).not.toBe(hex(subkeysFromMaster(master).manifestSeed))
    expect(() => syncKeyFromMaster(new Uint8Array(31))).toThrow()
  })
})
