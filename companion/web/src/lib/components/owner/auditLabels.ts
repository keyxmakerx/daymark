/**
 * Human-readable labels for the owner-readable audit log (COMPANION_SECURITY.md §9). Pure
 * mapping only — the server is the source of truth for the fixed action/actor vocabulary;
 * an unrecognized code is shown verbatim rather than hidden, so nothing is silently dropped.
 * auditLabels.test.ts reads that vocabulary from the server's own source and fails for any
 * action it can write to a relationship's log that has no line here.
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
  /*
   * The owner's own act, in the word on the button they pressed (#277). Never "Ended their
   * access": that is the clinician's line below, and the two must not read as one event. The
   * sentence about what revoking cannot reach belongs at the click (REVOKE_CAVEAT), not here.
   */
  'share.revoke': 'Revoked sharing',
  // Declared by the server and written by no route yet (COMPANION_SECURITY.md §9, #164).
  'share.denied': 'Could not open a share that had expired or been revoked',
  // Pairing (COMPANION_PAIRING.md §6–§7). A wrong code is never an event: the server cannot see
  // one. What it can see is a wrong invitation LINK being tried, and the run-level moves.
  'pairing.opened': 'Started a pairing with a new code',
  'pairing.responded': 'Answered the pairing code',
  'pairing.cancelled': 'Cancelled a pairing',
  'pairing.approved': 'Approved a pairing',
  // The button says "Take the approval back": nobody finished setting up, and the invitation is
  // open again. Not a report of a stranger, which is "Ended an invitation".
  'pairing.abandoned': 'Took back an approval',
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
  /*
   * Keys moving between the two of you. Each line is a receipt for a delivery, never a statement
   * that the right keys arrived: the server relays keys and vouches for none (COMPANION_SECURITY.md
   * §9). A second send is refused whoever made it and why, and the first keys stay, so the refusal
   * says that and guesses no reason. The owner's lines use the words of the owner's own buttons.
   */
  'therapist_key.registered': 'Published their keys',
  'therapist_key.refused': 'Published keys again; the first ones were kept',
  'therapist_key.fetched': "Read your clinician's published keys",
  'owner_key.registered': 'Sent your key to this server',
  'owner_key.refused': 'Sent your key again; the first one was kept',
  'owner_key.fetched': 'Read your published key',
  /*
   * Phones (#186, #189), written to the owner's own log rather than a relationship's. A receipt for
   * the console's Confirm and Revoke, in the words the re-issue's warning uses ("This disconnects
   * every paired phone"): the server holds the key it was handed and vouches for nothing about it.
   */
  'device.registered': 'Paired a phone',
  'device.revoked': 'Disconnected a phone',
}

export function auditActionLabel(action: string): string {
  return ACTION_LABELS[action] ?? action
}

export function auditActorLabel(actor: string): string {
  return actor === 'owner' ? 'You' : 'Your clinician'
}
