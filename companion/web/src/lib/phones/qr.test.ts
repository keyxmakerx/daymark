import { describe, it, expect } from 'vitest'
import qrcode from 'qrcode-generator'
import { QUIET_ZONE, dataCodewords, encodeQr, qrExtent, qrPath, type Ecl, type QrCode } from './qr'
import { pairingQrText } from './ceremony'

/*
 * THE QR ENCODER, MODULE FOR MODULE AGAINST AN INDEPENDENT ONE (#431).
 *
 * qr.ts is vendored rather than a runtime dependency, so its proof is here: for every version 1–40
 * and every error-correction level, the longest text that fits and a short one are drawn by it and
 * by qrcode-generator (Kazuhiko Arase's implementation, a devDependency pinned exactly, never in the
 * bundle), and every module must agree. The two choose their masks by different penalty arithmetic,
 * and any mask makes a valid code, so the mask the reference chose is read from its own format
 * information and qr.ts is asked for that one. Everything else (the bits, the error correction, the
 * interleaving, the patterns, the placement, the format and version information) is compared as is.
 */

const LEVELS: readonly Ecl[] = ['L', 'M', 'Q', 'H']
const ECL_OF_BITS: Record<number, Ecl> = { 1: 'L', 0: 'M', 3: 'Q', 2: 'H' }

/** The reference's matrix of [text] at [version] and [ecl], and the level and mask its format bits say. */
function reference(text: string, version: number, ecl: Ecl): { modules: boolean[][]; ecl: Ecl; mask: number } {
  const ref = qrcode(version as Parameters<typeof qrcode>[0], ecl)
  ref.addData(text, 'Byte')
  ref.make()
  const n = ref.getModuleCount()
  const modules = Array.from({ length: n }, (_, y) => Array.from({ length: n }, (_, x) => ref.isDark(y, x)))
  // The first copy of the 15 format bits, bit i at the place the standard gives it.
  const at = (i: number): boolean =>
    i <= 5 ? modules[i]![8]! : i === 6 ? modules[7]![8]! : i === 7 ? modules[8]![8]! : i === 8 ? modules[8]![7]! : modules[8]![14 - i]!
  let bits = 0
  for (let i = 0; i < 15; i++) if (at(i)) bits |= 1 << i
  const data = (bits ^ 0x5412) >>> 10
  return { modules, ecl: ECL_OF_BITS[data >>> 3]!, mask: data & 7 }
}

/** Printable ASCII, the same every run: a small linear congruential sequence from [seed]. */
function text(length: number, seed: number): string {
  let x = seed >>> 0
  let out = ''
  for (let i = 0; i < length; i++) {
    x = (Math.imul(x, 1103515245) + 12345) >>> 0
    out += String.fromCharCode(0x20 + ((x >>> 16) % 95))
  }
  return out
}

/** The most bytes byte mode fits at [version] and [ecl]. */
const capacity = (version: number, ecl: Ecl) => Math.floor((dataCodewords(version, ecl) * 8 - 4 - (version <= 9 ? 8 : 16)) / 8)

function differences(a: boolean[][], b: boolean[][]): number {
  if (a.length !== b.length) return Infinity
  let d = 0
  a.forEach((row, y) => row.forEach((cell, x) => (d += cell === b[y]![x] ? 0 : 1)))
  return d
}

