/*
 * THE SIX WORDS OF A PHONE'S KEY (#431), and the key's id.
 *
 * When a phone redeems a pairing code, the owner's page and the phone each show six words made from
 * the phone's public key, and the person compares them before confirming. If the server put a key
 * of its own in the phone's place, the words differ. That only works if both screens make the words
 * the same way, so this is the one definition, byte for byte, and the phone implements it again:
 *
 *   the first six bytes of BLAKE2b-256 over the UTF-8 text `daymark-device-words-v1`, one line feed
 *   (0x0A), and the public key exactly as the server sends it, in base64url without padding. Each
 *   byte is an index into SAS_WORDLIST (share/wordlist.ts), which has exactly 256 entries.
 *
 * words.test.ts holds the vectors the phone tests too. A change here without a change on the phone
 * makes every pairing a mismatch.
 *
 * THE KEY'S ID is worked out here from the same public key rather than taken from the server: base64url
 * of BLAKE2b-128 of the 32 key bytes, as the server's DeviceSignature.keyIdOf and
 * assignments/crypto.ts fingerprint() compute it. The confirmation names the key whose words were
 * shown, so a server that answered with one key's words and another key's id is refused before
 * anything is confirmed.
 *
 * PURE. No fetch, no DOM, no Svelte: libsodium and the word list.
 */
import { initCrypto } from '../sync/crypto'
import { SAS_WORDLIST } from '../share/wordlist'

/** What the key is hashed under, so these words can never be some other hash's words. */
export const DEVICE_WORDS_CONTEXT = 'daymark-device-words-v1'

/** How many words a key shows. */
export const DEVICE_WORD_COUNT = 6

/** A public key as the server sends one: the canonical base64url spelling of 32 bytes. */
const PUBLIC_KEY_B64 = /^[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]$/

/**
 * Whether [publicKeyB64] is a public key as the server sends one: 43 characters of base64url with no
 * padding, whose last character carries no stray bits, so each key has one spelling.
 */
export function isPublicKeyB64(publicKeyB64: string): boolean {
  return PUBLIC_KEY_B64.test(publicKeyB64)
}

/** The six words of the key [publicKeyB64], in order. */
export async function deviceWords(publicKeyB64: string): Promise<string[]> {
  const sodium = await initCrypto()
  const digest = sodium.crypto_generichash(32, sodium.from_string(`${DEVICE_WORDS_CONTEXT}\n${publicKeyB64}`))
  return Array.from(digest.subarray(0, DEVICE_WORD_COUNT), (byte) => SAS_WORDLIST[byte]!)
}

/** The key's id, base64url of BLAKE2b-128 of its bytes; null when [publicKeyB64] is not a key. */
export async function deviceKeyId(publicKeyB64: string): Promise<string | null> {
  if (!isPublicKeyB64(publicKeyB64)) return null
  const sodium = await initCrypto()
  const variant = sodium.base64_variants.URLSAFE_NO_PADDING
  const key = sodium.from_base64(publicKeyB64, variant)
  if (key.length !== 32) return null
  return sodium.to_base64(sodium.crypto_generichash(16, key), variant)
}
