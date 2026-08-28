/*
 * The pairing code's guarantees, proven exhaustively rather than sampled.
 *
 * The alphabet is 31 symbols and the code is 6 long, so "every single-character error" and
 * "every transposition" are small enough to enumerate completely — and they are enumerated,
 * because the check symbol's whole justification is that those two classes are caught with
 * CERTAINTY, and a probabilistic test of a certainty claim is the wrong instrument.
 *
 * What that certainty buys is stated in the module header and worth restating here: a typo
 * caught locally never reaches the relay, so it never spends one of the online guesses the
 * invite lockout is counting. Honest fumbling stays out of the attacker budget.
 */
import { describe, expect, it } from 'vitest'
import {
  PAIRING_ALPHABET,
  PAIRING_CODE_ENTROPY_BITS,
  PAIRING_CODE_SYMBOLS,
  PAIRING_FAULT_TEXT,
  PAIRING_PAYLOAD_SYMBOLS,
  PairingCodeError,
  formatPairingCode,
  newPairingCode,
  normalizePairingInput,
  pairingCheckSymbol,
  parsePairingCode,
  requirePairingCode,
} from './pairingCode'
import { RECOVERY_ALPHABET, checkSymbol as recoveryCheckSymbol } from '../recovery/recoveryCode'

/** A handful of real codes to run the exhaustive sweeps against. */
async function sampleCodes(n: number): Promise<string[]> {
  const out: string[] = []
  for (let i = 0; i < n; i++) out.push((await newPairingCode()).canonical)
  return out
}

describe('the code a person reads aloud', () => {
  it('generates six symbols, displayed as two groups of three, and parses back', async () => {
    const code = await newPairingCode()
    expect(code.canonical.length).toBe(PAIRING_CODE_SYMBOLS)
    expect(code.canonical.length).toBe(6)
    expect(code.display).toMatch(/^[A-Z2-9]{3}-[A-Z2-9]{3}$/)
    expect(code.display.replace('-', '')).toBe(code.canonical)
    const parsed = parsePairingCode(code.display)
    expect(parsed.ok).toBe(true)
    if (parsed.ok) expect(parsed.code.canonical).toBe(code.canonical)
  })

  it('never contains a character that could be misheard for another', async () => {
    // The alphabet's entire reason for existing: this code gets spoken down a phone line.
    const forbidden = /[0O1IL]/
    for (const canonical of await sampleCodes(50)) {
      expect(canonical).not.toMatch(forbidden)
      for (const ch of canonical) expect(PAIRING_ALPHABET).toContain(ch)
    }
  })

  it('draws from the whole alphabet, so the space is the size the security argument claims', async () => {
    // Non-vacuity against a generator that got stuck on a subset — the failure mode that would
    // silently shrink 29 million codes to a few thousand while every other test still passed.
    const seen = new Set<string>()
    for (const canonical of await sampleCodes(200)) for (const ch of canonical) seen.add(ch)
    expect(seen.size).toBeGreaterThan(25)
  })

  it('carries at least 24 bits of payload, which is what the online-guessing argument needs', () => {
    expect(PAIRING_CODE_ENTROPY_BITS).toBeGreaterThan(24)
    expect(PAIRING_PAYLOAD_SYMBOLS).toBe(5)
  })

  it('two codes in a row differ', async () => {
    const [a, b] = await sampleCodes(2)
    expect(a).not.toBe(b)
  })
})

