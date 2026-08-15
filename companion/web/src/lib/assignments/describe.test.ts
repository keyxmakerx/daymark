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

describe('describeAssignment — questionnaire phrasing', () => {
  const q = (instrumentId: string, cadence?: Assignment['cadence']) =>
    describeAssignment(a({ payload: { instrumentId }, ...(cadence ? { cadence } : {}) }))

  // Regression: the noun was appended unconditionally, so every shipped catalog title —
  // all of which already end in "self-check" — rendered "... self-check self-check".
  it('never doubles the noun for a title that already ends in "self-check"', () => {
    for (const id of ['wellbeing-selfcheck', 'focus-selfcheck']) {
      const s = q(id, { every: 'week', count: 1 })
      expect(s).not.toMatch(/self-check\s+self-check/i)
      expect(s.match(/self-check/gi)).toHaveLength(1)
    }
  })

  it('appends the noun for a title that does not already end in "self-check"', () => {
    // Unknown ids fall back to the raw id as the title — stands in for a future instrument.
    expect(q('Evening wind-down')).toBe('Assign the Evening wind-down self-check.')
    expect(q('Evening wind-down', { every: 'week', count: 1 })).toBe(
      'Assign the Evening wind-down self-check, every week.',
    )
    expect(q('Evening wind-down', { every: 'day', count: 3 })).toBe(
      'Assign the Evening wind-down self-check, every 3 days.',
    )
  })

  it('renders every catalog instrument × cadence form exactly', () => {
    const cases: Array<[string, Assignment['cadence'], string]> = [
      ['wellbeing-selfcheck', undefined, 'Assign the Daily wellbeing self-check.'],
      ['wellbeing-selfcheck', { every: 'day', count: 1 }, 'Assign the Daily wellbeing self-check, every day.'],
      ['wellbeing-selfcheck', { every: 'week', count: 1 }, 'Assign the Daily wellbeing self-check, every week.'],
      ['wellbeing-selfcheck', { every: 'month', count: 1 }, 'Assign the Daily wellbeing self-check, every month.'],
      ['wellbeing-selfcheck', { every: 'day', count: 3 }, 'Assign the Daily wellbeing self-check, every 3 days.'],
      ['wellbeing-selfcheck', { every: 'week', count: 2 }, 'Assign the Daily wellbeing self-check, every 2 weeks.'],
      ['wellbeing-selfcheck', { every: 'month', count: 2 }, 'Assign the Daily wellbeing self-check, every 2 months.'],
      ['focus-selfcheck', undefined, 'Assign the Focus & follow-through self-check.'],
      ['focus-selfcheck', { every: 'day', count: 1 }, 'Assign the Focus & follow-through self-check, every day.'],
      ['focus-selfcheck', { every: 'week', count: 1 }, 'Assign the Focus & follow-through self-check, every week.'],
      ['focus-selfcheck', { every: 'month', count: 1 }, 'Assign the Focus & follow-through self-check, every month.'],
      ['focus-selfcheck', { every: 'day', count: 3 }, 'Assign the Focus & follow-through self-check, every 3 days.'],
      ['focus-selfcheck', { every: 'week', count: 2 }, 'Assign the Focus & follow-through self-check, every 2 weeks.'],
      ['focus-selfcheck', { every: 'month', count: 2 }, 'Assign the Focus & follow-through self-check, every 2 months.'],
    ]
    for (const [id, cadence, expected] of cases) expect(q(id, cadence)).toBe(expected)
  })

  it('keeps the sentence well-formed: no doubled spaces, one trailing period', () => {
    for (const id of ['wellbeing-selfcheck', 'focus-selfcheck', 'Evening wind-down', '']) {
      for (const cadence of [undefined, { every: 'week', count: 1 } as const, { every: 'week', count: 2 } as const]) {
        const s = q(id, cadence)
        expect(s).not.toMatch(/ {2}/)
        expect(s).toMatch(/^Assign the .+\.$/)
        expect(s.toLowerCase()).not.toMatch(/diagnos|disorder|screen/)
      }
    }
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
