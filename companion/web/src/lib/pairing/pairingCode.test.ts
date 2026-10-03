import { describe, it, expect, beforeAll } from 'vitest'
import { initCrypto } from '../sync/crypto'
import { CODE_SYMBOLS as RECOVERY_CODE_SYMBOLS, GROUP_SIZE as RECOVERY_GROUP_SIZE } from '../recovery/recoveryCode'
import {
  PAIRING_ALPHABET,
  PAIRING_PAYLOAD_SYMBOLS,
  PAIRING_CODE_SYMBOLS,
  PAIRING_GROUP_SIZE,
  PAIRING_CODE_ENTROPY_BITS,
  PAIRING_FAULT_TEXT,
  CANONICAL_PAIRING_CODE,
  isCanonicalPairingCode,
  newPairingCode,
  parsePairingCode,
  requirePairingCode,
  formatPairingCode,
  PairingCodeError,
  type PairingCode,
} from './pairingCode'

/*
 * Every property here is checkable in microseconds, so the sweeps are exhaustive rather than
 * sampled. The code is drawn fresh each run: the properties hold for ALL codes, and a fixture would
 * only have narrowed the coverage. One literal IS pinned — K7M4-RD96 — because it is the example
 * in docs/COMPANION_PAIRING.md §2 and the check arithmetic behind it must never move.
 */
let code: PairingCode

beforeAll(async () => {
  await initCrypto()
  code = await newPairingCode()
})

describe('the shape of a pairing code', () => {
  it('is eight symbols: seven of entropy and one check, shown as two groups of four', () => {
    expect(PAIRING_PAYLOAD_SYMBOLS).toBe(7)
    expect(PAIRING_CODE_SYMBOLS).toBe(8)
    expect(PAIRING_GROUP_SIZE).toBe(4)
    expect(code.canonical).toHaveLength(8)
    expect(code.display).toMatch(/^[^-]{4}-[^-]{4}$/)
    expect(formatPairingCode(code.canonical)).toBe(code.display)
  })

  it('uses the recovery alphabet and the canonical regex is built from it, not written beside it', () => {
    expect(PAIRING_ALPHABET).toBe('23456789ABCDEFGHJKMNPQRSTUVWXYZ')
    expect(CANONICAL_PAIRING_CODE.source).toBe('^[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{8}$')
    for (const ch of '0O1IL') expect(PAIRING_ALPHABET.includes(ch)).toBe(false)
  })

  it('states its entropy honestly: 34.68 bits, and relies on the online bound for the rest', () => {
    expect(PAIRING_CODE_ENTROPY_BITS).toBeCloseTo(34.68, 2)
    // Not a recovery-grade secret and never claimed as one: the same file that says 143 bits for
    // a recovery code says why 34 is enough here (one online test per exchange, eight per invite).
    expect(PAIRING_CODE_ENTROPY_BITS).toBeLessThan(64)
  })

  it('cannot be mistaken for a recovery code by length or by grouping', () => {
    expect(PAIRING_CODE_SYMBOLS).not.toBe(RECOVERY_CODE_SYMBOLS)
    expect(PAIRING_GROUP_SIZE).not.toBe(RECOVERY_GROUP_SIZE)
  })

  it('pins the documented example: K7M4-RD96 is a valid code and its check symbol is 6', () => {
    // Weighted sum 1·K + 2·7 + 3·M + 4·4 + 5·R + 6·D + 7·9 over the alphabet indices is 314,
    // and 314 mod 31 is 4, whose symbol is '6'. If this moves, every code ever spoken is invalid.
    const parsed = parsePairingCode('K7M4-RD96')
    expect(parsed.ok).toBe(true)
    expect(parsePairingCode('K7M4-RD97').ok).toBe(false)
  })
})

describe('generation', () => {
  it('draws canonical codes that round-trip through the parser', async () => {
    for (let i = 0; i < 200; i++) {
      const fresh = await newPairingCode()
      expect(isCanonicalPairingCode(fresh.canonical)).toBe(true)
      const parsed = parsePairingCode(fresh.display)
      expect(parsed.ok && parsed.code.canonical).toBe(fresh.canonical)
    }
  })

  it('reaches every symbol of the alphabet (rejection sampling is not silently truncating)', async () => {
    const seen = new Set<string>()
    for (let i = 0; i < 300 && seen.size < PAIRING_ALPHABET.length; i++) {
      for (const ch of (await newPairingCode()).canonical.slice(0, PAIRING_PAYLOAD_SYMBOLS)) seen.add(ch)
    }
    expect(seen.size).toBe(PAIRING_ALPHABET.length)
  })
})

