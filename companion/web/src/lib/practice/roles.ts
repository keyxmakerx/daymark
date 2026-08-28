/*
 * THE ROLE CATALOG — docs/COMPANION_ACCESS_CONTROL.md § Role catalog, transcribed as data.
 *
 * ─── WHY A ROLE IS A PRESET AND NOT A PERMISSION ────────────────────────────────────────────────
 *
 * The specification opens its role table with the sentence the whole clinical layer hangs on:
 *
 *     "Roles gate actions (server-enforced). Read capability is separate and comes only from a
 *      patient grant. 'Can read clinical content?' below means is normally granted a key, not is
 *      technically permitted to hold one by role."
 *
 * So a role is a PRESET over the capability vocabulary in capabilities.ts — a named bundle of
 * actions the practice's RBAC allows a member to perform at all — and it is emphatically not a
 * second, parallel notion of permission sitting next to the owner-signed Grant that already
 * ships. Two consequences follow, and both are enforced by threePlane.test.ts rather than merely
 * described here:
 *
 *   A ROLE NEVER CARRIES A KEY. Every read scope in the catalog is authorization 'grant-only',
 *   and no preset below contains one. Membership in a practice, holding an admin title, being on
 *   someone's care team by the practice's say-so — none of them decrypt anything. The patient's
 *   signature does, and only the patient's signature does.
 *
 *   THE PATIENT IS NOT OWNED BY THE ORG. § Orgs: "A patient/client is not owned by the org. They
 *   own their keys; the org is a membership/addressing convenience." That is why the patient is a
 *   row in this table at all — they are a participant with capabilities of their own, sitting at
 *   the root of every grant, not a record the practice administers.
 *
 * ─── WHY `planes` IS DECLARED AND NOT COMPUTED ──────────────────────────────────────────────────
 *
 * It would be tidier to derive each role's planes from the planes of its capabilities. It would
 * also gut the test. The rule under scrutiny is
 *
 *     "admins live in the control and monitoring planes, never the data plane"
 *
 * and a derived field turns that into a tautology: give the org admin a read capability and its
 * computed planes would silently grow a 'data' entry, at which point it is no longer an
 * admin-plane-only role and the assertion skips it. The violation would erase the evidence of
 * itself. Declared, the field is a statement of DESIGN INTENT that the capabilities are then
 * checked against, and adding that read capability breaks two assertions instead of none.
 *
 * ─── HOW MUCH OF THIS IS TRANSCRIPTION AND HOW MUCH IS JUDGEMENT ────────────────────────────────
 *
 * `docLabel`, `managesSummary` and `readsSummary` are transcription, verbatim, and the test
 * compares them cell-by-cell against the document at run time — if the table is edited, the suite
 * names the cell that moved. `manages` is judgement: the specification's "Manages" column is a
 * phrase, not a list of ids, so each preset is this file's reading of that phrase plus the other
 * sections that name the same role (§ Behavioral guard puts guard review on the org admin;
 * § Cross-provider sharing puts referral requests on clinicians). Where the reading is a choice
 * rather than a transcription, the choice is argued at the role.
 */
import type { PracticeCapability, Plane } from './capabilities'
import { CAPABILITIES, mintsReadForOthers, readsOthersClinical, readsAnyClinical } from './capabilities'

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   1. The eight roles.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

export type RoleId =
  | 'patient'
  | 'clinician'
  | 'psychiatrist'
  | 'assistant'
  | 'front-desk'
  | 'supervisor'
  | 'org-admin'
  | 'platform-sysadmin'

/**
 * The third column of § Role catalog — "Normally reads clinical content?" — as five values,
 * because the column has five distinct answers and flattening them loses the two that matter.
 *
 *   own-data                "Their own data — root of trust". The patient. Not a grant; they hold
 *                           the key because it is theirs.
 *   granted                 "Yes — for clients who granted them". The word doing the work is
 *                           GRANTED: the answer is yes about what usually happens, not about what
 *                           the title confers.
 *   granted-narrowed        "Narrowed — only what's granted". Minimum necessary, one step tighter.
 *   never                   "No". Front desk, org admin, platform sysadmin. This is the value the
 *                           central claim is made of.
 *   only-by-separate-grant  "Only via explicit, consented grant (clinical supervision), never by
 *                           title". The supervisor, and the reason this enum is not a boolean: a
 *                           supervisor CAN come to read, and never because they are a supervisor.
 */
