/*
 * THE JOIN BETWEEN THE SERVER'S SEATABLE ROLES AND THE ROLE CATALOG — and the facts a role picker
 * is allowed to draw.
 *
 * ─── WHY THIS FILE EXISTS AT ALL, GIVEN roles.ts ALREADY MODELS THE ROLES ────────────────────────
 *
 * Two catalogs answer two different questions and they are deliberately not the same list.
 *
 *   roles.ts is docs/COMPANION_ACCESS_CONTROL.md § Role catalog: EIGHT rows, including the patient
 *   and the platform sysadmin, because the document is describing everyone who participates in the
 *   clinical layer.
 *
 *   OrgRole.kt is what a practice may write into a membership row: SIX values, and the two it
 *   leaves out are left out on purpose. `PATIENT` is absent because "a patient/client is not owned
 *   by the org" — if it were seatable, an administrator could assert a clinical relationship the
 *   patient never consented to, from the control plane, with one row. `PLATFORM_SYSADMIN` is absent
 *   because they run the server and are not in anybody's practice, and seating them would quietly
 *   create the god admin the whole design exists to rule out.
 *
 * A picker built from the eight would offer two roles the server refuses, and the person using it
 * would learn that only from a 400. A picker built from a hand-typed six would be a third catalog,
 * free to drift from both. So this file is the JOIN: it names the six wire values the server
 * accepts, points each at its row in the catalog, and then reads every displayable fact off that
 * row rather than restating it. [ASSIGNABLE_ROLE_IDS] is checked against roles.ts at test time, and
 * the two roles that are absent are modelled as an explicit, reasoned absence rather than as a
 * silent gap — see [UNSEATABLE].
 *
 * ─── WHY THE FACTS ARE DERIVED AND NOT WRITTEN ───────────────────────────────────────────────────
 *
 * The claim the access-control document is built around is that "an admin can revoke anyone and see
 * who-accessed-what, yet cannot read a single clinical note", and threePlane.test.ts enforces it
 * over the role model. An interface that retyped "Front desk — no clinical access" into its markup
 * would be making the same claim with nothing checking it: the day somebody adds a read capability
 * to the front-desk preset, the test fails and the screen goes on saying the reassuring thing.
 *
 * So [roleFacts] computes what the picker renders. `readsSummary` and `managesSummary` are the
 * document's own cells, carried through the model. `clinicalReadActions` is a FILTER over the
 * preset, not an assertion about it, so a role that acquired a read capability would render a list
 * of them instead of the sentence saying there are none. The interface reports what the model says;
 * it does not promise what the model is supposed to say.
 *
 * ─── WHAT THIS FILE MUST NEVER GROW ──────────────────────────────────────────────────────────────
 *
 * A field saying what a role can READ. There is no such field, because there is no such fact: read
 * capability is a key wrapped on a patient's device for one named person, and nothing about being
 * seated in a practice produces one. `readsSummary` is a sentence about what NORMALLY HAPPENS to
 * people holding this title — "is normally granted a key", in the document's words — and it is
 * carried verbatim so it cannot be quietly turned into a permission.
 */
import type { Plane, PracticeCapability } from './capabilities'
import { CAPABILITIES, PLANE_LABEL, describePracticeCapability, readsOthersClinical } from './capabilities'
import type { PracticeRole, ReadPosture, RoleId } from './roles'
import { ROLES, readClinicalCapabilitiesIn, readMintingCapabilitiesIn, roleById } from './roles'

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   1. The six values the server will accept in a membership row.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

/**
 * The wire strings from `OrgRole.wire`, exactly. These are sent in request bodies and come back in
 * roster rows and audit metadata, so a typo here is a 400 at best and a role mislabelled on screen
 * at worst.
 */
export type OrgRoleWire =
  | 'clinician'
  | 'psychiatrist'
  | 'therapist_assistant'
  | 'front_desk'
  | 'supervisor'
  | 'org_admin'

/**
 * The wire value each seatable role is written as, in the server's own declaration order.
 *
 * Order matters for one reason and it is not tidiness: it is the order the picker offers, and a
 * picker that led with the most powerful role would make "practice admin" the path of least
 * resistance. The server's order starts at the clinical roles and ends at the administrative one,
 * which is also the order least likely to over-privilege somebody by accident.
 */
