import { describe, it, expect, beforeAll } from 'vitest'
import { initCrypto } from '../../sync/crypto'
import {
  RECOVERY_ALPHABET,
  CODE_SYMBOLS,
  GROUP_SIZE,
  newRecoveryCode,
  type RecoveryCode,
} from '../../recovery/recoveryCode'
import {
  GROUP_COUNT,
  codeFromGroups,
  distributeIntoGroups,
  emptyGroups,
  firstGroupProblem,
  groupOfPosition,
  groupsAreEmpty,
  groupsToTyped,
  normalizeGroup,
} from './groups'
import { CHECKSUM_CANNOT_POINT } from './copy'

/*
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * WHAT THIS SUITE IS FOR
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 *
 * recoveryCode.ts already proves the arithmetic: that a single mistyped symbol is caught with
 * certainty, that a transposition is caught with certainty, that a character outside the alphabet is
 * reported at its position. This suite is about the layer above, where those facts become sentences
 * a person acts on, and it exists to hold two lines that are easy to cross by accident:
 *
 *   THE POSITION MUST BE THE ONE ON THE PAPER. A position reported as an index into thirty
 *   characters is useless to somebody reading six groups of five, and — worse — it is WRONG the
 *   moment a group is short, because every later symbol shifts. So the grouped checks are asserted
 *   against groups that are the wrong length, which is the case a concatenating implementation
 *   passes on the happy path and fails exactly when it matters.
 *
 *   THE CHECK CHARACTER MUST NOT BE GIVEN A POSITION IT DOES NOT HAVE. This is the one place the
 *   helpful thing is the dishonest thing, so it is asserted exhaustively rather than by example:
 *   every single-symbol substitution at every position of a real code must come back as a checksum
 *   fault naming NO group. An implementation that "helpfully" pointed at one would send somebody to
 *   rewrite a character that was right.
 *
 * Nothing here costs an Argon2id derivation. Every property is a property of strings, which is what
 * makes the sweeps below affordable enough to be exhaustive.
 */

/**
 * One real code, generated fresh per run rather than pinned as a fixture.
 *
 * The sweeps assert properties that hold for ALL codes, so a fixture would only have narrowed the
 * coverage — the same reasoning recoveryCode.test.ts gives for drawing its own.
 */
let code: RecoveryCode
let good: string[]

beforeAll(async () => {
  await initCrypto()
  code = await newRecoveryCode()
  good = code.display.split('-')
})

/** The groups of `good`, with one group replaced. */
const withGroup = (index: number, value: string) => good.map((g, i) => (i === index ? value : g))

describe('the suite has a subject', () => {
  it('generated a real code in the shape the groups describe', () => {
    // Guard the guards: every sweep below is a mutation of `good`, and if the split had produced
    // something else the mutations would be testing nothing.
    expect(GROUP_COUNT).toBe(6)
    expect(good).toHaveLength(GROUP_COUNT)
    expect(good.every((g) => g.length === GROUP_SIZE)).toBe(true)
    expect(good.join('')).toBe(code.canonical)
    expect(code.canonical).toHaveLength(CODE_SYMBOLS)
    // And the unmutated code really is a code, or every "this is now a fault" assertion below
    // would pass for the wrong reason.
    expect(firstGroupProblem(good)).toBeNull()
  })
})

