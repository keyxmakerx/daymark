/*
 * THE MIGRATION QUESTION, WRITTEN DOWN — what happens to the people who already have snapshots on a
 * server, encrypted under a key that IS their passphrase.
 *
 * This file exists because "we'll figure out the upgrade later" is how a zero-knowledge system ends
 * up with a flag day that strands somebody's journal. The answer turned out to be short, so it is
 * here in full, with the parts that are NOT implemented named as such rather than implied.
 *
 * ─── WHAT AN EXISTING USER HAS TODAY ────────────────────────────────────────────────────────────
 *
 *   keyparams (published, non-secret) = { v:1, alg, kdf: {argon2id, 256 MiB, 3 ops}, saltB64 }
 *   master        = Argon2id(passphrase, salt, kdf)                       // sync/crypto.ts
 *   SYNC_KEY      = crypto_kdf(master, id 1, "dmsync01")                  // encrypts every snapshot
 *   MANIFEST_SEED = crypto_kdf(master, id 2, "dmsync01")                  // Ed25519 signing identity
 *
 * Every snapshot on the server is sealed under SYNC_KEY, and every manifest is signed under the
 * Ed25519 key that MANIFEST_SEED implies. Both hang off `master`.
 *
 * ─── THE UPGRADE, AND WHY IT COSTS NOTHING ──────────────────────────────────────────────────────
 *
 * The wrapped secret is the MASTER, not the sync key. That one choice is what makes the migration
 * free, and it is why dataKey.ts wraps 32 bytes that the caller supplies rather than insisting on
 * generating them:
 *
 *   - New owner: master is 32 random bytes. Nothing derives it from anything; both slots wrap it.
 *   - Existing owner: master is the value their passphrase already derives. We wrap THAT.
 *
 * Because the subkey derivation below is byte-identical to sync/crypto.ts, an existing owner who
 * migrates keeps the same SYNC_KEY and the same MANIFEST_SEED. Consequences worth being explicit
 * about, since each one is a way this could have gone wrong and does not:
 *
 *   - NOT ONE SNAPSHOT IS RE-ENCRYPTED. The archive is untouched, so migration cannot half-finish
 *     across a large history, cannot run out of quota, and cannot be interrupted into a state where
 *     some versions open and others do not.
 *   - THE LINEAGE AND VERSION CHAIN ARE UNBROKEN. The AAD binds lineage and version
 *     ("daymark.snapshot.v1|lineage|version"); a new key would have meant a new lineage, which the
 *     append-only store treats as a different history.
 *   - THE MANIFEST PUBLIC KEY DOES NOT CHANGE. That key is the anti-rollback trust anchor
 *     (COMPANION_SECURITY.md §8, R6): a reader re-derives it and compares. Rotating it during an
 *     upgrade would be indistinguishable, from the reader's side, from a hostile server swapping
 *     the signing identity — the exact alarm the design is built to raise.
 *
 * So the whole upgrade is: derive the master the way you already do, generate a recovery code, wrap
 * the master twice, publish the blob. That is enrolExistingOwner() below, and it is a handful of
 * lines because the design decision above did the work.
 *
 * ─── THE HAZARD THIS INTRODUCES, WHICH IS THE REASON TO WRITE ANY OF THIS DOWN ──────────────────
 *
 * After migration, a migrated owner has TWO routes to the same master: the new passphrase slot, and
 * the old keyparams record, which still says "Argon2id(passphrase, this salt, these params)". The
 * old route cannot be revoked by anything in this module.
 *
 * That is harmless while both routes need the same passphrase. It stops being harmless the moment
 * the owner changes it. replacePassphrase() re-wraps the passphrase slot, and a reader of this code
 * would reasonably assume the old passphrase is now useless — but for a MIGRATED owner it is not:
 * the old passphrase plus the still-published keyparams still reproduces the master, and the master
 * still opens everything. A passphrase change is therefore only real for a migrated owner once the
 * legacy keyparams record is gone.
 *
 * Two ways out, and the choice belongs with whoever owns the server and client surfaces:
 *
 *   (a) DELETE THE KEYPARAMS RECORD as the last step of migration, once the owner's other devices
 *       have upgraded. Cheap and complete, but it is a flag day per owner: a device still running
 *       the old code has no way to derive anything afterwards, so the ORDER MATTERS — every reader
 *       must understand the blob before any writer removes the keyparams.
 *   (b) ACCEPT THE LEGACY ROUTE and say so in the passphrase-change copy, until (a) is safe.
 *
 * The dishonest third option is to change the passphrase, show the person a reassuring sentence,
 * and leave the old one working. This comment exists so nobody picks it by accident.
 *
 * A related, smaller note: for a NEW owner the master is random and no keyparams record is ever
 * published, so a client that only knows the old format cannot read them at all. Ship the reader
 * before the writer.
 *
 * ─── WHAT IS AND IS NOT IMPLEMENTED HERE ────────────────────────────────────────────────────────
 *
 * IMPLEMENTED: the whole cryptographic half — deriving the existing master, reproducing the two
 * subkeys from it (pinned against sync/crypto.ts by a conformance test, see below), and wrapping it
 * into a recoverable blob. It is pure and takes no transport, so it can be called from a browser,
 * from the phone, or from a script.
 *
 * NOT IMPLEMENTED, deliberately: where the blob is stored and when the migration runs. That means a
 * keyparams v2 document (or a new endpoint) on the server, the SyncClient calls to read and write
 * it, and the enrolment UI that shows the code once and makes the person confirm they have written
 * it down. Those live in companion/server and in sync/client.ts, which this change does not own, so
 * shipping half of them here would have produced an untested surface in somebody else's file.
 *
 * ALSO NOT IMPLEMENTED: the "optional social / Shamir recovery" that COMPANION_ACCESS_CONTROL.md
 * lists alongside recovery codes. The slot list in dataKey.ts is shaped to accept it; nothing here
 * splits a secret.
 *
 * ─── WHY THESE THREE CONSTANTS ARE COPIED ───────────────────────────────────────────────────────
 *
 * The KDF context string and the two subkey ids below are duplicated from sync/crypto.ts, which
 * does not export them. Copying a wire constant is a real risk — the two copies can drift and the
 * failure would be silent and total (every existing snapshot stops opening). The mitigation is that
 * migration.test.ts derives the subkeys BOTH ways and asserts they are byte-identical, so a change
 * to either file that moves them apart fails a test rather than a user's archive. If sync/crypto.ts
 * ever exports them, delete these and import instead.
 */
