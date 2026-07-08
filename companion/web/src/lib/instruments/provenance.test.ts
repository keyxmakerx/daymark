import { describe, it, expect } from 'vitest'
import { CATALOG, REQUIRED_LEDGER_ANCHORS } from './catalog'
import { validateDefinition } from './validate'
import { CUSTOM_DISCLAIMER, PROVENANCE_LABEL, provenanceDisclaimer, provenanceSource } from './provenance'
import type { InstrumentDefinition } from './types'

const anchors = new Set(REQUIRED_LEDGER_ANCHORS)

/** A minimal, VALID custom instrument (intro carries the non-diagnostic disclaimer). */
function base(): InstrumentDefinition {
  return {
    instrumentId: 'x-selfcheck', instrumentVersion: '1.0.0', title: 'X', license: 'self',
    ledgerRef: 'INSTRUMENTS.md#wellbeing-selfcheck', provenance: { tier: 'custom' },
    nonDiagnostic: true, noScreeningFlag: true,
    items: [{ id: 'q1', type: 'likert', options: [{ id: 'a', label: 'A', value: 0 }, { id: 'b', label: 'B', value: 4 }] }],
    scoring: { scales: [{ id: 's', method: 'sum', items: ['q1'], bands: [{ label: 'ok', tone: 'neutral' }], bandFraming: 'descriptive — not a diagnosis' }] },
    framing: { intro: 'A self-check — not a diagnosis.' },
  }
}

describe('provenance — honesty gate', () => {
  it('every shipped instrument declares a provenance tier and still validates', () => {
    for (const def of CATALOG) {
      expect(['validated', 'adapted', 'custom']).toContain(def.provenance?.tier)
      const { ok, errors } = validateDefinition(def, anchors)
      expect(ok, errors.join('; ')).toBe(true)
    }
  })

  it('rejects a definition with no provenance', () => {
    const bad = { ...base(), provenance: undefined as unknown as InstrumentDefinition['provenance'] }
    expect(validateDefinition(bad, anchors).ok).toBe(false)
  })

  it('rejects an invalid tier', () => {
    const bad = { ...base(), provenance: { tier: 'official' as unknown as 'custom' } }
    expect(validateDefinition(bad, anchors).ok).toBe(false)
  })

  it("'validated' requires a source; passes once it has one", () => {
    expect(validateDefinition({ ...base(), provenance: { tier: 'validated' } }, anchors).ok).toBe(false)
    expect(validateDefinition({ ...base(), provenance: { tier: 'validated', source: 'PHQ-9 (Kroenke et al., 2001)' } }, anchors).ok).toBe(true)
  })

  it("'adapted' requires basedOn; passes once it has one", () => {
    expect(validateDefinition({ ...base(), provenance: { tier: 'adapted' } }, anchors).ok).toBe(false)
    expect(validateDefinition({ ...base(), provenance: { tier: 'adapted', basedOn: 'behavioral activation' } }, anchors).ok).toBe(true)
  })

  it("'custom' must open with a non-diagnostic disclaimer in framing.intro", () => {
    const noDisclaimer = { ...base(), framing: { intro: 'A quick check-in on your week.' } }
    expect(validateDefinition(noDisclaimer, anchors).ok).toBe(false)
    // The valid base() (intro says "not a diagnosis") passes:
    expect(validateDefinition(base(), anchors).ok).toBe(true)
  })
})

describe('provenance — labels & copy', () => {
  it('labels each tier', () => {
    expect(PROVENANCE_LABEL.validated).toBe('Validated')
    expect(PROVENANCE_LABEL.adapted).toBe('Adapted')
    expect(PROVENANCE_LABEL.custom).toBe('Custom')
  })

  it('custom shows the fixed disclaimer; validated shows none', () => {
    expect(provenanceDisclaimer({ tier: 'custom' })).toBe(CUSTOM_DISCLAIMER)
    expect(provenanceDisclaimer({ tier: 'validated', source: 'PHQ-9' })).toBeNull()
  })

  it('adapted names the method it draws from', () => {
    expect(provenanceDisclaimer({ tier: 'adapted', basedOn: 'behavioral activation' })).toContain('behavioral activation')
  })

  it('validated exposes its source in place of a warning', () => {
    expect(provenanceSource({ tier: 'validated', source: 'GAD-7 (Spitzer et al., 2006)' })).toBe('GAD-7 (Spitzer et al., 2006)')
    expect(provenanceSource({ tier: 'custom' })).toBeNull()
  })
})
