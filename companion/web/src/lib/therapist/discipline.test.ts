import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import {
  DISCIPLINES, PROFILES, loadDiscipline, saveDiscipline, presetsFor, DISCIPLINE_STORAGE_KEY,
  type DisciplineStorage,
} from './discipline'
import { PRESETS } from '../timers/timers'

const mem = (init: Record<string, string> = {}): DisciplineStorage & { m: Record<string, string> } => {
  const m = { ...init }
  return { m, getItem: (k) => m[k] ?? null, setItem: (k, v) => void (m[k] = v) }
}

describe('the discipline is remembered in this browser', () => {
  it('round-trips each discipline', () => {
    for (const d of DISCIPLINES) {
      const s = mem()
      saveDiscipline(s, d)
      expect(loadDiscipline(s)).toBe(d)
    }
  })
  it('falls back to the therapist for nothing, junk, or a refusing browser', () => {
    expect(loadDiscipline(null)).toBe('therapist')
    expect(loadDiscipline(mem({ [DISCIPLINE_STORAGE_KEY]: 'surgeon' }))).toBe('therapist')
    const throwing: DisciplineStorage = { getItem: () => { throw new Error('denied') }, setItem: () => { throw new Error('denied') } }
    expect(loadDiscipline(throwing)).toBe('therapist')
    expect(() => saveDiscipline(throwing, 'psychiatrist')).not.toThrow()
    // Control: a stored, known value is read, so the fallback above is not the only path.
    expect(loadDiscipline(mem({ [DISCIPLINE_STORAGE_KEY]: 'psychologist' }))).toBe('psychologist')
  })
})

describe('a discipline arranges, it never grants', () => {
  it('every discipline orders the same four read tabs, adding and dropping none', () => {
    for (const d of DISCIPLINES) expect([...PROFILES[d].tabOrder].sort()).toEqual(['calendar', 'record', 'shared', 'today'])
  })

  it('the portal gates the read tabs on the grant, never on the discipline', () => {
    const src = readFileSync(new URL('../components/therapist/TherapistPortal.svelte', import.meta.url), 'utf8')
    // Positive control: the portal does read the discipline.
    expect(src).toMatch(/discipline/)
    // The read tabs sit behind the read.share capability…
    expect(src).toMatch(/hasCapability\(grant, 'read\.share'\)/)
    // …and no capability check anywhere consults the discipline.
    for (const line of src.split('\n').filter((l) => l.includes('hasCapability'))) {
      expect(line).not.toMatch(/discipline/)
    }
  })

  it('each discipline leads its timers with its own preset, and keeps them all', () => {
    for (const d of DISCIPLINES) {
      const p = presetsFor(d, PRESETS)
      expect(p[0]!.label).toBe(PROFILES[d].firstPreset)
      expect(p.map((x) => x.label).sort()).toEqual(PRESETS.map((x) => x.label).sort())
    }
  })

  it('note sections are distinct within a discipline', () => {
    for (const d of DISCIPLINES) {
      const ids = PROFILES[d].noteSections.map((s) => s.id)
      expect(new Set(ids).size).toBe(ids.length)
    }
  })
})
