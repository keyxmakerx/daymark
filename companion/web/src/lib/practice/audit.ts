/*
 * READING A PRACTICE'S OWN LOG — labels for the control-plane audit chain.
 *
 * The server writes six actions against a practice (`AuditStore.AuditAction`, the ORG_* entries)
 * and four actor kinds. This maps them to something a person can read, and does three things
 * carefully:
 *
 *   AN UNKNOWN CODE IS SHOWN VERBATIM. A server that has moved ahead of this page writes actions
 *   this build has never heard of, and the wrong answer is to hide them or to fold them into
 *   "other". A log with rows missing is worse than a log with rows a reader has to look up: the
 *   whole value of the thing is that it is the complete record of what the server did. Same
 *   posture as owner/auditLabels.ts, which set the precedent for this surface.
 *
 *   THE LABELS DO NOT PROMOTE THE EVENTS. "Member seated with a role" rather than "Access
 *   granted"; "Member removed from the practice" rather than "Access revoked". Both of the tempting
 *   phrasings would describe an act on the data plane, and every one of these entries is
 *   control-plane metadata. The server's own file is explicit that the removal copy must say
 *   "removed from the practice" and never "access revoked", and a log label is product copy.
 *
 *   ROLE NAMES IN METADATA GO THROUGH THE ROLE CATALOG. An entry's `role`, `from` and `to`
 *   annotations carry wire values (`org_admin`, `front_desk`). Rendering them raw is unreadable;
 *   rendering them through a second, hand-typed lookup is a way for the log to start disagreeing
 *   with the roster on the same screen. They go through roleLabel, which reads the catalog.
 */
import type { OrgAuditEvent } from './client'
import { roleLabel } from './orgRoles'

/**
 * One line per action the org routes append. Each says what HAPPENED, in the plane it happened in.
 *
 * `org.action_denied` is the one worth keeping legible: a refusal is the more informative half of
 * this log — routine membership changes are routine, while an account repeatedly attempting acts
 * its role does not carry is either a broken client or somebody finding the wall, and the server
 * cannot tell those apart. It refuses both identically and writes the line so a person can ask.
 */
export const ORG_ACTION_LABEL: Record<string, string> = {
  'org.created': 'Practice created, first administrator seated',
  'org.member_added': 'Member seated with a role',
  'org.member_accepted': 'Member accepted their own seat',
  'org.member_role_changed': 'Member’s role changed',
  'org.member_removed': 'Member removed from the practice',
  'org.action_denied': 'An action was refused',
}

/** Who acted. Four kinds, and the distinction between the middle two is what makes a refusal legible. */
export const ORG_ACTOR_LABEL: Record<string, string> = {
  org_admin: 'A practice administrator',
  org_member: 'A practice member',
  platform: 'The server’s provisioning credential',
  owner: 'The owner',
  therapist: 'A clinician',
}

export function orgAuditActionLabel(action: string): string {
  return ORG_ACTION_LABEL[action] ?? action
}

export function orgAuditActorLabel(actor: string): string {
  return ORG_ACTOR_LABEL[actor] ?? actor
}

/**
 * The annotations on one entry, as label/value pairs ready to render.
 *
 * WHAT THIS DELIBERATELY DOES NOT DO: drop keys it does not recognise. The meta map is small, fixed
 * and non-content by construction — member ids, role names, a session count, and the source address
 * only where the operator opted in — so there is nothing here to protect a reader from, and hiding
 * an unfamiliar key would mean this page decides what part of the record a person may see.
 *
 * Order is the map's own insertion order, which is the order the server built it in: the acting
 * party first, then what changed. Sorting alphabetically would put `actor` after `from`, which
 * reads as though the log were describing the change before saying who made it.
 */
export function orgAuditAnnotations(event: OrgAuditEvent): { key: string; label: string; value: string }[] {
  const meta = event.meta
  if (!meta) return []
  return Object.entries(meta).map(([key, raw]) => ({
    key,
    label: META_LABEL[key] ?? key,
    // Role-shaped values go through the catalog; everything else is passed through untouched,
    // because it is an id or a count and this page has nothing to add to it.
    value: ROLE_VALUED_KEYS.has(key) ? roleLabel(raw) : raw,
  }))
}

/** The annotation keys the org routes write, named. Anything else is rendered under its own key. */
const META_LABEL: Record<string, string> = {
  actor: 'Acting member',
  role: 'Role',
  from: 'Role before',
  to: 'Role after',
  sessionsCut: 'Portal sessions ended',
  ip: 'Source address',
}

/** Annotation keys whose value is a role's wire name. */
const ROLE_VALUED_KEYS = new Set(['role', 'from', 'to'])

/**
 * What an entry is about — the `objectRef`.
 *
 * Left as the raw identifier, with a label saying what kind of identifier it is per action, because
 * the subject differs: for a membership event it is the member's id, and for a refusal it is the
 * name of the ACTION that was refused, which would be actively misleading rendered under "Member".
 */
export function orgAuditSubjectLabel(action: string): string {
  return action === 'org.action_denied' ? 'Refused action' : 'Member'
}
