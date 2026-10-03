/*
 * A QR CODE ENCODER, BYTE MODE ONLY (#431): the one drawing the Phones section makes, of the text
 * `daymark-pair:v1?server=…&code=…` (ceremony.ts, pairingQrText) for the phone's camera.
 *
 * WHY IT IS VENDORED HERE AND NOT A RUNTIME DEPENDENCY. The owner's page holds the access token, and
 * every runtime package is code that runs beside it on every load, published by somebody else. The
 * page's posture is vendored and third-party-free, with libsodium the one runtime package. An encoder
 * for one short text in one mode is the tables and four steps below, and its only way to fail is a
 * code that reads as something else, which qr.test.ts rules out by comparing every module it draws
 * with an independent implementation (qrcode-generator, a devDependency that never ships in the
 * bundle), for every version and every error-correction level.
 *
 * THE STANDARD (ISO/IEC 18004), and the steps in order:
 *   1. the bits: mode 0100, the byte count, the bytes, a terminator, padding 0xEC 0x11;
 *   2. Reed–Solomon error correction over GF(256) (x^8 + x^4 + x^3 + x^2 + 1) per block, and the
 *      blocks interleaved;
 *   3. the function patterns (finders, timing, alignment, format and version information) and the
 *      codewords laid in the two-column zigzag;
 *   4. one of the eight masks, by the standard's penalty score unless one is given.
 *
 * The steps, the tables and several formulas follow Project Nayuki's QR Code generator library
 * (https://www.nayuki.io/page/qr-code-generator-library), whose notice is kept here as its licence
 * asks:
 *
 *   Copyright (c) Project Nayuki. (MIT License)
 *
 *   Permission is hereby granted, free of charge, to any person obtaining a copy of this software
 *   and associated documentation files (the "Software"), to deal in the Software without
 *   restriction, including without limitation the rights to use, copy, modify, merge, publish,
 *   distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the
 *   Software is furnished to do so, subject to the following conditions:
 *   - The above copyright notice and this permission notice shall be included in all copies or
 *     substantial portions of the Software.
 *   - The Software is provided "as is", without warranty of any kind, express or implied, including
 *     but not limited to the warranties of merchantability, fitness for a particular purpose and
 *     noninfringement. In no event shall the authors or copyright holders be liable for any claim,
 *     damages or other liability, whether in an action of contract, tort or otherwise, arising
 *     from, out of or in connection with the Software or the use or other dealings in the Software.
 *
 * PURE. Text in, a square of booleans out (true is dark). No DOM.
 */

export type Ecl = 'L' | 'M' | 'Q' | 'H'

/** The two format bits of each level: L 01, M 00, Q 11, H 10. */
const FORMAT_BITS: Record<Ecl, number> = { L: 1, M: 0, Q: 3, H: 2 }
const ECL_ORDER: readonly Ecl[] = ['L', 'M', 'Q', 'H']

/* Per version 1–40 (index 0 unused): error-correction codewords in each block, and the number of blocks. */
// prettier-ignore
const ECC_PER_BLOCK: Record<Ecl, readonly number[]> = {
  L: [-1, 7, 10, 15, 20, 26, 18, 20, 24, 30, 18, 20, 24, 26, 30, 22, 24, 28, 30, 28, 28, 28, 28, 30, 30, 26, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30],
  M: [-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26, 26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28],
  Q: [-1, 13, 22, 18, 26, 18, 24, 18, 22, 20, 24, 28, 26, 24, 20, 30, 24, 28, 28, 26, 30, 28, 30, 30, 30, 30, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30],
  H: [-1, 17, 28, 22, 16, 22, 28, 26, 26, 24, 28, 24, 28, 22, 24, 24, 30, 28, 28, 26, 28, 30, 24, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30],
}
// prettier-ignore
const BLOCKS: Record<Ecl, readonly number[]> = {
  L: [-1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4, 4, 4, 4, 4, 6, 6, 6, 6, 7, 8, 8, 9, 9, 10, 12, 12, 12, 13, 14, 15, 16, 17, 18, 19, 19, 20, 21, 22, 24, 25],
  M: [-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16, 17, 17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49],
  Q: [-1, 1, 1, 2, 2, 4, 4, 6, 6, 8, 8, 8, 10, 12, 16, 12, 17, 16, 18, 21, 20, 23, 23, 25, 27, 29, 34, 34, 35, 38, 40, 43, 45, 48, 51, 53, 56, 59, 62, 65, 68],
  H: [-1, 1, 1, 2, 4, 4, 4, 5, 6, 8, 8, 11, 11, 16, 16, 18, 16, 19, 21, 25, 25, 25, 34, 30, 32, 35, 37, 40, 42, 45, 48, 51, 54, 57, 60, 63, 66, 70, 74, 77, 81],
}

