/*
 * THE PERSON'S OWN MONTH, as pure data (#335).
 *
 * What the owner's month calendar draws on each day, what a screen reader hears for it, and the
 * records the day panel lists. Nothing here imports Svelte or touches the DOM; the component,
 * components/calendar/MonthCalendar.svelte, draws what this returns and decides nothing about a
 * day. The grid, the four kinds and the bucketing are the clinician calendar's (calendar/core.ts);
 * what a day shows follows the phone's month (app/src/main/java/com/daymark/app/ui/calendar/
 * CalendarDays.kt, #397), so a person who looks at both sees the same thing.
 *
 * THE RULES A DAY KEEPS
 *
 *  · Every day is the same plain square, whatever it holds. A day with nothing on it has no marks
 *    and is drawn exactly like every other day — nothing dimmer, dotted, struck or counted — and a
 *    screen reader hears "nothing recorded" after its date. There is no field below that says a
 *    day is empty, so there is nothing for a renderer to treat differently.
 *  · Each check-in is its own mark: a filled square in its own mood's colour, beside that mood's
 *    word, in the order they were made. Nothing blends two moods or colours a day by an average: a
 *    day with a Bad and a Good is a Bad square and a Good one. At most six, as on the phone: a
 *    fuller day keeps the first check-in of every mood it holds, then its earliest others, and
 *    never says how many it left out; the day panel lists every one.
 *  · A journal entry, a sleep log and a self-check each add their kind's shape once — ring, bar,
 *    slash, never a tick — however many the day holds.
 *  · Nothing here computes a figure: no count, no average, no best or worst day.
 *
 * THE DAY PANEL is the calendar's text equivalent (#259): each record's kind, its time, and for a
 * check-in its mood's word, for a self-check the self-check's name and its band's word. It shows
 * no self-check total. The phone's own view of a day lists entries only and shows no self-check
 * there at all, so it shows no total in the same place, and neither does this.
 */

import type { MoodLevel } from '../mood'
import {
  EVENT_KINDS,
  KIND_LABEL,
  NOTHING_RECORDED,
  buildMonthGrid,
  formatClock,
  formatDayLong,
  formatMonthTitle,
  moodWord,
  type CalendarEvent,
  type DayBucket,
  type EpochDay,
  type EventKind,
  type WeekStart,
} from './core'

/**
 * The most check-in squares one day draws: the phone's `CalendarDays.MAX_DOTS`. At least five, the
 * number of mood levels, so a day with more check-ins than this still shows every mood it holds.
 */
export const MAX_CHECKIN_MARKS = 6

/** One mark in a day's cell, in the order drawn. */
export interface DayMark {
  kind: EventKind
  /**
   * A check-in's mood: the square's colour and the word beside it. Null on the other kinds, and on
   * a check-in whose mood the file does not say, which is drawn as the plain square with no word.
   */
  mood: { level: MoodLevel; word: string } | null
}

/** A day of the month, as the grid draws it. */
export interface OwnDay {
  day: EpochDay
  /** 1–31, drawn in ink on every day alike. */
  dayOfMonth: number
  isToday: boolean
  /** In the order drawn. Empty for a day with nothing on it, which is drawn exactly like any other. */
  marks: DayMark[]
  /** What a screen reader hears for the day: its date, then each mark in the order drawn. */
  label: string
}

export interface OwnMonth {
  year: number
  month: number
  /** "September 2026". */
  title: string
  /** Column headings, rotated to the week's first day. */
  weekdayLabels: string[]
  /** Rows of seven. A cell outside the month is null: blank space, not a day. */
  weeks: (OwnDay | null)[][]
}

/** One record in the day panel. */
export interface DayRecord {
  /** Stable across rebuilds, for a keyed list. */
  key: string
  kind: EventKind
  /** "09:14"; a sleep log's bed and wake times, "23:10–07:05"; empty where the file holds no time. */
  time: string
  /** A check-in's mood, for its square and word; null on the other kinds. */
  mood: { level: MoodLevel; word: string } | null
  /** A self-check's name and its band's word, "Daily wellbeing self-check · Middling"; empty on the rest. */
  text: string
}

/** Records made at the same moment go in the order they were made, as on the phone. */
const byMoment = (a: CalendarEvent, b: CalendarEvent) =>
  a.at - b.at || EVENT_KINDS.indexOf(a.kind) - EVENT_KINDS.indexOf(b.kind) || a.id - b.id

/**
 * Which of a day's check-ins draw a square, in the order made: every one up to six; past six, the
 * first of each mood the day holds, then the earliest of the rest. Every one returned is one of
 * the day's own; none is made up. The phone's `CalendarDays.dots`.
 */
