/*
 * The two things an owner can do to the pin record: FORGET it, and ROTATE one entry.
 *
 * Both are dangerous in opposite directions, so both are tested by their consequence at the seal
 * rather than by the shape of the store. Forgetting is supposed to give up key-substitution cover —
 * the screen says so in those words — and the test that proves it does so by showing a swapped key
 * being accepted afterwards, which is the same behaviour pinStore.test.ts case 1 records as the
 * original bug. That is not a contradiction: there the acceptance was silent and unasked-for, here
 * it is what a person deliberately chose, having been told. The difference is the whole design, and
 * it only shows up if the test asserts the uncomfortable half out loud.
 *
 * Rotation is the other direction: it hands the pin's one property back if it can be reached by a
 * click. So the tests here try to reach it without the out-of-band words — with the wrong phrase,
 * with an empty phrase confirming an empty phrase — and require that the stored key survives every
 * attempt. Each of those is paired with the same call carrying the real words, so a refusal that
 * refuses everything cannot pass for a check.
 */
import { describe, it, expect, beforeAll } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { buildShare, ShareUnpinnedError, type ShareMeta } from '../assignments/share'
import type { ShareBundle } from '../share/sharecrypto'
import {
  initShareCrypto,
  newIdentity,
  newBoxKeyPair,
  publicOf,
  sasWords,
  fingerprint,
  PinStore,
  PairingError,
  type Identity,
  type BoxKeyPair,
} from '../share/pairing'
import {
  loadPins,
  savePins,
  listPins,
  forgetAllPins,
  forgetPeer,
  pendingRotation,
  rotatePin,
  sasWordsMatch,
  pinOnFirstUse,
  PIN_STORAGE_KEY,
  type PinStorage,
} from './pinStore'

/** localStorage's shape, including the part that can erase. */
function memoryStorage(): PinStorage & { data: Map<string, string> } {
  const data = new Map<string, string>()
  return {
    data,
    getItem: (k) => data.get(k) ?? null,
    setItem: (k, v) => void data.set(k, v),
    removeItem: (k) => void data.delete(k),
  }
}

