import { describe, it, expect } from 'vitest'
import { activityAssociation, assessmentSeries, dailyMoodInRange } from './stats'
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
