import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { CATALOG } from '../instruments'
import { CLINICIAN_BAND_WORDS, forClinician } from './clinicianBands'
import { buildClientRecord, bandLanes } from './clientRecord'
import type { BackupData } from '../backup'

/* A clinician never reads a band label addressed to "you" (#422); an old result keeps its lane. */

const SECOND_PERSON = /\byour(s|self)?\b|\byou\b/i

/** Every band label the catalog declares, whatever the instrument. */
const LABELS: string[] = CATALOG.flatMap((def) => (def.scoring?.scales ?? []).flatMap((s) => [...s.bands.map((b) => b.label), ...(s.bandFraming ? [s.bandFraming] : [])]))

describe('band words for a clinician', () => {
  it('positive control: the walk found the labels, and the detector sees a second-person one', () => {
    expect(LABELS.length).toBeGreaterThan(5)
    expect(LABELS.some((l) => SECOND_PERSON.test(l))).toBe(true)
    expect(SECOND_PERSON.test('Lower than your own usual')).toBe(true)
  })

  it('every catalog band label, after the mapping, says nothing to "you"', () => {
    expect(LABELS.filter((l) => SECOND_PERSON.test(forClinician(l)))).toEqual([])
  })

  it('every mapped entry is words the catalog really declares, and its words differ', () => {
    for (const [recorded, shown] of Object.entries(CLINICIAN_BAND_WORDS)) {
      expect(LABELS.some((l) => l.includes(recorded)), recorded).toBe(true)
      expect(shown).not.toBe(recorded)
      expect(SECOND_PERSON.test(shown), shown).toBe(false)
    }
  })

  it('maps a label inside a longer line, and leaves other text alone', () => {
    expect(forClinician('12 · Around your own middle today')).toBe('12 · Around this person’s own middle today')
    expect(forClinician('More focus bumps')).toBe('More focus bumps')
  })

  it('a result recorded under the old words is still placed on its lane, and the lane keeps the catalog words', () => {
    const old = 'Around your own middle today'
    const data = {
      version: 1,
      exportedAt: 1000,
      entries: [], activities: [], refs: [], journal: [], sleepLogs: [],
      assessments: [{ id: 1, key: 'wellbeing-selfcheck', dateTime: 500, score: 10, bandLabel: old }],
    } as unknown as BackupData
    const view = buildClientRecord({ data, now: 2000 })
    const record = view.instruments.find((i) => i.key === 'wellbeing-selfcheck')!
    const lane = bandLanes(record).find((l) => l.runs.length > 0)
    expect(lane?.step.label).toBe(old)
    expect(forClinician(lane!.step.label)).toBe('Around this person’s own middle today')
  })

  it('the clinician screens apply it, and the person’s own dashboard view does not', () => {
    const read = (p: string) => readFileSync(fileURLToPath(new URL(p, import.meta.url)), 'utf8')
    expect(read('../components/therapist/ClientRecordScreen.svelte')).toContain('forClinician(lane.step.label)')
    expect(read('../components/therapist/CalendarScreen.svelte')).toContain('forClinician(measure.value)')
    expect(read('./today.ts')).toContain('forClinician(to)')
    const dash = read('../components/Dashboard.svelte')
    expect(dash).toContain('ownData ? a.latestBand : forClinician(a.latestBand)')
    expect(read('./clientRecord.ts')).not.toContain('forClinician')
  })
})