/** Read and write but no erase — what a narrower Storage slice, or a blocked one, looks like. */
function unerasableStorage(): PinStorage & { data: Map<string, string> } {
  const s = memoryStorage()
  return { data: s.data, getItem: s.getItem, setItem: s.setItem }
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

describe('forgetting and rotating the pin record', () => {
  let owner: Identity
  let ther: Identity
  let other: Identity
  /** The same therapist identity with a different encryption key: a re-key, or a substitution. */
  let newBox: BoxKeyPair
  let ownerFp: string
  let therFp: string
  /** The phrase both consoles show once the therapist is holding newBox. */
  let newWords: string

  beforeAll(async () => {
    await initShareCrypto()
    owner = newIdentity()
    ther = newIdentity()
    other = newIdentity()
    newBox = newBoxKeyPair()
    ownerFp = fingerprint(owner.ed25519.publicKey)
    therFp = fingerprint(ther.ed25519.publicKey)
    newWords = sasWords(publicOf(owner), { x25519Pub: newBox.publicKey, ed25519Pub: ther.ed25519.publicKey }).join(' ')
  })

  /** What ShareBuilder.seal() does: address the share to whatever box key it was handed. */
  function seal(boxPub: Uint8Array, pins: PinStore, peer: Identity = ther) {
    const meta: ShareMeta = {
      context: 'daymark.share.v1',
      shareId: 'share-1',
      version: 0,
      recipientFp: fingerprint(boxPub),
      expiry: 10_000,
      ownerSigningFp: ownerFp,
    }
    return buildShare(bundleFor(ownerFp), meta, boxPub, owner.ed25519, fingerprint(peer.ed25519.publicKey), pins)
  }

  /** A storage that already holds a pin for `ther` on their original key. */
  function storageWithPin() {
    const storage = memoryStorage()
    const pins = new PinStore()
    pins.pin(publicOf(ther))
    savePins(pins, storage)
    return storage
  }

  /* ── Forgetting ───────────────────────────────────────────────────────── */

  it('forgetting removes the record rather than writing an empty one', () => {
    const storage = storageWithPin()
    // Detector: the record is there, and it is the fingerprints the screen shows.
    expect(storage.data.has(PIN_STORAGE_KEY)).toBe(true)
    expect(listPins(loadPins(storage)).map((p) => p.ed25519Fp)).toEqual([therFp])

    forgetAllPins(storage)

    // The KEY itself is gone, not set to '[]'. An empty array would still be a
    // `daymark.owner.pins.v1` entry announcing that this browser profile is an owner console,
    // which is half of what someone clearing the record is clearing.
    expect(storage.data.has(PIN_STORAGE_KEY)).toBe(false)
    expect([...storage.data.values()].join('')).not.toContain(therFp)
    expect(listPins(loadPins(storage))).toEqual([])
  })

  it('forgetting re-arms trust on first use — the cost the screen states in words', () => {
    const storage = storageWithPin()
    // Before: the substituted key is refused, so the refusal below is a real one to give up.
    expect(() => seal(newBox.publicKey, loadPins(storage))).toThrow(ShareUnpinnedError)

    forgetAllPins(storage)

    // After: the next seal records whatever key it is handed and goes through. This is the
    // sentence "it means the next seal trusts on first use again", asserted rather than promised.
    const pins = loadPins(storage)
    expect(pinOnFirstUse(pins, { x25519Pub: newBox.publicKey, ed25519Pub: ther.ed25519.publicKey })).toBe('pinned-now')
    expect(seal(newBox.publicKey, pins).recipientFp).toBe(fingerprint(newBox.publicKey))
  })

  it('storage that cannot erase refuses out loud instead of reporting a clear that did not happen', () => {
    const storage = unerasableStorage()
    const pins = new PinStore()
    pins.pin(publicOf(ther))
    savePins(pins, storage)

    expect(() => forgetAllPins(storage)).toThrow(PairingError)
    // The point of throwing: the record is still there, and nobody was told otherwise.
    expect(storage.data.has(PIN_STORAGE_KEY)).toBe(true)
    expect(listPins(loadPins(storage)).map((p) => p.ed25519Fp)).toEqual([therFp])
    // And no storage at all is refused too, rather than counting as "already clear".
    expect(() => forgetAllPins(null)).toThrow(PairingError)
  })

  it('forgetting one relationship leaves the others pinned, cover and all', () => {
    const storage = memoryStorage()
    const pins = new PinStore()
    pins.pin(publicOf(ther))
    pins.pin(publicOf(other))
    savePins(pins, storage)

    savePins(forgetPeer(loadPins(storage), therFp), storage)

    const after = loadPins(storage)
    expect(listPins(after).map((p) => p.ed25519Fp)).toEqual([fingerprint(other.ed25519.publicKey)])
    // The forgotten one is back to trust-on-first-use…
    expect(pinOnFirstUse(after, { x25519Pub: newBox.publicKey, ed25519Pub: ther.ed25519.publicKey })).toBe('pinned-now')
    // …and the one that was kept still refuses a swap, which is what forget-all would have cost.
    expect(() => seal(newBox.publicKey, loadPins(storage), other)).toThrow(ShareUnpinnedError)
  })

  /* ── Rotation ─────────────────────────────────────────────────────────── */

  it('a rotation is offered only when the recorded key and the live key actually disagree', () => {
    const pins = loadPins(storageWithPin())
    // Unchanged key: no prompt. Training someone to click through a confirmation that usually
    // means nothing is how a real substitution gets waved past.
    expect(pendingRotation(pins, publicOf(ther))).toBeNull()
    // Never pinned: trust-on-first-use's job, not a decision to put in front of anyone.
    expect(pendingRotation(pins, publicOf(other))).toBeNull()

    const rotation = pendingRotation(pins, { x25519Pub: newBox.publicKey, ed25519Pub: ther.ed25519.publicKey })
    expect(rotation).not.toBeNull()
    expect(rotation!.pinnedX25519Fp).toBe(fingerprint(ther.x25519.publicKey))
    expect(rotation!.offeredX25519Fp).toBe(fingerprint(newBox.publicKey))
    expect(rotation!.ed25519Fp).toBe(therFp)
  })

  it('the wrong words leave the recorded key exactly where it was', () => {
    const storage = storageWithPin()
    const pins = loadPins(storage)
    const peer = { x25519Pub: newBox.publicKey, ed25519Pub: ther.ed25519.publicKey }

    expect(() =>
      rotatePin(pins, peer, { expectedWords: newWords, typedWords: 'anchor harbour lantern meadow' }),
    ).toThrow(PairingError)
    expect(pins.pinnedX25519Fp(therFp)).toBe(fingerprint(ther.x25519.publicKey))
    expect(() => seal(newBox.publicKey, pins)).toThrow(ShareUnpinnedError)

    // Detector: the same call with the words the owner would have read back does go through, so
    // the refusal above is about the words and not about rotation being wired shut.
    expect(rotatePin(pins, peer, { expectedWords: newWords, typedWords: newWords }).offeredX25519Fp).toBe(
      fingerprint(newBox.publicKey),
    )
    expect(pins.pinnedX25519Fp(therFp)).toBe(fingerprint(newBox.publicKey))
  })

  it('an empty confirmation cannot confirm itself', () => {
    // The failure this shape invites: a caller that could not compute the words passes '', the
    // owner types nothing, '' === '' and the console rotates on a click — a confirmation that is
    // a tautology, which is the exact defect the pin fix was about in the first place.
    const pins = loadPins(storageWithPin())
    const peer = { x25519Pub: newBox.publicKey, ed25519Pub: ther.ed25519.publicKey }

    expect(() => rotatePin(pins, peer, { expectedWords: '', typedWords: '' })).toThrow(PairingError)
    expect(() => rotatePin(pins, peer, { expectedWords: '   ', typedWords: '' })).toThrow(PairingError)
    expect(() => rotatePin(pins, peer, { expectedWords: 'lantern meadow', typedWords: 'lantern meadow' })).toThrow(
      PairingError,
    )
    expect(pins.pinnedX25519Fp(therFp)).toBe(fingerprint(ther.x25519.publicKey))

    // Detector for all three: a real six-word phrase, typed back, is accepted.
    expect(newWords.split(' ')).toHaveLength(6)
    expect(rotatePin(pins, peer, { expectedWords: newWords, typedWords: newWords })).toBeTruthy()
  })

  it('the words are compared as words — case and spacing are how they were said aloud', () => {
    expect(sasWordsMatch(newWords, `  ${newWords.toUpperCase().replace(/ /g, '   ')}\n`)).toBe(true)
    // But not word-blind: dropping one is a different phrase, and so is reordering.
    const words = newWords.split(' ')
    expect(sasWordsMatch(newWords, words.slice(0, 5).join(' '))).toBe(false)
    expect(sasWordsMatch(newWords, [words[1], words[0], ...words.slice(2)].join(' '))).toBe(false)
    // And the floor holds regardless of what was typed back.
    expect(sasWordsMatch('', '')).toBe(false)
    expect(sasWordsMatch('one two three', 'one two three')).toBe(false)
  })

  it('rotation replaces the recorded key rather than adding to it, across sessions', () => {
    const storage = storageWithPin()
    const pins = loadPins(storage)
    const peer = { x25519Pub: newBox.publicKey, ed25519Pub: ther.ed25519.publicKey }
    rotatePin(pins, peer, { expectedWords: newWords, typedWords: newWords })
    savePins(pins, storage)

    // A later session, reading the stored bytes alone.
    const later = loadPins(storage)
    expect(listPins(later)).toHaveLength(1)
    expect(seal(newBox.publicKey, later).recipientFp).toBe(fingerprint(newBox.publicKey))
    // The old key is now the one that gets refused — otherwise "rotate" would mean "trust both",
    // and a substituted key would stay usable for as long as the owner kept sealing.
    expect(() => seal(ther.x25519.publicKey, later)).toThrow(ShareUnpinnedError)
  })

  it('there is nothing to rotate when nothing is pinned, or when nothing changed', () => {
    const pins = loadPins(storageWithPin())
    // Not pinned: rotating would be pinning, under a word that promises a comparison was made.
    expect(() => rotatePin(pins, publicOf(other), { expectedWords: newWords, typedWords: newWords })).toThrow(
      PairingError,
    )
    // Unchanged: refused rather than quietly re-pinning, which is how the original bug was written.
    const same = sasWords(publicOf(owner), publicOf(ther)).join(' ')
    expect(() => rotatePin(pins, publicOf(ther), { expectedWords: same, typedWords: same })).toThrow(PairingError)
    expect(pins.pinnedX25519Fp(therFp)).toBe(fingerprint(ther.x25519.publicKey))
  })
})

/*
 * The screen itself has no rendering harness in this project, so what is checked here is the one
 * property that would make the store's refusals irrelevant: another way in. If the component can
 * write a pin without going through rotatePin, every test above is about a door beside an open
 * window.
 */
describe('the console has no second way to overwrite a pin', () => {
  const src = readFileSync(fileURLToPath(new URL('../components/owner/PinRecord.svelte', import.meta.url)), 'utf8')

  it('the screen was read, and it is the one that does this work', () => {
    expect(src.length).toBeGreaterThan(2000)
    expect(src).toContain('rotatePin(')
    expect(src).toContain('forgetAllPins(')
    expect(src).toContain('forgetPeer(')
  })

  it('every write to the record goes through rotatePin or forgetPeer', () => {
    // `pins.pin(...)` is the store's raw setter and `pinOnFirstUse` is the seal path's; either one
    // here would record a key with no words asked for. Detector: the patterns do match the calls
    // they are looking for, so an empty result below means absence and not a broken regex.
    expect(/\.pin\(/.test('pins.pin(peer)')).toBe(true)
    expect(/\.pin\(/.test('row.pin.ed25519Fp')).toBe(false)
    expect(/\bpinOnFirstUse\b/.test('pinOnFirstUse(pins, peer)')).toBe(true)

    expect(/\.pin\(/.test(src)).toBe(false)
    expect(/\bpinOnFirstUse\b/.test(src)).toBe(false)
  })

  it('the accept button is inert until the words match, and shares the store’s predicate', () => {
    // Belt to the store's braces: rotatePin refuses regardless, but a button that looks live
    // invites the click that a served page would want, and a second copy of the comparison in the
    // component is a copy that can drift into being more permissive than the one that decides.
    expect(src).toContain('sasWordsMatch(')
    expect(src).toContain('disabled={!matches}')
  })
})
