import { describe, it, expect } from 'vitest'
import {
  SIGNALS,
  SIGNAL_NAMES,
  SIGNAL_VOCABULARY_VERSION,
  signalsFor,
  assertRefsAllowed,
} from './signals'
import type { SignalAuthor, SignalName, SignalType } from './signals'

/*
 * The vocabulary is a security boundary, not a config file.
 *
 * Two things are being pinned here. First, that the list stays CLOSED at eight — a ninth signal
 * is a coupling point and docs/COMPANION_DIALOGUE.md asks that adding one require an argument,
 * so the count is asserted and a silent addition breaks the suite. Second, the author partition
 * from Finding 3: a therapist-authored branch on private data is an oracle for that data, because
 * the arms of the branch differ in ways the therapist can already observe.
 */

const ALL_EIGHT: SignalName[] = [
  'daysSinceLastOpen',
  'daysSinceLastCheckIn',
  'checkInsLast7',
  'hardDaysLast7',
  'hasSafetyPlan',
  'prescribedModules',
  'lastOfferOutcome',
  'timeOfDay',
]

describe('the vocabulary is closed', () => {
  it('has exactly eight signals', () => {
    // If this fails because a ninth was added: that is the point. Make the case in the docs,
    // then change the number here deliberately.
    expect(SIGNAL_NAMES).toHaveLength(8)
    expect(Object.keys(SIGNALS)).toHaveLength(8)
  })

  it('is exactly the eight the design document lists', () => {
    expect([...SIGNAL_NAMES].sort()).toEqual([...ALL_EIGHT].sort())
  })

  it('gives every signal a spec that names itself', () => {
    for (const name of SIGNAL_NAMES) {
      expect(SIGNALS[name].name, `${name} spec disagrees with its key`).toBe(name)
    }
  })

  it('types every signal within the declared shapes', () => {
    const types: SignalType[] = ['int', 'bool', 'enum', 'stringArray']
    for (const name of SIGNAL_NAMES) expect(types).toContain(SIGNALS[name].type)
  })

  it('describes every signal in plain language, for the authoring UI', () => {
    for (const name of SIGNAL_NAMES) {
      expect(SIGNALS[name].description.length, `${name} needs a description`).toBeGreaterThan(20)
    }
  })

  it('records a vocabulary version, so a later rename can fail loudly', () => {
    expect(SIGNAL_VOCABULARY_VERSION).toBe(1)
  })
})

describe('the author partition (finding 3)', () => {
  it('lets the app branch on all eight', () => {
    expect(signalsFor('app')).toHaveLength(8)
    expect([...signalsFor('app')].sort()).toEqual([...ALL_EIGHT].sort())
  })

  it('lets a therapist branch on prescribedModules and timeOfDay only', () => {
    expect([...signalsFor('therapist')].sort()).toEqual(['prescribedModules', 'timeOfDay'])
  })

  it('never grants a therapist a signal the app does not also have', () => {
    const app = new Set(signalsFor('app'))
    for (const name of signalsFor('therapist')) expect(app.has(name)).toBe(true)
  })

  it('lists app on every signal — the app authors against the whole substrate', () => {
    for (const name of SIGNAL_NAMES) expect(SIGNALS[name].authors).toContain('app')
  })

  it('explains the restriction on exactly the app-only signals', () => {
    for (const name of SIGNAL_NAMES) {
      const s = SIGNALS[name]
      if (s.authors.includes('therapist')) expect(s.whyAppOnly).toBeUndefined()
      else expect(s.whyAppOnly, `${name} needs a reason a clinician can read`).toBeTruthy()
    }
  })

  it('returns a fresh array, so a caller cannot widen the partition for everyone else', () => {
    const first = signalsFor('therapist')
    first.push('hardDaysLast7')
    expect(signalsFor('therapist')).toHaveLength(2)
  })

  it('freezes the specs, so the partition cannot be edited at runtime', () => {
    try {
      // @ts-expect-error — deliberately attempting the mutation this test forbids
      SIGNALS.hardDaysLast7.authors.push('therapist')
    } catch {
      /* strict mode throws; non-strict silently ignores. Either is fine — the assert is below. */
    }
    expect(SIGNALS.hardDaysLast7.authors).toEqual(['app'])
    expect(assertRefsAllowed(['hardDaysLast7'], 'therapist')).toHaveLength(1)
  })
})

describe('hasSafetyPlan is not merely app-only', () => {
  it('is marked never-disclosable', () => {
    expect(SIGNALS.hasSafetyPlan.neverDisclosable).toBe(true)
    expect(SIGNALS.hasSafetyPlan.authors).toEqual(['app'])
  })

  it('is the only signal carrying that mark, and no therapist-visible signal carries it', () => {
    const marked = SIGNAL_NAMES.filter((n) => SIGNALS[n].neverDisclosable)
    expect(marked).toEqual(['hasSafetyPlan'])
    for (const name of signalsFor('therapist')) expect(SIGNALS[name].neverDisclosable).toBeUndefined()
  })
})

