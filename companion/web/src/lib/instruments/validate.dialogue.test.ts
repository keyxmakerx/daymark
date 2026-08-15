import { describe, it, expect } from 'vitest'
import { validateDefinition, validateDialogueDefinition, MAX_PREDICATE_NODES } from './validate'
import { evalPredicate, MAX_PREDICATE_DEPTH } from './predicate'
import { CATALOG, REQUIRED_LEDGER_ANCHORS } from './catalog'
import { SIGNAL_NAMES, signalsFor } from '../companion/signals'
import type { InstrumentDefinition, Predicate } from './types'

/*
 * Authoring-time guards for companion dialogue — docs/COMPANION_DIALOGUE.md, findings 2 and 3.
 *
 * The runtime halves live in predicate.test.ts. These are the paired checks that catch the same
 * two problems while a definition is being authored, where the error can still be acted on:
 * content that would take the runner down, and content that turns a person's private history
 * into something its author can observe.
 *
 * Every test below fails if its guard is removed, and the last describe block exists to prove
 * the guards cost the four shipped definitions nothing.
 */

const anchors = new Set(REQUIRED_LEDGER_ANCHORS)
const P = (o: unknown) => o as Predicate

/**
 * A minimal valid definition with one scored item and one unscored `info` item that carries the
 * gate under test. The gate deliberately sits on the unscored item: a `sum` scale may not score
 * a conditionally hidden item, and tripping that unrelated rule would make these tests pass for
 * the wrong reason.
 */
function base(gate?: unknown): InstrumentDefinition {
  return {
    instrumentId: 'x-dialogue',
    instrumentVersion: '1.0.0',
    title: 'X',
    license: 'self',
    ledgerRef: 'INSTRUMENTS.md#wellbeing-selfcheck',
    provenance: { tier: 'custom' },
    nonDiagnostic: true,
    noScreeningFlag: true,
    items: [
      { id: 'q1', type: 'likert', options: [{ id: 'a', label: 'A', value: 0 }, { id: 'b', label: 'B', value: 4 }] },
      { id: 'line', type: 'info', body: 'No agenda.', ...(gate ? { visibleWhen: P(gate) } : {}) },
    ],
    scoring: {
      scales: [
        {
          id: 's',
          method: 'sum',
          items: ['q1'],
          bands: [{ label: 'ok', tone: 'neutral' }],
          bandFraming: 'descriptive — not a diagnosis',
        },
      ],
    },
    framing: { intro: 'self-check — not a diagnosis' },
  }
}

const leaf = { ref: 'q1', op: 'gte', value: 3 }

/** A tree of `levels` levels: a bare leaf is 1, each wrapper adds one. */
function nest(levels: number, key: 'all' | 'any' = 'all'): unknown {
  let p: unknown = leaf
  for (let i = 1; i < levels; i++) p = { [key]: [p] }
  return p
}

/** A two-level tree of `n` leaves, so `n + 1` nodes at depth 2. */
function wide(n: number): unknown {
  return { all: Array.from({ length: n }, () => ({ ...leaf })) }
}

const errorsOf = (def: InstrumentDefinition) => validateDefinition(def, anchors).errors.join(' | ')

describe('predicate depth is bounded at authoring time (finding 2)', () => {
  it('accepts a tree exactly at the limit', () => {
    const { ok, errors } = validateDefinition(base(nest(MAX_PREDICATE_DEPTH)), anchors)
    expect(ok, errors.join('; ')).toBe(true)
  })

  it('rejects one level past the limit, naming the item', () => {
    const errors = errorsOf(base(nest(MAX_PREDICATE_DEPTH + 1)))
    expect(errors).toMatch(/item "line" visibleWhen nests deeper than 16 levels/)
  })

  it('bounds `any` as well as `all`', () => {
    expect(errorsOf(base(nest(MAX_PREDICATE_DEPTH + 1, 'any')))).toMatch(/nests deeper than/)
  })

  it('rejects the 50,000-deep tree without blowing its own stack', () => {
    // The regression that matters. A recursive validator would throw "Maximum call stack size
    // exceeded" here — failing on the exact input it exists to reject — so this asserts a
    // returned error rather than a thrown one.
    let result: ReturnType<typeof validateDefinition> | undefined
    expect(() => {
      result = validateDefinition(base(nest(50_000)), anchors)
    }).not.toThrow()
    expect(result?.ok).toBe(false)
    expect(result?.errors.join(' | ')).toMatch(/nests deeper than/)
  })

  it('terminates on a cyclic predicate instead of hanging', () => {
    // Not reachable from parsed JSON, but a generator that reuses a node object can build one,
    // and a validator that loops forever is a worse failure than one that rejects.
    const cycle: { all: unknown[] } = { all: [] }
    cycle.all.push(cycle)
    expect(errorsOf(base(cycle))).toMatch(/nests deeper than/)
  })

  it('accepts what it accepts only if the evaluator can also evaluate it', () => {
    // The two bounds are counted differently (levels here, root-at-zero there), so pin the
    // relationship rather than trusting that 16 means the same thing in both files.
    const deepest = nest(MAX_PREDICATE_DEPTH)
    expect(validateDefinition(base(deepest), anchors).ok).toBe(true)
    expect(() => evalPredicate(P(deepest), { q1: 4 })).not.toThrow()
    expect(evalPredicate(P(deepest), { q1: 4 })).toBe(true)
  })
})

