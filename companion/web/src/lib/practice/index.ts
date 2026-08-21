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
