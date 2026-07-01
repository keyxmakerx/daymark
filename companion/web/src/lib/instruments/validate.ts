/*
 * Load-time / CI honesty gate for instrument definitions. A definition failing ANY check
 * does not load (and fails the build). This encodes docs/COMPANION_FEATURES.md §0 & §2:
 * non-diagnostic by construction, no clinical cutoff / screening flag, no self-harm slot,
 * license-clean only. See instruments.test.ts for the CI enforcement.
 */
import type { InstrumentDefinition, Item } from './types'

/** Source instruments that are licensed / permission-required — forbidden in the Companion. */
export const FORBIDDEN_SOURCES = [
  'tova', 'conners', 'caars', 'isi', 'ess', 'stop-bang', 'stopbang', 'psqi', 'panas',
  'wemwbs', 'dass-21', 'dass21', 'pss', 'aaq-ii', 'aaq2', 'vlq', "bull's-eye", 'bulls-eye',
]

// Self-harm / suicidality must be structurally absent from every definition.
const SELF_HARM = /(self[-\s]?harm|suicid|kill (your|him|her|them)self|end (your|his|her|their) life|hurt(ing)? (your|him|her|them)self)/i

// Affirmative clinical-verdict language forbidden in a band LABEL (a descriptive label must
// never present a screen/cutoff result). This is applied to labels only — the bandFraming
// disclaimer is *supposed* to say "not a diagnosis", so it is checked separately below.
const CLINICAL_VERDICT = /(clinical cutoff|screen(ed)? positive|positive screen|negative screen|meets criteria|probable (adhd|depression|anxiety)|disorder present|you (likely )?have)/i
// The bandFraming must carry an explicit non-diagnostic disclaimer.
const DISCLAIMER = /not a (diagnosis|screen|clinical|medical)/i

export interface ValidationResult {
  ok: boolean
  errors: string[]
}

export function validateDefinition(def: InstrumentDefinition, knownLedgerAnchors?: Set<string>): ValidationResult {
  const errors: string[] = []
  const fail = (m: string) => errors.push(`[${def.instrumentId ?? 'unknown'}] ${m}`)

  if (def.nonDiagnostic !== true) fail('nonDiagnostic must be exactly true')
  if (def.noScreeningFlag !== true) fail('noScreeningFlag must be exactly true')
  if (!def.license || !def.license.trim()) fail('license is required')
  if (!def.framing?.intro?.trim()) fail('framing.intro is required')

  // License-clean: reject forbidden source instruments.
  const hay = `${def.instrumentId} ${def.sourceVersion ?? ''} ${def.title}`.toLowerCase()
  for (const bad of FORBIDDEN_SOURCES) {
    if (hay.includes(bad)) fail(`forbidden source instrument referenced: "${bad}"`)
  }

  // ledgerRef must resolve (format + known-anchor check when a set is supplied).
  if (!/^INSTRUMENTS\.md#[a-z0-9-]+$/.test(def.ledgerRef)) fail(`ledgerRef "${def.ledgerRef}" is not a valid INSTRUMENTS.md anchor`)
  else if (knownLedgerAnchors) {
    const anchor = def.ledgerRef.split('#')[1]
    if (!knownLedgerAnchors.has(anchor)) fail(`ledgerRef anchor "#${anchor}" does not exist in INSTRUMENTS.md`)
  }

  // Self-harm structurally absent (ids + prompts + bodies).
  for (const it of def.items) {
    const text = `${it.id} ${it.prompt ?? ''} ${it.body ?? ''}`
    if (SELF_HARM.test(text)) fail(`item "${it.id}" references a self-harm/suicidality slot (forbidden)`)
  }

  // Scoring integrity.
  const ids = new Set(def.items.map((i) => i.id))
  const scaleIds = new Set<string>()
  for (const scale of def.scoring.scales) {
    if (scaleIds.has(scale.id)) fail(`duplicate scale id "${scale.id}"`)
    scaleIds.add(scale.id)
    for (const ref of scale.items) if (!ids.has(ref)) fail(`scale "${scale.id}" references unknown item "${ref}"`)
    for (const ref of scale.reverse ?? []) if (!scale.items.includes(ref)) fail(`scale "${scale.id}" reverses non-member item "${ref}"`)
    if (!scale.bandFraming?.trim()) fail(`scale "${scale.id}" is missing bandFraming (the non-diagnostic disclaimer)`)
    else if (!DISCLAIMER.test(scale.bandFraming)) fail(`scale "${scale.id}" bandFraming must state it is "not a diagnosis/screen/clinical threshold"`)
    if (!scale.bands.length) fail(`scale "${scale.id}" has no bands`)
    // Bands must be ordered and non-overlapping, and worded descriptively.
    let prevHi = Number.NEGATIVE_INFINITY
    for (const b of scale.bands) {
      const lo = b.min ?? Number.NEGATIVE_INFINITY
      const hi = b.max ?? Number.POSITIVE_INFINITY
      if (lo > hi) fail(`scale "${scale.id}" band "${b.label}" has min > max`)
      if (lo <= prevHi && prevHi !== Number.NEGATIVE_INFINITY) fail(`scale "${scale.id}" bands overlap or are out of order at "${b.label}"`)
      prevHi = hi
      if (CLINICAL_VERDICT.test(b.label)) fail(`scale "${scale.id}" band "${b.label}" uses clinical/screening language`)
    }
  }

  return { ok: errors.length === 0, errors }
}

/** Throwing variant used by the runtime loader. */
export function assertValid(def: InstrumentDefinition, knownLedgerAnchors?: Set<string>): void {
  const { ok, errors } = validateDefinition(def, knownLedgerAnchors)
  if (!ok) throw new Error(`instrument "${def.instrumentId}" failed validation:\n- ${errors.join('\n- ')}`)
}

export type { Item }
