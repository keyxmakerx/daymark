/*
 * THE OWNER'S PAIRING IDENTITY, AND THE ONE PROPERTY EVERYTHING ELSE RESTS ON.
 *
 * The defect this module removes (issue #121) was not a weak key or a bad algorithm. It was a key
 * that was PERFECTLY GOOD AND DIFFERENT EVERY TIME. Nothing in the type system, nothing in a build,
 * and nothing in a functional test of the console noticed, because a fresh keypair does everything
 * a keypair is supposed to do — signs, verifies against itself, produces a clean fingerprint. It
 * failed only against a clinician who had written the old fingerprint down, days later, on a
 * different machine, in a way that looked to them exactly like the server substituting a key.
 *
 * So the assertions below are mostly about SAMENESS, which is an unusual shape for a crypto test
 * and is the whole point: derive twice, derive after a round trip through the wrapped blob, derive
 * after the migration that enrols an existing owner into recovery, and demand the same bytes every
 * time. A regression here would restore the original bug, and (b) exists so that it cannot be
 * restored quietly by someone changing a subkey id or the context string.
 */
import { describe, it, expect, beforeAll } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import _sodium from 'libsodium-wrappers-sumo'

import { ownerIdentityFromMaster, zeroizeOwnerIdentity, SUBKEY_OWNER_BOX, SUBKEY_OWNER_SIGN } from './identity'
import { DataKeyError, createRecoverableDataKey, unwrapWithPassphrase, unwrapWithRecoveryCode } from '../recovery/dataKey'
import { subkeysFromMaster, enrolExistingOwner } from '../recovery/migration'
import { initCrypto, newSalt } from '../sync/crypto'

/** Argon2id at the production floor takes seconds per call; these are the test-only params. */
const FAST = { alg: 'argon2id' as const, memMiB: 256, ops: 3 }

const b64 = (u: Uint8Array) => Buffer.from(u).toString('base64')