export const SEATABLE_ROLES: readonly { wire: OrgRoleWire; id: RoleId }[] = [
  { wire: 'clinician', id: 'clinician' },
  { wire: 'psychiatrist', id: 'psychiatrist' },
  { wire: 'therapist_assistant', id: 'assistant' },
  { wire: 'front_desk', id: 'front-desk' },
  { wire: 'supervisor', id: 'supervisor' },
  { wire: 'org_admin', id: 'org-admin' },
]

export const SEATABLE_ROLE_WIRES: readonly OrgRoleWire[] = SEATABLE_ROLES.map((r) => r.wire)

/**
 * The two catalog roles a practice cannot seat, and why — rendered on the screen rather than left
 * as an unexplained absence from a dropdown.
 *
 * A missing option is indistinguishable from an oversight, and both of these absences are load
 * bearing: one keeps a practice from asserting that somebody is its patient, the other keeps the
 * operator from being a tenant of a practice they also run. The reasons are the server's, condensed
 * from OrgRole.kt's own header, and they are shown where the choice is made because that is where
 * somebody would otherwise go looking for the missing entry.
 */
export const UNSEATABLE: readonly { id: RoleId; label: string; why: string }[] = [
  {
    id: 'patient',
    label: roleById('patient').label,
    why:
      'A patient is not a member of a practice and cannot be seated in one. They own their keys; ' +
      'the practice is a way of addressing people, not a claim over them. If an administrator ' +
      'could seat somebody as a patient, a practice could assert a clinical relationship the ' +
      'person never agreed to.',
  },
  {
    id: 'platform-sysadmin',
    label: roleById('platform-sysadmin').label,
    why:
      'Whoever runs the server is not in anybody’s practice. Seating them would put one person ' +
      'inside a practice and outside every practice at once, which is the single arrangement this ' +
      'design exists to rule out.',
  },
]

/** The role catalog row for a wire value, or null if the server sent something this build predates. */
export function roleForWire(wire: string): PracticeRole | null {
  const found = SEATABLE_ROLES.find((r) => r.wire === wire)
  // Null rather than a throw or a default. A roster row carrying an unknown role is a server that
  // has moved on ahead of this page, and the honest rendering is the raw value with no claim
  // attached — never a guess, and never the first entry in the list, which would silently label
  // somebody a clinician.
  return found ? roleById(found.id) : null
}

/** The wire value for a catalog role, or null when the role is one a practice cannot seat. */
export function wireForRole(id: RoleId): OrgRoleWire | null {
  return SEATABLE_ROLES.find((r) => r.id === id)?.wire ?? null
}

/**
 * What to call a role on screen when the server sent a value this build does not model.
 *
 * The raw wire string, unchanged. Not "Unknown role", which throws away the only information the
 * response carried, and not a prettified guess, which would invent a label for something nobody
 * here has read the definition of.
 */
export function roleLabel(wire: string): string {
  return roleForWire(wire)?.label ?? wire
}

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   2. The facts a picker may draw.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

/**
 * One action in a role's preset, with the two facts that decide how it must be read: which plane it
 * lives in, and whether exercising it opens somebody else's clinical content.
 */
export interface RoleAction {
  readonly id: PracticeCapability
  readonly title: string
  readonly description: string
  readonly plane: Plane
  readonly planeLabel: string
  readonly readsOthersClinical: boolean
}

/**
 * Everything the role picker renders about one role — every field read off the catalog.
 *
 * There is intentionally no `canRead` boolean anywhere in this shape. The nearest thing is
 * [clinicalReadActions], which is a list produced by filtering the preset, so it says what IS in
 * the model rather than what the model is supposed to contain.
 */
export interface RoleFacts {
  readonly wire: OrgRoleWire
  readonly id: RoleId
  /** What a person is called on screen — the catalog's short label. */
  readonly label: string
  /** The catalog's first-cell label, which is the join key to the document's own table. */
  readonly docLabel: string
  /** § Role catalog, "Manages" cell, verbatim through the model. */
  readonly managesSummary: string
  /** § Role catalog, "Normally reads clinical content?" cell, verbatim through the model. */
  readonly readsSummary: string
  /** The same cell as a machine value, for a caller that needs to branch rather than print. */
  readonly reads: ReadPosture
  /** The planes this role inhabits by title, named. */
  readonly planeLabels: readonly string[]
  /** Every action the role's preset carries, in the preset's own order. */
  readonly actions: readonly RoleAction[]
  /**
   * Actions in the preset that read ANOTHER person's clinical content. Empty for every role in the
   * catalog, and computed rather than asserted so that a preset which acquired one would say so on
   * screen instead of being described by a sentence that had stopped being true.
   */
  readonly clinicalReadActions: readonly PracticeCapability[]
  /**
   * Actions whose exercise authorizes somebody ELSE to read — minting, not reading. Empty for every
   * seatable role, because minting is the patient's act and no practice title confers it. Kept
   * separate from the list above because a filter over read scopes walks straight past a capability
   * that reads nothing itself and yet hands out keys.
   */
  readonly readMintingActions: readonly PracticeCapability[]
}

