import { describe, it, expect, beforeAll } from 'vitest'
import { initCrypto } from '../sync/crypto'
import {
  RECOVERY_ALPHABET,
  ALPHABET_SIZE,
  PAYLOAD_SYMBOLS,
  CODE_SYMBOLS,
  GROUP_SIZE,
  RECOVERY_CODE_ENTROPY_BITS,
  RECOVERY_FAULT_TEXT,
  newRecoveryCode,
  parseRecoveryCode,
  formatRecoveryCode,
  normalizeRecoveryInput,
  requireRecoveryCode,
  RecoveryCodeError,
  type RecoveryCode,
} from './recoveryCode'

/*
 * Nothing in this file costs an Argon2id derivation — the code is a string, and every property that
 * makes it a GOOD string (uniform draw, exact single-error detection, a diagnosis instead of a
 * shrug) is checkable in microseconds. That is deliberate: the expensive tests live in
 * dataKey.test.ts, and the cheap ones are the ones that get to be exhaustive.
 */

/**
 * One code, generated once at the top level and reused by every sweep below.
 *
 * It is drawn fresh on each run rather than pinned as a fixture, so the exhaustive substitution and
 * transposition sweeps exercise a different code every time the suite runs. The properties they
 * assert hold for ALL codes, so a fixture would only have narrowed the coverage.
 */
let code: RecoveryCode

beforeAll(async () => {
  await initCrypto()
  code = await newRecoveryCode()
})

describe('the recovery code alphabet', () => {
  it('is exactly the 31 symbols, in the order every printed code depends on', () => {
    // Pinned as a literal, not recomputed. The order IS the check-symbol arithmetic: reordering it
    // silently invalidates every code anybody has ever written on paper, and there is no way to
    // detect that from the outside. A test that recomputed the expected value from the same
    // constant would have agreed with any change at all.
    expect(RECOVERY_ALPHABET).toBe('23456789ABCDEFGHJKMNPQRSTUVWXYZ')
    expect(ALPHABET_SIZE).toBe(31)
  })

  it('is 31 symbols and 31 is prime — which is what makes the check symbol exact', () => {
    for (let d = 2; d * d <= ALPHABET_SIZE; d++) expect(ALPHABET_SIZE % d).not.toBe(0)
  })

  it('has no repeated symbol', () => {
    expect(new Set(RECOVERY_ALPHABET).size).toBe(ALPHABET_SIZE)
  })

  it('contains none of the characters that collide in handwriting', () => {
    // Both members of each pair are gone, which is stricter than Crockford (who keeps 0 and 1 and
    // folds O/I/L onto them). The payoff is that any of these appearing in input is a definite
    // error at a definite position rather than an ambiguity resolved by guessing.
    for (const ch of ['0', 'O', '1', 'I', 'L']) expect(RECOVERY_ALPHABET).not.toContain(ch)
  })

  it('is upper-case ASCII alphanumerics only', () => {
    expect(RECOVERY_ALPHABET).toMatch(/^[A-Z2-9]+$/)
  })
})

describe('the entropy claim', () => {
  it('clears 128 bits, the "never brute-forceable" line', () => {
    // This code protects the same journal the passphrase protects, and its wrapped slot is designed
    // to sit on a server, so an attacker who steals the disk gets unlimited offline guesses with no
    // rate limit and nobody watching. Argon2id multiplies each guess by a constant; only entropy
    // divides the search space. Four digits would be 13.3 bits and would fall instantly.
    expect(RECOVERY_CODE_ENTROPY_BITS).toBeGreaterThanOrEqual(128)
  })

  it('is the stated 143.67 bits: 29 symbols drawn uniformly from 31', () => {
    expect(RECOVERY_CODE_ENTROPY_BITS).toBeCloseTo(143.67, 2)
    expect(PAYLOAD_SYMBOLS).toBe(29)
    expect(CODE_SYMBOLS).toBe(30)
  })
})

