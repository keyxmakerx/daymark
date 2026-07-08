export * from './types'
export { evalPredicate, visibleItemIds } from './predicate'
export { scoreInstrument, bandFor } from './scoring'
export { validateDefinition, assertValid, FORBIDDEN_SOURCES } from './validate'
export { PROVENANCE_LABEL, PROVENANCE_GLYPH, CUSTOM_DISCLAIMER, provenanceDisclaimer, provenanceSource } from './provenance'
export { CATALOG, getInstrument, REQUIRED_LEDGER_ANCHORS } from './catalog'

import type { InstrumentDefinition } from './types'

/** The non-diagnostic disclaimer that accompanies a scale's band. */
export function bandFramingFor(def: InstrumentDefinition, scaleId: string): string {
  return def.scoring.scales.find((s) => s.id === scaleId)?.bandFraming ?? ''
}