describe('predicate size is bounded at authoring time (finding 2)', () => {
  it('accepts a tree exactly at the node limit', () => {
    const { ok, errors } = validateDefinition(base(wide(MAX_PREDICATE_NODES - 1)), anchors)
    expect(ok, errors.join('; ')).toBe(true)
  })

  it('rejects one node past the limit, though it is only two levels deep', () => {
    const errors = errorsOf(base(wide(MAX_PREDICATE_NODES)))
    expect(errors).toMatch(/item "line" visibleWhen has more than 256 predicate nodes/)
    expect(errors).not.toMatch(/nests deeper than/)
  })

  it('rejects a wide tree far past the limit', () => {
    expect(errorsOf(base(wide(20_000)))).toMatch(/more than 256 predicate nodes/)
  })

  it('measures each visibleWhen on its own, not summed across the definition', () => {
    // Many small honest gates are the normal shape of a long form and must not add up to a
    // rejection; the cost being bounded is the cost of one tree handed to the evaluator.
    const def = base()
    for (let i = 0; i < 40; i++) {
      def.items.push({ id: `extra${i}`, type: 'info', body: 'x', visibleWhen: P(wide(20)) })
    }
    const { ok, errors } = validateDefinition(def, anchors)
    expect(ok, errors.join('; ')).toBe(true)
  })

  it('leaves an ungated definition alone', () => {
    expect(validateDefinition(base(), anchors).ok).toBe(true)
  })
})