describe('the shape of a grouped entry field', () => {
  it('starts as six empty groups, and knows that is not an error yet', () => {
    const empty = emptyGroups()
    expect(empty).toEqual(['', '', '', '', '', ''])
    expect(groupsAreEmpty(empty)).toBe(true)
    expect(groupsAreEmpty(good)).toBe(false)
    // Whitespace typed into one box is still an empty form, not a fault to complain about.
    expect(groupsAreEmpty(['  ', '', '', '', '', ''])).toBe(true)
  })

  it('normalises what a code picks up on its way through other software', () => {
    // The two Unicode dashes are what a code comes back as after a word processor, a chat client
    // or a PDF. None of them is a mistake by the person, and all of them must survive.
    expect(normalizeGroup(' k7m q2 ')).toBe('K7MQ2')
    expect(normalizeGroup('K7M–Q2')).toBe('K7MQ2')
    // NOT folded: O stays O and I stays I. That is the whole basis of the positioned diagnosis —
    // folding them to 0 and 1 would turn an exact error into a silent substitution.
    expect(normalizeGroup('o')).toBe('O')
    expect(normalizeGroup('il')).toBe('IL')
  })

  it('joins the groups into the form the parser reads', () => {
    expect(groupsToTyped(good)).toBe(code.display)
    expect(groupsToTyped(['ab', '', '', '', '', ''])).toBe('AB-----')
  })
})

describe('pasting a whole code into one box', () => {
  it('spreads thirty characters across the six groups from where they landed', () => {
    // The case this exists for: somebody arrives with the code in a file and one cursor. Without
    // the spread they lose twenty-five characters and are then told there are problems in five
    // groups, none of which are real.
    expect(distributeIntoGroups(emptyGroups(), 0, code.canonical)).toEqual(good)
    expect(distributeIntoGroups(emptyGroups(), 0, code.display)).toEqual(good)
    expect(distributeIntoGroups(emptyGroups(), 0, `  ${code.display.toLowerCase()}  `)).toEqual(good)
  })

  it('starts at the box it was pasted into and stops at the end', () => {
    const from2 = distributeIntoGroups(emptyGroups(), 2, code.canonical)
    expect(from2.slice(0, 2)).toEqual(['', ''])
    expect(from2.slice(2)).toEqual(good.slice(0, 4))
  })

  it('leaves the other groups alone when the paste fits in one box', () => {
    // Correcting a single group by pasting five characters into it must not wipe the other five.
    // This is the branch that makes the spread safe to have at all.
    const fixed = distributeIntoGroups(good, 3, 'ab2cd')
    expect(fixed[3]).toBe('AB2CD')
    expect(fixed.filter((_, i) => i !== 3)).toEqual(good.filter((_, i) => i !== 3))
  })

  it('does not mutate the array it was given', () => {
    const before = [...good]
    distributeIntoGroups(good, 0, 'ZZZZZZZZ')
    expect(good).toEqual(before)
  })
})

describe('a character that can never be in a code is named at its exact place', () => {
  it('reports the group and the offset, in words, for each of the five excluded characters', () => {
    // 0, O, 1, I and L are the two handwriting collisions the alphabet dropped BOTH members of,
    // precisely so that seeing one is a diagnosis rather than a guess. That is the property being
    // spent here, so all five are checked.
    for (const ch of ['0', 'O', '1', 'I', 'L']) {
      const typed = withGroup(2, `AB${ch}CD`)
      const problem = firstGroupProblem(typed)
      expect(problem?.kind, ch).toBe('confusable')
      expect(problem?.group, ch).toBe(3)
      expect(problem?.positionInGroup, ch).toBe(3)
      expect(problem?.message, ch).toBe('There is a mistake in group 3, at the third character.')
      expect(problem?.detail, ch).toContain('0, O, 1, I or L')
    }
  })

  it('reports any other stray character at its place too', () => {
    const problem = firstGroupProblem(withGroup(0, '$BCDE'))
    expect(problem?.kind).toBe('notInAlphabet')
    expect(problem?.group).toBe(1)
    expect(problem?.positionInGroup).toBe(1)
    expect(problem?.message).toBe('There is a mistake in group 1, at the first character.')
  })

  it('names the FIRST bad character, left to right, and not all of them', () => {
    // A person reading six simultaneous complaints has to decide which to act on, and the first is
    // nearly always the cause of the rest.
    const problem = firstGroupProblem(withGroup(1, 'ABODE').map((g, i) => (i === 4 ? 'ABIDE' : g)))
    expect(problem?.group).toBe(2)
  })

  it('names the character before it names the length, even when the group is short', () => {
    // Order matters here: a group holding a single "O" is both wrong-lengthed and unreadable, and
    // "that character is never in a code" is the one that tells somebody their handwritten 0 is an
    // O. "Group 3 has one character" would leave them counting.
    const problem = firstGroupProblem(withGroup(2, 'O'))
    expect(problem?.kind).toBe('confusable')
    expect(problem?.group).toBe(3)
  })
})

