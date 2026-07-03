import { describe, it, expect } from 'vitest'
import { auditActionLabel, auditActorLabel } from './auditLabels'

describe('audit label mapping', () => {
  it('maps every documented server action code to a human-readable label', () => {
    for (const action of [
      'auth.success',
      'auth.fail',
      'lockout',
      'enrol.ok',
      'share.open',
      'gameplan.open',
      'assignment.publish',
      'gameplan.publish',
      'session.expired',
    ]) {
      expect(auditActionLabel(action)).not.toBe(action)
    }
  })

  it('falls back to the raw code for an unrecognized action rather than hiding it', () => {
    expect(auditActionLabel('some.future.event')).toBe('some.future.event')
  })

  it('labels actor roles', () => {
    expect(auditActorLabel('owner')).toBe('You')
    expect(auditActorLabel('therapist')).toBe('Your therapist')
  })
})
