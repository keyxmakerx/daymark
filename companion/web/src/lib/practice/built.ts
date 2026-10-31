/*
 * WHICH ROLE DUTIES THIS BUILD ACTUALLY DOES.
 *
 * The role catalog (roles.ts) transcribes what each role manages in docs/COMPANION_ACCESS_CONTROL.md
 * § Role catalog. Much of it has no route and no screen yet, and a role card that lists a duty
 * without saying so reads as a promise: the office walkthrough's front desk was offered "Manage the
 * calendar" on a server where a front desk can only read the roster. So every duty the picker shows
 * is either named here, with the source file that does it, or labelled "Not built". Practice mode
 * is frozen at that (the maintainer's 2026-10-30 sign-off); building a duty adds it here.
 *
 * A duty is here only when a person can do it today from a console this server serves. Each value
 * names that console, and built.test.ts reads the console's source for the call that does it, so
 * a removed screen cannot leave a duty still claimed as built.
 */
import type { PracticeCapability } from './capabilities'

export type BuiltIn = 'practice console' | 'clinician console' | 'server console'

export const BUILT: Partial<Record<PracticeCapability, BuiltIn>> = {
  'member.manage': 'practice console',
  'role.assign': 'practice console',
  'audit.review': 'practice console',
  // For a client who granted it.
  'assign.questionnaire': 'clinician console',
  'assign.task': 'clinician console',
  'assign.goal': 'clinician console',
  'assign.reminder': 'clinician console',
  authorGamePlan: 'clinician console',
  'server.operate': 'server console',
  'ops.metrics': 'server console',
}

/** The chip a duty with no route and no screen carries, wherever a role's duties are listed. */
export const NOT_BUILT_CHIP = 'Not built'

/** Said once beneath a role's duties when any of them is not built. */
export const NOT_BUILT_MEANS =
  'Duties marked "Not built" have no screen on this server yet. Holding the role does not let ' +
  'anyone do them.'

export function isBuilt(id: PracticeCapability): boolean {
  return BUILT[id] !== undefined
}
