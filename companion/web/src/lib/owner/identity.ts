/*
 * THE OWNER'S PAIRING IDENTITY — DERIVED FROM THE MASTER KEY, NEVER GENERATED, NEVER STORED.
 *
 * ─── THE DEFECT THIS EXISTS TO REMOVE ───────────────────────────────────────────────────────────
 *
 * OwnerUnlock.svelte used to mint a fresh X25519 + Ed25519 pair on every visit, with a comment
 * saying a later slice would replace it with a key held at rest. Until that happened the owner's
 * signing identity was a different identity every session, so a clinician who pinned it correctly
 * on Monday could verify nothing the owner signed on Tuesday. Every grant and every share after
 * the owner's first session failed verification for a clinician who had done everything right.
 *
 * The failure was silent in the worst direction: from the clinician's side a rotated owner key and
 * a server substituting its own key look identical.
 *
 * ─── WHY DERIVE RATHER THAN WRAP A SECOND SECRET ────────────────────────────────────────────────
 *
 * The obvious alternative — generate a random identity and wrap it the way therapist/keyStore.ts
 * wraps the clinician's keys — creates a state that must not exist: OWNER CAN READ THEIR JOURNAL
 * BUT HAS LOST THEIR IDENTITY. That state is the original bug wearing a longer coat, because the
 * console's only available move in it is to generate a fresh pair, and a fresh pair is exactly
 * what a published owner_keys row makes useless (the server's table is insert-only by primary key;
 * see AuthStore.registerOwnerKeys, which is INSERT OR IGNORE and has no update path).
 *
 * Deriving makes the identity exist if and only if the journal is readable. Every way in that
 * already exists — the passphrase slot, the recovery-code slot, and the WebAuthn-PRF slot the
 * RecoverableDataKey format already leaves room for — covers the identity for free, and neither
 * replacePassphrase() nor rotateRecoveryCode() disturbs it, because the master does not move.
 *
 * THE HONEST COST, decided by the maintainer rather than assumed here: whoever holds the master
 * can now SIGN as the owner, not merely read. Concretely, the recovery code on a piece of paper in
 * a filing cabinet is no longer read-only — it can send an assignment to the clinician as the
 * owner. This was weighed against a second, unbacked secret for someone who may be unwell, and
 * the second secret lost: a thing you can lose without recourse protects less than it costs. The
 * copy where a recovery code is shown has to say what it now covers.
 *
 * ─── THE DERIVATION ─────────────────────────────────────────────────────────────────────────────
 *
 *   master --crypto_kdf(ctx="dmsync01")--+- id 1 -> SYNC_KEY       (sync/crypto.ts)
 *                                        +- id 2 -> MANIFEST_SEED  (sync/crypto.ts)
 *                                        +- id 3 -> owner X25519 seed   (here)
 *                                        +- id 4 -> owner Ed25519 seed  (here)
 *
 * Ids 1 and 2 are taken on BOTH platforms — sync/crypto.ts and sync-crypto/SyncCrypto.kt name the
 * same two — so 3 and 4 are the first free ones and must stay reserved for this on both. The
 * context string is the same 8 bytes for the same reason it is the same in recovery/migration.ts:
 * a different context would silently produce a different identity from the same master.
 *
 * Seeded keypairs, not random ones, are what make the identity a pure function of the master. That
 * is the property the whole fix rests on, and identityIsStable() in the tests is its proof.
 *
 * STABLE ACROSS THE RECOVERY MIGRATION. recovery/migration.ts wraps the master an existing owner's
 * passphrase already derives rather than minting a new one — that choice is why not one snapshot is
 * re-encrypted and why the manifest public key does not change. The same choice carries this
 * identity across the upgrade unchanged, so migrating cannot look, from a clinician's side, like
 * the owner being replaced.
 *
 * ─── WHAT THIS MODULE WILL NOT DO ───────────────────────────────────────────────────────────────
 *
 * No storage of any kind, for the master or for what it derives — the reasoning in
 * components/recovery/session.ts applies here word for word, and a test asserts the absence by
 * reading this source. No fetch. No fallback that invents an identity when the master is absent:
 * a caller without a master gets an exception, never a usable key, because a usable key from
 * nowhere is the defect this file was written to delete.
 */
import _sodium from 'libsodium-wrappers-sumo'
import { DATA_KEY_BYTES, DataKeyError } from '../recovery/dataKey'
import type { Identity } from '../share/pairing'

/** Exactly 8 bytes, per crypto_kdf. Must equal KDF_CONTEXT in sync/crypto.ts and SyncCrypto.kt. */
const KDF_CONTEXT = 'dmsync01'

/**
 * Subkey ids. 1 and 2 belong to the sync layer on both platforms and are NOT free; these two are
 * reserved for the owner's pairing identity and must not be reused for anything else.
 */
export const SUBKEY_OWNER_BOX = 3
export const SUBKEY_OWNER_SIGN = 4

/** crypto_box and crypto_sign both take a 32-byte seed. */
const SEED_BYTES = 32

/**
 * master (32 bytes, from a wrapped slot or from the passphrase) -> the owner's pairing identity.
 *
 * Synchronous, and libsodium must already be ready — same contract as subkeysFromMaster() in
 * recovery/migration.ts, so a caller that has just unwrapped a data key can derive without a
 * second await and without this module deciding when crypto initialises.
 *
 * Throws on anything but exactly 32 bytes rather than padding or hashing to fit. A caller holding
 * the wrong length holds the wrong value, and deriving an identity from it would produce a key
 * that looks fine and verifies against nothing.
 */
export function ownerIdentityFromMaster(master: Uint8Array): Identity {
  if (master.length !== DATA_KEY_BYTES) throw new DataKeyError('master key must be exactly 32 bytes')

  const boxSeed = _sodium.crypto_kdf_derive_from_key(SEED_BYTES, SUBKEY_OWNER_BOX, KDF_CONTEXT, master)
  const signSeed = _sodium.crypto_kdf_derive_from_key(SEED_BYTES, SUBKEY_OWNER_SIGN, KDF_CONTEXT, master)
  try {
    const box = _sodium.crypto_box_seed_keypair(boxSeed)
    const sign = _sodium.crypto_sign_seed_keypair(signSeed)
    return {
      x25519: { publicKey: box.publicKey, privateKey: box.privateKey },
      ed25519: { publicKey: sign.publicKey, privateKey: sign.privateKey },
    }
  } finally {
    // The seeds are as good as the private keys they imply; nothing else in this function needs
    // them once the pairs exist.
    _sodium.memzero(boxSeed)
    _sodium.memzero(signSeed)
  }
}

/**
 * Wipe the private halves when the console locks.
 *
 * The public halves are left alone deliberately: they are not secret, and a screen still showing a
 * fingerprint while it tears down should show the right one rather than a row of zeroes.
 */
export function zeroizeOwnerIdentity(identity: Identity): void {
  _sodium.memzero(identity.x25519.privateKey)
  _sodium.memzero(identity.ed25519.privateKey)
}