describe('generating a code', () => {
  it('produces 30 canonical symbols drawn from the alphabet', () => {
    expect(code.canonical).toHaveLength(CODE_SYMBOLS)
    for (const ch of code.canonical) expect(RECOVERY_ALPHABET).toContain(ch)
  })

  it('displays as six groups of five, which is the shape a person copies down', () => {
    expect(code.display).toBe(formatRecoveryCode(code.canonical))
    const groups = code.display.split('-')
    expect(groups).toHaveLength(CODE_SYMBOLS / GROUP_SIZE)
    for (const g of groups) expect(g).toHaveLength(GROUP_SIZE)
  })

  it('reads back through the parser it will be typed into', () => {
    const parsed = parseRecoveryCode(code.display)
    expect(parsed.ok).toBe(true)
    if (parsed.ok) expect(parsed.code.canonical).toBe(code.canonical)
  })

  it('every generated code is a valid code (200 draws)', async () => {
    // The generator and the parser share only the alphabet constant, so this catches a check-symbol
    // that is computed one way and verified another — the failure mode where every code the product
    // ever issues is rejected the first time somebody tries to use it.
    for (let i = 0; i < 200; i++) {
      const c = await newRecoveryCode()
      expect(parseRecoveryCode(c.display).ok).toBe(true)
    }
  })

  it('draws from the whole alphabet, so the rejection sampling has not lost symbols', async () => {
    // 200 codes is 5800 draws over 31 symbols, ~187 expected each. The chance any single symbol is
    // absent is (30/31)^5800, which is about e^-187 — so this cannot flake, and it WOULD fail if a
    // modulo bug or a wrong rejection bound quietly excluded the tail of the alphabet.
    const seen = new Set<string>()
    for (let i = 0; i < 200; i++) for (const ch of (await newRecoveryCode()).canonical) seen.add(ch)
    expect(seen.size).toBe(ALPHABET_SIZE)
  })
})

describe('a single mistyped character is caught by the checksum', () => {
  /*
   * THE REQUIREMENT THIS FILE EXISTS FOR. A person is reading thirty characters off a piece of
   * paper months after they wrote them. The realistic failure is exactly one character: a 5 read as
   * an S, a B as an 8. Without a check symbol that surfaces as "wrong code" after a three-second
   * Argon2id derivation, which tells them nothing about whether the paper is wrong, their typing is
   * wrong, or their data is gone. With it, it surfaces instantly as "read that one again".
   *
   * This sweep is exhaustive rather than sampled: every position, every alternative symbol.
   */
  it('every one of the 900 possible single-symbol substitutions fails as a checksum fault', () => {
    let checked = 0
    for (let i = 0; i < CODE_SYMBOLS; i++) {
      for (const replacement of RECOVERY_ALPHABET) {
        if (replacement === code.canonical[i]) continue
        const typo = code.canonical.slice(0, i) + replacement + code.canonical.slice(i + 1)
        const parsed = parseRecoveryCode(typo)
        expect(parsed.ok).toBe(false)
        if (!parsed.ok) expect(parsed.fault).toBe('checksum')
        checked++
      }
    }
    expect(checked).toBe(CODE_SYMBOLS * (ALPHABET_SIZE - 1))
  })

  it('every transposition of two different payload symbols fails as a checksum fault', () => {
    // The second guarantee the prime modulus buys: swapping any two distinct symbols always moves
    // the weighted sum, because the weights differ and the modulus is prime.
    let checked = 0
    for (let i = 0; i < PAYLOAD_SYMBOLS; i++) {
      for (let j = i + 1; j < PAYLOAD_SYMBOLS; j++) {
        if (code.canonical[i] === code.canonical[j]) continue
        const chars = code.canonical.split('')
        ;[chars[i], chars[j]] = [chars[j], chars[i]]
        const parsed = parseRecoveryCode(chars.join(''))
        expect(parsed.ok).toBe(false)
        if (!parsed.ok) expect(parsed.fault).toBe('checksum')
        checked++
      }
    }
    expect(checked).toBeGreaterThan(300)
  })
})