export type ReadPosture = 'own-data' | 'granted' | 'granted-narrowed' | 'never' | 'only-by-separate-grant'

export interface PracticeRole {
  readonly id: RoleId
  /**
   * The role's first-cell label in § Role catalog, bold markers stripped. This is the join key
   * between the code and the document, so it is written to match exactly — the completeness test
   * fails if the table lists a role this file does not model, or the reverse.
   */
  readonly docLabel: string
  /** What a person is called on screen. Shorter than docLabel where the doc gives two names. */
  readonly label: string
  /**
   * The planes this role inhabits BY TITLE. Design intent, checked against `manages` — see the
   * file header for why this is not derived.
   */
  readonly planes: readonly Plane[]
  /** The preset: what role membership alone lets a member do. */
  readonly manages: readonly PracticeCapability[]
  /** § Role catalog, "Manages" cell, verbatim. */
  readonly managesSummary: string
  /** § Role catalog, "Normally reads clinical content?" cell, as a machine value. */
  readonly reads: ReadPosture
  /** § Role catalog, "Normally reads clinical content?" cell, verbatim. */
  readonly readsSummary: string
}

/**
 * The catalog, in the document's own row order so the two read side by side.
 */
export const ROLES: readonly PracticeRole[] = [
  {
    id: 'patient',
    docLabel: 'Patient / owner',
    label: 'Patient',
    // The only role in all three planes, and the only one that may be: they hold their own key
    // (data), decide every grant and revocation (control), and read their own access log
    // (monitoring). That is not a god admin — a god admin is someone who administers OTHERS and
    // can also read them. This person administers and reads exactly one record, their own.
    planes: ['data', 'control', 'monitoring'],
    // "their own keys, grants, consent, audit view" maps one-to-one: keys -> keys.manage;
    // grants -> grant.issue + grant.revoke, split because § The annoyance budget prices them
    // differently on purpose and one capability cannot hold two prices; consent ->
    // consent.orgTeam; audit view -> audit.viewOwn. Pruning a care-team roster is grant.revoke
    // rather than a capability of its own, so the safe direction is priced once and cheaply.
    manages: ['keys.manage', 'grant.issue', 'grant.revoke', 'consent.orgTeam', 'audit.viewOwn'],
    managesSummary: 'their own keys, grants, consent, audit view',
    reads: 'own-data',
    readsSummary: 'Their own data — root of trust',
  },
  {
    id: 'clinician',
    docLabel: 'Psychologist / clinician',
    label: 'Clinician',
    // Data plane because authoring clinical content is data-plane work; control plane because
    // referrals and care relationships are control-plane objects that move no key.
    planes: ['data', 'control'],
    // "assignments, notes, plans for granted clients" — the five assign.*, notes.author, and
    // authorGamePlan, all of which are 'role-and-grant': the role is the ceiling, the patient's
    // grant is the authorisation. referral.request comes from § Cross-provider sharing ("any
    // clinician may request that another see a client's data"), care.relationship.manage from
    // § Clinician turnover. Note what is absent: no read capability of any kind. A clinician who
    // has not been granted one reads nothing, and this list is why.
    manages: [
      'assign.questionnaire',
      'assign.task',
      'assign.largeAssessment',
      'assign.reminder',
      'assign.goal',
      'authorGamePlan',
      'suggest.setting',
      'notes.author',
      'referral.request',
      'care.relationship.manage',
    ],
    managesSummary: 'assignments, notes, plans for granted clients',
    reads: 'granted',
    readsSummary: 'Yes — for clients who granted them',
  },
  {
    id: 'psychiatrist',
    docLabel: 'Psychiatrist',
    label: 'Psychiatrist',
    planes: ['data', 'control'],
    // "same as clinician; may publish Validated/Adapted tools" — so, literally the clinician
    // preset plus one. The publishing capability is a provenance claim, not a read scope;
    // instruments/builder.ts already notes that gating it belongs at the RBAC layer.
    manages: [
      'assign.questionnaire',
      'assign.task',
      'assign.largeAssessment',
      'assign.reminder',
      'assign.goal',
      'authorGamePlan',
      'suggest.setting',
      'notes.author',
      'referral.request',
      'care.relationship.manage',
      'tool.publishValidated',
    ],
    managesSummary: 'same as clinician; may publish Validated/Adapted tools',
    reads: 'granted',
    readsSummary: 'Yes — for granted clients',
  },
  {
    id: 'assistant',
    docLabel: 'Therapist assistant',
    label: 'Therapist assistant',
    // Data plane only: an assistant supports one clinician's work with granted clients and holds
    // no practice-level authority at all. Nothing to invite, nothing to revoke, no log to review.
    planes: ['data'],
    // JUDGEMENT, flagged as such. § Consent model says only that "an assistant's [grant] is
    // narrower than the clinician's" and does not enumerate the narrowing, so this is a reading:
    // the assistant may put routine self-checks and tasks in front of a client, and may not author
    // the clinical record (no notes.author), write the plan (no authorGamePlan), propose app
    // settings, or move care relationships. If a practice disagrees, this is the line to argue
    // with — it is a choice, and it is written down where the argument can happen.
    manages: ['assign.questionnaire', 'assign.task', 'assign.reminder'],
    managesSummary: "supports a clinician's work",
    reads: 'granted-narrowed',
    readsSummary: "Narrowed — only what's granted",
  },
  {
    id: 'front-desk',
    docLabel: 'Front desk',
    label: 'Front desk',
    // Control plane only. This is one of the three rows the central claim is made of, and the row
    // that answers the question the design document records as recurring: "attendants who only
    // handle the time/scheduling piece?" — yes, and they read no notes.
    planes: ['control'],
    // "scheduling, invites, membership logistics". member.logistics is deliberately a separate
    // capability from member.manage: the front desk does the paperwork of somebody joining
    // without holding the authority to admit, remove or re-role them. That authority is the org
    // admin's, and § Orgs is explicit that membership is what "drives access".
    //
    // read.scheduleMetadata is NOT here even though § Consent model says "a front-desk grant is
    // scheduling metadata" — because that is a GRANT. Even the least sensitive read in the whole
    // system is not conferred by a title.
    manages: ['schedule.manage', 'member.invite', 'member.logistics'],
    managesSummary: 'scheduling, invites, membership logistics',
    reads: 'never',
    readsSummary: 'No — scheduling metadata only, no notes',
  },
  {
    id: 'supervisor',
    docLabel: 'Supervisor',
    label: 'Supervisor',
    // Control plane only, and this is the row most likely to be "fixed" by someone who finds it
    // inconvenient. A supervisor plainly needs to see a supervisee's work — and the document is
    // categorical that they get there by a grant the client signed for clinical supervision,
    // "never by title". So: no data plane by title.
    planes: ['control'],
    // team.oversee is the whole of what the title confers. read.supervision exists in the
    // vocabulary and is deliberately absent from this list; threePlane.test.ts asserts that
    // absence by name, because "the supervisor role does not include the supervision read scope"
    // is the single most load-bearing sentence in this file.
    manages: ['team.oversee'],
    managesSummary: 'oversees a team of clinicians',
    reads: 'only-by-separate-grant',
    readsSummary: 'Only via explicit, consented grant (clinical supervision), never by title',
  },
  {
    id: 'org-admin',
    docLabel: 'Org admin',
    label: 'Practice admin',
    // Control and monitoring, never data. THIS IS THE CLAIM. Everything else in these two files
    // exists so that this row can be checked rather than asserted: an org admin can revoke anyone
    // (access.revoke, access.killSwitch) and see who accessed what (audit.review), and holds not
    // one capability that opens a single clinical note.
    planes: ['control', 'monitoring'],
    // "practice membership, roles, revocation, audit review", scoped to this practice — § Orgs is
    // explicit that this is "not a global super-admin". guard.review is added from § Behavioral
    // guard, whose response to a signal is to "freeze pending admin review"; that admin is this
    // one, and the guard watches behaviour rather than content, so it stays in the monitoring
    // plane where this role already lives.
    manages: [
      'member.invite',
      'member.logistics',
      'member.manage',
      'role.assign',
      'access.revoke',
      'access.killSwitch',
      'audit.review',
      'guard.review',
    ],
    managesSummary: 'practice membership, roles, revocation, audit review',
    reads: 'never',
    readsSummary: 'No — control/monitoring only',
  },
  {
    id: 'platform-sysadmin',
    docLabel: 'Platform sysadmin',
    label: 'Platform sysadmin',
    // Control and monitoring. The sysadmin stores every byte of ciphertext and holds no key, which
    // is precisely why storing the data is not being in the data plane: the plane is defined by
    // who can open the content, not by whose disk it sits on. § Honest limits keeps this honest in
    // the other direction — metadata leaks even when content does not.
    planes: ['control', 'monitoring'],
    manages: ['server.operate', 'ops.metrics'],
    managesSummary: 'runs the server/infra',
    reads: 'never',
    readsSummary: 'No — by design. Ciphertext + ops metadata only',
  },
]

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   2. Reading the catalog.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

