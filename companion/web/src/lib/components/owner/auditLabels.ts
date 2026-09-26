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
  // What you share, not "a report": a share is access, and a report is a copy (#305, #337).
  'share.open': 'Opened what you share',
  'gameplan.open': 'Opened a game plan',
  'assignment.publish': 'Sent a new assignment',
  'gameplan.publish': 'Sent a new game plan',
  'session.expired': 'Session expired',
  // Pairing (COMPANION_PAIRING.md §6–§7). A wrong code is never an event: the server cannot see
  // one. What it can see is a wrong invitation LINK being tried, and the run-level moves.
  'pairing.opened': 'Started a pairing with a new code',
  'pairing.responded': 'Answered the pairing code',
  'pairing.cancelled': 'Cancelled a pairing',
  'pair.guess_failed': 'A wrong invitation link was tried',
  'invite.reported': 'Ended an invitation',
  /*
   * The one line in this log the owner did not cause (issue #91).
   *
   * "Ended their access" rather than "left" or "revoked". Left is a story about why, which nothing
   * here knows; revoked is the owner's own word for their own act and would read as something they
   * did. This says what happened and nothing about what it meant.
   *
   * The actor label already reads "Your clinician", so the two together say the whole fact.
   */
  'relationship.ended': 'Ended their access to what you share',
}

export function auditActionLabel(action: string): string {
  return ACTION_LABELS[action] ?? action
}

export function auditActorLabel(actor: string): string {
  return actor === 'owner' ? 'You' : 'Your clinician'
}
