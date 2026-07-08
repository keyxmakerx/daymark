/*
 * The shipped instrument catalog. Every definition is validated at module load — a bad
 * one throws and the app fails fast (mirrors the CI gate in instruments.test.ts).
 *
 * FORBIDDEN: do not add TOVA, Conners, CAARS, ISI, ESS, STOP-Bang, PSQI, PANAS, WEMWBS,
 * DASS-21, PSS, AAQ-II, VLQ, or any other licensed/permission-required instrument. The
 * validator (validate.ts FORBIDDEN_SOURCES) rejects them and the build fails.
 */
import type { InstrumentDefinition } from '../types'
import { assertValid } from '../validate'
import { wellbeingCheck } from './wellbeing'
import { focusSelfCheck } from './focus'

export const CATALOG: InstrumentDefinition[] = [wellbeingCheck, focusSelfCheck]

/** Ledger anchors the catalog references (CI verifies these exist in companion/INSTRUMENTS.md). */
export const REQUIRED_LEDGER_ANCHORS = CATALOG.flatMap((d) => (d.ledgerRef ? [d.ledgerRef.split('#')[1]] : []))

// Fail fast at load if any shipped definition is invalid.
for (const def of CATALOG) assertValid(def, new Set(REQUIRED_LEDGER_ANCHORS))

export function getInstrument(id: string): InstrumentDefinition | undefined {
  return CATALOG.find((d) => d.instrumentId === id)
}
