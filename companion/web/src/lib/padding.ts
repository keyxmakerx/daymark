/*
 * PADDING BEFORE ENCRYPTION — one rule for every item the consoles seal (#214, #315).
 *
 * The server stores ciphertext it cannot read, but it sees exact sizes, and a size is information
 * derived from content: how much someone wrote, how a share grew between versions, and for short
 * items what kind of item it is. So every item is padded on the device, INSIDE the encryption, to
 * a size taken from a small set:
 *
 *   - up to 1 MiB: the next power of two, never less than 4 KiB;
 *   - above 1 MiB: the Padmé length (Nikitin et al., "Reducing Metadata Leakage from Encrypted
 *     Files and Communication with PURBs", PETS 2019), never more than about 12% larger.
 *
 * Padding hides how much, never when. Nothing that uses this may say or imply that timing is
 * hidden.
 *
 * LAYOUT (a wire contract; the phone reproduces it byte for byte, #316):
 *
 *   padded = u32 big-endian n || the n plaintext bytes || zero bytes
 *   length(padded) = paddedLength(4 + n)
 *
 * A length prefix rather than libsodium's sodium_pad because it is trivially the same in Kotlin.
 * Unpadding is strict: the prefix must fit, the total must be exactly the bucket for that
 * prefix, and every byte after the plaintext must be zero. Whatever carries a padded item also
 * authenticates it (an AEAD or a signature), so strictness is not the security boundary; it
 * makes a writer that pads wrongly fail loudly instead of producing items only it can read.
 */

export const MIN_PADDED = 4096
export const POWER_OF_TWO_LIMIT = 1 << 20 // 1 MiB
const PREFIX = 4
const MAX_PLAINTEXT = 0xffffffff

export class PaddingError extends Error {}

/** Number of bits needed to write x (x >= 1): 1 -> 1, 2 -> 2, 4 -> 3. Exact for any safe integer. */
function bitLength(x: number): number {
  return x.toString(2).length
}

/**
 * The Padmé length for L: keep the top bits of L's binary form and round the rest up, so at
 * most log2(log2(L)) + 1 significant bits remain.
 */
export function padme(L: number): number {
  if (!Number.isSafeInteger(L) || L < 1) throw new RangeError(`padme: bad length ${L}`)
  const E = bitLength(L) - 1 // floor(log2 L)
  const S = bitLength(E) // floor(log2 E) + 1
  const lastBits = E - S
  if (lastBits <= 0) return L
  const step = 2 ** lastBits
  return Math.ceil(L / step) * step
}

/** The size an item of `x` bytes (prefix included) is padded to. */
export function paddedLength(x: number): number {
  if (!Number.isSafeInteger(x) || x < 0) throw new RangeError(`paddedLength: bad length ${x}`)
  if (x <= MIN_PADDED) return MIN_PADDED
  if (x <= POWER_OF_TWO_LIMIT) return 2 ** bitLength(x - 1)
  return padme(x)
}

/** Pad a plaintext before it is encrypted. */
export function pad(plaintext: Uint8Array): Uint8Array {
  const n = plaintext.length
  if (n > MAX_PLAINTEXT) throw new PaddingError('item too large to pad')
  const out = new Uint8Array(paddedLength(PREFIX + n)) // zero-filled
  new DataView(out.buffer).setUint32(0, n, false)
  out.set(plaintext, PREFIX)
  return out
}

/** Undo pad(). Throws PaddingError on anything pad() could not have produced. */
export function unpad(padded: Uint8Array): Uint8Array {
  if (padded.length < PREFIX) throw new PaddingError('padded item too short')
  const n = new DataView(padded.buffer, padded.byteOffset, padded.byteLength).getUint32(0, false)
  if (PREFIX + n > padded.length) throw new PaddingError('padded item length prefix overruns the item')
  if (padded.length !== paddedLength(PREFIX + n)) throw new PaddingError('padded item is not its bucket size')
  for (let i = PREFIX + n; i < padded.length; i++) {
    if (padded[i] !== 0) throw new PaddingError('padded item has non-zero padding')
  }
  return padded.slice(PREFIX, PREFIX + n)
}
