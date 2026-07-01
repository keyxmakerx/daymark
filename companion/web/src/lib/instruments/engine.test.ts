import { describe, it, expect } from 'vitest'
import { evalPredicate, visibleItemIds } from './predicate'
import { scoreInstrument, bandFor } from './scoring'
import type { InstrumentDefinition, Band, Item } from './types'

describe('branching DSL', () => {
  const ans = { q1: 3, q2: ['a', 'trouble'], q3: 1 }
  it('evaluates leaf ops', () => {
    expect(evalPredicate({ ref: 'q1', op: 'gte', value: 3 }, ans)).toBe(true)
    expect(evalPredicate({ ref: 'q1', op: 'lt', value: 3 }, ans)).toBe(false)
    expect(evalPredicate({ ref: 'q1', op: 'in', value: [3, 4] }, ans)).toBe(true)
    expect(evalPredicate({ ref: 'q2', op: 'includes', value: 'trouble' }, ans)).toBe(true)
  })
  it('evaluates all/any nesting', () => {
    expect(evalPredicate({ all: [{ ref: 'q1', op: 'gte', value: 2 }, { any: [{ ref: 'q3', op: 'eq', value: 9 }, { ref: 'q3', op: 'eq', value: 1 }] }] }, ans)).toBe(true)
  })
  it('never executes definition-supplied code (unknown node throws)', () => {
    expect(() => evalPredicate({} as never, ans)).toThrow()
  })
  it('hides items whose visibleWhen is false', () => {
    const items: Item[] = [
      { id: 'a', type: 'likert' },
      { id: 'b', type: 'likert', visibleWhen: { ref: 'a', op: 'gte', value: 3 } },
    ]
    expect(visibleItemIds(items, { a: 1 }).has('b')).toBe(false)
    expect(visibleItemIds(items, { a: 4 }).has('b')).toBe(true)
  })
})

describe('scoring', () => {
  const likert = [
    { id: 'n', label: 'Never', value: 0 },
    { id: 's', label: 'Sometimes', value: 2 },
    { id: 'a', label: 'Always', value: 4 },
  ]
  const def: InstrumentDefinition = {
    instrumentId: 't', instrumentVersion: '1.0.0', title: 't', license: 'self', ledgerRef: 'INSTRUMENTS.md#t',
    nonDiagnostic: true, noScreeningFlag: true,
    items: [
      { id: 'q1', type: 'likert', options: likert },
      { id: 'q2', type: 'likert', options: likert },
      { id: 'note', type: 'freeText', excludeFromScoring: true },
    ],
    scoring: { scales: [{ id: 's1', method: 'sum', items: ['q1', 'q2'], reverse: ['q2'], bands: [{ max: 3, label: 'low', tone: 'neutral' }, { min: 4, label: 'high', tone: 'attention' }], bandFraming: 'descriptive' }] },
    framing: { intro: 'self-check' },
  }

  it('sums with reverse scoring', () => {
    // q1='a'(4) + reverse(q2='n' → 4+0-0 = 4) = 8
    const r = scoreInstrument(def, { q1: 'a', q2: 'n' })
    expect(r[0].score).toBe(8)
    expect(r[0].bandLabel).toBe('high')
  })

  it('ignores unanswered and free-text items', () => {
    const r = scoreInstrument(def, { q1: 's' }) // q2 unanswered
    expect(r[0].score).toBe(2)
    expect(r[0].bandLabel).toBe('low')
  })

  it('bandFor is inclusive and ordered', () => {
    const bands: Band[] = [{ max: 3, label: 'lo', tone: 'neutral' }, { min: 4, max: 6, label: 'mid', tone: 'neutral' }, { min: 7, label: 'hi', tone: 'attention' }]
    expect(bandFor(3, bands)?.label).toBe('lo')
    expect(bandFor(4, bands)?.label).toBe('mid')
    expect(bandFor(99, bands)?.label).toBe('hi')
  })
})