/**
 * The short phrase a chip carries beside a role, one per posture in the catalog's read column.
 *
 * WHY A SEPARATE MAP RATHER THAN TRUNCATING `readsSummary`. The summaries are the document's
 * sentences and several of them do not survive being cut ("Only via explicit, consented grant
 * (clinical supervision), never by title" loses the entire point if the tail is dropped). So the
 * chip is written per posture, the full sentence is rendered directly beneath it, and a test asserts
 * the map is total over the postures the catalog actually uses — so a new posture cannot arrive
 * with no chip and quietly fall back to something reassuring.
 *
 * Every phrase names the SOURCE of the reading rather than the permission, because that is the
 * distinction the whole design turns on: none of these is something the role hands out.
 */
export const READ_POSTURE_CHIP: Record<ReadPosture, string> = {
  'own-data': 'Their own data',
  granted: 'Only what a patient grants',
  'granted-narrowed': 'Only what a patient grants, narrowed',
  never: 'No clinical content',
  'only-by-separate-grant': 'Only by a separate consented grant',
}

/** The facts for one seatable role, all of them read off the catalog. */
export function roleFacts(wire: OrgRoleWire): RoleFacts {
  const role = roleForWire(wire)
  // Unreachable while OrgRoleWire and SEATABLE_ROLES agree, and the suite asserts they do. A throw
  // rather than a fallback for the same reason roleById throws: a picker that silently rendered an
  // empty fact sheet would be telling somebody this role does nothing, confidently and wrongly.
  if (!role) throw new Error(`no catalog role for wire value: ${wire}`)
  return {
    wire,
    id: role.id,
    label: role.label,
    docLabel: role.docLabel,
    managesSummary: role.managesSummary,
    readsSummary: role.readsSummary,
    reads: role.reads,
    planeLabels: role.planes.map((p) => PLANE_LABEL[p]),
    actions: role.manages.map((id) => {
      const copy = describePracticeCapability(id)
      const facts = CAPABILITIES[id]
      return {
        id,
        title: copy.title,
        description: copy.desc,
        plane: facts.plane,
        planeLabel: PLANE_LABEL[facts.plane],
        readsOthersClinical: readsOthersClinical(id),
      }
    }),
    clinicalReadActions: readClinicalCapabilitiesIn(role),
    readMintingActions: readMintingCapabilitiesIn(role),
  }
}

/** Every seatable role's facts, in the order the picker offers them. */
export function seatableRoleFacts(): RoleFacts[] {
  return SEATABLE_ROLES.map((r) => roleFacts(r.wire))
}

/**
 * Do the seatable presets, as they stand right now, contain any action that reads another person's
 * clinical content?
 *
 * The three-plane claim, evaluated rather than quoted. The console renders the standing sentence
 * about roles carrying no read access only when this is false, and renders the offending roles when
 * it is true — so the interface degrades into an accurate alarm rather than into a stale promise.
 * threePlane.test.ts fails first, in a run that is looking for exactly this; this is the belt to
 * that suite's braces, for anyone reading the screen rather than the CI log.
 */
export function seatableRolesThatReadClinical(): RoleFacts[] {
  return seatableRoleFacts().filter((f) => f.clinicalReadActions.length > 0)
}

/**
 * Every role in the WHOLE catalog whose preset can hand somebody else a read capability.
 *
 * Exactly one — the patient — and the console shows that by name, because "the only person on this
 * server who can authorize a read is the person whose data it is" lands harder as a computed
 * one-item list than as a paragraph. Drawn from ROLES rather than from the seatable six, since the
 * point is precisely that the answer is a role a practice cannot seat.
 */
export function rolesThatMintReads(): PracticeRole[] {
  return ROLES.filter((r) => readMintingCapabilitiesIn(r).length > 0)
}