export const ROLE_IDS: readonly RoleId[] = ROLES.map((r) => r.id)

export function roleById(id: RoleId): PracticeRole {
  const found = ROLES.find((r) => r.id === id)
  // Unreachable while RoleId and ROLES agree, and catalog.test.ts asserts they do. Throwing beats
  // returning undefined: a caller that silently got no role would render an empty permission list,
  // which reads as "this person can do nothing" — a confident and wrong answer.
  if (!found) throw new Error(`unknown practice role: ${id}`)
  return found
}

/**
 * A role that lives only in the control and/or monitoring planes — an ADMIN, in the sense
 * § The three planes uses the word. The predicate the central claim is quantified over.
 *
 * Note that it is written as "does not inhabit the data plane" rather than as a list of admin-ish
 * role ids. A list would have to be maintained; this cannot fall behind a role added next year.
 */
export function isAdminPlaneOnly(role: PracticeRole): boolean {
  return !role.planes.includes('data')
}

/** Capabilities in this role's preset that read someone ELSE's clinical content. Must be empty. */
export function readClinicalCapabilitiesIn(role: PracticeRole): PracticeCapability[] {
  return role.manages.filter(readsOthersClinical)
}

/**
 * Capabilities in this role's preset that read ANY clinical content, including the actor's own.
 * Empty for every role except the patient, whose own key is the point of the system.
 */