describe('a group of the wrong length is named as that group', () => {
  it('says which group and how many characters are in it', () => {
    const short = firstGroupProblem(withGroup(3, 'AB2'))
    expect(short?.kind).toBe('length')
    expect(short?.group).toBe(4)
    expect(short?.positionInGroup).toBeNull()
    expect(short?.message).toBe('Group 4 has 3 characters. Each group has five.')

    const long = firstGroupProblem(withGroup(0, 'AB2CDE'))
    expect(long?.message).toBe('Group 1 has 6 characters. Each group has five.')
  })

  it('says “character”, singular, when there is one of them', () => {
    expect(firstGroupProblem(withGroup(5, 'A'))?.message).toBe(
      'Group 6 has 1 character. Each group has five.',
    )
  })

  it('points at the group a person is looking at, not at an index into thirty symbols', () => {
    // THE reason this module exists rather than positions being read off the concatenated string.
    // With group 1 a character short, the fifth symbol of group 2 is symbol 9 by index and group 2
    // on the page. An implementation that concatenated would report group 2 as group 1 or 3 here.
    const typed = withGroup(0, good[0].slice(0, 4))
    const problem = firstGroupProblem(typed)
    expect(problem?.kind).toBe('length')
    expect(problem?.group).toBe(1)

    // And with the short group fixed and a bad character in group 5, the offset is still counted
    // within group 5 rather than from the start of the code.
    const later = firstGroupProblem(withGroup(4, 'AB*CD'))
    expect(later?.group).toBe(5)
    expect(later?.positionInGroup).toBe(3)
  })

  it('reports an empty form as empty rather than as five wrong lengths', () => {
    const problem = firstGroupProblem(emptyGroups())
    expect(problem?.kind).toBe('empty')
    expect(problem?.group).toBeNull()
    expect(problem?.message).toBe('No recovery code was entered.')
  })
})