describe('every module agrees with an independent encoder', () => {
  const masksSeen = new Set<number>()

  for (let version = 1; version <= 40; version++) {
    it(`version ${version}, every level, full and short`, () => {
      for (const ecl of LEVELS) {
        for (const length of [capacity(version, ecl), 1 + (version % 7)]) {
          const t = text(length, version * 100 + length)
          const ref = reference(t, version, ecl)
          expect(ref.ecl, `${version}${ecl}`).toBe(ecl)
          masksSeen.add(ref.mask)
          const ours = encodeQr(t, { version, ecl, boost: false, mask: ref.mask })
          expect(ours.size).toBe(version * 4 + 17)
          expect(differences(ours.modules, ref.modules), `version ${version} ${ecl} length ${length}`).toBe(0)
        }
      }
    })
  }

  it('the comparison saw every one of the eight masks, and can fail', () => {
    expect([...masksSeen].sort()).toEqual([0, 1, 2, 3, 4, 5, 6, 7])
    // Positive control: one byte changed is a different code, and the comparison says so.
    const t = text(20, 7)
    const other = t.slice(0, 5) + (t[5] === 'a' ? 'b' : 'a') + t.slice(6)
    expect(other).not.toBe(t)
    const ref = reference(t, 2, 'M')
    expect(differences(encodeQr(other, { version: 2, ecl: 'M', boost: false, mask: ref.mask }).modules, ref.modules)).toBeGreaterThan(0)
    // And so is another mask.
    const wrongMask = (ref.mask + 1) % 8
    expect(differences(encodeQr(t, { version: 2, ecl: 'M', boost: false, mask: wrongMask }).modules, ref.modules)).toBeGreaterThan(0)
  })

  it('picks the smallest version that fits, as the reference does', () => {
    for (const length of [1, 14, 15, 60, 61, 84, 85, 200, 400]) {
      const t = text(length, length)
      for (const ecl of LEVELS) {
        const auto = qrcode(0, ecl)
        auto.addData(t, 'Byte')
        auto.make()
        const ours = encodeQr(t, { ecl, boost: false })
        expect(ours.size, `${length} ${ecl}`).toBe(auto.getModuleCount())
      }
    }
  })

  it('refuses text that no version holds', () => {
    expect(() => encodeQr(text(capacity(40, 'L') + 1, 1), { ecl: 'L' })).toThrow(RangeError)
    expect(() => encodeQr('x', { version: 41 })).toThrow(RangeError)
    expect(() => encodeQr('x', { mask: 8 })).toThrow(RangeError)
    expect(encodeQr(text(capacity(40, 'L'), 1), { ecl: 'L' }).version).toBe(40)
  })
})

describe("the pairing code's QR", () => {
  const addresses = [
    'https://daymark.example.org',
    'https://journal.a-rather-long-home-server-name.example.net:8443/daymark',
    "https://daymark.example.org/a b!'()*~",
  ]

  it('draws exactly the pairing text, at M or better, as the reference draws it', () => {
    for (const address of addresses) {
      const t = pairingQrText(address, 'K7M4RD96QA')
      const ours: QrCode = encodeQr(t)
      expect(['M', 'Q', 'H']).toContain(ours.ecl)
      const ref = reference(t, ours.version, ours.ecl)
      const same = encodeQr(t, { version: ours.version, ecl: ours.ecl, boost: false, mask: ref.mask })
      expect(differences(same.modules, ref.modules), address).toBe(0)
      // The code drawn is that same code under the mask qr.ts chose.
      expect(encodeQr(t, { version: ours.version, ecl: ours.ecl, boost: false, mask: ours.mask }).modules).toEqual(ours.modules)
    }
  })

  it('is small enough to read from a screen: version 7 or less for an ordinary address', () => {
    expect(encodeQr(pairingQrText(addresses[0]!, 'K7M4RD96QA')).version).toBeLessThanOrEqual(7)
    expect(encodeQr(pairingQrText(addresses[1]!, 'K7M4RD96QA')).version).toBeLessThanOrEqual(7)
  })
})

describe('the drawing', () => {
  it('is one unit square per dark module, inside a quiet zone of four', () => {
    const code = encodeQr(pairingQrText('https://daymark.example.org', 'K7M4RD96QA'))
    const path = qrPath(code)
    const dark = code.modules.flat().filter(Boolean).length
    expect(path.match(/M/g)).toHaveLength(dark)
    expect(QUIET_ZONE).toBe(4)
    expect(qrExtent(code)).toBe(code.size + 8)
    // The top-left finder's corner module is dark and sits just inside the quiet zone.
    expect(code.modules[0]![0]).toBe(true)
    expect(path.startsWith('M4 4h1v1h-1z')).toBe(true)
    // Nothing is drawn in the quiet zone.
    for (const [, x, y] of path.matchAll(/M(\d+) (\d+)/g)) {
      expect(Number(x)).toBeGreaterThanOrEqual(QUIET_ZONE)
      expect(Number(y)).toBeLessThan(code.size + QUIET_ZONE)
    }
  })
})
