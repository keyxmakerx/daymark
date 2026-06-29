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