describe('reading what a person actually typed', () => {
  it('accepts the hyphens it printed, plus spaces, lower case and word-processor dashes', () => {
    // A code that has been through a chat client, a PDF or an email comes back with en/em dashes
    // where the hyphens were. That is the software's doing, not the person's, and refusing it would
    // be blaming them for it.
    const spaced = code.canonical.match(/.{1,5}/g)!.join(' ')
    expect(parseRecoveryCode(spaced).ok).toBe(true)
    expect(parseRecoveryCode(code.display.toLowerCase()).ok).toBe(true)
    expect(parseRecoveryCode(code.display.replace(/-/g, '—')).ok).toBe(true)
    expect(parseRecoveryCode(`  ${code.display}\n`).ok).toBe(true)
  })

  it('never folds O onto 0 or I/L onto 1 — it names them instead', () => {
    const withO = 'O' + code.canonical.slice(1)
    const parsed = parseRecoveryCode(withO)
    expect(parsed.ok).toBe(false)
    if (!parsed.ok) {
      expect(parsed.fault).toBe('confusable')
      expect(parsed.at).toBe(1)
    }
    for (const ch of ['0', '1', 'I', 'l']) {
      const at7 = code.canonical.slice(0, 6) + ch + code.canonical.slice(7)
      const p = parseRecoveryCode(at7)
      expect(p.ok).toBe(false)
      if (!p.ok) {
        expect(p.fault).toBe('confusable')
        expect(p.at).toBe(7)
      }
    }
  })

  it('names any other stray character and where it is', () => {
    const parsed = parseRecoveryCode(code.canonical.slice(0, 3) + '*' + code.canonical.slice(4))
    expect(parsed.ok).toBe(false)
    if (!parsed.ok) {
      expect(parsed.fault).toBe('notInAlphabet')
      expect(parsed.at).toBe(4)
    }
  })

  it('distinguishes nothing typed, too few and too many', () => {
    expect(parseRecoveryCode('')).toEqual({ ok: false, fault: 'empty' })
    expect(parseRecoveryCode('   - -\n')).toEqual({ ok: false, fault: 'empty' })
    expect(parseRecoveryCode(code.canonical.slice(0, 25))).toEqual({ ok: false, fault: 'tooShort' })
    expect(parseRecoveryCode(code.canonical + 'X')).toEqual({ ok: false, fault: 'tooLong' })
  })

  it('normalizes to the canonical form the KDF is fed', () => {
    expect(normalizeRecoveryInput(code.display.toLowerCase())).toBe(code.canonical)
  })
})

describe('the fault surface', () => {
  it('requireRecoveryCode throws a RecoveryCodeError carrying the diagnosis and position', () => {
    expect(() => requireRecoveryCode(code.display)).not.toThrow()
    let thrown: unknown = null
    try {
      requireRecoveryCode('O' + code.canonical.slice(1))
    } catch (err) {
      thrown = err
    }
    expect(thrown).toBeInstanceOf(RecoveryCodeError)
    expect((thrown as RecoveryCodeError).fault).toBe('confusable')
    expect((thrown as RecoveryCodeError).at).toBe(1)
  })

  it('no fault message ever quotes what was typed', () => {
    // Same rule inviteLink.ts states for the invite secret: a fault message is the thing most likely
    // to be screenshotted into a support thread, and half of what it would be quoting is a live key
    // to somebody's journal. These are static sentences with no interpolation at all, so there is
    // nothing for a caller to accidentally splice a secret into.
    for (const text of Object.values(RECOVERY_FAULT_TEXT)) {
      expect(text).not.toMatch(/[{}$]|%s/)
      expect(text.length).toBeGreaterThan(20)
    }
  })

  it('says nothing that reads as a score, a grade or a congratulation', () => {
    for (const text of Object.values(RECOVERY_FAULT_TEXT)) {
      expect(text).not.toMatch(/success|correct!|well done|perfect|score|%|streak|grade/i)
    }
  })
})