describe('the check character detects, and refuses to point', () => {
  it('catches EVERY single-symbol substitution, at every position, without naming a group', () => {
    // The load-bearing sweep, and the reason it is a sweep. Two claims at once:
    //   1. every single-character misreading of a code is caught — the property the prime alphabet
    //      and the weighted checksum were chosen to guarantee;
    //   2. none of them is given a position, because the syndrome can be solved for a correction at
    //      every position at once and naming one would be a guess dressed as a finding.
    // 30 positions x 30 wrong symbols = 900 parses, all of them string work.
    let checked = 0
    for (let pos = 0; pos < CODE_SYMBOLS; pos++) {
      const g = Math.floor(pos / GROUP_SIZE)
      const offset = pos % GROUP_SIZE
      for (const symbol of RECOVERY_ALPHABET) {
        if (symbol === code.canonical[pos]) continue
        const group = good[g].slice(0, offset) + symbol + good[g].slice(offset + 1)
        const problem = firstGroupProblem(withGroup(g, group))
        expect(problem, `position ${pos + 1}`).not.toBeNull()
        expect(problem!.kind, `position ${pos + 1}`).toBe('checksum')
        expect(problem!.group, `position ${pos + 1}`).toBeNull()
        expect(problem!.positionInGroup, `position ${pos + 1}`).toBeNull()
        checked++
      }
    }
    // Non-vacuity: the sweep really did mutate and re-check every position of the code.
    expect(checked).toBe(CODE_SYMBOLS * (RECOVERY_ALPHABET.length - 1))
  })

  it('catches every transposition of two adjacent, different symbols, and still names no group', () => {
    // The second guarantee the prime alphabet buys, and the other realistic transcription error:
    // two characters written down the right way round and typed the wrong way round. Only pairs of
    // DIFFERENT symbols are swapped, because swapping a symbol with itself is not a transposition
    // and would leave the code untouched — the sweep would then be asserting that a valid code is
    // a fault, roughly one run in thirty-one.
    let swapped = 0
    for (let pos = 0; pos + 1 < CODE_SYMBOLS; pos++) {
      if (code.canonical[pos] === code.canonical[pos + 1]) continue
      const canonical =
        code.canonical.slice(0, pos) +
        code.canonical[pos + 1] +
        code.canonical[pos] +
        code.canonical.slice(pos + 2)
      const typed = Array.from({ length: GROUP_COUNT }, (_, i) =>
        canonical.slice(i * GROUP_SIZE, (i + 1) * GROUP_SIZE),
      )
      const problem = firstGroupProblem(typed)
      expect(problem?.kind, `positions ${pos + 1}/${pos + 2}`).toBe('checksum')
      expect(problem?.group, `positions ${pos + 1}/${pos + 2}`).toBeNull()
      swapped++
    }
    // Non-vacuity: a code of thirty symbols drawn from thirty-one has adjacent repeats about once
    // per code, never twenty-nine times, so this floor cannot be met by an empty sweep.
    expect(swapped).toBeGreaterThan(20)
  })

  it('explains why it cannot say where, rather than saying nothing', () => {
    // A single substitution, which is guaranteed to change the code and therefore guaranteed to
    // reach the check character's branch.
    const last = good[5]
    const changed = last.slice(0, 4) + (last[4] === 'Z' ? 'Y' : 'Z')
    const problem = firstGroupProblem(withGroup(5, changed))
    expect(problem?.kind).toBe('checksum')
    expect(problem?.message).toContain('does not match the rest of the code')
    expect(problem?.detail).toBe(CHECKSUM_CANNOT_POINT)
    expect(problem?.detail).toContain('cannot tell us which')
  })

  it('accepts a code that is a code, in whatever shape it was typed', () => {
    expect(firstGroupProblem(good)).toBeNull()
    expect(firstGroupProblem(good.map((g) => g.toLowerCase()))).toBeNull()
    expect(firstGroupProblem(good.map((g) => ` ${g} `))).toBeNull()
    expect(codeFromGroups(good)?.canonical).toBe(code.canonical)
    expect(codeFromGroups(withGroup(1, 'AB2CD'))).toBeNull()
  })
})

describe('nothing a diagnosis says quotes what was typed', () => {
  it('reports positions and never characters', () => {
    // A fault message is the thing most likely to be screenshotted into a support thread, and half
    // of what it would be quoting is a live key. Checked against a distinctive character that
    // could not appear in the sentence for any other reason, and then against the real groups.
    const marked = firstGroupProblem(withGroup(2, 'AB§CD'))
    expect(marked?.message).not.toContain('§')
    expect(marked?.detail ?? '').not.toContain('§')

    const wrong = firstGroupProblem(
      withGroup(0, good[0].slice(0, 4) + (good[0][4] === 'Z' ? 'Y' : 'Z')),
    )
    const said = `${wrong?.message} ${wrong?.detail}`
    for (const group of good) expect(said).not.toContain(group)
  })
})

describe('a position in a whole code maps to the group a person would look at', () => {
  it('counts groups of five from one', () => {
    expect(groupOfPosition(1)).toEqual({ group: 1, positionInGroup: 1 })
    expect(groupOfPosition(5)).toEqual({ group: 1, positionInGroup: 5 })
    expect(groupOfPosition(6)).toEqual({ group: 2, positionInGroup: 1 })
    expect(groupOfPosition(17)).toEqual({ group: 4, positionInGroup: 2 })
    expect(groupOfPosition(CODE_SYMBOLS)).toEqual({ group: GROUP_COUNT, positionInGroup: GROUP_SIZE })
  })
})
