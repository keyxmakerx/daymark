import { describe, it, expect, beforeAll } from 'vitest'
import { wrap, unwrap, zeroize, KeyUnwrapError, type TherapistKeys, type WrappedKeyBlob } from './keyStore'
import { initAssignmentCrypto, newBoxKeyPair, newSignKeyPair } from '../assignments/crypto'

// Argon2id at the 256MiB floor is intentionally expensive; keep derivations to a minimum by
// reusing one wrapped blob across the round-trip + wrong-passphrase cases.
describe('therapist key custody (wrap/unwrap)', () => {
  let keys: TherapistKeys
  let blob: WrappedKeyBlob
  const pass = 'correct horse battery staple reading'

  beforeAll(async () => {
    await initAssignmentCrypto()
    keys = { box: newBoxKeyPair(), sign: newSignKeyPair() }
    blob = await wrap(keys, pass)
  }, 60000)

  it('round-trips X25519 + Ed25519 keys under the reading passphrase', async () => {
    const out = await unwrap(blob, pass)
    expect(Array.from(out.box.privateKey)).toEqual(Array.from(keys.box.privateKey))
    expect(Array.from(out.box.publicKey)).toEqual(Array.from(keys.box.publicKey))
    expect(Array.from(out.sign.privateKey)).toEqual(Array.from(keys.sign.privateKey))
    expect(Array.from(out.sign.publicKey)).toEqual(Array.from(keys.sign.publicKey))
  }, 60000)

  it('throws on the wrong passphrase', async () => {
    await expect(unwrap(blob, 'wrong passphrase entirely')).rejects.toThrow(KeyUnwrapError)
  }, 60000)

  it('refuses a below-floor KDF', async () => {
    const weak: WrappedKeyBlob = { ...blob, kdf: { alg: 'argon2id', memMiB: 8, ops: 1 } }
    await expect(unwrap(weak, pass)).rejects.toThrow(KeyUnwrapError)
  })

  it('zeroize() overwrites in-memory key material', async () => {
    const k: TherapistKeys = { box: newBoxKeyPair(), sign: newSignKeyPair() }
    zeroize(k)
    expect(k.box.privateKey.every((b) => b === 0)).toBe(true)
    expect(k.sign.privateKey.every((b) => b === 0)).toBe(true)
  })
})
