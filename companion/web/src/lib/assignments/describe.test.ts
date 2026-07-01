import { describe, it, expect } from 'vitest'
import { describeAssignment, describeCapability, describeSetting, describeCadence } from './describe'
import { ALL_CAPABILITIES, SETTING_ALLOWLIST, type Assignment } from './types'

function a(over: Partial<Assignment>): Assignment {
  return {
    assignmentId: 'a1', lineageId: 'l', version: 0, type: 'questionnaire',
    capability: 'assign.questionnaire', payload: { instrumentId: 'wellbeing-selfcheck' },
    issuedAt: 1, authorFingerprint: 'fp', ...over,
  }
}

describe('describeAssignment (non-diagnostic previews)', () => {
  it('describes a questionnaire with a cadence using the catalog title', () => {
    const s = describeAssignment(a({ cadence: { every: 'week', count: 1 } }))
    expect(s).toContain('self-check')
    expect(s).toContain('every week')
    // Non-diagnostic: no clinical framing words.
    expect(s.toLowerCase()).not.toMatch(/diagnos|disorder|screen/)
  })

  it('describes a task', () => {
    const s = describeAssignment(a({ type: 'task', capability: 'assign.task', payload: { taskId: 'steady-attention' } }))
    expect(s).toContain('task')
  })

  it('describes a goal by its title', () => {
    const s = describeAssignment(a({ type: 'goal', capability: 'assign.goal', payload: { title: 'Walk daily' } }))
    expect(s).toContain('Walk daily')
  })

  it('describes a setting via describeSetting', () => {
    const s = describeAssignment(a({ type: 'setting', capability: 'suggest.setting', payload: { key: 'theme', value: 'dark' } }))
    expect(s).toContain('App theme')
    expect(s).toContain('dark')
  })

  it('describes a bundle listing each item', () => {
    const s = describeAssignment(a({
      type: 'largeAssessment', capability: 'assign.largeAssessment',
      payload: { bundle: [{ kind: 'questionnaire', id: 'wellbeing-selfcheck' }, { kind: 'task', id: 'steady-attention' }] },
    }))
    expect(s).toContain('bundle')
  })
})

describe('describeCadence', () => {
  it('handles singular and plural', () => {
    expect(describeCadence({ every: 'week', count: 1 })).toBe('every week')
    expect(describeCadence({ every: 'week', count: 2 })).toBe('every 2 weeks')
    expect(describeCadence(undefined)).toBe('')
  })
})

describe('describeCapability', () => {
  it('returns a title + desc for every capability', () => {
    for (const cap of ALL_CAPABILITIES) {
      const { title, desc } = describeCapability(cap)
      expect(title.length).toBeGreaterThan(0)
      expect(desc.length).toBeGreaterThan(0)
    }
  })

  it('notes that suggest.setting is never automatic', () => {
    expect(describeCapability('suggest.setting').desc.toLowerCase()).toContain('never automatic')
  })
})

describe('describeSetting', () => {
  it('renders every allowlisted key with a human label (not the raw key)', () => {
    for (const key of SETTING_ALLOWLIST) {
      const s = describeSetting(key, 'x')
      expect(s).toContain('→')
      expect(s).not.toMatch(new RegExp(`^${key}\\b`)) // uses a human label, not the raw key
    }
  })

  it('formats booleans as on/off', () => {
    expect(describeSetting('theme', true)).toContain('on')
    expect(describeSetting('theme', false)).toContain('off')
  })
})
