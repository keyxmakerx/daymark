/*
 * Capability-scoped therapist assignments (owner-granted, Android-permission style).
 * See docs/COMPANION_ASSIGNMENTS.md. The therapist has no inherent powers: the owner holds
 * a Grant that says exactly what each therapist may do, and every assignment is checked
 * against it before it can apply.
 */

export type Capability =
  | 'read.share'
  | 'assign.questionnaire'
  | 'assign.task'
  | 'assign.largeAssessment'
  | 'assign.reminder'
  | 'assign.goal'
  | 'authorGamePlan'
  | 'suggest.setting'

export const ALL_CAPABILITIES: Capability[] = [
  'read.share',
  'assign.questionnaire',
  'assign.task',
  'assign.largeAssessment',
  'assign.reminder',
  'assign.goal',
  'authorGamePlan',
  'suggest.setting',
]

export type ApplyMode = 'propose' | 'auto'

export interface CapabilityGrant {
  granted: boolean
  apply: ApplyMode
}

/** The owner's per-therapist permission policy. Signed by the owner; the therapist can read it. */
export interface Grant {
  therapistFingerprint: string // pinned Ed25519 fingerprint of the therapist
  capabilities: Partial<Record<Capability, CapabilityGrant>>
}

export type AssignmentType = 'questionnaire' | 'task' | 'largeAssessment' | 'reminder' | 'goal' | 'setting'

export interface Cadence {
  every: 'day' | 'week' | 'month'
  count: number
}

export interface Assignment {
  assignmentId: string
  lineageId: string
  version: number
  type: AssignmentType
  capability: Capability
  payload: AssignmentPayload
  cadence?: Cadence
  note?: string
  issuedAt: number
  authorFingerprint: string // therapist's Ed25519 fp; verified against the owner's pinned value
}

export type AssignmentPayload =
  | { instrumentId: string } // questionnaire
  | { taskId: string } // task
  | { bundle: Array<{ kind: 'questionnaire' | 'task'; id: string }> } // largeAssessment
  | { every: Cadence['every']; count: number } // reminder
  | { title: string; activityId?: number | null } // goal
  | { key: string; value: string | number | boolean } // setting

/** Which capability a given assignment type requires. */
export const TYPE_CAPABILITY: Record<AssignmentType, Capability> = {
  questionnaire: 'assign.questionnaire',
  task: 'assign.task',
  largeAssessment: 'assign.largeAssessment',
  reminder: 'assign.reminder',
  goal: 'assign.goal',
  setting: 'suggest.setting',
}

/**
 * The ONLY app-setting keys a therapist may propose. Fixed allowlist — enforced client-side
 * and (per design) structurally server-side. Excludes anything security/privacy/crypto:
 * no PIN, lock, biometric, encryption, network, or backup keys can ever be here.
 */
export const SETTING_ALLOWLIST = ['visibleSelfChecks', 'reminderTime', 'reminderCadence', 'theme'] as const
export type AllowedSettingKey = (typeof SETTING_ALLOWLIST)[number]
