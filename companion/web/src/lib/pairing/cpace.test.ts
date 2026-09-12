/*
 * CPace pinned to the CFRG working group's own test vectors, byte for byte.
 *
 * The vectors below are copied verbatim from the draft-irtf-cfrg-cpace repository's
 * testvectors.md ("Test vector for CPace using group ristretto255 and hash SHA-512") — the
 * same bytes the Kotlin mirror (sync-crypto CpaceCrypto) is pinned to. Two implementations
 * that both reproduce these vectors agree with each other transitively, which is the whole
 * mechanism by which a JVM party and a browser party can pair: neither side is the reference
 * for the other, the standard is the reference for both.
 */
import { beforeAll, describe, expect, it } from 'vitest'
import {
  CPACE_ISK_BYTES,
  CPACE_SID_BYTES,
  calculateGenerator,
  cpaceFinish,
  cpaceRespond,
  cpaceResumeRespond,
  cpaceStart,
  generatorString,
  initCpace,
  lvCat,
  parseLv,
  prependLen,
} from './cpace'

const hex = (h: string): Uint8Array => {
  const clean = h.replace(/\s/g, '')
  const out = new Uint8Array(clean.length / 2)
  for (let i = 0; i < out.length; i++) out[i] = parseInt(clean.slice(i * 2, i * 2 + 2), 16)
  return out
}
const toHex = (b: Uint8Array): string =>
  Array.from(b)
    .map((x) => x.toString(16).padStart(2, '0'))
    .join('')
const utf8 = (s: string): Uint8Array => new TextEncoder().encode(s)

// --- the draft's CPACE-RISTRETTO255-SHA512 vector, verbatim -------------------------------

const PRS = utf8('Password')
const CI = hex('6f630b425f726573706f6e6465720b415f696e69746961746f72')
const SID = hex('7e4b4791d6a8ef019b936c79fb7f2c57')
const GENERATOR_STRING = hex(
  '11435061636552697374726574746f3235350850617373776f726464' +
    '00000000000000000000000000000000000000000000000000000000' +
    '00000000000000000000000000000000000000000000000000000000' +
    '00000000000000000000000000000000000000000000000000000000' +
    '000000000000000000000000000000001a6f630b425f726573706f6e' +
    '6465720b415f696e69746961746f72107e4b4791d6a8ef019b936c79' +
    'fb7f2c57',
)
const GENERATOR = hex('a6fc82c3b8968fbb2e06fee81ca858586dea50d248f0c7ca6a18b0902a30b36b')
const ADA = utf8('ADa')
const YA_SCALAR = hex('da3d23700a9e5699258aef94dc060dfda5ebb61f02a5ea77fad53f4ff0976d08')
const YA_POINT = hex('d40fb265a7abeaee7939d91a585fe59f7053f982c296ec413c624c669308f87a')
const ADB = utf8('ADb')
const YB_SCALAR = hex('d2316b454718c35362d83d69df6320f38578ed5984651435e2949762d900b80d')
const YB_POINT = hex('08bcf6e9777a9c313a3db6daa510f2d398403319c2341bd506a92e672eb7e307')
const ISK_IR = hex(
  '4c5469a16b2364c4b944ebc1a79e51d1674ad47db26e8718154f59fa' +
    'ebfaa52d8346f30aa58377117eb20d527f2cbc5c76381f7fd372e89d' +
    'f8239f87f2e02ed1',
)

const INPUTS = { prs: PRS, ci: CI, sid: SID }

beforeAll(async () => {
  await initCpace()
})

describe('lv encoding (draft appendix: prepend_len / lv_cat)', () => {
  it('encodes the draft pins, including the two-byte LEB128 boundary at 128', () => {
    expect(toHex(prependLen(new Uint8Array(0)))).toBe('00')
    expect(prependLen(utf8('1234')).length).toBe(5)
    expect(toHex(prependLen(utf8('1234')).subarray(0, 1))).toBe('04')
    const b128 = new Uint8Array(128).map((_, i) => i)
    const enc = prependLen(b128)
    // 128 needs two LEB128 bytes: 0x80 (low 7 bits + continue), 0x01.
    expect(enc.length).toBe(130)
    expect(toHex(enc.subarray(0, 2))).toBe('8001')
    expect(toHex(parseLv(enc, 1)[0])).toBe(toHex(b128))
  })

  it('parse is the exact inverse and refuses trailing or truncated bytes', () => {
    const cat = lvCat(utf8('ab'), new Uint8Array(0), utf8('c'))
    const [a, b, c] = parseLv(cat, 3)
    expect([toHex(a), toHex(b), toHex(c)]).toEqual(['6162', '', '63'])
    expect(() => parseLv(cat.subarray(0, cat.length - 1), 3)).toThrow(/truncated/)
    const padded = new Uint8Array(cat.length + 1)
    padded.set(cat)
    expect(() => parseLv(padded, 3)).toThrow(/trailing/)
  })
})

