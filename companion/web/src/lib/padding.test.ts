import { describe, it, expect } from 'vitest'
import { MIN_PADDED, POWER_OF_TWO_LIMIT, padme, paddedLength, pad, unpad, PaddingError } from './padding'

const MiB = 1 << 20

/** A length the padding could have produced for some input: the buckets, and nothing else. */
function isBucket(len: number): boolean {
  return len >= MIN_PADDED && paddedLength(len) === len
}

/** Byte-for-byte equality; toEqual walks a 1 MiB array element by element and takes seconds. */
function sameBytes(a: Uint8Array, b: Uint8Array): boolean {
  return Buffer.from(a.buffer, a.byteOffset, a.byteLength).equals(Buffer.from(b.buffer, b.byteOffset, b.byteLength))
}

function bytes(n: number, seed = 7): Uint8Array {
  const b = new Uint8Array(n)
  for (let i = 0; i < n; i++) b[i] = (i * 31 + seed) & 0xff
  return b
}

describe('the bucket rule (#315)', () => {
  it('is 4 KiB up to 4 KiB, then the next power of two up to 1 MiB', () => {
    expect(paddedLength(0)).toBe(4096)
    expect(paddedLength(1)).toBe(4096)
    expect(paddedLength(4096)).toBe(4096)
    expect(paddedLength(4097)).toBe(8192)
    expect(paddedLength(8192)).toBe(8192)
    expect(paddedLength(8193)).toBe(16384)
    expect(paddedLength(MiB - 1)).toBe(MiB)
    expect(paddedLength(MiB)).toBe(MiB)
  })

  it('is the Padmé length above 1 MiB, never more than 12% larger', () => {
    expect(paddedLength(MiB + 1)).toBe(padme(MiB + 1))
    expect(padme(MiB + 1)).toBe(1_081_344)
    for (let L = MiB + 1; L < 64 * MiB; L += 104_729) {
      const p = paddedLength(L)
      expect(p).toBeGreaterThanOrEqual(L)
      expect((p - L) / L).toBeLessThanOrEqual(0.12)
      expect(paddedLength(p)).toBe(p) // a bucket pads to itself
    }
  })

  it('never shrinks an item, and every result is a bucket', () => {
    for (const x of [0, 5, 4095, 4096, 4097, 70_000, MiB - 5, MiB, MiB + 1, 3 * MiB + 17, 25 * MiB]) {
      expect(paddedLength(x)).toBeGreaterThanOrEqual(x)
      expect(isBucket(paddedLength(x))).toBe(true)
    }
  })

  it('the bucket check can fail: most lengths are not buckets (positive control)', () => {
    for (const len of [4097, 5000, 12_345, MiB + 1, 2 * MiB + 3]) {
      expect(isBucket(len)).toBe(false)
    }
  })
})

describe('pad and unpad', () => {
  it('round-trip one byte under, exactly on, and one byte over each boundary', () => {
    // The 4-byte prefix counts toward the bucket, so the boundaries sit 4 bytes below it.
    const edges = [0, 1, MIN_PADDED - 5, MIN_PADDED - 4, MIN_PADDED - 3, 8188, 8189, POWER_OF_TWO_LIMIT - 5, POWER_OF_TWO_LIMIT - 4, POWER_OF_TWO_LIMIT - 3]
    for (const n of edges) {
      const plain = bytes(n, n)
      const padded = pad(plain)
      expect(isBucket(padded.length)).toBe(true)
      expect(padded.length).toBe(paddedLength(4 + n))
      expect(sameBytes(unpad(padded), plain)).toBe(true)
    }
  })

  it('the byte comparison used above can fail (positive control)', () => {
    const a = bytes(100)
    const b = bytes(100)
    b[99] = a[99] === 0 ? 1 : 0
    expect(sameBytes(a, a.slice())).toBe(true)
    expect(sameBytes(a, b)).toBe(false)
    expect(sameBytes(a, bytes(99))).toBe(false)
  })

  it('the padded length says nothing finer than the bucket', () => {
    expect(pad(bytes(10)).length).toBe(pad(bytes(4000)).length)
    expect(pad(bytes(5000)).length).toBe(pad(bytes(8000)).length)
  })

  it('refuses a non-zero byte in the padding', () => {
    const padded = pad(bytes(10))
    const last = padded.length - 1
    expect(padded[last]).toBe(0) // so setting it to 1 really is a change
    padded[last] = 1
    expect(() => unpad(padded)).toThrow(PaddingError)
  })

  it('refuses a length that is not the bucket for its prefix', () => {
    const padded = pad(bytes(10))
    const longer = new Uint8Array(padded.length + 1)
    longer.set(padded)
    expect(() => unpad(longer)).toThrow(PaddingError)
    expect(() => unpad(padded.slice(0, padded.length - 1))).toThrow(PaddingError)
  })

  it('refuses a prefix that overruns the item, and an item too short to hold one', () => {
    const padded = pad(bytes(10))
    new DataView(padded.buffer).setUint32(0, padded.length, false)
    expect(() => unpad(padded)).toThrow(PaddingError)
    expect(() => unpad(new Uint8Array(3))).toThrow(PaddingError)
  })

  it('reads a padded item held inside a larger buffer', () => {
    const padded = pad(bytes(100))
    const host = new Uint8Array(padded.length + 16)
    host.set(padded, 8)
    expect(unpad(host.subarray(8, 8 + padded.length))).toEqual(bytes(100))
  })
})

describe('the vector the phone is held to (#316)', () => {
  it('pads "daymark" to 4096 bytes: length prefix, the text, then zeros', () => {
    const padded = pad(new TextEncoder().encode('daymark'))
    expect(padded.length).toBe(4096)
    const head = Array.from(padded.slice(0, 11), (b) => b.toString(16).padStart(2, '0')).join('')
    expect(head).toBe('00000007' + '6461796d61726b') // n = 7, then 'daymark' in UTF-8
    expect(padded.slice(11).every((b) => b === 0)).toBe(true)
  })

  it('maps these plaintext lengths to these padded lengths', () => {
    // Computed independently from the paper's bit-mask form of Padmé, not from this module.
    const table: Array<[number, number]> = [
      [0, 4096],
      [4092, 4096],
      [4093, 8192],
      [MiB - 4, MiB],
      [MiB - 3, 1_081_344],
      [5_000_000, 5_111_808],
      [25 * MiB, 26_738_688],
    ]
    for (const [n, expected] of table) expect(paddedLength(4 + n)).toBe(expected)
  })
})