describe('assertRefsAllowed — the therapist side', () => {
  it('rejects hardDaysLast7 — the oracle', () => {
    const errors = assertRefsAllowed(['hardDaysLast7'], 'therapist')
    expect(errors).toHaveLength(1)
    expect(errors[0]).toContain('hardDaysLast7')
  })

  it('rejects hasSafetyPlan', () => {
    const errors = assertRefsAllowed(['hasSafetyPlan'], 'therapist')
    expect(errors).toHaveLength(1)
    expect(errors[0]).toContain('hasSafetyPlan')
    expect(errors[0]).toMatch(/safety plan/i)
  })

  it('rejects every app-only signal, one error each', () => {
    const appOnly = SIGNAL_NAMES.filter((n) => !SIGNALS[n].authors.includes('therapist'))
    expect(appOnly).toHaveLength(6)
    expect(assertRefsAllowed(appOnly, 'therapist')).toHaveLength(6)
  })

  it('allows the two signals a therapist may use', () => {
    expect(assertRefsAllowed(['prescribedModules', 'timeOfDay'], 'therapist')).toEqual([])
  })

  it('flags only the offending ref in a mixed definition', () => {
    const errors = assertRefsAllowed(['prescribedModules', 'checkInsLast7', 'timeOfDay'], 'therapist')
    expect(errors).toHaveLength(1)
    expect(errors[0]).toContain('checkInsLast7')
  })

  it('explains WHY, because a clinician reads this message', () => {
    // "not permitted" teaches nothing. The message has to name what the branch would reveal.
    const [message] = assertRefsAllowed(['hardDaysLast7'], 'therapist')
    expect(message).toMatch(/would let you infer/)
    expect(message).toMatch(/mood band/i)
    // and it must say what they CAN do, so the error is actionable
    expect(message).toContain('prescribedModules')
    expect(message).toContain('timeOfDay')
  })

  it('does not present a forbidden real signal as a typo', () => {
    const [message] = assertRefsAllowed(['lastOfferOutcome'], 'therapist')
    expect(message).toContain('is a real signal')
    expect(message).not.toMatch(/not a signal/)
  })
})

describe('assertRefsAllowed — unknown names', () => {
  it('rejects an unknown name for a therapist', () => {
    const errors = assertRefsAllowed(['moodScore'], 'therapist')
    expect(errors).toHaveLength(1)
    expect(errors[0]).toMatch(/not a signal/)
  })

  it('rejects an unknown name for the app too — the list is closed for both', () => {
    const errors = assertRefsAllowed(['moodScore'], 'app')
    expect(errors).toHaveLength(1)
    expect(errors[0]).toMatch(/not a signal/)
  })

  it('names the vocabulary version, so drift is legible', () => {
    expect(assertRefsAllowed(['renamedAwayInV2'], 'app')[0]).toContain(`v${SIGNAL_VOCABULARY_VERSION}`)
  })

  it('treats a near-miss as unknown rather than guessing', () => {
    // Case and pluralisation drift must fail closed, not resolve to the real signal.
    for (const ref of ['HardDaysLast7', 'harddayslast7', 'hardDaysLast07', 'prescribedModule']) {
      expect(assertRefsAllowed([ref], 'app'), ref).toHaveLength(1)
    }
  })

  it('does not resolve inherited object properties as signals', () => {
    for (const ref of ['toString', 'constructor', '__proto__', 'hasOwnProperty']) {
      const errors = assertRefsAllowed([ref], 'app')
      expect(errors, ref).toHaveLength(1)
      expect(errors[0]).toMatch(/not a signal/)
    }
  })

  it('offers only signals the author may actually use', () => {
    // Suggesting app-only names to a clinician would be both useless and a hint about what exists.
    const [message] = assertRefsAllowed(['typo'], 'therapist')
    expect(message).toContain('prescribedModules')
    expect(message).not.toContain('hardDaysLast7')
    expect(message).not.toContain('hasSafetyPlan')
  })
})

describe('assertRefsAllowed — shape of the result', () => {
  it('accepts all eight from the app', () => {
    expect(assertRefsAllowed(ALL_EIGHT, 'app')).toEqual([])
    expect(assertRefsAllowed(SIGNAL_NAMES, 'app')).toEqual([])
  })

  it('returns an empty array for no refs', () => {
    expect(assertRefsAllowed([], 'app')).toEqual([])
    expect(assertRefsAllowed([], 'therapist')).toEqual([])
  })

  it('returns errors rather than throwing, so an editor can show them all at once', () => {
    let errors: string[] = []
    expect(() => {
      errors = assertRefsAllowed(['hardDaysLast7', 'typo', 'hasSafetyPlan'], 'therapist')
    }).not.toThrow()
    expect(errors).toHaveLength(3)
  })

  it('preserves the order the refs were given', () => {
    const errors = assertRefsAllowed(['typo', 'hardDaysLast7'], 'therapist')
    expect(errors[0]).toContain('typo')
    expect(errors[1]).toContain('hardDaysLast7')
  })

  it('reports a repeated ref once', () => {
    expect(assertRefsAllowed(['hardDaysLast7', 'hardDaysLast7'], 'therapist')).toHaveLength(1)
    expect(assertRefsAllowed(['typo', 'typo', 'typo'], 'app')).toHaveLength(1)
  })

  it('fails closed on an author role it does not recognise', () => {
    // Author can arrive from stored or transmitted content; an unrecognised role gets nothing.
    const rogue = 'admin' as unknown as SignalAuthor
    const errors = assertRefsAllowed(['timeOfDay'], rogue)
    expect(errors).toHaveLength(1)
    expect(errors[0]).toMatch(/unknown author role/)
  })
})
