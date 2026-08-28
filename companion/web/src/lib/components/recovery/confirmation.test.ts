import { describe, it, expect, beforeAll } from 'vitest'
import { initCrypto } from '../../sync/crypto'
import { GROUP_SIZE, newRecoveryCode, type RecoveryCode } from '../../recovery/recoveryCode'
import {
  CONFIRMATION_GROUPS,
  checkWrittenDown,
  chooseConfirmationGroups,
  confirmationIsComplete,
  firstMismatch,
  groupOfCode,
  unitRandom,
} from './confirmation'
import { GROUP_COUNT } from './groups'

/*
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * WHAT THIS SUITE IS FOR
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 *
 * The confirmation is the step that decides whether anybody ever recovers anything, and the way it
 * fails is not by throwing. It fails by being satisfiable without a piece of paper — which is what
 * a checkbox is, and which no amount of testing would catch, because a checkbox works perfectly.
 *
 * So the assertions here are about the properties that make the check mean something:
 *
 *   IT COMPARES AGAINST THE CODE. A stub that returned "matches" would pass a naive test on a
 *   correct answer, so every case below is asserted in both directions — the right answer matches,
 *   and a one-character change to the same answer does not.
 *
 *   IT ASKS ABOUT REAL GROUPS. The selection is drawn distinctly, in range and in reading order,
 *   over a large number of draws, because "pick two at random" written the obvious way returns the
 *   same group twice roughly one time in six.
 *
 *   IT SAYS NOTHING BACK. The result carries a group number and a boolean and nothing else. A
 *   result that carried the expected value would turn the confirmation into a second way of reading
 *   the code off the screen, which is the one thing hiding it exists to prevent.
 */

let code: RecoveryCode
let groups: string[]

beforeAll(async () => {
  await initCrypto()
  code = await newRecoveryCode()
  groups = code.display.split('-')
})

/** A stub source: hands out the given numbers in order, then repeats the last one. */
const feed = (...values: number[]) => {
  let i = 0
  return () => values[Math.min(i++, values.length - 1)]
}

describe('the suite has a subject', () => {
  it('has a real code, split the way the sheet prints it', () => {
    expect(groups).toHaveLength(GROUP_COUNT)
    expect(groups.every((g) => g.length === GROUP_SIZE)).toBe(true)
    expect(CONFIRMATION_GROUPS).toBe(2)
  })
})

describe('which groups get asked for', () => {
  it('draws the number asked for, distinct, in range, in reading order', () => {
    // Two hundred draws from the real source. Distinctness is the property the obvious
    // implementation gets wrong — "pick one, pick another" collides about one time in six — and a
    // confirmation that asked for group 4 twice would be a one-group check wearing a disguise.
    for (let i = 0; i < 200; i++) {
      const asked = chooseConfirmationGroups()
      expect(asked).toHaveLength(CONFIRMATION_GROUPS)
      expect(new Set(asked).size).toBe(CONFIRMATION_GROUPS)
      expect([...asked].sort((a, b) => a - b)).toEqual(asked)
      for (const g of asked) {
        expect(Number.isInteger(g)).toBe(true)
        expect(g).toBeGreaterThanOrEqual(1)
        expect(g).toBeLessThanOrEqual(GROUP_COUNT)
      }
    }
  })

  it('reaches every group across enough draws', () => {
    // Non-vacuity for the sweep above: an implementation that always returned [1, 2] would satisfy
    // every assertion in it. Six groups, two per draw, four hundred draws — a group that is never
    // reachable shows up here and nowhere else.
    const seen = new Set<number>()
    for (let i = 0; i < 400; i++) for (const g of chooseConfirmationGroups()) seen.add(g)
    expect(seen.size).toBe(GROUP_COUNT)
  })

  it('is pinnable, so the selection can be reasoned about rather than sampled', () => {
    expect(chooseConfirmationGroups(6, 2, feed(0, 0))).toEqual([1, 2])
    expect(chooseConfirmationGroups(6, 2, () => 0.999)).toEqual([1, 6])
    expect(chooseConfirmationGroups(6, 3, feed(0, 0, 0))).toEqual([1, 2, 3])
  })

  it('survives a source that hands back the endpoint', () => {
    // A stub, a fake, or a badly written generator returning exactly 1 would index one past the end
    // of the pool and put `undefined` in the list — which reaches the interface as a confirmation
    // asking for "group undefined". Clamped rather than trusted.
    const asked = chooseConfirmationGroups(6, 2, () => 1)
    expect(asked).toHaveLength(2)
    expect(asked.every((g) => Number.isInteger(g) && g >= 1 && g <= 6)).toBe(true)
  })

  it('cannot ask for more groups than there are', () => {
    expect(chooseConfirmationGroups(6, 99)).toHaveLength(6)
    expect(chooseConfirmationGroups(6, 0)).toEqual([])
  })

  it('draws in the unit interval', () => {
    for (let i = 0; i < 100; i++) {
      const r = unitRandom()
      expect(r).toBeGreaterThanOrEqual(0)
      expect(r).toBeLessThan(1)
    }
  })
})

