import { describe, it, expect, beforeAll } from 'vitest'
import {
  initShareCrypto,
  newIdentity,
  publicOf,
  fingerprints,
  sasWords,
  PinStore,
  PairingError,
} from './pairing'
import { SAS_WORDLIST } from './wordlist'

describe('pairing — fingerprints + SAS + TOFU pin store', () => {
  beforeAll(async () => {
    await initShareCrypto()
  })

  it('fingerprints are stable + distinct across identities', () => {
    const a = newIdentity()
    const fp1 = fingerprints(publicOf(a))
    const fp2 = fingerprints(publicOf(a))
    expect(fp1).toEqual(fp2)
    expect(fp1.x25519Fp).not.toBe(fp1.ed25519Fp)
    const b = newIdentity()
    expect(fingerprints(publicOf(b)).ed25519Fp).not.toBe(fp1.ed25519Fp)
  })

  it('SAS is order-independent (both clients render the identical phrase)', () => {
    const owner = publicOf(newIdentity())
    const ther = publicOf(newIdentity())
    expect(sasWords(owner, ther)).toEqual(sasWords(ther, owner))
  })

  it('SAS avalanches: flipping one byte of one pubkey changes the phrase', () => {
    const owner = publicOf(newIdentity())
    const ther = publicOf(newIdentity())
    const base = sasWords(owner, ther)
    const mutated = { ...ther, ed25519Pub: Uint8Array.from(ther.ed25519Pub) }
    mutated.ed25519Pub[0] ^= 0x01
    expect(sasWords(owner, mutated)).not.toEqual(base)
  })

  it('two independent pairs (almost) never collide', () => {
    const p1 = sasWords(publicOf(newIdentity()), publicOf(newIdentity()))
    const p2 = sasWords(publicOf(newIdentity()), publicOf(newIdentity()))
    expect(p1).not.toEqual(p2)
  })

  it('SAS length + vocabulary: exactly `words` entries, all from the wordlist', () => {
    const owner = publicOf(newIdentity())
    const ther = publicOf(newIdentity())
    for (const n of [4, 5, 6]) {
      const w = sasWords(owner, ther, n)
      expect(w).toHaveLength(n)
      for (const word of w) {
        expect(word).toBeTruthy()
        expect(SAS_WORDLIST).toContain(word)
      }
    }
    expect(() => sasWords(owner, ther, 3)).toThrow(PairingError)
    expect(() => sasWords(owner, ther, 9)).toThrow(PairingError)
  })

  it('PinStore TOFU: unpinned → pinned', () => {
    const store = new PinStore()
    const ther = publicOf(newIdentity())
    const { ed25519Fp, x25519Fp } = fingerprints(ther)
    expect(store.isPinned(ed25519Fp)).toBe(false)
    expect(() => store.assertPinned(ed25519Fp)).toThrow(PairingError)
    store.pin(ther)
    expect(store.isPinned(ed25519Fp)).toBe(true)
    expect(store.pinnedX25519Fp(ed25519Fp)).toBe(x25519Fp)
    expect(store.assertPinned(ed25519Fp).x25519Fp).toBe(x25519Fp)
  })

  it('PinStore serialize/load round-trips and carries no key bytes (only fingerprints)', () => {
    const store = new PinStore()
    const ther = publicOf(newIdentity())
    const owner = publicOf(newIdentity())
    store.pin(ther, 1000)
    store.pin(owner, 2000)
    const json = store.serialize()
    // Only fingerprints + pinnedAt should be present — no public/secret key byte arrays.
    const parsed = JSON.parse(json) as Array<Record<string, unknown>>
    for (const entry of parsed) {
      expect(Object.keys(entry).sort()).toEqual(['ed25519Fp', 'pinnedAt', 'x25519Fp'])
    }
    const reloaded = PinStore.load(json)
    const tf = fingerprints(ther)
    expect(reloaded.isPinned(tf.ed25519Fp)).toBe(true)
    expect(reloaded.pinnedX25519Fp(tf.ed25519Fp)).toBe(tf.x25519Fp)
    expect(reloaded.serialize()).toBe(json)
  })

  it('PinStore.load rejects malformed entries', () => {
    expect(() => PinStore.load(JSON.stringify([{ foo: 'bar' }]))).toThrow(PairingError)
  })
})