export const MIN_VERSION = 1
export const MAX_VERSION = 40

/** The modules of a version left for data and error correction once every function pattern is drawn. */
function rawDataModules(version: number): number {
  let result = (16 * version + 128) * version + 64
  if (version >= 2) {
    const align = Math.floor(version / 7) + 2
    result -= (25 * align - 10) * align - 55
    if (version >= 7) result -= 36
  }
  return result
}

/** How many 8-bit data codewords a version holds at a level. */
export function dataCodewords(version: number, ecl: Ecl): number {
  return Math.floor(rawDataModules(version) / 8) - ECC_PER_BLOCK[ecl][version]! * BLOCKS[ecl][version]!
}

/** The bits byte mode needs for [length] bytes at [version]: mode, count and data. */
function bitsNeeded(length: number, version: number): number {
  return 4 + (version <= 9 ? 8 : 16) + 8 * length
}

/* ── Reed–Solomon over GF(256), reducing polynomial 0x11D ──────────────────────────────────────── */

function gfMultiply(x: number, y: number): number {
  let z = 0
  for (let i = 7; i >= 0; i--) {
    z = (z << 1) ^ ((z >>> 7) * 0x11d)
    z ^= ((y >>> i) & 1) * x
  }
  return z & 0xff
}

/** The generator polynomial of [degree], highest coefficient first and the leading 1 left out. */
function rsDivisor(degree: number): number[] {
  const result = new Array<number>(degree).fill(0)
  result[degree - 1] = 1
  let root = 1
  for (let i = 0; i < degree; i++) {
    for (let j = 0; j < result.length; j++) {
      result[j] = gfMultiply(result[j]!, root)
      if (j + 1 < result.length) result[j]! ^= result[j + 1]!
    }
    root = gfMultiply(root, 0x02)
  }
  return result
}

function rsRemainder(data: readonly number[], divisor: readonly number[]): number[] {
  const result = divisor.map(() => 0)
  for (const b of data) {
    const factor = b ^ result.shift()!
    result.push(0)
    divisor.forEach((coef, i) => {
      result[i]! ^= gfMultiply(coef, factor)
    })
  }
  return result
}

/* ── The encoder ───────────────────────────────────────────────────────────────────────────────── */

export interface QrOptions {
  /** The least error correction to use. */
  ecl?: Ecl
  /** Raise the level while the text still fits the same version. On by default. */
  boost?: boolean
  /** Use this version, or refuse; by default the smallest that fits. */
  version?: number
  /** Use this mask, 0–7; by default the one with the lowest penalty. */
  mask?: number
}

export interface QrCode {
  version: number
  ecl: Ecl
  mask: number
  size: number
  /** modules[y][x], true for dark. */
  modules: boolean[][]
}

/** The QR code of [text], as UTF-8 bytes in byte mode. Throws when it cannot fit version 40. */
export function encodeQr(text: string, options: QrOptions = {}): QrCode {
  return encodeQrBytes(new TextEncoder().encode(text), options)
}

