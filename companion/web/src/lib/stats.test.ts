import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { activityAssociation, assessmentSeries, dailyMoodInRange, summarize, WINDOW_DAYS } from './stats'
import type { BackupData } from './backup'

const DAY = 86_400_000

function data(over: Partial<BackupData> = {}): BackupData {
  return {
    version: 12, exportedAt: 0,
    entries: [
      { id: 1, dateTime: 10 * DAY, moodLevel: 2, note: '' },
      { id: 2, dateTime: 11 * DAY, moodLevel: 4, note: '' },
      { id: 3, dateTime: 100 * DAY, moodLevel: 5, note: '' },
    ],
    activities: [
      { id: 1, name: 'exercise', iconKey: 'x', sortOrder: 0, archived: false },
      { id: 2, name: 'work', iconKey: 'w', sortOrder: 1, archived: false },
      { id: 3, name: 'old', iconKey: 'o', sortOrder: 2, archived: true },
    ],
    refs: [
      { entryId: 2, activityId: 1 }, // exercise on a good day (mood 4)
      { entryId: 3, activityId: 1 }, // exercise on a great day (mood 5)
      { entryId: 1, activityId: 2 }, // work on a low day (mood 2)
    ],
    ...over,
  }
}

describe('dashboard stats', () => {
  it('activityAssociation ranks by delta vs overall and skips archived/untagged', () => {
    const a = activityAssociation(data())
    // overall avg = (2+4+5)/3 = 3.67; exercise avg = 4.5 (delta +) ranks above work avg 2 (delta -)
    expect(a.map((x) => x.name)).toEqual(['exercise', 'work'])
    expect(a[0].delta).toBeGreaterThan(0)
    expect(a[1].delta).toBeLessThan(0)
    expect(a.find((x) => x.name === 'old')).toBeUndefined()
  })

  it('dailyMoodInRange limits to the last N days relative to the newest entry', () => {
    const all = dailyMoodInRange(data(), 'all')
    expect(all.length).toBe(3)
    const recent = dailyMoodInRange(data(), 30) // newest is day 100 → cutoff day 71
    expect(recent.length).toBe(1)
  })

  it('assessmentSeries groups by key, sorts chronologically, exposes latest band', () => {
    const d = data({
      assessments: [
        { id: 1, key: 'wellbeing', dateTime: 5 * DAY, score: 10, bandLabel: 'mid' },
        { id: 2, key: 'wellbeing', dateTime: 20 * DAY, score: 16, bandLabel: 'bright' },
        { id: 3, key: 'focus', dateTime: 3 * DAY, score: 8, bandLabel: 'few' },
      ],
    })
    const s = assessmentSeries(d)
    const wb = s.find((x) => x.key === 'wellbeing')!
    expect(wb.points.map((p) => p.score)).toEqual([10, 16])
    expect(wb.latestBand).toBe('bright')
    expect(s.length).toBe(2)
  })
})

/*
 * DAYS WITH AN ENTRY, AND WHY IT IS NOT A STREAK.
 *
 * The summary used to carry `currentStreakDays`: consecutive days ending today or yesterday, zero
 * the moment a day was missed. The number was therefore at its smallest exactly when the person
 * had been away and had just come back, which is the failure mode §D6 names.
 *
 * These build their days relative to a fixed "now" rather than to literal timestamps, because the
 * window ends today: a fixture pinned to an epoch would pass on the day it was written and fail
 * forever after.
 */
function atDaysAgo(...offsets: number[]): BackupData {
  const entries = offsets.map((back, i) => {
    const d = new Date()
    d.setHours(12, 0, 0, 0)
    d.setDate(d.getDate() - back)
    return { id: i + 1, dateTime: d.getTime(), moodLevel: 3, note: '' }
  })
  return { version: 12, exportedAt: 0, entries, activities: [], refs: [] }
}

describe('days with an entry in the last 30', () => {
  it('counts distinct calendar days, not entries', () => {
    // Three entries, two of them on the same day.
    expect(summarize(atDaysAgo(0, 0, 4)).daysWithEntryLast30).toBe(2)
  })

  it('a gap costs the days it covers and nothing more', () => {
    // The property the whole change exists for. As a streak, the missed day at 3 would have taken
    // days 4..9 with it and the figure would have read 3.
    const withGap = atDaysAgo(0, 1, 2, 4, 5, 6, 7, 8, 9)
    expect(summarize(withGap).daysWithEntryLast30).toBe(9)
    // Control: the count does move with the data, so the 9 above is not a coincidence.
    expect(summarize(atDaysAgo(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)).daysWithEntryLast30).toBe(10)
  })

  it('both edges of the window are inside it, and nothing older is', () => {
    expect(summarize(atDaysAgo(0)).daysWithEntryLast30).toBe(1)
    expect(summarize(atDaysAgo(WINDOW_DAYS - 1)).daysWithEntryLast30).toBe(1)
    expect(summarize(atDaysAgo(WINDOW_DAYS)).daysWithEntryLast30).toBe(0)
    expect(summarize(atDaysAgo(400)).daysWithEntryLast30).toBe(0)
  })

  it('is zero, not a negative or a NaN, with no entries at all', () => {
    expect(summarize({ version: 12, exportedAt: 0, entries: [], activities: [], refs: [] }).daysWithEntryLast30).toBe(0)
  })
})

/*
 * The two places this number is rendered, asserted over the component source. There is no DOM in
 * these tests, so the check is structural: the old strings are gone, the new caption is present,
 * and each site is guarded so that a zero renders nothing at all.
 */
const src = (p: string) => readFileSync(fileURLToPath(new URL(p, import.meta.url)), 'utf8')

describe('the overview and dashboard say "of the last 30 days", and say nothing at zero', () => {
  const overview = src('./components/Overview.svelte')
  const dashboard = src('./components/Dashboard.svelte')

  it('the sources were read', () => {
    // Guards every assertion below: an unread file would make them all pass.
    expect(overview).toContain('<script lang="ts">')
    expect(dashboard).toContain('<script lang="ts">')
  })

  it('neither renders a streak any more', () => {
    // Rendered prose only: a comment explaining what was removed must not stand in for the rule.
    const prose = (s: string) => s.replace(/<!--[\s\S]*?-->/g, '').replace(/<script[\s\S]*?<\/script>/g, '')
    expect(prose(overview)).not.toMatch(/streak/i)
    expect(prose(dashboard)).not.toMatch(/streak/i)
    // The stripper must actually strip, or the two assertions above read an empty string.
    expect(prose(overview)).toContain('of the last')
    expect(prose('<!-- streak --><p>kept</p>')).toBe('<p>kept</p>')
  })

  it('both read the same field and the same window constant', () => {
    expect(overview).toContain('s.daysWithEntryLast30')
    expect(dashboard).toContain('s.daysWithEntryLast30')
    expect(overview).toContain('WINDOW_DAYS')
    expect(dashboard).toContain('WINDOW_DAYS')
  })

  it('both guard on the count being above zero before drawing anything', () => {
    expect(overview).toContain('{#if s.daysWithEntryLast30 > 0}')
    expect(dashboard).toContain('s.daysWithEntryLast30 > 0')
  })
})
