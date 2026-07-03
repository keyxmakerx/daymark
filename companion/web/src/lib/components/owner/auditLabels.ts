/**
 * Human-readable labels for the owner-readable audit log (COMPANION_SECURITY.md §9). Pure
 * mapping only — the server is the source of truth for the fixed action/actor vocabulary;
 * an unrecognized code is shown verbatim rather than hidden, so nothing is silently dropped.
 */
const ACTION_LABELS: Record<string, string> = {
  'auth.success': 'Signed in',
  'auth.fail': 'Failed sign-in attempt',
  lockout: 'Sign-in temporarily locked (too many attempts)',
  'enrol.ok': 'Enrolled a new sign-in credential',
  'share.open': 'Opened a shared report',
  'gameplan.open': 'Opened a game plan',
  'assignment.publish': 'Sent a new assignment',
  'gameplan.publish': 'Sent a new game plan',
  'session.expired': 'Session expired',
}

export function auditActionLabel(action: string): string {
  return ACTION_LABELS[action] ?? action
}

export function auditActorLabel(actor: string): string {
  return actor === 'owner' ? 'You' : 'Your therapist'
}