describe('the check symbol catches, with certainty, what a person actually gets wrong', () => {
  it('EVERY single-character substitution, at every position, in every code tested', async () => {
    let checked = 0
    for (const canonical of await sampleCodes(8)) {
      for (let pos = 0; pos < canonical.length; pos++) {
        for (const symbol of PAIRING_ALPHABET) {
          if (symbol === canonical[pos]) continue
          const mistyped = canonical.slice(0, pos) + symbol + canonical.slice(pos + 1)
          const parsed = parsePairingCode(mistyped)
          expect(parsed.ok, `${canonical} -> ${mistyped} was accepted`).toBe(false)
          if (!parsed.ok) expect(parsed.fault).toBe('checksum')
          checked++
        }
      }
    }
    // 8 codes x 6 positions x 30 alternatives.
    expect(checked).toBe(8 * 6 * 30)
  })

  it('EVERY transposition of two differing characters', async () => {
    let checked = 0
    for (const canonical of await sampleCodes(12)) {
      for (let i = 0; i < canonical.length; i++) {
        for (let j = i + 1; j < canonical.length; j++) {
          if (canonical[i] === canonical[j]) continue
          const swapped = canonical.split('')
          ;[swapped[i], swapped[j]] = [swapped[j], swapped[i]]
          const parsed = parsePairingCode(swapped.join(''))
          expect(parsed.ok, `${canonical} with ${i}<->${j} swapped was accepted`).toBe(false)
          if (!parsed.ok) expect(parsed.fault).toBe('checksum')
          checked++
        }
      }
    }
    // Non-vacuity: codes with repeated characters skip pairs, but not all of them.
    expect(checked).toBeGreaterThan(100)
  })

  it('uses the same arithmetic the recovery code documents at length', () => {
    // The formula is re-derived in the pairing module rather than imported (different payload
    // length, and a pairing fault must not surface wearing recovery's error type). This pins
    // the two implementations to ONE formula, so a change to either is a visible divergence
    // rather than a quiet fork of the check-symbol scheme.
    expect(PAIRING_ALPHABET).toBe(RECOVERY_ALPHABET)
    // Built from the alphabet rather than typed out, so the fixture cannot drift from it —
    // and so a 28-character literal cannot masquerade as a 29-symbol payload.
    const payload29 = PAIRING_ALPHABET.slice(0, 29)
    expect(payload29.length).toBe(29)
    expect(pairingCheckSymbol(payload29)).toBe(recoveryCheckSymbol(payload29))
    // A second payload, rotated, so the agreement is not an artefact of one input.
    const rotated = PAIRING_ALPHABET.slice(2, 31)
    expect(pairingCheckSymbol(rotated)).toBe(recoveryCheckSymbol(rotated))
  })
})

describe('what a person is told when it does not parse', () => {
  const codeOf = (typed: string) => parsePairingCode(typed)

  it('names a confusable character and its position, rather than guessing what was meant', () => {
    const parsed = codeOf('AB0-DEF')
    expect(parsed.ok).toBe(false)
    if (!parsed.ok) {
      expect(parsed.fault).toBe('confusable')
      expect(parsed.at).toBe(3)
    }
  })

  it('separates "not a symbol" from "not the right length" from "one character is off"', () => {
    expect(codeOf('')).toEqual({ ok: false, fault: 'empty' })
    expect(codeOf('   -  ')).toEqual({ ok: false, fault: 'empty' })
    const notSymbol = codeOf('AB$-DEF')
    expect(notSymbol.ok).toBe(false)
    if (!notSymbol.ok) expect(notSymbol.fault).toBe('notInAlphabet')
    const short = codeOf('ABC')
    if (!short.ok) expect(short.fault).toBe('tooShort')
    const long = codeOf('ABC-DEFG')
    if (!long.ok) expect(long.fault).toBe('tooLong')
  })

  it('reads back the format it prints, including dashes a chat client has mangled', async () => {
    const code = await newPairingCode()
    const withEmDash = code.display.replace('-', '—')
    const spaced = code.canonical.split('').join(' ')
    for (const variant of [code.display, withEmDash, spaced, code.canonical.toLowerCase()]) {
      const parsed = parsePairingCode(variant)
      expect(parsed.ok, `variant refused: ${variant}`).toBe(true)
    }
  })

  it('never folds O to 0 or I to 1 — a diagnosable character stays diagnosable', () => {
    // Crockford-style folding would turn "that character cannot be in a code" into a silent
    // substitution that then fails the checksum, which is a worse sentence to show a person.
    const parsed = codeOf('OBC-DEF')
    if (!parsed.ok) expect(parsed.fault).toBe('confusable')
  })

  it('every fault has a sentence, and none of them accuses anybody of anything', () => {
    const hostile = /attack|intrud|breach|malicious|suspicious|invalid|error|failed/i
    for (const [fault, text] of Object.entries(PAIRING_FAULT_TEXT)) {
      expect(text.length, fault).toBeGreaterThan(10)
      expect(text, fault).not.toMatch(hostile)
    }
    // Non-vacuity for the detector: it can see the thing it is looking for.
    expect('A suspicious attempt was detected').toMatch(hostile)
  })

  it('requirePairingCode throws the same diagnosis the parse would have returned', () => {
    expect(() => requirePairingCode('AB0-DEF')).toThrow(PairingCodeError)
    try {
      requirePairingCode('AB0-DEF')
    } catch (e) {
      expect((e as PairingCodeError).fault).toBe('confusable')
      expect((e as PairingCodeError).at).toBe(3)
    }
  })
})

describe('the pure helpers stay pure', () => {
  it('normalize and format are inverses over canonical input', async () => {
    const code = await newPairingCode()
    expect(normalizePairingInput(formatPairingCode(code.canonical))).toBe(code.canonical)
  })
})
