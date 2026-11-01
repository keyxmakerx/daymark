/*
 * THE OWNER'S RECOVERY KEY (#325): the key that gets the owner back in when their sign-in is lost.
 *
 *   master --crypto_kdf(ctx="dmsync01")--+- id 1, 2 -> the sync layer (sync/crypto.ts)
 *                                        +- id 3, 4 -> the pairing identity (owner/identity.ts)
 *                                        +- id 5    -> the recovery key (here)
 *
 * WHAT MUST STAY TRUE.
 * - ITS OWN SUBKEY. Id 5 is used for nothing else, on either platform: sync-crypto's SyncCrypto.kt
 *   derives the same key from the same master, and both test files pin the same public key for
 *   master bytes 0..31. It is never the key that signs shares and grants (id 4), so a recovery
 *   signature can never be passed off as one of those, nor one of those as a recovery.
 * - IT EXISTS IF AND ONLY IF THE JOURNAL IS READABLE, for the reason identity.ts gives: derived, never
 *   generated or stored. Whoever can open the master can make it; nobody else can.
 * - THE MESSAGE IS THE SERVER'S, BYTE FOR BYTE (auth/OwnerSignIn.kt, recoveryMessage): a fixed first
 *   line, the owner's id, the address this page is talking to, and the server's challenge, one per
 *   line. The address is this page's own origin, never one the server names, so a challenge relayed
 *   from another server is signed for the wrong address and refused there.
 * - THE MASTER IS WIPED BEFORE [proveRecovery] RETURNS, on every path, and only the signature leaves.
 */
import _sodium from 'libsodium-wrappers-sumo'
import { DATA_KEY_BYTES, DataKeyError, unwrapWithRecoveryCode, zeroizeDataKey, type RecoverableDataKey } from '../recovery/dataKey'
import { initCrypto } from '../sync/crypto'

/** Exactly 8 bytes, per crypto_kdf. The context identity.ts and sync/crypto.ts derive under. */
const KDF_CONTEXT = 'dmsync01'

/** Subkey 5: the recovery key's seed, and nothing else's. */
export const SUBKEY_OWNER_RECOVERY = 5

/** The first line of every recovery message. Must equal OwnerSignIn.RECOVERY_CONTEXT. */
export const RECOVERY_CONTEXT = 'daymark-owner-recovery-v1'

const SEED_BYTES = 32

export interface RecoveryKeyPair {
  publicKey: Uint8Array
  privateKey: Uint8Array
}

/** The recovery key of [master]. Synchronous; libsodium must be ready. */
export function recoveryKeyFromMaster(master: Uint8Array): RecoveryKeyPair {
  if (master.length !== DATA_KEY_BYTES) throw new DataKeyError('master key must be exactly 32 bytes')
  const seed = _sodium.crypto_kdf_derive_from_key(SEED_BYTES, SUBKEY_OWNER_RECOVERY, KDF_CONTEXT, master)
  try {
    const pair = _sodium.crypto_sign_seed_keypair(seed)
    return { publicKey: pair.publicKey, privateKey: pair.privateKey }
  } finally {
    _sodium.memzero(seed)
  }
}

/** Only the public half, for the account to record: the private half is wiped here. */
export function recoveryPublicKeyFromMaster(master: Uint8Array): Uint8Array {
  const pair = recoveryKeyFromMaster(master)
  _sodium.memzero(pair.privateKey)
  return pair.publicKey
}

/** The bytes the server checks. */
export function recoveryMessage(ownerId: string, address: string, challenge: string): Uint8Array {
  return new TextEncoder().encode(`${RECOVERY_CONTEXT}\n${ownerId}\n${address}\n${challenge}`)
}

/** base64url without padding, the spelling every key and signature takes on the wire. */
export function b64url(bytes: Uint8Array): string {
  return _sodium.to_base64(bytes, _sodium.base64_variants.URLSAFE_NO_PADDING)
}

/** What the server's challenge carries. */
export interface RecoveryChallenge {
  challengeId: string
  challenge: string
  ownerId: string
  expiresAt: number
  /** The owner's wrapped key, with its recovery-code slots only. */
  recoveryKey: RecoverableDataKey
}

/**
 * Open the wrapped key with [typedCode], sign [c] for [address] with the recovery key, and wipe
 * everything but the signature. Throws what unwrapWithRecoveryCode throws for a mistyped or wrong
 * code, so the caller can say which.
 */
export async function proveRecovery(c: RecoveryChallenge, typedCode: string, address: string): Promise<string> {
  await initCrypto()
  const master = await unwrapWithRecoveryCode(c.recoveryKey, typedCode)
  try {
    const pair = recoveryKeyFromMaster(master)
    try {
      return b64url(_sodium.crypto_sign_detached(recoveryMessage(c.ownerId, address, c.challenge), pair.privateKey))
    } finally {
      _sodium.memzero(pair.privateKey)
    }
  } finally {
    zeroizeDataKey(master)
  }
}