describe('checking what was written down', () => {
  it('takes the group off the code the person was shown', () => {
    for (let g = 1; g <= GROUP_COUNT; g++) expect(groupOfCode(code.canonical, g)).toBe(groups[g - 1])
  })

  it('matches a correctly copied group, and does not match a changed one', () => {
    // Both directions, because a stub that always said "matches" would satisfy the first half.
    const asked = [2, 5]
    const right = checkWrittenDown(code.canonical, asked, [groups[1], groups[4]])
    expect(right.map((r) => r.matches)).toEqual([true, true])
    expect(confirmationIsComplete(right)).toBe(true)

    const oneOff = groups[4].slice(0, 4) + (groups[4][4] === 'Z' ? 'Y' : 'Z')
    const wrong = checkWrittenDown(code.canonical, asked, [groups[1], oneOff])
    expect(wrong.map((r) => r.matches)).toEqual([true, false])
    expect(confirmationIsComplete(wrong)).toBe(false)
    expect(firstMismatch(wrong)).toBe(5)
  })

  it('forgives lower case, spaces and a hyphen typed out of habit', () => {
    // None of those is a transcription error, and reporting one as a mistake sends somebody to
    // check a piece of paper that is perfectly correct — which teaches them that this screen is
    // unreliable, on the one screen that is telling them the truth about losing their data.
    const asked = [1]
    for (const typed of [groups[0].toLowerCase(), ` ${groups[0]} `, `${groups[0]}-`]) {
      expect(confirmationIsComplete(checkWrittenDown(code.canonical, asked, [typed]))).toBe(true)
    }
  })

  it('does not forgive a character the alphabet excludes', () => {
    // The Crockford fold — O to 0, I and L to 1 — is exactly what must NOT happen here. Somebody
    // who wrote a Q and read it back as an O has misread their own handwriting, and that is the
    // discovery this whole step exists to produce.
    const withO = 'O' + groups[0].slice(1)
    expect(confirmationIsComplete(checkWrittenDown(code.canonical, [1], [withO]))).toBe(false)
  })

  it('treats a missing answer as a mismatch rather than an error', () => {
    // Half a confirmation is not a confirmation, and the caller has a form to render rather than an
    // exception to handle.
    const results = checkWrittenDown(code.canonical, [1, 3], [groups[0]])
    expect(results.map((r) => r.matches)).toEqual([true, false])
    expect(firstMismatch(results)).toBe(3)
    expect(confirmationIsComplete(checkWrittenDown(code.canonical, [2], ['']))).toBe(false)
  })

  it('reports the first group that did not match, in the order they were asked', () => {
    const results = checkWrittenDown(code.canonical, [2, 4], ['', ''])
    expect(firstMismatch(results)).toBe(2)
    expect(firstMismatch(checkWrittenDown(code.canonical, [2, 4], [groups[1], groups[3]]))).toBeNull()
  })

  it('is not satisfied by an empty ask', () => {
    // A confirmation with nothing in it must not read as a confirmation — that is the checkbox,
    // reintroduced by an edge case.
    expect(confirmationIsComplete([])).toBe(false)
    expect(confirmationIsComplete(checkWrittenDown(code.canonical, [], []))).toBe(false)
  })
})

describe('the result says nothing it was not asked', () => {
  it('carries a group number and a boolean, and no part of the code', () => {
    const results = checkWrittenDown(code.canonical, [1, 4], ['', ''])
    for (const r of results) expect(Object.keys(r).sort()).toEqual(['group', 'matches'])
    // Belt and braces against a future field: no group of the code may appear anywhere in the
    // serialised result, whether the answer was right or wrong.
    const serialised = JSON.stringify(checkWrittenDown(code.canonical, [1, 4], [groups[0], 'ZZZZZ']))
    for (const group of groups) expect(serialised).not.toContain(group)
  })
})

describe('the whole step, over a real code', () => {
  it('passes only for somebody who can read the asked groups back', () => {
    // The end-to-end shape of the check as the component runs it: draw the groups, answer from the
    // code, and answer from a copy with one character wrong in one of the asked groups.
    const asked = chooseConfirmationGroups()
    const fromPaper = asked.map((g) => groups[g - 1])
    expect(confirmationIsComplete(checkWrittenDown(code.canonical, asked, fromPaper))).toBe(true)

    const badPaper = [...fromPaper]
    badPaper[1] = badPaper[1].slice(0, 2) + (badPaper[1][2] === 'Q' ? 'R' : 'Q') + badPaper[1].slice(3)
    const results = checkWrittenDown(code.canonical, asked, badPaper)
    expect(confirmationIsComplete(results)).toBe(false)
    expect(firstMismatch(results)).toBe(asked[1])
  })
})
