/*
 * Daymark Companion — instrument engine types (data-driven questionnaires).
 *
 * A new license-clean instrument is added by dropping in a definition object + a ledger
 * row — no per-instrument code. The runner is a generic interpreter of this schema.
 * Non-diagnostic by construction: bands are descriptive labels, there is NO clinical
 * cutoff / positive-negative screening flag, and there is structurally no self-harm slot.
 * See docs/COMPANION_FEATURES.md.
 */

export type Tone = 'neutral' | 'attention' | 'positive'

// --- branching DSL (pure, sandboxed — no eval, no executable code in a definition) ---
export type PredicateOp = 'eq' | 'ne' | 'gt' | 'gte' | 'lt' | 'lte' | 'in' | 'includes'
export type Predicate =
  | { ref: string; op: PredicateOp; value: number | string | Array<number | string> }
  | { all: Predicate[] }
  | { any: Predicate[] }

// --- items ---
export interface Option {
  id: string
  label: string
  value?: number // numeric weight for scoring (likert/singleSelect)
}

export type ItemType = 'info' | 'likert' | 'singleSelect' | 'multiSelect' | 'slider' | 'numeric' | 'freeText'

export interface Item {
  id: string
  type: ItemType
  prompt?: string
  /** Static panel text (info items; also used for attribution notices/disclaimers). */
  body?: string
  options?: Option[]
  min?: number
  max?: number
  step?: number
  unit?: string
  required?: boolean
  /** Excluded from scoring and from any future share (e.g. free text). */
  excludeFromScoring?: boolean
  visibleWhen?: Predicate
}

// --- scoring (deterministic, descriptive only) ---
export type ScoreMethod = 'sum' | 'mean' | 'percent_of_max'

export interface Band {
  min?: number
  max?: number
  label: string
  tone: Tone
}

export interface Scale {
  id: string
  method: ScoreMethod
  items: string[]
  reverse?: string[]
  /** Ordered, non-overlapping, DESCRIPTIVE bands. Never a clinical cutoff. */
  bands: Band[]
  bandFraming: string
}

export interface Scoring {
  scales: Scale[]
}

export interface InstrumentDefinition {
  instrumentId: string
  instrumentVersion: string // semver of OUR definition
  sourceVersion?: string
  title: string
  license: string
  attribution?: string
  noticeText?: string
  ledgerRef: string
  nonDiagnostic: true // hard-required literal true
  noScreeningFlag: true // hard-required literal true
  estimatedMinutes?: number
  items: Item[]
  scoring: Scoring
  framing: {
    intro: string
    crisisPosture?: 'offline-static'
  }
}

// --- results ---
export interface ScaleResult {
  scaleId: string
  score: number
  bandLabel: string
  tone: Tone
}

export interface InstrumentResult {
  kind: 'instrument'
  instrumentId: string
  instrumentVersion: string
  takenAt: number // epoch millis
  scales: ScaleResult[]
  // Raw item answers stay local by default and are never part of a share (design §6).
  answers: Record<string, unknown>
}

export interface TaskResult {
  kind: 'task'
  taskId: string
  taskVersion: string
  takenAt: number
  timing: { flag: 'ok' | 'lower-precision'; frameJitterMs: number; droppedFrames: number; refreshMs: number }
  metrics: Record<string, number>
}