describe('generator derivation (draft: calculate_generator)', () => {
  it('builds the exact 172-byte generator string — DSI and PRS padded to one SHA-512 block', () => {
    expect(toHex(generatorString(PRS, CI, SID))).toBe(toHex(GENERATOR_STRING))
  })

  it('hashes to the published generator point', () => {
    expect(toHex(calculateGenerator(PRS, CI, SID))).toBe(toHex(GENERATOR))
  })
})

describe('the full exchange against the published vector', () => {
  it("reproduces MSGa, MSGb and the initiator/responder ISK byte-for-byte", () => {
    const a = cpaceStart(INPUTS, ADA, YA_SCALAR)
    expect(toHex(a.msgA)).toBe(toHex(lvCat(YA_POINT, ADA)))

    const b = cpaceRespond(INPUTS, a.msgA, ADB, YB_SCALAR)
    expect(toHex(b.msgB)).toBe(toHex(lvCat(YB_POINT, ADB)))
    expect(toHex(b.isk)).toBe(toHex(ISK_IR))

    expect(toHex(cpaceFinish(INPUTS, a, b.msgB))).toBe(toHex(ISK_IR))
  })
})

describe('the properties the pairing design leans on', () => {
  it('a fresh random exchange agrees on both sides and never repeats', () => {
    const run = () => {
      const a = cpaceStart(INPUTS, ADA)
      const b = cpaceRespond(INPUTS, a.msgA, ADB)
      const iskA = cpaceFinish(INPUTS, a, b.msgB)
      expect(toHex(iskA)).toBe(toHex(b.isk))
      expect(iskA.length).toBe(CPACE_ISK_BYTES)
      return toHex(iskA)
    }
    expect(run()).not.toBe(run())
  })

  it('a wrong code yields a different key and NO error — the burn rule depends on this', () => {
    // The mismatch is discovered by an AEAD failing at the next layer, where a human decides
    // what it means. Nothing at this layer may distinguish "typo" from "attacker", because
    // nothing can: that indistinguishability is why a wrong code must never burn an invite.
    const a = cpaceStart(INPUTS, ADA)
    const b = cpaceRespond({ ...INPUTS, prs: utf8('Passw0rd') }, a.msgA, ADB)
    expect(toHex(cpaceFinish(INPUTS, a, b.msgB))).not.toBe(toHex(b.isk))
  })

  it('a mismatched sid or ci also diverges — the whole context is authenticated, not just the code', () => {
    const a = cpaceStart(INPUTS, ADA)
    const otherSid = new Uint8Array(SID)
    otherSid[0] ^= 1
    const b = cpaceRespond({ ...INPUTS, sid: otherSid }, a.msgA, ADB)
    expect(toHex(cpaceFinish(INPUTS, a, b.msgB))).not.toBe(toHex(b.isk))

    const c = cpaceRespond({ ...INPUTS, ci: utf8('someone-else') }, a.msgA, ADB)
    expect(toHex(cpaceFinish(INPUTS, a, c.msgB))).not.toBe(toHex(c.isk))
  })

  it('a tampered message either aborts or diverges — a relay that edits bytes gains nothing', () => {
    const a = cpaceStart(INPUTS, ADA)
    const honest = cpaceRespond(INPUTS, a.msgA, ADB, YB_SCALAR)
    const tampered = new Uint8Array(a.msgA)
    tampered[5] ^= 0x40
    let outcome: string
    try {
      outcome = toHex(cpaceRespond(INPUTS, tampered, ADB, YB_SCALAR).isk)
    } catch {
      outcome = 'aborted'
    }
    expect(outcome).not.toBe(toHex(honest.isk))
  })

  it("the identity point is refused outright — the draft's mandatory scalar_mult_vfy abort", () => {
    const identity = new Uint8Array(32)
    const forged = lvCat(identity, ADA)
    expect(() => cpaceRespond(INPUTS, forged, ADB)).toThrow()
  })

  it('a sid of the wrong size is refused before any crypto runs', () => {
    expect(CPACE_SID_BYTES).toBe(16)
    expect(() => cpaceStart({ ...INPUTS, sid: SID.subarray(0, 8) }, ADA)).toThrow(/16 bytes/)
  })

  it('the responder can re-derive its key from the scalar and the transcript, with no code', () => {
    // What a clinician's tab has after a reload: the scalar it answered with, and the two public
    // messages. The code is gone, and this is the whole reason the owner's keys can be sealed to
    // them at approval rather than at answer.
    const a = cpaceStart(INPUTS, ADA)
    const b = cpaceRespond(INPUTS, a.msgA, ADB)
    expect(toHex(cpaceResumeRespond({ sid: SID }, b.yb, a.msgA, b.msgB))).toBe(toHex(b.isk))

    // Another run's scalar, or another run's transcript, gives a different key — never this one.
    const other = cpaceRespond(INPUTS, a.msgA, ADB)
    expect(toHex(cpaceResumeRespond({ sid: SID }, other.yb, a.msgA, b.msgB))).not.toBe(toHex(b.isk))
    expect(toHex(cpaceResumeRespond({ sid: SID }, b.yb, a.msgA, other.msgB))).not.toBe(toHex(b.isk))
  })
})
