import { beforeAll, describe, expect, it } from 'vitest'
import _sodium from 'libsodium-wrappers-sumo'
import { readFileSync } from 'node:fs'
import { initCrypto } from '../sync/crypto'
import { wrapExistingDataKey } from '../recovery/dataKey'
import { ownerIdentityFromMaster } from './identity'
import {
  RECOVERY_CONTEXT,
  SUBKEY_OWNER_RECOVERY,
  proveRecovery,
  recoveryMessage,
  recoveryPublicKeyFromMaster,
  type RecoveryChallenge,
} from './recoveryKey'

/* The owner's recovery key (#325): its own subkey, the server's message byte for byte, and a proof. */

const MASTER_0_TO_31 = Uint8Array.from({ length: 32 }, (_, i) => i)
/**
 * The cross-platform contract: subkey 5 of `dmsync01` over master bytes 0..31. sync-crypto's
 * SyncCryptoTest pins the same bytes, and it was checked once against a BLAKE2b and Ed25519 written
 * from the specifications rather than through libsodium.
 */
const RECOVERY_PUB = 'lCOQjQ7t587Ojhl-HRl7Dz5LhZoYXBaMo1s0TGnjDfU'
const FAST = { alg: 'argon2id' as const, memMiB: 256, ops: 3 }
const ORIGIN = 'https://daymark.example.org'
const b64 = (b: Uint8Array) => Buffer.from(b).toString('base64url')

beforeAll(async () => {
  await initCrypto()
})

describe('the key', () => {
  it('derives the pinned public key from the pinned master', () => {
    expect(b64(recoveryPublicKeyFromMaster(MASTER_0_TO_31))).toBe(RECOVERY_PUB)
    // Control: another master gives another key, so the pin is not of a constant.
    expect(b64(recoveryPublicKeyFromMaster(new Uint8Array(32)))).not.toBe(RECOVERY_PUB)
  })

  it('is its own subkey: never the key that signs shares and grants, nor the box key', () => {
    expect(SUBKEY_OWNER_RECOVERY).toBe(5)
    const id = ownerIdentityFromMaster(MASTER_0_TO_31)
    expect(b64(id.ed25519.publicKey)).not.toBe(RECOVERY_PUB)
    expect(b64(id.x25519.publicKey)).not.toBe(RECOVERY_PUB)
  })
})

describe('the message', () => {
  it('is the server’s, byte for byte', () => {
    expect(RECOVERY_CONTEXT).toBe('daymark-owner-recovery-v1')
    const bytes = recoveryMessage('owner-1', ORIGIN, 'ch-abc')
    expect(new TextDecoder().decode(bytes)).toBe('daymark-owner-recovery-v1\nowner-1\nhttps://daymark.example.org\nch-abc')
    const server = readFileSync(
      new URL('../../../../server/src/main/kotlin/com/daymark/companion/auth/OwnerSignIn.kt', import.meta.url),
      'utf8',
    )
    expect(server).toContain('const val RECOVERY_CONTEXT = "daymark-owner-recovery-v1"')
    expect(server).toContain('"$RECOVERY_CONTEXT\\n$ownerId\\n$address\\n$challenge"')
  })
})

describe('the proof', () => {
  it('signs the challenge for the given address with the recovery key, and with nothing else', async () => {
    const master = Uint8Array.from(MASTER_0_TO_31)
    const { blob, recoveryCode } = await wrapExistingDataKey(master, 'a passphrase long enough to pass', FAST)
    const challenge: RecoveryChallenge = {
      challengeId: 'rc_1',
      challenge: 'ch-xyz',
      ownerId: 'owner-1',
      expiresAt: 0,
      recoveryKey: { v: 1, slots: blob.slots.filter((s) => s.kind === 'recovery') },
    }
    const sig = Buffer.from(await proveRecovery(challenge, recoveryCode.display, ORIGIN), 'base64url')
    const pub = Buffer.from(RECOVERY_PUB, 'base64url')
    const verify = (msg: Uint8Array, key: Uint8Array) => _sodium.crypto_sign_verify_detached(sig, msg, key)
    expect(verify(recoveryMessage('owner-1', ORIGIN, 'ch-xyz'), pub)).toBe(true)
    // For another address, owner or challenge it proves nothing.
    expect(verify(recoveryMessage('owner-1', 'https://elsewhere.example', 'ch-xyz'), pub)).toBe(false)
    expect(verify(recoveryMessage('owner-2', ORIGIN, 'ch-xyz'), pub)).toBe(false)
    expect(verify(recoveryMessage('owner-1', ORIGIN, 'ch-xyy'), pub)).toBe(false)
    // And it is not the share-signing key's signature.
    expect(verify(recoveryMessage('owner-1', ORIGIN, 'ch-xyz'), ownerIdentityFromMaster(MASTER_0_TO_31).ed25519.publicKey)).toBe(false)
  }, 60_000)

  it('throws for a code that does not open the key, before signing anything', async () => {
    const { blob, recoveryCode } = await wrapExistingDataKey(Uint8Array.from(MASTER_0_TO_31), 'a passphrase long enough to pass', FAST)
    const { recoveryCode: other } = await wrapExistingDataKey(Uint8Array.from(MASTER_0_TO_31), 'another passphrase entirely', FAST)
    expect(other.canonical).not.toBe(recoveryCode.canonical)
    const challenge: RecoveryChallenge = { challengeId: 'rc_1', challenge: 'c', ownerId: 'o', expiresAt: 0, recoveryKey: blob }
    await expect(proveRecovery(challenge, other.display, ORIGIN)).rejects.toThrow()
  }, 60_000)
})
