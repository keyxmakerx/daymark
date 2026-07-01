import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { CATALOG, REQUIRED_LEDGER_ANCHORS } from './catalog'
import { validateDefinition } from './validate'
import type { InstrumentDefinition } from './types'

const anchors = new Set(REQUIRED_LEDGER_ANCHORS)

function base(): InstrumentDefinition {
  return {
    instrumentId: 'x-selfcheck', instrumentVersion: '1.0.0', title: 'X', license: 'self',
    ledgerRef: 'INSTRUMENTS.md#wellbeing-selfcheck', nonDiagnostic: true, noScreeningFlag: true,
    items: [{ id: 'q1', type: 'likert', options: [{ id: 'a', label: 'A', value: 0 }, { id: 'b', label: 'B', value: 4 }] }],
    scoring: { scales: [{ id: 's', method: 'sum', items: ['q1'], bands: [{ label: 'ok', tone: 'neutral' }], bandFraming: 'descriptive — not a diagnosis' }] },
    framing: { intro: 'self-check' },
  }
}

describe('CI honesty gate', () => {
  it('every shipped instrument passes validation', () => {
    for (const def of CATALOG) {
      const { ok, errors } = validateDefinition(def, anchors)
      expect(ok, errors.join('; ')).toBe(true)
    }
  })

  it('every shipped instrument is non-diagnostic with no screening flag', () => {
    for (const def of CATALOG) {
      expect(def.nonDiagnostic).toBe(true)
      expect(def.noScreeningFlag).toBe(true)
    }
  })

  it('rejects a forbidden (licensed) source instrument', () => {
    const bad = { ...base(), instrumentId: 'conners-3', title: 'Conners 3 attention' }
    expect(validateDefinition(bad, anchors).ok).toBe(false)
  })

  it('rejects forbidden source text hidden in an item prompt (not just the id)', () => {
    const bad = base()
    bad.items = [{ id: 'q1', type: 'likert', prompt: 'Item copied from the Conners scale', options: [{ id: 'a', label: 'A', value: 0 }] }]
    bad.scoring.scales[0].items = ['q1']
    expect(validateDefinition(bad, anchors).ok).toBe(false)
  })

  it('does not false-positive on innocuous words like "restless" (word-boundary match)', () => {
    const ok = base()
    ok.items = [{ id: 'q1', type: 'likert', prompt: 'I felt restless and could not settle', options: [{ id: 'a', label: 'Never', value: 0 }] }]
    ok.scoring.scales[0].items = ['q1']
    expect(validateDefinition(ok, anchors).ok).toBe(true)
  })

  it('rejects a scored item that branching can hide (sum scale)', () => {
    const bad = base()
    bad.items = [
      { id: 'q1', type: 'likert', options: [{ id: 'a', label: 'A', value: 0 }, { id: 'b', label: 'B', value: 4 }] },
      { id: 'q2', type: 'likert', options: [{ id: 'a', label: 'A', value: 0 }], visibleWhen: { ref: 'q1', op: 'gte', value: 3 } },
    ]
    bad.scoring.scales[0].items = ['q1', 'q2']
    expect(validateDefinition(bad, anchors).ok).toBe(false)
  })

  it('rejects a gap between sum-scale bands', () => {
    const bad = base()
    bad.scoring.scales[0].bands = [{ max: 5, label: 'lo', tone: 'neutral' }, { min: 9, label: 'hi', tone: 'attention' }]
    expect(validateDefinition(bad, anchors).ok).toBe(false)
  })

  it('rejects a self-harm slot', () => {
    const bad = base()
    bad.items = [{ id: 'sh', type: 'likert', prompt: 'Thoughts of self-harm today', options: [{ id: 'a', label: 'A', value: 0 }] }]
    bad.scoring.scales[0].items = ['sh']
    expect(validateDefinition(bad, anchors).ok).toBe(false)
  })

  it('rejects clinical/screening band language', () => {
    const bad = base()
    bad.scoring.scales[0].bands = [{ label: 'Positive screen for ADHD', tone: 'attention' }]
    expect(validateDefinition(bad, anchors).ok).toBe(false)
  })

  it('rejects nonDiagnostic !== true', () => {
    const bad = { ...base(), nonDiagnostic: false as unknown as true }
    expect(validateDefinition(bad, anchors).ok).toBe(false)
  })

  it('rejects an unresolved ledger anchor', () => {
    const bad = { ...base(), ledgerRef: 'INSTRUMENTS.md#does-not-exist' }
    expect(validateDefinition(bad, anchors).ok).toBe(false)
  })

  it('every catalog ledger anchor exists in companion/INSTRUMENTS.md', () => {
    const ledger = readFileSync(resolve(process.cwd(), '../INSTRUMENTS.md'), 'utf8')
    for (const anchor of REQUIRED_LEDGER_ANCHORS) {
      expect(ledger.includes(`id="${anchor}"`), `INSTRUMENTS.md missing anchor #${anchor}`).toBe(true)
    }
  })
})
