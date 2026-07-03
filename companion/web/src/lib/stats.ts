/* Pure, framework-free stats over a parsed BackupData. Easy to unit-test later. */

import type { BackupData, BackupEntry } from './backup'

export interface Summary {
  entryCount: number
  journalCount: number
  activityCount: number
  firstEntry: number | null
  lastEntry: number | null
  averageMood: number | null
  distribution: number[] // index 0..4 => mood level 1..5 counts
  currentStreakDays: number
}

const DAY_MS = 24 * 60 * 60 * 1000

function startOfLocalDay(ms: number): number {
  const d = new Date(ms)
  d.setHours(0, 0, 0, 0)
  return d.getTime()
}

export function summarize(data: BackupData): Summary {
  const entries = [...data.entries].sort((a, b) => a.dateTime - b.dateTime)
  const distribution = [0, 0, 0, 0, 0]
  let moodSum = 0
  for (const e of entries) {
    if (e.moodLevel >= 1 && e.moodLevel <= 5) distribution[e.moodLevel - 1]++
    moodSum += e.moodLevel
  }
  return {
    entryCount: entries.length,
    journalCount: data.journal?.length ?? 0,
    activityCount: data.activities.filter((a) => !a.archived).length,
    firstEntry: entries.length ? entries[0].dateTime : null,
    lastEntry: entries.length ? entries[entries.length - 1].dateTime : null,
    averageMood: entries.length ? moodSum / entries.length : null,
    distribution,
    currentStreakDays: currentStreak(entries),
  }
}

/** Consecutive days (ending today or the most recent logged day) with >=1 entry. */
function currentStreak(sortedEntries: BackupEntry[]): number {
  if (!sortedEntries.length) return 0
  const days = new Set(sortedEntries.map((e) => startOfLocalDay(e.dateTime)))
  const today = startOfLocalDay(Date.now())
  let cursor = days.has(today) ? today : today - DAY_MS
  // If the most recent entry is older than yesterday, the streak is 0.
  const mostRecent = startOfLocalDay(sortedEntries[sortedEntries.length - 1].dateTime)
  if (mostRecent < cursor) return 0
  let streak = 0
  while (days.has(cursor)) {
    streak++
    cursor -= DAY_MS
  }
  return streak
}

export interface DailyMood {
  day: number // start-of-day epoch millis
  avg: number
}

/** Average mood per day, chronologically — feeds the trend sparkline. */
export function dailyMood(data: BackupData): DailyMood[] {
  const byDay = new Map<number, { sum: number; n: number }>()
  for (const e of data.entries) {
    const day = startOfLocalDay(e.dateTime)
    const cur = byDay.get(day) ?? { sum: 0, n: 0 }
    cur.sum += e.moodLevel
    cur.n++
    byDay.set(day, cur)
  }
  return [...byDay.entries()]
    .sort((a, b) => a[0] - b[0])
    .map(([day, { sum, n }]) => ({ day, avg: sum / n }))
}

export function formatDate(ms: number | null): string {
  if (!ms) return '—'
  return new Date(ms).toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' })
}

// --- interactive dashboard helpers ---

export type RangeDays = 30 | 90 | 365 | 'all'

/** Daily mood restricted to the last N days (relative to the most recent entry). */
export function dailyMoodInRange(data: BackupData, range: RangeDays): DailyMood[] {
  const all = dailyMood(data)
  if (range === 'all' || all.length === 0) return all
  const last = all[all.length - 1].day
  const cutoff = last - (range - 1) * DAY_MS
  return all.filter((d) => d.day >= cutoff)
}

export interface ActivityAssoc {
  activityId: number
  name: string
  count: number
  avgMood: number
  delta: number // avgMood minus the overall average (association, not causation)
}

/** Average mood on entries tagged with each (non-archived) activity, vs the overall average. */
export function activityAssociation(data: BackupData): ActivityAssoc[] {
  const moodByEntry = new Map(data.entries.map((e) => [e.id, e.moodLevel]))
  const overall = data.entries.length ? data.entries.reduce((a, e) => a + e.moodLevel, 0) / data.entries.length : 0
  const byActivity = new Map<number, number[]>()
  for (const ref of data.refs) {
    const mood = moodByEntry.get(ref.entryId)
    if (mood == null) continue
    const arr = byActivity.get(ref.activityId) ?? []
    arr.push(mood)
    byActivity.set(ref.activityId, arr)
  }
  const out: ActivityAssoc[] = []
  for (const act of data.activities) {
    if (act.archived) continue
    const moods = byActivity.get(act.id)
    if (!moods || moods.length === 0) continue
    const avg = moods.reduce((a, b) => a + b, 0) / moods.length
    out.push({ activityId: act.id, name: act.name, count: moods.length, avgMood: round2(avg), delta: round2(avg - overall) })
  }
  return out.sort((a, b) => b.delta - a.delta)
}

export interface AssessmentSeries {
  key: string
  points: { dateTime: number; score: number; band: string }[]
  latestBand: string
}

/** Self-check (assessment) score history grouped by instrument key, chronological. */
export function assessmentSeries(data: BackupData): AssessmentSeries[] {
  const byKey = new Map<string, { dateTime: number; score: number; band: string }[]>()
  for (const a of data.assessments ?? []) {
    const arr = byKey.get(a.key) ?? []
    arr.push({ dateTime: a.dateTime, score: a.score, band: a.bandLabel })
    byKey.set(a.key, arr)
  }
  return [...byKey.entries()].map(([key, pts]) => {
    pts.sort((a, b) => a.dateTime - b.dateTime)
    return { key, points: pts, latestBand: pts[pts.length - 1]?.band ?? '—' }
  })
}

function round2(n: number): number {
  return Math.round(n * 100) / 100
}