export function encodeQrBytes(bytes: Uint8Array, options: QrOptions = {}): QrCode {
  let ecl: Ecl = options.ecl ?? 'M'
  const fits = (v: number, level: Ecl) => bitsNeeded(bytes.length, v) <= dataCodewords(v, level) * 8
  let version = options.version ?? 0
  if (version === 0) {
    version = MIN_VERSION
    while (version <= MAX_VERSION && !fits(version, ecl)) version++
    if (version > MAX_VERSION) throw new RangeError('text too long for a QR code')
  } else if (!Number.isInteger(version) || version < MIN_VERSION || version > MAX_VERSION || !fits(version, ecl)) {
    throw new RangeError('text does not fit that version')
  }
  if (options.boost ?? true) {
    for (const level of ECL_ORDER.slice(ECL_ORDER.indexOf(ecl) + 1)) if (fits(version, level)) ecl = level
  }
  if (options.mask !== undefined && !(Number.isInteger(options.mask) && options.mask >= 0 && options.mask <= 7)) {
    throw new RangeError('a mask is 0 to 7')
  }

  // 1. The bits.
  const bits: number[] = []
  const push = (value: number, length: number) => {
    for (let i = length - 1; i >= 0; i--) bits.push((value >>> i) & 1)
  }
  push(0b0100, 4)
  push(bytes.length, version <= 9 ? 8 : 16)
  for (const b of bytes) push(b, 8)
  const capacity = dataCodewords(version, ecl) * 8
  push(0, Math.min(4, capacity - bits.length))
  push(0, (8 - (bits.length % 8)) % 8)
  const data: number[] = []
  for (let i = 0; i < bits.length; i += 8) data.push(bits.slice(i, i + 8).reduce((acc, bit) => (acc << 1) | bit, 0))
  for (let pad = 0xec; data.length < capacity / 8; pad ^= 0xec ^ 0x11) data.push(pad)

  // 2. Error correction, block by block, then interleaved.
  const codewords = interleave(data, version, ecl)

  // 3. The patterns and the codewords.
  const qr = new Matrix(version)
  qr.drawFunctionPatterns(ecl)
  qr.drawCodewords(codewords)

  // 4. The mask.
  let mask = options.mask ?? -1
  if (mask < 0) {
    let best = Infinity
    for (let m = 0; m < 8; m++) {
      qr.applyMask(m)
      qr.drawFormatBits(ecl, m)
      const penalty = qr.penalty()
      if (penalty < best) {
        best = penalty
        mask = m
      }
      qr.applyMask(m)
    }
  }
  qr.applyMask(mask)
  qr.drawFormatBits(ecl, mask)
  return { version, ecl, mask, size: qr.size, modules: qr.modules }
}

function interleave(data: readonly number[], version: number, ecl: Ecl): number[] {
  const numBlocks = BLOCKS[ecl][version]!
  const eccLen = ECC_PER_BLOCK[ecl][version]!
  const raw = Math.floor(rawDataModules(version) / 8)
  const numShort = numBlocks - (raw % numBlocks)
  const shortLen = Math.floor(raw / numBlocks)
  const divisor = rsDivisor(eccLen)
  const blocks: number[][] = []
  for (let i = 0, k = 0; i < numBlocks; i++) {
    const dat = data.slice(k, k + shortLen - eccLen + (i < numShort ? 0 : 1))
    k += dat.length
    const ecc = rsRemainder(dat, divisor)
    if (i < numShort) dat.push(0)
    blocks.push(dat.concat(ecc))
  }
  const result: number[] = []
  for (let i = 0; i < blocks[0]!.length; i++) {
    blocks.forEach((block, j) => {
      // The placeholder that evened the short blocks' length is not a codeword.
      if (i !== shortLen - eccLen || j >= numShort) result.push(block[i]!)
    })
  }
  return result
}

class Matrix {
  readonly size: number
  readonly modules: boolean[][]
  private readonly isFunction: boolean[][]

  constructor(readonly version: number) {
    this.size = version * 4 + 17
    this.modules = Array.from({ length: this.size }, () => new Array<boolean>(this.size).fill(false))
    this.isFunction = Array.from({ length: this.size }, () => new Array<boolean>(this.size).fill(false))
  }

  private set(x: number, y: number, dark: boolean) {
    this.modules[y]![x] = dark
    this.isFunction[y]![x] = true
  }

