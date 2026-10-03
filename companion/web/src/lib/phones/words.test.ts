import { describe, it, expect } from 'vitest'
import { initCrypto } from '../sync/crypto'
import { SAS_WORDLIST } from '../share/wordlist'
import { DEVICE_WORDS_CONTEXT, deviceKeyId, deviceWords, isPublicKeyB64 } from './words'

/*
 * THE VECTORS THE PHONE TESTS TOO (#431). The words are a comparison between two screens, so the
 * only thing that matters is that both make the same six from the same key. These two keys and their
 * words are the shared fixture: the phone's implementation must reproduce them exactly.
 */
const VECTORS: [string, string[]][] = [
  ['JUO5L_EJVRFHatyDadtt3JM2ZaEZeN2hQE7hBmypVZ0', ['hotel', 'cedar', 'earth', 'coral', 'nacho', 'hotel']],
  ['A'.repeat(43), ['inlet', 'arrow', 'cedar', 'cobra', 'crisp', 'fault']],
]

describe('the six words of a phone key', () => {
  it('match the vectors the phone tests', async () => {
    for (const [key, words] of VECTORS) {
      expect(await deviceWords(key), key).toEqual(words)
    }
  })

  it('are the first six bytes of BLAKE2b-256 over the context, a line feed and the key text', async () => {
    // Worked out here from the definition rather than from the function, so a function that drifted
    // from the definition (another context, no line feed, the key's bytes instead of its text) and a
    // vector updated to match it would still disagree with this.
    const sodium = await initCrypto()
    const [key, words] = VECTORS[0]!
    const digest = sodium.crypto_generichash(32, sodium.from_string(`daymark-device-words-v1\n${key}`))
    expect(DEVICE_WORDS_CONTEXT).toBe('daymark-device-words-v1')
    expect(Array.from(digest.subarray(0, 6), (b) => SAS_WORDLIST[b])).toEqual(words)
    // Positive control: the same bytes read one place along the list give other words, so a list
    // shifted by one entry is seen.
    expect(Array.from(digest.subarray(0, 6), (b) => SAS_WORDLIST[(b + 1) % 256])).not.toEqual(words)
  })

  it('read a list of exactly 256 distinct words, so every byte has one', () => {
    expect(SAS_WORDLIST).toHaveLength(256)
    expect(new Set(SAS_WORDLIST).size).toBe(256)
  })

  it('differ for keys one character apart', async () => {
    const [key] = VECTORS[0]!
    // The mutation is derived from the key, so it really changes it.
    const other = key.slice(0, 5) + (key[5] === 'B' ? 'C' : 'B') + key.slice(6)
    expect(other).not.toBe(key)
    expect(await deviceWords(other)).not.toEqual(await deviceWords(key))
  })
})

describe('the key id the confirmation names', () => {
  it('is base64url of BLAKE2b-128 of the key bytes, as the server computes it', async () => {
    const sodium = await initCrypto()
    const variant = sodium.base64_variants.URLSAFE_NO_PADDING
    for (const [key] of VECTORS) {
      const expected = sodium.to_base64(sodium.crypto_generichash(16, sodium.from_base64(key, variant)), variant)
      expect(await deviceKeyId(key)).toBe(expected)
      expect(expected).toHaveLength(22)
    }
  })

  it('is refused for text that is not one canonical key', async () => {
    const [key] = VECTORS[0]!
    expect(isPublicKeyB64(key)).toBe(true)
    // Stray bits in the last character: the same bytes, another spelling.
    const respelt = key.slice(0, 42) + (key[42] === '1' ? '2' : '1')
    expect(respelt).not.toBe(key)
    expect(isPublicKeyB64(respelt)).toBe(false)
    expect(await deviceKeyId(respelt)).toBeNull()
    expect(await deviceKeyId(key + '=')).toBeNull()
    expect(await deviceKeyId(key.slice(1))).toBeNull()
    expect(await deviceKeyId(key.replace('_', '/'))).toBeNull()
    expect(await deviceKeyId('')).toBeNull()
  })
})
