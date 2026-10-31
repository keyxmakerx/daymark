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
 * is the file that does it, and built.test.ts checks every one exists, so a removed screen cannot
 * leave a duty still claimed as built.
 */
import type { PracticeCapability } from './capabilities'

export const BUILT: Partial<Record<PracticeCapability, string>> = {
  // The practice console (OrgRoutes.kt).
  'member.manage': 'companion/web/src/lib/components/practice/AddMemberPanel.svelte',
  'role.assign': 'companion/web/src/lib/components/practice/RosterPanel.svelte',
  'audit.review': 'companion/web/src/lib/components/practice/AuditPanel.svelte',
  // The clinician console, for a client who granted it.
  'assign.questionnaire': 'companion/web/src/lib/components/therapist/AssignSurface.svelte',
  'assign.task': 'companion/web/src/lib/components/therapist/AssignSurface.svelte',
  'assign.goal': 'companion/web/src/lib/components/therapist/AssignSurface.svelte',
  'assign.reminder': 'companion/web/src/lib/components/therapist/AssignSurface.svelte',
  'authorGamePlan': 'companion/web/src/lib/therapist/gamePlan.ts',
  // The server console.
  'server.operate': 'companion/web/src/lib/components/admin/AdminConsole.svelte',
  'ops.metrics': 'companion/web/src/lib/components/admin/AdminConsole.svelte',
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
