/*
 * REGRESSION: the wipe that wasn't.
 *
 * zeroize() advertises that the therapist's long-term keys are gone from memory on logout/idle.
 * It was not true. wrap() and unwrap() each minted two buffers holding the whole secret and then
 * dropped them on the floor: the 32-byte Argon2id master (which opens the wrapped blob by
 * itself), and the 160-byte box.priv||box.pub||sign.priv||sign.pub concatenation that unwrap
 * slices its four return values out of. zeroize() only ever saw those four slices. So a therapist
 * who logged out — or whose session idled out mid-appointment — still had their Ed25519 signing
 * key and their share-opening X25519 key sitting in the tab's heap, reachable by anything that
 * later got to read that heap.
 *
 * A local variable cannot be inspected from outside, so this reaches the buffers the only honest
 * way: it wraps the two libsodium calls that MINT them and keeps the references. fill(0) is
 * in-place, so a live reference sees the wipe. Each case first snapshots the buffer at the moment
 * it was minted and asserts the snapshot is NOT all-zero — otherwise "it is all zeros now" would
 * pass just as happily on a buffer that never held anything, which is the failure mode a
 * wipe test is most likely to have. The three wipe cases fail against the pre-fix keyStore.ts; the other three are the guards that
 * make them mean something -- they pass either way by design, because a wipe assertion on a buffer
 * that never held key material would be worth nothing.
 */
import { describe, it, expect, beforeAll } from 'vitest'
import _sodium from 'libsodium-wrappers-sumo'
import { wrap, unwrap, KeyUnwrapError, type TherapistKeys, type WrappedKeyBlob } from './keyStore'
import { initAssignmentCrypto, newBoxKeyPair, newSignKeyPair } from '../assignments/crypto'

/** A buffer we intercepted, plus what it contained at the moment we intercepted it. */
interface Grab {
  buf: Uint8Array
  atBirth: Uint8Array
}

interface Capture {
  /** Argon2id outputs — keyStore's `master`. */
  masters: Grab[]
  /** The 160-byte key concatenation — keyStore's `plain`, on both the wrap and unwrap sides. */
  plaintexts: Grab[]
}

type AnyFn = (...args: unknown[]) => unknown

/**
 * Run `fn` with crypto_pwhash / the AEAD pair wrapped so we keep references to the secret buffers
 * keyStore creates internally. Patching the singleton is safe here because keyStore looks the
 * functions up on the same object at call time, and the originals are restored either way.
 */
async function withCapture<T>(fn: () => Promise<T>): Promise<{ result: T; captured: Capture }> {
  const so = _sodium as unknown as Record<string, AnyFn>
  const realPwhash = so.crypto_pwhash
  const realEncrypt = so.crypto_aead_xchacha20poly1305_ietf_encrypt
  const realDecrypt = so.crypto_aead_xchacha20poly1305_ietf_decrypt
  const captured: Capture = { masters: [], plaintexts: [] }
  const grab = (into: Grab[], buf: Uint8Array) => {
    into.push({ buf, atBirth: buf.slice() })
    return buf
  }
  so.crypto_pwhash = (...args: unknown[]) => grab(captured.masters, realPwhash(...args) as Uint8Array)
  so.crypto_aead_xchacha20poly1305_ietf_encrypt = (...args: unknown[]) => {
    // wrap() passes its assembled `plain` in as the message — capture the argument, not a result.
    grab(captured.plaintexts, args[0] as Uint8Array)
    return realEncrypt(...args)
  }
  so.crypto_aead_xchacha20poly1305_ietf_decrypt = (...args: unknown[]) =>
    grab(captured.plaintexts, realDecrypt(...args) as Uint8Array)
  try {
    return { result: await fn(), captured }
  } finally {
    so.crypto_pwhash = realPwhash
    so.crypto_aead_xchacha20poly1305_ietf_encrypt = realEncrypt
    so.crypto_aead_xchacha20poly1305_ietf_decrypt = realDecrypt
  }
}

const allZero = (b: Uint8Array) => b.every((x) => x === 0)
const anyNonZero = (b: Uint8Array) => b.some((x) => x !== 0)

