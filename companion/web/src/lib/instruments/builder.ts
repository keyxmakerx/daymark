/*
 * Provenance-aware tool builder (docs/PROVENANCE.md — BLD-1).
 *
 * A ToolDraft is the editable shape the builder UI holds. compileDraft() turns it into a full
 * InstrumentDefinition; validateDraft() runs the same honesty gate the shipped catalog uses, so
 * the UI can show live errors and only enable Publish when the gate passes.
 *
 * Note: authoring a 'validated'/'adapted' tool is gated to credentialed roles at the RBAC layer
 * (docs/COMPANION_ACCESS_CONTROL.md) — this module only compiles and checks; it does not decide
 * who may publish which tier.
 */
import type { InstrumentDefinition, Item, ItemType, Provenance, Scale, ScoreMethod, Tone } from './types'
import { validateDefinition, type ValidationResult } from './validate'

export interface DraftOption {
  id: string
  label: string
  value?: number
}

export interface DraftItem {
  id: string
  type: ItemType
  prompt?: string
  body?: string
  options?: DraftOption[]
  required?: boolean
  min?: number
  max?: number
  step?: number
  unit?: string
  excludeFromScoring?: boolean
}

export interface DraftBand {
  min?: number
  max?: number
  label: string
  tone: Tone
}

export interface ToolDraft {
  instrumentId: string
  title: string
  intro: string
  provenance: Provenance
  /** Optional override; a custom tool defaults to a self-authored GPL license. */
  license?: string
  /** Only meaningful (and required) for validated/adapted tools. */
  ledgerRef?: string
  estimatedMinutes?: number
  items: DraftItem[]
  scoreMethod: ScoreMethod
  bands: DraftBand[]
  bandFraming: string
}

/** Item types that contribute a numeric value to the score. */
const SCOREABLE: ItemType[] = ['likert', 'singleSelect', 'slider', 'numeric']

const DEFAULT_LICENSE = 'Self-authored (original items) — GPL-3.0, no third-party instrument reproduced'

/** A fresh, valid-by-default Custom draft (its intro already carries the non-diagnostic disclaimer). */
export function newDraft(): ToolDraft {
  return {
    instrumentId: '',
    title: '',
    intro: 'A short, private reflection — not a diagnosis.',
    provenance: { tier: 'custom' },
    items: [],
    scoreMethod: 'sum',
    bands: [{ label: 'Noted today', tone: 'neutral' }],
    bandFraming:
      'These bands describe your own answers today — not a diagnosis, not a screen, and not a clinical threshold.',
  }
}

/** The items that feed the score (drives the single scale). Excludes info/free-text/opted-out items. */
export function scoredItemIds(draft: ToolDraft): string[] {
  return draft.items.filter((it) => SCOREABLE.includes(it.type) && !it.excludeFromScoring).map((it) => it.id)
}

/** Turn a draft into a full instrument definition. A tool with no scoreable items emits no scale. */
export function compileDraft(draft: ToolDraft): InstrumentDefinition {
  const items: Item[] = draft.items.map((it) => ({
    id: it.id,
    type: it.type,
    prompt: it.prompt,
    body: it.body,
    options: it.options,
    required: it.required,
    min: it.min,
    max: it.max,
    step: it.step,
    unit: it.unit,
    excludeFromScoring: it.excludeFromScoring,
  }))

  const scored = scoredItemIds(draft)
  const scales: Scale[] = scored.length
    ? [
        {
          id: 'score',
          method: draft.scoreMethod,
          items: scored,
          bands: draft.bands.map((b) => ({ min: b.min, max: b.max, label: b.label, tone: b.tone })),
          bandFraming: draft.bandFraming,
        },
      ]
    : []

  return {
    instrumentId: draft.instrumentId,
    instrumentVersion: '1.0.0',
    title: draft.title,
    license: draft.license?.trim() || DEFAULT_LICENSE,
    ledgerRef: draft.ledgerRef,
    provenance: draft.provenance,
    nonDiagnostic: true,
    noScreeningFlag: true,
    estimatedMinutes: draft.estimatedMinutes,
    items,
    scoring: { scales },
    framing: { intro: draft.intro, crisisPosture: 'offline-static' },
  }
}

/** Compile + run the honesty gate. `ok` is what the UI uses to enable Publish. */
export function validateDraft(draft: ToolDraft, knownLedgerAnchors?: Set<string>): ValidationResult {
  return validateDefinition(compileDraft(draft), knownLedgerAnchors)
}