  drawFunctionPatterns(ecl: Ecl) {
    const size = this.size
    for (let i = 0; i < size; i++) {
      this.set(6, i, i % 2 === 0)
      this.set(i, 6, i % 2 === 0)
    }
    this.drawFinder(3, 3)
    this.drawFinder(size - 4, 3)
    this.drawFinder(3, size - 4)
    const positions = this.alignmentPositions()
    const n = positions.length
    for (let i = 0; i < n; i++) {
      for (let j = 0; j < n; j++) {
        // Not over the three finders.
        if ((i === 0 && j === 0) || (i === 0 && j === n - 1) || (i === n - 1 && j === 0)) continue
        this.drawAlignment(positions[i]!, positions[j]!)
      }
    }
    // Reserve the format modules now; the real bits are drawn with the mask.
    this.drawFormatBits(ecl, 0)
    this.drawVersion()
  }

  private drawFinder(x: number, y: number) {
    for (let dy = -4; dy <= 4; dy++) {
      for (let dx = -4; dx <= 4; dx++) {
        const dist = Math.max(Math.abs(dx), Math.abs(dy))
        const xx = x + dx
        const yy = y + dy
        if (xx >= 0 && xx < this.size && yy >= 0 && yy < this.size) this.set(xx, yy, dist !== 2 && dist !== 4)
      }
    }
  }

  private drawAlignment(x: number, y: number) {
    for (let dy = -2; dy <= 2; dy++) {
      for (let dx = -2; dx <= 2; dx++) this.set(x + dx, y + dy, Math.max(Math.abs(dx), Math.abs(dy)) !== 1)
    }
  }

  private alignmentPositions(): number[] {
    if (this.version === 1) return []
    const n = Math.floor(this.version / 7) + 2
    const step = Math.floor((this.version * 8 + n * 3 + 5) / (n * 4 - 4)) * 2
    const result = [6]
    for (let pos = this.size - 7; result.length < n; pos -= step) result.splice(1, 0, pos)
    return result
  }

  drawFormatBits(ecl: Ecl, mask: number) {
    const data = (FORMAT_BITS[ecl] << 3) | mask
    let rem = data
    for (let i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537)
    const bits = ((data << 10) | rem) ^ 0x5412
    const bit = (i: number) => ((bits >>> i) & 1) !== 0
    const size = this.size
    for (let i = 0; i <= 5; i++) this.set(8, i, bit(i))
    this.set(8, 7, bit(6))
    this.set(8, 8, bit(7))
    this.set(7, 8, bit(8))
    for (let i = 9; i < 15; i++) this.set(14 - i, 8, bit(i))
    for (let i = 0; i < 8; i++) this.set(size - 1 - i, 8, bit(i))
    for (let i = 8; i < 15; i++) this.set(8, size - 15 + i, bit(i))
    this.set(8, size - 8, true)
  }

  private drawVersion() {
    if (this.version < 7) return
    let rem = this.version
    for (let i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1f25)
    const bits = (this.version << 12) | rem
    for (let i = 0; i < 18; i++) {
      const dark = ((bits >>> i) & 1) !== 0
      const a = this.size - 11 + (i % 3)
      const b = Math.floor(i / 3)
      this.set(a, b, dark)
      this.set(b, a, dark)
    }
  }

  drawCodewords(codewords: readonly number[]) {
    const size = this.size
    let i = 0
    for (let right = size - 1; right >= 1; right -= 2) {
      if (right === 6) right = 5
      for (let vert = 0; vert < size; vert++) {
        for (let j = 0; j < 2; j++) {
          const x = right - j
          const upward = ((right + 1) & 2) === 0
          const y = upward ? size - 1 - vert : vert
          if (!this.isFunction[y]![x] && i < codewords.length * 8) {
            this.modules[y]![x] = ((codewords[i >>> 3]! >>> (7 - (i & 7))) & 1) !== 0
            i++
          }
        }
      }
    }
  }