describe('the author partition (finding 3)', () => {
  const signal = (ref: string) => ({ ref, op: 'gte', value: 3 })

  /*
   * Which signals sit on which side of the partition is signals.ts's decision and signals.ts's
   * test. What is under test HERE is that this file collects every ref, exempts the author's own
   * items, and hands the rest to assertRefsAllowed — so the app-only fixture is derived from the
   * vocabulary rather than hard-coded. Pinning a name here would make these tests fail whenever
   * that table legitimately changes, and pass while asserting nothing about the delegation.
   */
  const appOnly = SIGNAL_NAMES.find((n) => !signalsFor('therapist').includes(n))

  it('has something to enforce — the partition is not empty', () => {
    // If this ever fails, the tests below are vacuous rather than passing, so it is checked
    // rather than assumed.
    expect(appOnly, 'no app-only signal exists; finding 3 has nothing left to enforce').toBeDefined()
    expect(signalsFor('therapist').length).toBeGreaterThan(0)
  })

  it('is not applied when no author is given', () => {
    // Existing catalog validation must not acquire a new requirement: those definitions branch
    // on item ids, and an unauthored call is validateDefinition exactly.
    const def = base(signal(appOnly!))
    expect(validateDialogueDefinition(def).ok).toBe(true)
    expect(validateDialogueDefinition(def, undefined, anchors)).toEqual(validateDefinition(def, anchors))
  })

  it('lets app-authored dialogue branch on a private signal', () => {
    const { ok, errors } = validateDialogueDefinition(base(signal(appOnly!)), 'app', anchors)
    expect(ok, errors.join('; ')).toBe(true)
  })

  it('refuses the same branch when a therapist authored it — the oracle', () => {
    const { ok, errors } = validateDialogueDefinition(base(signal(appOnly!)), 'therapist', anchors)
    expect(ok).toBe(false)
    expect(errors.join(' | ')).toMatch(appOnly!)
  })

  it('refuses a therapist-authored branch on hasSafetyPlan', () => {
    // The one signal pinned by name, because docs/COMPANION_DIALOGUE.md gives it its own
    // paragraph: whether someone wrote a safety plan is never disclosed to a clinician by any
    // route, and inference from the shape of authored content is a route.
    const gate = { ref: 'hasSafetyPlan', op: 'eq', value: 'true' }
    expect(validateDialogueDefinition(base(gate), 'therapist', anchors).ok).toBe(false)
    expect(validateDialogueDefinition(base(gate), 'app', anchors).ok).toBe(true)
  })

  it('finds a forbidden signal nested inside all/any, not just at the root', () => {
    const gate = { all: [{ any: [signal(appOnly!)] }, signal('timeOfDay')] }
    expect(validateDialogueDefinition(base(gate), 'therapist', anchors).ok).toBe(false)
  })

  it('allows the two signals a therapist may see', () => {
    const gate = {
      any: [
        { ref: 'prescribedModules', op: 'includes', value: 'compassion-hard-moment' },
        { ref: 'timeOfDay', op: 'eq', value: 'evening' },
      ],
    }
    const { ok, errors } = validateDialogueDefinition(base(gate), 'therapist', anchors)
    expect(ok, errors.join('; ')).toBe(true)
  })

  it('allows a therapist to branch on an answer given inside their own module', () => {
    // Finding 3 permits this explicitly: `q1` is an item of this definition, not a signal, so
    // it never reaches the vocabulary. Without the exemption every branching module a clinician
    // authored would be rejected.
    const { ok, errors } = validateDialogueDefinition(base({ ref: 'q1', op: 'gte', value: 3 }), 'therapist', anchors)
    expect(ok, errors.join('; ')).toBe(true)
  })

  it('rejects a ref that is neither an item nor a signal, for either author', () => {
    // The authoring half of finding 1: a typo, or a signal a later release renamed away.
    const gate = { ref: 'hardDaysLast8', op: 'gte', value: 3 }
    expect(validateDialogueDefinition(base(gate), 'therapist', anchors).ok).toBe(false)
    expect(validateDialogueDefinition(base(gate), 'app', anchors).ok).toBe(false)
    expect(validateDialogueDefinition(base(gate), 'app', anchors).errors.join(' | ')).toMatch(/hardDaysLast8/)
  })

  it('prefixes partition errors with the instrument id like every other line', () => {
    const { errors } = validateDialogueDefinition(base(signal(appOnly!)), 'therapist', anchors)
    expect(errors.length).toBeGreaterThan(0)
    for (const e of errors) expect(e.startsWith('[x-dialogue]')).toBe(true)
  })

  it('still reports the ordinary honesty-gate failures alongside', () => {
    const def = base(signal(appOnly!))
    def.nonDiagnostic = false as unknown as true
    const errors = validateDialogueDefinition(def, 'therapist', anchors).errors.join(' | ')
    expect(errors).toMatch(/nonDiagnostic must be exactly true/)
    expect(errors).toMatch(appOnly!)
  })

  it('applies the depth and size bounds through the dialogue entry point too', () => {
    expect(validateDialogueDefinition(base(nest(50_000)), 'app', anchors).ok).toBe(false)
    expect(validateDialogueDefinition(base(wide(20_000)), 'app', anchors).ok).toBe(false)
  })
})

describe('the shipped catalog is unaffected', () => {
  // The guard against the guards. Four definitions ship today and none of them may start
  // failing because dialogue authoring acquired new rules.
  it('every shipped instrument still passes validateDefinition', () => {
    for (const def of CATALOG) {
      const { ok, errors } = validateDefinition(def, anchors)
      expect(ok, `${def.instrumentId}: ${errors.join('; ')}`).toBe(true)
    }
  })

  it('every shipped instrument passes the dialogue gate with no author', () => {
    for (const def of CATALOG) {
      const { ok, errors } = validateDialogueDefinition(def, undefined, anchors)
      expect(ok, `${def.instrumentId}: ${errors.join('; ')}`).toBe(true)
    }
  })

  it('every shipped instrument passes the dialogue gate as app-authored', () => {
    for (const def of CATALOG) {
      const { ok, errors } = validateDialogueDefinition(def, 'app', anchors)
      expect(ok, `${def.instrumentId}: ${errors.join('; ')}`).toBe(true)
    }
  })

  it('reports byte-identical results with and without an author', () => {
    for (const def of CATALOG) {
      expect(validateDialogueDefinition(def, undefined, anchors)).toEqual(validateDefinition(def, anchors))
    }
  })
})
