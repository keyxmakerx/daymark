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
      'pairing.opened',
      'pairing.responded',
      'pairing.cancelled',
      'pair.guess_failed',
      'invite.reported',
      'relationship.ended',
    ]) {
      expect(auditActionLabel(action)).not.toBe(action)
    }
  })

  it('falls back to the raw code for an unrecognized action rather than hiding it', () => {
    expect(auditActionLabel('some.future.event')).toBe('some.future.event')
  })

  it('names the clinician’s own ending without narrating why', () => {
    // The server cannot know whether somebody retired, moved practice, or fell out with the person,
    // and the label must not imply it does. It also must not read as the owner's own revoke.
    const label = auditActionLabel('relationship.ended')
    expect(label).toContain('Ended their access')
    expect(label).not.toMatch(/revoked|left the|quit|resigned|removed/i)
    // Control: the same search fires on labels that do narrate or borrow the owner's word.
    expect('Revoked their access').toMatch(/revoked|left the|quit|resigned|removed/i)
    expect('Your therapist left the practice').toMatch(/revoked|left the|quit|resigned|removed/i)
  })

  it('labels actor roles', () => {
    expect(auditActorLabel('owner')).toBe('You')
    expect(auditActorLabel('therapist')).toBe('Your therapist')
  })
})
