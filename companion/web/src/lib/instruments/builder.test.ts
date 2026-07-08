import { describe, it, expect } from 'vitest'
import { newDraft, compileDraft, validateDraft, scoredItemIds, type ToolDraft, type DraftItem } from './builder'
import { scoreInstrument } from './scoring'

function likert(id: string): DraftItem {
  return { id, type: 'likert', prompt: `Question ${id}`, required: true, options: [{ id: 'a', label: 'No', value: 0 }, { id: 'b', label: 'Yes', value: 2 }] }
}

/** A minimal, valid Custom draft. */
function customDraft(): ToolDraft {
  const d = newDraft()
  d.instrumentId = 'my-wind-down'
  d.title = 'Evening wind-down'
  d.items = [likert('q1'), likert('q2')]
  return d
}

describe('tool builder', () => {
  it('compiles a valid custom draft that passes the honesty gate', () => {
    const { ok, errors } = validateDraft(customDraft())
    expect(ok, errors.join('; ')).toBe(true)
  })

  it('emits a single scale over the scoreable items and scores correctly', () => {
    const def = compileDraft(customDraft())
    expect(def.scoring.scales).toHaveLength(1)
    expect(def.scoring.scales[0].items).toEqual(['q1', 'q2'])
    expect(scoreInstrument(def, { q1: 'b', q2: 'b' })[0].score).toBe(4)
  })

  it('a reflection tool with no scoreable items emits no scale but still validates', () => {
    const d = newDraft()
    d.instrumentId = 'reflect'
    d.title = 'Reflect'
    d.items = [{ id: 'note', type: 'freeText', prompt: 'Anything you want to note?' }]
    expect(compileDraft(d).scoring.scales).toHaveLength(0)
    expect(validateDraft(d).ok).toBe(true)
  })

  it('a custom tool without a non-diagnostic disclaimer in its intro fails the gate', () => {
    const d = customDraft()
    d.intro = 'A quick check-in on your evening.'
    expect(validateDraft(d).ok).toBe(false)
  })

  it('a validated tool requires both a source and a ledgerRef', () => {
    const d = customDraft()
    d.provenance = { tier: 'validated' }
    expect(validateDraft(d).ok).toBe(false) // no source, no ledgerRef
    d.provenance = { tier: 'validated', source: 'PHQ-9 (Kroenke et al., 2001)' }
    d.ledgerRef = 'INSTRUMENTS.md#phq-9'
    expect(validateDraft(d).ok).toBe(true) // format-only check when no anchor set supplied
  })

  it('rejects a forbidden (licensed) source pasted into an item', () => {
    const d = customDraft()
    d.items = [likert('q1'), { id: 'q2', type: 'likert', prompt: 'Item taken from the PANAS scale', required: true, options: [{ id: 'a', label: 'x', value: 0 }] }]
    expect(validateDraft(d).ok).toBe(false)
  })

  it('rejects a self-harm item even in a custom tool', () => {
    const d = customDraft()
    d.items = [likert('q1'), { id: 'sh', type: 'likert', prompt: 'Thoughts of self-harm', required: true, options: [{ id: 'a', label: 'x', value: 0 }] }]
    expect(validateDraft(d).ok).toBe(false)
  })

  it('scoredItemIds excludes info, free-text, and opted-out items', () => {
    const d = customDraft()
    d.items.push({ id: 'info', type: 'info', body: 'intro panel' }, { id: 'note', type: 'freeText', prompt: 'note' }, { id: 'q3', type: 'likert', options: [{ id: 'a', label: 'x', value: 0 }], excludeFromScoring: true })
    expect(scoredItemIds(d)).toEqual(['q1', 'q2'])
  })

  it('rejects clinical/screening language in a band label (custom tools can\'t pose as clinical)', () => {
    const d = customDraft()
    d.bands = [{ label: 'Positive screen for anxiety', tone: 'attention' }]
    expect(validateDraft(d).ok).toBe(false)
  })
})