beforeAll(async () => {
  await initCrypto()
  await _sodium.ready
})

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   (a) The same master always yields the same identity. This is the fix.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('(a) the identity is a function of the master and nothing else', () => {
  it('derives byte-identical keys from the same master, twice', () => {
    const master = _sodium.randombytes_buf(32)
    const first = ownerIdentityFromMaster(master)
    const second = ownerIdentityFromMaster(master)

    expect(b64(second.ed25519.publicKey)).toBe(b64(first.ed25519.publicKey))
    expect(b64(second.ed25519.privateKey)).toBe(b64(first.ed25519.privateKey))
    expect(b64(second.x25519.publicKey)).toBe(b64(first.x25519.publicKey))
    expect(b64(second.x25519.privateKey)).toBe(b64(first.x25519.privateKey))
  })

  it('derives different keys from different masters', () => {
    // The mirror of the assertion above, and not a formality: a derivation that ignored its input
    // entirely — a constant, a mis-ordered argument — would satisfy every sameness test on this
    // page and would be the worst possible bug, one key for every owner in the world.
    const a = ownerIdentityFromMaster(_sodium.randombytes_buf(32))
    const b = ownerIdentityFromMaster(_sodium.randombytes_buf(32))
    expect(b64(a.ed25519.publicKey)).not.toBe(b64(b.ed25519.publicKey))
    expect(b64(a.x25519.publicKey)).not.toBe(b64(b.x25519.publicKey))
  })

  it('is not one keypair wearing two hats', () => {
    const id = ownerIdentityFromMaster(_sodium.randombytes_buf(32))
    expect(b64(id.ed25519.publicKey)).not.toBe(b64(id.x25519.publicKey))
    expect(b64(id.ed25519.privateKey)).not.toBe(b64(id.x25519.privateKey))
  })

  it('refuses a master that is not exactly 32 bytes, rather than padding it to fit', () => {
    for (const n of [0, 16, 31, 33, 64]) {
      expect(() => ownerIdentityFromMaster(_sodium.randombytes_buf(n)), `${n} bytes`).toThrow(DataKeyError)
    }
  })
})

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   (b) Purpose separation: these are not the sync layer's keys.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('(b) the pairing identity is separate from every other key the master implies', () => {
  it('uses subkey ids the sync layer has not already taken', () => {
    // 1 is SYNC_KEY and 2 is MANIFEST_SEED, on the web (sync/crypto.ts) AND on Android
    // (sync-crypto/SyncCrypto.kt). Reusing either would make the owner's signing identity equal to
    // the anti-rollback manifest identity, which is a different key with a different lifetime and a
    // different blast radius.
    expect(SUBKEY_OWNER_BOX).toBe(3)
    expect(SUBKEY_OWNER_SIGN).toBe(4)
  })

  it('shares no bytes with the sync key or the manifest seed', () => {
    const master = _sodium.randombytes_buf(32)
    const { syncKey, manifestSeed } = subkeysFromMaster(master)
    const id = ownerIdentityFromMaster(master)

    const sync = b64(syncKey)
    const manifest = b64(manifestSeed)
    for (const [name, key] of [
      ['owner box private', id.x25519.privateKey],
      ['owner box public', id.x25519.publicKey],
      ['owner sign public', id.ed25519.publicKey],
    ] as const) {
      expect(b64(key), name).not.toBe(sync)
      expect(b64(key), name).not.toBe(manifest)
    }
    // The Ed25519 private key is 64 bytes and contains its own 32-byte seed as a prefix, so the
    // comparison that matters for it is against that prefix rather than the whole value.
    expect(b64(id.ed25519.privateKey.slice(0, 32))).not.toBe(manifest)
    expect(b64(id.ed25519.privateKey.slice(0, 32))).not.toBe(sync)
  })

  it('is not the manifest identity under another name', () => {
    // The sharpest form of the previous assertion: MANIFEST_SEED already seeds an Ed25519 identity,
    // so the failure mode worth naming is the owner's signing key silently BEING it.
    const master = _sodium.randombytes_buf(32)
    const manifestPub = _sodium.crypto_sign_seed_keypair(subkeysFromMaster(master).manifestSeed).publicKey
    expect(b64(ownerIdentityFromMaster(master).ed25519.publicKey)).not.toBe(b64(manifestPub))
  })
})

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   (c) The identity survives every way the master can come back.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('(c) every route to the master yields the same identity', () => {
  it('is the same after a round trip through the passphrase slot and the recovery slot', async () => {
    const { blob, recoveryCode, dataKey } = await createRecoverableDataKey('correct horse battery staple', FAST)
    const direct = ownerIdentityFromMaster(dataKey)

    const viaPassphrase = ownerIdentityFromMaster(await unwrapWithPassphrase(blob, 'correct horse battery staple'))
    const viaCode = ownerIdentityFromMaster(await unwrapWithRecoveryCode(blob, recoveryCode.canonical))

    // The recovery code is the path a person uses on the worst day they will ever use this product,
    // on a device that has never seen their passphrase. If it produced a different identity, their
    // clinician would see a stranger at exactly that moment.
    expect(b64(viaPassphrase.ed25519.publicKey)).toBe(b64(direct.ed25519.publicKey))
    expect(b64(viaCode.ed25519.publicKey)).toBe(b64(direct.ed25519.publicKey))
    expect(b64(viaCode.x25519.publicKey)).toBe(b64(direct.x25519.publicKey))
  }, 60_000)

  it('does not change when an existing owner is enrolled into recovery', async () => {
    // migration.ts wraps the master the passphrase ALREADY derives rather than minting a new one —
    // the choice that leaves the snapshot archive untouched. This asserts the same choice carries
    // the pairing identity across the upgrade, so migrating cannot look, from the clinician's side,
    // like the owner being replaced.
    const salt = newSalt()
    const before = await enrolExistingOwner('an existing owner passphrase', salt, FAST)
    const after = await enrolExistingOwner('an existing owner passphrase', salt, FAST)

    expect(b64(ownerIdentityFromMaster(after.master).ed25519.publicKey)).toBe(
      b64(ownerIdentityFromMaster(before.master).ed25519.publicKey),
    )
  }, 60_000)
})

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   (d) The derived keys are real keys.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('(d) the derived keys work with the primitives that will use them', () => {
  it('signs and verifies', () => {
    const id = ownerIdentityFromMaster(_sodium.randombytes_buf(32))
    const msg = _sodium.from_string('a grant')
    const sig = _sodium.crypto_sign_detached(msg, id.ed25519.privateKey)
    expect(_sodium.crypto_sign_verify_detached(sig, msg, id.ed25519.publicKey)).toBe(true)
    // And refuses a signature from a different owner, or the check above proves only that
    // verification returns true.
    const other = ownerIdentityFromMaster(_sodium.randombytes_buf(32))
    expect(_sodium.crypto_sign_verify_detached(sig, msg, other.ed25519.publicKey)).toBe(false)
  })

  it('opens a sealed box addressed to it', () => {
    // How an assignment reaches the owner: crypto_box_seal to their X25519 public key.
    const id = ownerIdentityFromMaster(_sodium.randombytes_buf(32))
    const sealed = _sodium.crypto_box_seal(_sodium.from_string('an assignment'), id.x25519.publicKey)
    const opened = _sodium.crypto_box_seal_open(sealed, id.x25519.publicKey, id.x25519.privateKey)
    expect(_sodium.to_string(opened)).toBe('an assignment')
  })

  it('wipes the private halves on lock and leaves the public ones readable', () => {
    const id = ownerIdentityFromMaster(_sodium.randombytes_buf(32))
    const publicBefore = b64(id.ed25519.publicKey)
    zeroizeOwnerIdentity(id)
    expect(id.ed25519.privateKey.every((byte) => byte === 0)).toBe(true)
    expect(id.x25519.privateKey.every((byte) => byte === 0)).toBe(true)
    // A screen still showing a fingerprint as the console tears down should show the right one.
    expect(b64(id.ed25519.publicKey)).toBe(publicBefore)
  })
})

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   (e) Nothing here stores or sends what it derives.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('(e) the master and the identity do not leave the tab', () => {
  const source = readFileSync(fileURLToPath(new URL('./identity.ts', import.meta.url)), 'utf8')
  /*
   * Commentary stripped. This module's header NAMES the things it refuses to do — "No storage of
   * any kind", "No fetch" — so a guard run over the raw text would be satisfied by the explanation
   * of the rule instead of by the rule.
   */
  const code = source.replace(/\/\*[\s\S]*?\*\//g, '').replace(/(?<!:)\/\/[^\n]*/g, '')

  const FORBIDDEN: { name: string; pattern: RegExp; planted: string }[] = [
    { name: 'localStorage', pattern: /\blocalStorage\b/, planted: 'localStorage.setItem("id", priv)' },
    { name: 'sessionStorage', pattern: /\bsessionStorage\b/, planted: 'sessionStorage.setItem("id", priv)' },
    { name: 'indexedDB', pattern: /\bindexedDB\b/i, planted: 'indexedDB.open("identity")' },
    { name: 'cookies', pattern: /document\s*\.\s*cookie/, planted: 'document.cookie = "k=" + priv' },
    { name: 'the system clipboard', pattern: /navigator\s*\.\s*clipboard|\.writeText\s*\(/, planted: 'navigator.clipboard.writeText(priv)' },
    { name: 'fetch', pattern: /\bfetch\s*\(/, planted: 'fetch("/v1/keys", { body: priv })' },
    { name: 'sendBeacon', pattern: /\bsendBeacon\b/, planted: 'navigator.sendBeacon("/x", priv)' },
    { name: 'the console', pattern: /\bconsole\s*\.\s*(log|info|warn|error|debug|trace|dir)\b/, planted: 'console.log(master)' },
    { name: 'randomness', pattern: /randombytes|crypto\s*\.\s*getRandomValues|Math\s*\.\s*random/, planted: 'const seed = randombytes_buf(32)' },
  ]

  it('the detectors detect', () => {
    // Nine assertions that each look for an absence are nine ways to be quietly vacuous.
    for (const { name, pattern, planted } of FORBIDDEN) {
      expect(pattern.test(planted), name).toBe(true)
    }
    expect(FORBIDDEN.some((f) => f.pattern.test('const box = crypto_box_seed_keypair(boxSeed)'))).toBe(false)
  })

  it('names none of them', () => {
    expect(FORBIDDEN.filter((f) => f.pattern.test(code)).map((f) => f.name)).toEqual([])
  })

  it('draws its key material from the master alone', () => {
    // The `randomness` detector above is the load-bearing one and deserves saying plainly: a single
    // randombytes_buf() call in this file would restore issue #121 exactly, and would restore it in
    // a form that passes every functional test of the console.
    expect(code).toContain('crypto_kdf_derive_from_key')
    expect(code).toContain('crypto_box_seed_keypair')
    expect(code).toContain('crypto_sign_seed_keypair')
    expect(code).not.toContain('crypto_box_keypair(')
    expect(code).not.toContain('crypto_sign_keypair(')
  })
})

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   (f) The derivation is frozen, because Android has to arrive at the same key.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('(f) a pinned vector holds the derivation still', () => {
  /*
   * Everything above is internally consistent: change KDF_CONTEXT from 'dmsync01' to 'dmownr01',
   * or swap subkey ids 3 and 4 for 5 and 6, and every sameness assertion on this page still passes,
   * because both sides of each comparison move together. What would NOT still pass is a clinician
   * who pinned a key last month, and — once the phone's half is built (#174) — a phone deriving
   * the same identity from the same master.
   *
   * So the derivation is pinned to bytes. This vector is the cross-platform contract: the Android
   * implementation is correct when it reproduces these two public keys from this master, and a
   * change here is a change to a published identity, never a refactor.
   */
  const MASTER_0_TO_31 = Uint8Array.from({ length: 32 }, (_, i) => i)
  const BOX_PUB = 'Zd9mRefJZVYSFKZIzfgyItz7yvoRPvxVy2AiO7ObPjg'
  const SIGN_PUB = 'Ps0OIIdt4nHg8dUlJTef98V9EJ_s6jsPMUI4LHoYOTQ'

  it('derives the pinned public keys from the pinned master', () => {
    const id = ownerIdentityFromMaster(MASTER_0_TO_31)
    expect(Buffer.from(id.x25519.publicKey).toString('base64url')).toBe(BOX_PUB)
    expect(Buffer.from(id.ed25519.publicKey).toString('base64url')).toBe(SIGN_PUB)
  })

  it('the vector is not the all-zero master', () => {
    // A vector taken from a degenerate input would still pin something, but it would pin the one
    // input most likely to be produced by a bug that zeroes the master before deriving.
    expect(MASTER_0_TO_31.every((b) => b === 0)).toBe(false)
    const zeroed = ownerIdentityFromMaster(new Uint8Array(32))
    expect(Buffer.from(zeroed.ed25519.publicKey).toString('base64url')).not.toBe(SIGN_PUB)
  })
})