export function anyClinicalReadCapabilitiesIn(role: PracticeRole): PracticeCapability[] {
  return role.manages.filter(readsAnyClinical)
}

/**
 * Capabilities in this role's preset whose exercise authorizes somebody ELSE to read.
 *
 * Empty for every role except the patient, and that is the machine form of § Consent model's first
 * line: "Patient is always the root of consent. Roles decide who may request access; the patient's
 * grant is what authorizes it." Kept separate from [readClinicalCapabilitiesIn] because the two
 * detectors look for different things and one cannot stand in for the other — a minting capability
 * reads nothing itself, so a filter over read scopes walks straight past it.
 */
export function readMintingCapabilitiesIn(role: PracticeRole): PracticeCapability[] {
  return role.manages.filter(mintsReadForOthers)
}

/** Capabilities the preset holds that fall outside the planes the role declares it inhabits. */
export function capabilitiesOutsideDeclaredPlanes(role: PracticeRole): PracticeCapability[] {
  return role.manages.filter((c) => !role.planes.includes(CAPABILITIES[c].plane))
}

/** Every role whose preset contains a given capability — "who can do this?", for the practice UI. */
export function rolesWith(cap: PracticeCapability): PracticeRole[] {
  return ROLES.filter((r) => r.manages.includes(cap))
}