// Argon2id at the 256MiB floor costs about a second per derivation, so every derivation this
// suite needs happens once, here, and the cases below only assert over what was captured.
describe('therapist key custody — internal buffers are wiped, not just the returned slices', () => {
  const pass = 'correct horse battery staple reading'
  let keys: TherapistKeys
  let blob: WrappedKeyBlob
  let onWrap: Capture
  let onUnwrap: Capture
  let unwrapped: TherapistKeys
  let onBadPass: Capture

  beforeAll(async () => {
    await initAssignmentCrypto()
    keys = { box: newBoxKeyPair(), sign: newSignKeyPair() }

    const w = await withCapture(() => wrap(keys, pass))
    blob = w.result
    onWrap = w.captured

    const u = await withCapture(() => unwrap(blob, pass))
    unwrapped = u.result
    onUnwrap = u.captured

    const bad = await withCapture(async () => {
      await expect(unwrap(blob, 'not the reading passphrase')).rejects.toThrow(KeyUnwrapError)
    })
    onBadPass = bad.captured
  }, 120000)

  it('the interception actually caught the buffers it claims to (guards the wipe cases below)', () => {
    expect(onWrap.masters).toHaveLength(1)
    expect(onWrap.plaintexts).toHaveLength(1)
    expect(onUnwrap.masters).toHaveLength(1)
    expect(onUnwrap.plaintexts).toHaveLength(1)
    expect(onWrap.masters[0].buf).toHaveLength(32)
    expect(onWrap.plaintexts[0].buf).toHaveLength(160)
    expect(onUnwrap.masters[0].buf).toHaveLength(32)
    expect(onUnwrap.plaintexts[0].buf).toHaveLength(160)
  })

  it('wrap() clears the Argon2id master and the assembled 160-byte key block', () => {
    for (const g of [onWrap.masters[0], onWrap.plaintexts[0]]) {
      expect(anyNonZero(g.atBirth)).toBe(true) // it really did hold key material
      expect(allZero(g.buf)).toBe(true)
    }
  })

  it('unwrap() clears the Argon2id master and the decrypted 160-byte key block', () => {
    for (const g of [onUnwrap.masters[0], onUnwrap.plaintexts[0]]) {
      expect(anyNonZero(g.atBirth)).toBe(true)
      expect(allZero(g.buf)).toBe(true)
    }
  })

  it('the decrypted block really was the key material — the same bytes unwrap returned', () => {
    // Without this, "the 160 bytes are zeroed" could be satisfied by wiping the wrong buffer.
    const expected = new Uint8Array(160)
    expected.set(keys.box.privateKey, 0)
    expected.set(keys.box.publicKey, 32)
    expected.set(keys.sign.privateKey, 64)
    expected.set(keys.sign.publicKey, 128)
    expect(Array.from(onUnwrap.plaintexts[0].atBirth)).toEqual(Array.from(expected))
  })

  it('wiping the source block does not damage the keys unwrap handed back', () => {
    // The slices are copies, so the wipe must not reach them. If it ever did, a therapist would
    // log in to an all-zero signing key and every assignment they signed would fail to verify.
    expect(Array.from(unwrapped.box.privateKey)).toEqual(Array.from(keys.box.privateKey))
    expect(Array.from(unwrapped.sign.privateKey)).toEqual(Array.from(keys.sign.privateKey))
    expect(Array.from(unwrapped.sign.publicKey)).toEqual(Array.from(keys.sign.publicKey))
  })

  it('a wrong passphrase still clears the master it derived', () => {
    // The throw used to jump straight past any cleanup, which left the buffer for the WRONG
    // passphrase behind — no use to an attacker, but it is the same code path that would have
    // leaked the right one had the blob been tampered with rather than the passphrase mistyped.
    expect(onBadPass.masters).toHaveLength(1)
    expect(anyNonZero(onBadPass.masters[0].atBirth)).toBe(true)
    expect(allZero(onBadPass.masters[0].buf)).toBe(true)
    expect(onBadPass.plaintexts).toHaveLength(0) // decrypt failed, so there was no block to wipe
  })
})
