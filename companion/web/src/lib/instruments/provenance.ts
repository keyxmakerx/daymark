/*
 * Provenance helpers — the clinical-honesty labels every tool wears.
 * See docs/PROVENANCE.md. The schema (Provenance / ProvenanceTier) lives in types.ts and the
 * enforcement in validate.ts; this module is the shared source of the user-facing badge + copy
 * so the runner, the assessment summary, and the builder all render the same thing.
 */
import type { Provenance, ProvenanceTier } from './types'

/** Short badge label per tier. */
export const PROVENANCE_LABEL: Record<ProvenanceTier, string> = {
  validated: 'Validated',
  adapted: 'Adapted',
  custom: 'Custom',
}

/** A small glyph paired with the label in the UI. */
export const PROVENANCE_GLYPH: Record<ProvenanceTier, string> = {
  validated: '✓',
  adapted: '◐',
  custom: '✎',
}

/**
 * The fixed disclaimer a Custom tool opens with (docs/PROVENANCE.md). It is shown before the
 * first item and is non-dismissible-until-seen in the UI.
 */
export const CUSTOM_DISCLAIMER =
  'Custom-made — a personal reflection tool, not a validated or clinical instrument. Not for diagnosis.'

/**
 * The line a tool shows up front:
 *  - custom   → the fixed non-clinical disclaimer
 *  - adapted  → names the method it draws from
 *  - validated→ null (it shows its {@link provenanceSource} instead of a warning)
 */
export function provenanceDisclaimer(p: Provenance): string | null {
  switch (p.tier) {
    case 'custom':
      return CUSTOM_DISCLAIMER
    case 'adapted':
      return `Adapted from ${p.basedOn?.trim() || 'an evidence-based method'} — not the original validated instrument.`
    case 'validated':
      return null
  }
}

/** What a Validated tool shows in place of a warning: its source citation. */
export function provenanceSource(p: Provenance): string | null {
  return p.tier === 'validated' ? (p.source?.trim() || null) : null
}
