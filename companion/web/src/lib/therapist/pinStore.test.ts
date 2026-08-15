/*
 * REGRESSION: "the console refuses to seal to an unpinned therapist" used to be a tautology.
 *
 * ShareBuilder.seal() built an EMPTY PinStore and pinned the therapist's keys into it one line
 * before handing it to buildShare — so all three of buildShare's pin assertions compared a value
 * with itself. Case 1 below reproduces that exact code and shows it happily sealing to a
 * SUBSTITUTED encryption key: an owner sharing months of journal entries would have been told
 * "sealed to their pinned key" while the bundle was sealed to someone else's. Case 2 is the same
 * situation against the fixed path and refuses.
 *
 * The two cases are deliberately adjacent. Case 1 is what makes case 2 non-vacuous: it proves the
 * mismatch is invisible unless the pin came from somewhere other than the thing being checked.
 */
import { describe, it, expect, beforeAll } from 'vitest'
import { buildShare, ShareUnpinnedError, type ShareMeta } from '../assignments/share'
import type { ShareBundle } from '../share/sharecrypto'
import { toBase64 } from '../share/sharecrypto'
import {
  initShareCrypto,
  newIdentity,
  newBoxKeyPair,
  publicOf,
  fingerprint,
  fingerprints,
  PinStore,
  PairingError,
  type Identity,
  type BoxKeyPair,
} from '../share/pairing'
import { loadPins, savePins, pinOnFirstUse, PIN_STORAGE_KEY, type PinStorage } from './pinStore'

/** A stand-in for localStorage that a test can inspect and that survives a "new session". */
function memoryStorage(): PinStorage & { data: Map<string, string> } {
  const data = new Map<string, string>()
  return {
    data,
    getItem: (k) => data.get(k) ?? null,
    setItem: (k, v) => void data.set(k, v),
  }
}

function bundleFor(ownerFp: string): ShareBundle {
  return {
    schema: 1,
    shareId: 'share-1',
    scope: { from: 0, to: 200, recordTypes: ['journal'] },
    ownerFp,
    checkIns: [],
    journal: [{ at: 120, text: 'the worst week so far' }],
  }
}

describe('owner console pin gate', () => {
  let owner: Identity
  let ther: Identity
  let imposterBox: BoxKeyPair
  let ownerFp: string

  beforeAll(async () => {
    await initShareCrypto()
    owner = newIdentity()
    ther = newIdentity()
    // Same therapist identity (Ed25519), different encryption key — the swap a pin must catch.
    imposterBox = newBoxKeyPair()
    ownerFp = fingerprint(owner.ed25519.publicKey)
  })

  /** Exactly what ShareBuilder.seal() does: address the share to whatever box key it was handed. */
  function seal(boxPub: Uint8Array, pins: PinStore) {
    const meta: ShareMeta = {
      context: 'daymark.share.v1',
      shareId: 'share-1',
      version: 0,
      recipientFp: fingerprint(boxPub),
      expiry: 10_000,
      ownerSigningFp: ownerFp,
    }
    return buildShare(bundleFor(ownerFp), meta, boxPub, owner.ed25519, fingerprint(ther.ed25519.publicKey), pins)
  }

  it('1. OLD BEHAVIOUR: a pin store built from the keys being sealed to accepts a swapped key', () => {
    const inlinePins = new PinStore()
    inlinePins.pin({ x25519Pub: imposterBox.publicKey, ed25519Pub: ther.ed25519.publicKey })
    // No throw. This is the pre-fix ShareBuilder, sealing the journal to the substituted key.
    expect(seal(imposterBox.publicKey, inlinePins).recipientFp).toBe(fingerprint(imposterBox.publicKey))
  })

  it('2. FIXED: a pin recorded in an earlier session refuses the swapped key', () => {
    const storage = memoryStorage()
    const firstSession = loadPins(storage)
    expect(pinOnFirstUse(firstSession, publicOf(ther))).toBe('pinned-now')
    savePins(firstSession, storage)

    // Later session: fresh load, therapist's box key has been substituted since.
    const laterSession = loadPins(storage)
    expect(pinOnFirstUse(laterSession, { x25519Pub: imposterBox.publicKey, ed25519Pub: ther.ed25519.publicKey }))
      .toBe('already-pinned')
    expect(() => seal(imposterBox.publicKey, laterSession)).toThrow(ShareUnpinnedError)

    // …and the genuine key still seals, so the refusal is about the swap and not about everything.
    expect(seal(ther.x25519.publicKey, laterSession).recipientFp).toBe(fingerprints(publicOf(ther)).x25519Fp)
  })

  it('3. pinOnFirstUse leaves an existing pin alone — overwriting it is the original bug', () => {
    const pins = new PinStore()
    pinOnFirstUse(pins, publicOf(ther))
    const before = pins.pinnedX25519Fp(fingerprint(ther.ed25519.publicKey))
    expect(before).toBe(fingerprint(ther.x25519.publicKey))

    pinOnFirstUse(pins, { x25519Pub: imposterBox.publicKey, ed25519Pub: ther.ed25519.publicKey })
    expect(pins.pinnedX25519Fp(fingerprint(ther.ed25519.publicKey))).toBe(before)
  })

  it('4. a pin written in one session is readable in the next, from the stored bytes alone', () => {
    const storage = memoryStorage()
    const pins = new PinStore()
    pins.pin(publicOf(ther))
    savePins(pins, storage)
    expect(storage.data.has(PIN_STORAGE_KEY)).toBe(true)

    const reloaded = loadPins(storage)
    expect(reloaded.isPinned(fingerprint(ther.ed25519.publicKey))).toBe(true)
    expect(reloaded.pinnedX25519Fp(fingerprint(ther.ed25519.publicKey))).toBe(fingerprint(ther.x25519.publicKey))
  })

  it('5. what gets persisted is fingerprints only — no key bytes', () => {
    const storage = memoryStorage()
    const pins = new PinStore()
    pins.pin(publicOf(ther))
    savePins(pins, storage)
    const stored = storage.data.get(PIN_STORAGE_KEY) ?? ''

    // Detector first: the thing we DO expect is in there, so "not found" below means something.
    expect(stored).toContain(fingerprint(ther.ed25519.publicKey))
    expect(stored).toContain(fingerprint(ther.x25519.publicKey))
    expect(stored).not.toContain(toBase64(ther.ed25519.publicKey))
    expect(stored).not.toContain(toBase64(ther.x25519.publicKey))
    expect(stored).not.toContain(toBase64(ther.ed25519.privateKey))
  })

  it('6. unreadable stored pins fail the seal instead of silently un-pinning everyone', () => {
    const storage = memoryStorage()
    storage.data.set(PIN_STORAGE_KEY, '{ not json')
    expect(() => loadPins(storage)).toThrow(PairingError)

    storage.data.set(PIN_STORAGE_KEY, JSON.stringify([{ pinnedAt: 1 }]))
    expect(() => loadPins(storage)).toThrow(PairingError)

    // Why it throws rather than returning new PinStore(): an empty store is what case 1 shows is
    // waved straight through. Losing the pins must not look the same as never having had any.
    const empty = new PinStore()
    expect(() => seal(imposterBox.publicKey, empty)).toThrow(ShareUnpinnedError)
  })

  it('7. no storage at all is refused, not treated as "nothing pinned yet"', () => {
    expect(() => loadPins(null)).toThrow(PairingError)
    expect(() => savePins(new PinStore(), null)).toThrow(PairingError)
  })
})
