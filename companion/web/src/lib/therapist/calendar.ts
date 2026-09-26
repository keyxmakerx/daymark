/*
 * THE CLINICIAN'S CALENDAR — the cadence surface, as pure data.
 *
 * Nothing in this file imports Svelte or touches the DOM. The month grid, the four kinds of
 * record and their shapes, and the bucketing of a backup's records into days are shared with the
 * person's own month and live in calendar/core.ts, which this module re-exports whole, so the
 * clinician's screen and its tests import one module. What is the clinician's alone is here: the
 * twelve-week ribbon, a day and a week described by counts, and the ribbon's short date. The
 * component that renders it all, components/therapist/CalendarScreen.svelte, is a projector.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * WHAT THIS MODULE REFUSES TO COMPUTE
 *
 * No percentage, no completion rate, no streak, no adherence figure, no comparison of one week
 * against another, and no sleep efficiency (which is a percentage that grades a night). The
 * ribbon reports counts and presence. A week with nothing in it reports `empty: true` and is
 * drawn blank — absence renders as absence, and never as a failure, because a person who logged
 * nothing for three weeks may have been having the worst month of their life.
 *
 * Mood is carried to the clinician as a WORD, never painted on a colour ramp: this surface is not
 * licensed to paint a person's reported experience, and the honest fallback is the label they
 * already have. The events carry a check-in's level because the person's own month draws it;
 * nothing here or in CalendarScreen reads it.
 */

import {
  EVENT_KINDS,
  KIND_LABEL,
  MONTH_NAMES,
  NOTHING_RECORDED,
  civilFromDays,
  formatDayLong,
  startOfWeek,
  zeroCounts,
  type CalendarEvent,
  type DayBucket,
  type EpochDay,
  type EventKind,
  type WeekStart,
} from '../calendar/core'

export * from '../calendar/core'

/** "1 Jun" — the ribbon's week label, short enough for a twelve-column strip. */
export function formatDayShort(day: EpochDay): string {
  const { month, day: dom } = civilFromDays(day)
  return `${dom} ${MONTH_NAMES[month - 1]!.slice(0, 3)}`
}

/* ═══════════════════════════════════════════════════════════════════════════
   Saying out loud what a cell shows.

   These are the strings a screen reader gets in place of the geometry, so they are the ONLY
   description of the calendar some people will ever have. They live here, tested, rather than
   being assembled in the template, because the wording is the honesty-sensitive part: a day
   with nothing on it says "nothing recorded" and never "missed", "incomplete", "no data" or
   anything else that grades the person whose day it was.
   ═══════════════════════════════════════════════════════════════════════════ */

/** "2 check-ins, 1 self-check". Empty string when nothing is present — the caller chooses the words for that. */
export function describeCounts(counts: Record<EventKind, number>): string {
  return EVENT_KINDS.filter((k) => counts[k] > 0)
    .map((k) => `${counts[k]} ${KIND_LABEL[k].toLowerCase()}${counts[k] === 1 ? '' : 's'}`)
    .join(', ')
}

/** The accessible name of a day cell. */
export function describeDay(day: EpochDay, bucket: DayBucket | undefined): string {
  const body = bucket ? describeCounts(bucket.counts) : ''
  return `${formatDayLong(day)}: ${body || NOTHING_RECORDED}`
}

/** The accessible name of a ribbon column. */
export function describeWeek(week: RibbonWeek): string {
  const body = describeCounts(week.counts)
  return `Week of ${formatDayLong(week.start)}: ${body || NOTHING_RECORDED}`
}

/* ═══════════════════════════════════════════════════════════════════════════
   The twelve-week ribbon.
   ═══════════════════════════════════════════════════════════════════════════ */

export interface RibbonWeek {
  start: EpochDay
  end: EpochDay
  counts: Record<EventKind, number>
  /** Kinds present this week, in draw order. Empty array for a week with nothing in it. */
  kinds: EventKind[]
  /** How many distinct days in the week carry anything. Reported, never divided by anything. */
  daysWithAny: number
  /**
   * Days of this week that have actually happened as of the anchor. The trailing week is usually
   * partial, and a partial week must not be read as a week where the rest was missed.
   */
  daysObserved: number
  /** Nothing was recorded. Draws blank — not red, not a gap with a label on it. */
  empty: boolean
}

/**
 * Twelve weeks ending with the week that contains `anchorDay`, oldest first.
 *
 * There is deliberately no rate, no percentage, no total-against-target and no comparison between
 * weeks. The ribbon answers "what happened, week by week" and stops there. Any arithmetic that
 * turned these counts into a single figure would be a compliance score wearing a sparkline.
 */
export function buildRibbon(
  events: CalendarEvent[],
  anchorDay: EpochDay,
  weekStart: WeekStart = 'mon',
  weekCount = 12,
): RibbonWeek[] {
  const lastWeekStart = startOfWeek(anchorDay, weekStart)
  const firstWeekStart = lastWeekStart - 7 * (weekCount - 1)

  const weeks: RibbonWeek[] = []
  for (let i = 0; i < weekCount; i++) {
    const start = firstWeekStart + 7 * i
    weeks.push({
      start,
      end: start + 6,
      counts: zeroCounts(),
      kinds: [],
      daysWithAny: 0,
      daysObserved: Math.max(0, Math.min(7, anchorDay - start + 1)),
      empty: true,
    })
  }

  const daysSeen = new Map<number, Set<EpochDay>>()
  for (const ev of events) {
    const index = Math.floor((ev.day - firstWeekStart) / 7)
    if (index < 0 || index >= weekCount) continue
    const week = weeks[index]!
    week.counts[ev.kind]++
    let seen = daysSeen.get(index)
    if (!seen) {
      seen = new Set<EpochDay>()
      daysSeen.set(index, seen)
    }
    seen.add(ev.day)
  }

  for (let i = 0; i < weekCount; i++) {
    const week = weeks[i]!
    week.kinds = EVENT_KINDS.filter((k) => week.counts[k] > 0)
    week.daysWithAny = daysSeen.get(i)?.size ?? 0
    week.empty = week.kinds.length === 0
  }

  return weeks
}
