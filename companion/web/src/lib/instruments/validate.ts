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

// Word-boundary matcher for forbidden source instruments (avoids "ess" matching
// "wellness"/"restless"; handles hyphen/space/apostrophe variants like "stop-bang").
function termToRegex(term: string): RegExp {
  const esc = term.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const flex = esc.replace(/[-\s'’]/g, "[-\\s'’]?")
  return new RegExp(`(?<![a-z0-9])${flex}(?![a-z0-9])`, 'i')
}
const FORBIDDEN_RE = FORBIDDEN_SOURCES.map(termToRegex)
function forbiddenHit(text: string): string | null {
  for (let i = 0; i < FORBIDDEN_RE.length; i++) if (FORBIDDEN_RE[i].test(text)) return FORBIDDEN_SOURCES[i]
  return null
}

// Self-harm / suicidality must be structurally absent from every definition (defense in
// depth behind human review). Flexible separators + common euphemisms; "my" included.
const SELF_HARM =
  /(self[-\s._]*harm|suicid|kill(ing)? (your|my|him|her|them)self|end(ing)? (your|my|his|her|their) life|end(ing)? it all|taking (your|my|his|her|their) own life|better off dead|cut(ting)? (your|my|him|her|them)self|hurt(ing)? (your|my|him|her|them)self)/i

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

  // --- provenance / clinical-honesty labeling (docs/PROVENANCE.md) ---
  // Every tool must declare what it clinically IS. The tier drives its badge + disclaimer;
  // the rules below are what make "a rando can't hand out a clinical screener" true —
  // anyone may publish 'custom', but it self-discloses and can never pose as clinical.
  const prov = def.provenance
  if (!prov || !prov.tier) {
    fail('provenance.tier is required (validated | adapted | custom)')
  } else if (prov.tier !== 'validated' && prov.tier !== 'adapted' && prov.tier !== 'custom') {
    fail(`provenance.tier "${prov.tier}" is invalid (validated | adapted | custom)`)
  } else {
    if (prov.tier === 'validated' && !prov.source?.trim())
      fail("provenance 'validated' requires a source (the published instrument it faithfully reproduces)")
    if (prov.tier === 'adapted' && !prov.basedOn?.trim())
      fail("provenance 'adapted' requires basedOn (the evidence-based method it draws from)")
    // A self-authored 'custom' tool must OPEN with the non-diagnostic disclaimer — framing.intro
    // is what the runner shows before the first item, so the person is never misled.
    if (prov.tier === 'custom' && !DISCLAIMER.test(def.framing?.intro ?? ''))
      fail('provenance \'custom\' requires framing.intro to carry a non-diagnostic disclaimer ("not a diagnosis/screen/clinical")')
  }

  // License-clean: reject forbidden source instruments — check the identity AND all
  // user-visible item text (prompts/bodies/option labels), so verbatim licensed item text
  // can't slip past the identity fields.
  const idHit = forbiddenHit(`${def.instrumentId} ${def.sourceVersion ?? ''} ${def.title}`)
  if (idHit) fail(`forbidden source instrument referenced: "${idHit}"`)
  for (const it of def.items) {
    const text = `${it.prompt ?? ''} ${it.body ?? ''} ${(it.options ?? []).map((o) => o.label).join(' ')}`
    const hit = forbiddenHit(text)
    if (hit) fail(`item "${it.id}" contains text from a forbidden source instrument: "${hit}"`)
  }

  // ledgerRef: required for validated/adapted (published sources need license tracking); optional
  // for self-authored custom tools. When present it must resolve (format + known-anchor check).
  const tierNeedsLedger = def.provenance?.tier === 'validated' || def.provenance?.tier === 'adapted'
  if (!def.ledgerRef) {
    if (tierNeedsLedger) fail(`provenance '${def.provenance.tier}' requires a ledgerRef (an INSTRUMENTS.md anchor)`)
  } else if (!/^INSTRUMENTS\.md#[a-z0-9-]+$/.test(def.ledgerRef)) {
    fail(`ledgerRef "${def.ledgerRef}" is not a valid INSTRUMENTS.md anchor`)
  } else if (knownLedgerAnchors) {
    const anchor = def.ledgerRef.split('#')[1]
    if (!knownLedgerAnchors.has(anchor)) fail(`ledgerRef anchor "#${anchor}" does not exist in INSTRUMENTS.md`)
  }

  // Self-harm structurally absent (ids + prompts + bodies + option labels).
  for (const it of def.items) {
    const text = `${it.id} ${it.prompt ?? ''} ${it.body ?? ''} ${(it.options ?? []).map((o) => o.label).join(' ')}`
    if (SELF_HARM.test(text)) fail(`item "${it.id}" references a self-harm/suicidality slot (forbidden)`)
  }

  // Scoring integrity.
  const ids = new Set(def.items.map((i) => i.id))
  const scaleIds = new Set<string>()
  for (const scale of def.scoring.scales) {
    if (scaleIds.has(scale.id)) fail(`duplicate scale id "${scale.id}"`)
    scaleIds.add(scale.id)
    const itemsById = new Map(def.items.map((i) => [i.id, i]))
    for (const ref of scale.items) if (!ids.has(ref)) fail(`scale "${scale.id}" references unknown item "${ref}"`)
    for (const ref of scale.reverse ?? []) if (!scale.items.includes(ref)) fail(`scale "${scale.id}" reverses non-member item "${ref}"`)
    // Branch-completeness (spec §2.3): for absolute-boundary methods (sum/percent_of_max) a
    // scored item must never be conditionally hidden, or branching would strand/deflate the
    // scale and misassign the descriptive band. `mean` tolerates hidden items (denominator adjusts).
    if (scale.method !== 'mean') {
      for (const ref of scale.items) {
        if (itemsById.get(ref)?.visibleWhen) {
          fail(`scale "${scale.id}" (${scale.method}) scores item "${ref}" which is conditionally hidden (visibleWhen) — would strand/deflate the scale`)
        }
      }
    }
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
      // For integer sum scales, bands must be contiguous (no gap that would yield a "—" label).
      else if (scale.method === 'sum' && Number.isFinite(prevHi) && Number.isFinite(lo) && lo !== prevHi + 1) {
        fail(`scale "${scale.id}" has a gap between bands before "${b.label}" (a score in the gap has no band)`)
      }
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
