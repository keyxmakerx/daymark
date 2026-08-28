/*
 * The practice (clinical-layer) access-control vocabulary, in one import.
 *
 * Two modules, and the split is deliberate: capabilities.ts is WHAT CAN BE DONE and roles.ts is
 * WHO IS PRESET TO DO IT. Keeping them apart is the file-level echo of the rule they encode —
 * docs/COMPANION_ACCESS_CONTROL.md § Role catalog, "Roles gate actions... Read capability is
 * separate and comes only from a patient grant" — so that a role can never be defined in the same
 * breath as the key it does not carry.
 *
 * threePlane.test.ts is the reason both files are shaped the way they are; read it next.
 */
export type {
  Plane,
  ClinicalRead,
  Authorization,
  Friction,
  PracticeCapability,
  PracticeOnlyCapability,
  CapabilityFacts,
} from './capabilities'
export {
  PLANES,
  PLANE_LABEL,
  PLANE_RULE,
  FRICTION_LABEL,
  frictionRank,
  CAPABILITIES,
  ALL_PRACTICE_CAPABILITIES,
  READ_CLINICAL_CAPABILITIES,
  READ_MINTING_CAPABILITIES,
  PATIENT_GRANT_ONLY_CAPABILITIES,
  PRACTICE_CAPABILITY_COPY,
  factsOf,
  mintsReadForOthers,
  readsOthersClinical,
  readsAnyClinical,
  isGrantVocabularyCapability,
  describePracticeCapability,
} from './capabilities'

export type { RoleId, ReadPosture, PracticeRole } from './roles'
export {
  ROLES,
  ROLE_IDS,
  roleById,
  isAdminPlaneOnly,
  readClinicalCapabilitiesIn,
  readMintingCapabilitiesIn,
  anyClinicalReadCapabilitiesIn,
  capabilitiesOutsideDeclaredPlanes,
  rolesWith,
} from './roles'

/*
 * ── The practice console's own modules ──────────────────────────────────────────────────────────
 *
 * Added beside the vocabulary rather than in a second barrel, because they are the same subject
 * seen from the wire: orgRoles.ts is the join between the catalog above and the six role values a
 * server membership row can hold, client.ts is the `/v1/orgs` surface, audit.ts reads that
 * surface's log, and copy.ts holds the sentences the console is obliged to say. The vocabulary
 * exports stay first because everything below depends on them and nothing above depends on any of
 * it — the layering is visible in the order.
 */
export type { OrgRoleWire, RoleAction, RoleFacts } from './orgRoles'
export {
  READ_POSTURE_CHIP,
  SEATABLE_ROLES,
  SEATABLE_ROLE_WIRES,
  UNSEATABLE,
  roleFacts,
  roleForWire,
  roleLabel,
  rolesThatMintReads,
  seatableRoleFacts,
  seatableRolesThatReadClinical,
  wireForRole,
} from './orgRoles'

export type {
  Member,
  OrgAuditEvent,
  OrgAuditPage,
  Practice,
  PracticeFailure,
  PracticeResult,
  Removal,
  WriteAuth,
} from './client'
export {
  FAILURE_SENTENCE,
  ID_CHARSET,
  MAX_PRACTICE_NAME,
  PracticeClient,
  failureSentence,
  formatInstant,
  isMemberId,
  isPracticeId,
  isPracticeName,
  memberIdProblem,
  nothingChanged,
  practiceNameProblem,
  readFailure,
} from './client'

export {
  ORG_ACTION_LABEL,
  ORG_ACTOR_LABEL,
  orgAuditActionLabel,
  orgAuditActorLabel,
  orgAuditAnnotations,
  orgAuditSubjectLabel,
} from './audit'