import _sodium from 'libsodium-wrappers-sumo'
import { initCrypto, DEFAULT_KDF, type KdfParams, type OwnerKeys } from '../sync/crypto'
import { wrapExistingDataKey, DataKeyError, DATA_KEY_BYTES, type RecoverableDataKey } from './dataKey'
import type { RecoveryCode } from './recoveryCode'

/** Exactly 8 bytes, per crypto_kdf. Must equal KDF_CONTEXT in sync/crypto.ts. */
const KDF_CONTEXT = 'dmsync01'
/** Subkey ids, likewise. 1 encrypts snapshots; 2 seeds the Ed25519 manifest identity. */
const SUBKEY_SYNC = 1
const SUBKEY_MANIFEST = 2

/**
 * master -> the two purpose-separated subkeys, exactly as sync/crypto.ts deriveKeys() does after
 * its Argon2id step.
 *
 * The split from deriveKeys() is the whole point: deriveKeys takes a passphrase and can only ever
 * take a passphrase, whereas the recoverable design needs the same subkeys from a master that might
 * have come out of a wrapped slot instead. Nothing about the subkeys changes; only where the master
 * came from does.
 */
export function subkeysFromMaster(master: Uint8Array): OwnerKeys {
  if (master.length !== DATA_KEY_BYTES) throw new DataKeyError('master key must be exactly 32 bytes')
  return {
    syncKey: _sodium.crypto_kdf_derive_from_key(32, SUBKEY_SYNC, KDF_CONTEXT, master),
    manifestSeed: _sodium.crypto_kdf_derive_from_key(32, SUBKEY_MANIFEST, KDF_CONTEXT, master),
  }
}

/**
 * Reproduce an existing owner's master from their passphrase and their published keyparams.
 *
 * The parameters are the ones the SERVER published, so they are hostile input and get the same
 * floor check every other derivation in this codebase gets (sync/client.ts validateKdf,
 * therapist/keyStore.ts validateKdf). A server that answers a migration request with 8 MiB / 1 pass
 * is trying to make the resulting master cheap to brute-force from the blob it is about to be
 * handed.
 */
export async function masterFromPassphrase(
  passphrase: string,
  salt: Uint8Array,
  params: KdfParams = DEFAULT_KDF,
): Promise<Uint8Array> {
  await initCrypto()
  if (params.alg !== 'argon2id' || params.memMiB < 256 || params.ops < 3) {
    throw new DataKeyError('published KDF parameters are below the security floor — refusing to derive')
  }
  return _sodium.crypto_pwhash(
    DATA_KEY_BYTES,
    passphrase,
    salt,
    params.ops,
    params.memMiB * 1024 * 1024,
    _sodium.crypto_pwhash_ALG_ARGON2ID13,
  )
}

/**
 * The migration, as one call: existing passphrase + existing keyparams -> a recoverable blob and a
 * recovery code to show the person once.
 *
 * The master comes back too, because the caller is mid-session and about to need it; it is the same
 * master they had before this ran, and zeroizeDataKey() applies to it exactly as it does to a new
 * one.
 */
export async function enrolExistingOwner(
  passphrase: string,
  salt: Uint8Array,
  params: KdfParams = DEFAULT_KDF,
): Promise<{ blob: RecoverableDataKey; recoveryCode: RecoveryCode; master: Uint8Array }> {
  const master = await masterFromPassphrase(passphrase, salt, params)
  const { blob, recoveryCode } = await wrapExistingDataKey(master, passphrase, params)
  return { blob, recoveryCode, master }
}