export function keptCheckins<T>(checkins: T[], levelOf: (c: T) => number | null): T[] {
  if (checkins.length <= MAX_CHECKIN_MARKS) return checkins
  const keep = new Array<boolean>(checkins.length).fill(false)
  const seen = new Set<number | null>()
  let kept = 0
  for (let i = 0; i < checkins.length && kept < MAX_CHECKIN_MARKS; i++) {
    const level = levelOf(checkins[i]!)
    if (!seen.has(level)) {
      seen.add(level)
      keep[i] = true
      kept++
    }
  }
  for (let i = 0; i < checkins.length && kept < MAX_CHECKIN_MARKS; i++) {
    if (!keep[i]) {
      keep[i] = true
      kept++
    }
  }
  return checkins.filter((_, i) => keep[i])
}

const moodOf = (ev: CalendarEvent, labels: unknown) =>
  ev.level === null ? null : { level: ev.level, word: moodWord(ev.level, labels) }

/** The marks one day draws, in the order drawn: its check-ins, then one shape for each other kind it holds. */
export function dayMarks(events: CalendarEvent[], labels: unknown): DayMark[] {
  const checkins = events.filter((e) => e.kind === 'checkin').sort(byMoment)
  const marks: DayMark[] = keptCheckins(checkins, (e) => e.level).map((e) => ({ kind: 'checkin', mood: moodOf(e, labels) }))
  for (const kind of EVENT_KINDS) {
    if (kind !== 'checkin' && events.some((e) => e.kind === kind)) marks.push({ kind, mood: null })
  }
  return marks
}

/** One mark, said: "check-in Good", "check-in" where the mood is not known, "journal", "sleep log", "self-check". */
export function sayMark(mark: DayMark): string {
  const kind = KIND_LABEL[mark.kind].toLowerCase()
  return mark.mood ? `${kind} ${mark.mood.word}` : kind
}

/**
 * What a screen reader hears for a day: "Wed 16 Sep 2026: check-in Good, check-in Meh, journal",
 * "Wed 16 Sep 2026: nothing recorded", and "…, today: …" for today, because the ring that marks
 * today is information too. The words are exactly the marks, so a listener is told what a glance
 * shows: never a count, never a summary.
 */
export function describeOwnDay(day: EpochDay, marks: DayMark[], isToday: boolean): string {
  const said = marks.length === 0 ? NOTHING_RECORDED : marks.map(sayMark).join(', ')
  return `${formatDayLong(day)}${isToday ? ', today' : ''}: ${said}`
}

/** The month as the grid draws it, from a backup's records bucketed by day. */
export function buildOwnMonth(
  buckets: Map<EpochDay, DayBucket>,
  labels: unknown,
  year: number,
  month: number,
  today: EpochDay,
  weekStart: WeekStart = 'mon',
): OwnMonth {
  const grid = buildMonthGrid(year, month, weekStart)
  const weeks = grid.weeks.map((row) =>
    row.map((cell): OwnDay | null => {
      if (!cell.inMonth) return null
      const marks = dayMarks(buckets.get(cell.day)?.events ?? [], labels)
      const isToday = cell.day === today
      return { day: cell.day, dayOfMonth: cell.date.day, isToday, marks, label: describeOwnDay(cell.day, marks, isToday) }
    }),
  )
  return { year, month, title: formatMonthTitle(year, month), weekdayLabels: grid.weekdayLabels, weeks }
}

/** A sleep log's time: when it began and ended, "23:10–07:05"; empty where either is not a time. */
function sleepTime(ev: CalendarEvent, zone: 'local' | 'utc'): string {
  const s = ev.sleep
  if (!s || !Number.isFinite(s.bedTime) || !Number.isFinite(s.wakeTime)) return ''
  return `${formatClock(s.bedTime, zone)}–${formatClock(s.wakeTime, zone)}`
}

/** Every record of one day, in the order made, for the day panel. */
export function dayRecords(events: CalendarEvent[], labels: unknown, zone: 'local' | 'utc' = 'local'): DayRecord[] {
  return [...events].sort(byMoment).map((ev) => ({
    key: ev.key,
    kind: ev.kind,
    time: ev.kind === 'sleep' ? sleepTime(ev, zone) : formatClock(ev.at, zone),
    mood: ev.kind === 'checkin' ? moodOf(ev, labels) : null,
    text: ev.kind === 'selfcheck' ? [ev.title, ev.band].filter(Boolean).join(' · ') : '',
  }))
}