  /** XOR mask [mask] over every module that is not a function pattern: applying it twice undoes it. */
  applyMask(mask: number) {
    for (let y = 0; y < this.size; y++) {
      for (let x = 0; x < this.size; x++) {
        let invert: boolean
        switch (mask) {
          case 0: invert = (x + y) % 2 === 0; break
          case 1: invert = y % 2 === 0; break
          case 2: invert = x % 3 === 0; break
          case 3: invert = (x + y) % 3 === 0; break
          case 4: invert = (Math.floor(x / 3) + Math.floor(y / 2)) % 2 === 0; break
          case 5: invert = ((x * y) % 2) + ((x * y) % 3) === 0; break
          case 6: invert = (((x * y) % 2) + ((x * y) % 3)) % 2 === 0; break
          default: invert = (((x + y) % 2) + ((x * y) % 3)) % 2 === 0; break
        }
        if (!this.isFunction[y]![x] && invert) this.modules[y]![x] = !this.modules[y]![x]
      }
    }
  }

  /** The standard's penalty: long runs, 2×2 blocks, finder-like patterns, and dark/light imbalance. */
  penalty(): number {
    const size = this.size
    const m = this.modules
    let result = 0
    const line = (get: (a: number, b: number) => boolean) => {
      for (let a = 0; a < size; a++) {
        let runColor = false
        let run = 0
        const history = [0, 0, 0, 0, 0, 0, 0]
        for (let b = 0; b < size; b++) {
          if (get(a, b) === runColor) {
            run++
            if (run === 5) result += 3
            else if (run > 5) result++
          } else {
            this.addHistory(run, history)
            if (!runColor) result += this.finderLike(history) * 40
            runColor = get(a, b)
            run = 1
          }
        }
        result += this.terminate(runColor, run, history) * 40
      }
    }
    line((y, x) => m[y]![x]!)
    line((x, y) => m[y]![x]!)
    for (let y = 0; y < size - 1; y++) {
      for (let x = 0; x < size - 1; x++) {
        const c = m[y]![x]
        if (c === m[y]![x + 1] && c === m[y + 1]![x] && c === m[y + 1]![x + 1]) result += 3
      }
    }
    let dark = 0
    for (const row of m) for (const cell of row) if (cell) dark++
    const total = size * size
    result += (Math.ceil(Math.abs(dark * 20 - total * 10) / total) - 1) * 10
    return result
  }

  private finderLike(history: readonly number[]): number {
    const n = history[1]!
    const core = n > 0 && history[2] === n && history[3] === n * 3 && history[4] === n && history[5] === n
    return (core && history[0]! >= n * 4 && history[6]! >= n ? 1 : 0) + (core && history[6]! >= n * 4 && history[0]! >= n ? 1 : 0)
  }

  private terminate(runColor: boolean, run: number, history: number[]): number {
    let length = run
    if (runColor) {
      this.addHistory(length, history)
      length = 0
    }
    length += this.size
    this.addHistory(length, history)
    return this.finderLike(history)
  }

  private addHistory(run: number, history: number[]) {
    let length = run
    if (history[0] === 0) length += this.size
    history.pop()
    history.unshift(length)
  }
}

/* ── Drawing ───────────────────────────────────────────────────────────────────────────────────── */

/** The light margin a reader needs around the code, in modules. */
export const QUIET_ZONE = 4

/**
 * The dark modules as one SVG path, one unit per module, offset by the quiet zone, for a viewBox of
 * [qrViewBox].
 */
export function qrPath(code: QrCode): string {
  const parts: string[] = []
  code.modules.forEach((row, y) => {
    row.forEach((dark, x) => {
      if (dark) parts.push(`M${x + QUIET_ZONE} ${y + QUIET_ZONE}h1v1h-1z`)
    })
  })
  return parts.join('')
}

/** The width and height of the drawing, quiet zone included, in modules. */
export function qrExtent(code: QrCode): number {
  return code.size + 2 * QUIET_ZONE
}

/** What an SVG needs to draw [text]: its extent for the viewBox, and the path of its dark modules. */
export function qrDrawing(text: string): { extent: number; path: string } {
  const code = encodeQr(text)
  return { extent: qrExtent(code), path: qrPath(code) }
}