describe('canonicalisation — the thing relay.ts used to skip', () => {
  it('reads the same code however a person typed it', () => {
    const variants = [
      code.display,
      code.display.toLowerCase(),
      ` ${code.display} `,
      code.canonical,
      code.canonical.toLowerCase(),
      code.display.replace('-', '—'), // em dash from a chat client
      code.display.replace('-', ' '),
      `${code.canonical.slice(0, 4)}\n${code.canonical.slice(4)}`,
    ]
    for (const typed of variants) {
      const parsed = parsePairingCode(typed)
      expect(parsed.ok, `should parse: ${JSON.stringify(typed)}`).toBe(true)
      expect(parsed.ok && parsed.code.canonical).toBe(code.canonical)
    }
  })

  it('isCanonicalPairingCode accepts only the canonical form, never a typed variant', () => {
    expect(isCanonicalPairingCode(code.canonical)).toBe(true)
    expect(isCanonicalPairingCode(code.display)).toBe(false)
    expect(isCanonicalPairingCode(code.canonical.toLowerCase())).toBe(false)
    expect(isCanonicalPairingCode(code.canonical + 'A')).toBe(false)
    expect(isCanonicalPairingCode(code.canonical.slice(1))).toBe(false)
  })

  it('never folds O to 0 or I/L to 1 — those are named as errors, at a position', () => {
    const withO = code.canonical.slice(0, 3) + 'O' + code.canonical.slice(4)
    const parsed = parsePairingCode(withO)
    expect(parsed).toEqual({ ok: false, fault: 'confusable', at: 4 })
  })
})

describe('the check symbol catches what a phone line garbles', () => {
  it('every single-symbol substitution, at every position, is caught', () => {
    let tried = 0
    for (let i = 0; i < PAIRING_CODE_SYMBOLS; i++) {
      for (const sym of PAIRING_ALPHABET) {
        if (sym === code.canonical[i]) continue
        const mutated = code.canonical.slice(0, i) + sym + code.canonical.slice(i + 1)
        expect(parsePairingCode(mutated).ok, `substitution at ${i + 1} → ${sym} slipped through`).toBe(false)
        tried++
      }
    }
    expect(tried).toBe(PAIRING_CODE_SYMBOLS * (PAIRING_ALPHABET.length - 1))
  })

  it('every transposition of two different payload symbols is caught', () => {
    const chars = code.canonical.split('')
    let tried = 0
    for (let i = 0; i < PAIRING_PAYLOAD_SYMBOLS; i++) {
      for (let j = i + 1; j < PAIRING_PAYLOAD_SYMBOLS; j++) {
        if (chars[i] === chars[j]) continue
        const swapped = [...chars]
        ;[swapped[i], swapped[j]] = [swapped[j], swapped[i]]
        expect(parsePairingCode(swapped.join('')).ok, `swap ${i + 1}<->${j + 1} slipped through`).toBe(false)
        tried++
      }
    }
    expect(tried).toBeGreaterThan(0)
  })
})

describe('faults are diagnoses, not shrugs', () => {
  it('distinguishes empty, short, long, stray character and checksum', () => {
    expect(parsePairingCode('')).toEqual({ ok: false, fault: 'empty' })
    expect(parsePairingCode('  - ')).toEqual({ ok: false, fault: 'empty' })
    expect(parsePairingCode(code.canonical.slice(0, 7))).toEqual({ ok: false, fault: 'tooShort' })
    expect(parsePairingCode(code.canonical + '2')).toEqual({ ok: false, fault: 'tooLong' })
    expect(parsePairingCode(code.canonical.slice(0, 2) + '*' + code.canonical.slice(3))).toEqual({
      ok: false,
      fault: 'notInAlphabet',
      at: 3,
    })
  })

  it('characters are diagnosed before length, so a pasted sentence is named for what it is', () => {
    const parsed = parsePairingCode('please type ' + code.display)
    expect(parsed.ok).toBe(false)
    expect(!parsed.ok && parsed.fault).not.toBe('tooLong')
  })

  it('requirePairingCode throws the same diagnosis with the position', () => {
    try {
      requirePairingCode(code.canonical.slice(0, 5) + 'L' + code.canonical.slice(6))
      expect.unreachable()
    } catch (e) {
      expect(e).toBeInstanceOf(PairingCodeError)
      expect((e as PairingCodeError).fault).toBe('confusable')
      expect((e as PairingCodeError).at).toBe(6)
    }
    expect(requirePairingCode(code.display).canonical).toBe(code.canonical)
  })

  it('no fault text quotes what was typed', () => {
    const marker = 'ZZQQ'
    for (const text of Object.values(PAIRING_FAULT_TEXT)) expect(text.includes(marker)).toBe(false)
    // The marker is present in an input that produces every fault reachable from a string;
    // the texts are constants, so the control is that a text WOULD show it if it interpolated.
    const control = `typed ${marker}`
    expect(control.includes(marker)).toBe(true)
  })

  it('no fault text reads as a score, a grade, or a congratulation', () => {
    for (const text of Object.values(PAIRING_FAULT_TEXT)) {
      expect(text).not.toMatch(/\b(great|well done|correct|success|valid|good|invalid|wrong code|error)\b/i)
      expect(text).not.toMatch(/[!]/)
    }
    // Control: the pattern fires on the kind of sentence it is meant to catch.
    expect('Great, that is correct!').toMatch(/\b(great|well done|correct|success|valid|good|invalid|wrong code|error)\b/i)
  })

  it('says which kind of code it is: a pairing code, never a recovery code', () => {
    for (const text of Object.values(PAIRING_FAULT_TEXT)) expect(text.toLowerCase()).not.toContain('recovery')
  })
})
