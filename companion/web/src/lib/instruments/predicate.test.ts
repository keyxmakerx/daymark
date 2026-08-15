import { describe, it, expect } from 'vitest'
import { evalPredicate, visibleItemIds, MAX_PREDICATE_DEPTH } from './predicate'
import type { Predicate } from './types'

/*
 * Security regression tests for the branching DSL (docs/COMPANION_DIALOGUE.md, findings 1 and 2).
 *
 * These matter more than ordinary branching tests because a definition may be authored by a
 * clinician and evaluated on someone else's device. Both behaviours below were found by executing
 * the evaluator, not by reading it.
 */

const P = (o: unknown) => o as Predicate

describe('unknown refs fail closed (finding 1)', () => {
  // The bug: `ne` alone returned true for a missing signal, because undefined !== value. A branch
  // gated on a renamed or mistyped signal SHOWED instead of hiding, silently.
  const answers = { known: 3 }

  it('returns false for every operator when the ref is absent', () => {
    const ops: Array<[string, unknown]> = [
      ['eq', 1],
      ['ne', 1],
      ['gt', 1],
      ['gte', 1],
      ['lt', 1],
      ['lte', 1],
      ['in', [1, 2]],
      ['includes', 1],
    ]
    for (const [op, value] of ops) {
      expect(evalPredicate(P({ ref: 'absent', op, value }), answers), `op ${op} must fail closed`).toBe(false)
    }
  })

  it('specifically pins `ne`, which is the one that regressed', () => {
    expect(evalPredicate(P({ ref: 'absent', op: 'ne', value: 1 }), answers)).toBe(false)
  })

  it('still evaluates `ne` normally when the ref IS present', () => {
    // Fail-closed must not become fail-always: a real answer still compares.
    expect(evalPredicate(P({ ref: 'known', op: 'ne', value: 1 }), answers)).toBe(true)
    expect(evalPredicate(P({ ref: 'known', op: 'ne', value: 3 }), answers)).toBe(false)
  })

  it('treats an explicitly undefined value as present, not absent', () => {
    // `in` distinguishes "we have no answer" from "the answer is undefined"; only the first hides.
    expect(evalPredicate(P({ ref: 'u', op: 'ne', value: 1 }), { u: undefined })).toBe(true)
  })

  it('hides an item whose gate references a signal that no longer exists', () => {
    // The end-to-end shape of the drift scenario: content outlives the signal it was authored against.
    const items = [
      { id: 'always' },
      { id: 'gated', visibleWhen: P({ ref: 'renamedAwayInV2', op: 'ne', value: 'x' }) },
    ]
    const visible = visibleItemIds(items, { checkInsLast7: 4 })
    expect(visible.has('always')).toBe(true)
    expect(visible.has('gated')).toBe(false)
  })
})

describe('the evaluator is bounded (finding 2)', () => {
  const nest = (depth: number): Predicate => {
    let p: Predicate = P({ ref: 'known', op: 'eq', value: 3 })
    for (let i = 0; i < depth; i++) p = P({ all: [p] })
    return p
  }

  it('evaluates a tree within the bound', () => {
    expect(evalPredicate(nest(MAX_PREDICATE_DEPTH - 1), { known: 3 })).toBe(true)
  })

  it('throws a named error rather than blowing the stack', () => {
    // Previously: 50k nested all[] threw "Maximum call stack size exceeded" from the runtime.
    expect(() => evalPredicate(nest(50_000), { known: 3 })).toThrow(/nested deeper than/)
  })

  it('bounds `any` as well as `all`', () => {
    let p: Predicate = P({ ref: 'known', op: 'eq', value: 3 })
    for (let i = 0; i < 5_000; i++) p = P({ any: [p] })
    expect(() => evalPredicate(p, { known: 3 })).toThrow(/nested deeper than/)
  })
})

describe('the sandbox holds', () => {
  it('rejects an unknown node shape instead of guessing', () => {
    expect(() => evalPredicate(P({ nope: [] }), {})).toThrow(/invalid predicate node/)
  })

  it('rejects an unknown operator instead of defaulting to true', () => {
    expect(() => evalPredicate(P({ ref: 'known', op: 'exec', value: 1 }), { known: 1 })).toThrow(/unknown predicate op/)
  })

  it('carries no code path — a function value is compared, never called', () => {
    let called = false
    const bomb = () => {
      called = true
      return true
    }
    expect(evalPredicate(P({ ref: 'f', op: 'eq', value: bomb }), { f: bomb })).toBe(true)
    expect(called).toBe(false)
  })
})

describe('an empty substrate is the normal case, not an edge case', () => {
  // A fresh install has no history, so every signal is absent. Nothing should throw, and nothing
  // gated should appear (docs/COMPANION_DIALOGUE.md — robustness).
  it('hides every gated item and shows every ungated one', () => {
    const items = [
      { id: 'greeting' },
      { id: 'rough', visibleWhen: P({ ref: 'hardDaysLast7', op: 'gte', value: 3 }) },
      { id: 'quiet', visibleWhen: P({ ref: 'checkInsLast7', op: 'lte', value: 1 }) },
      { id: 'notThis', visibleWhen: P({ ref: 'timeOfDay', op: 'ne', value: 'night' }) },
    ]
    const visible = visibleItemIds(items, {})
    expect([...visible]).toEqual(['greeting'])
  })
})
