/*
 * CALENDAR — the days a person's records fall on, as pure data, for both calendars.
 *
 * Two surfaces draw a month from a backup: the clinician's calendar (therapist/calendar.ts and
 * components/therapist/CalendarScreen.svelte), over a share, and the person's own month
 * (calendar/ownMonth.ts and components/calendar/MonthCalendar.svelte), over their own backup
 * (#335). This file is what they share: the month grid, the four kinds of record and their shapes,
 * the bucketing of a backup's records into days, and the words for a day and a time. Nothing here
 * imports Svelte or touches the DOM. Its tests are therapist/calendar.test.ts, which exercises all
 * of it, and calendar/ownMonth.test.ts.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * WHY DATE ARITHMETIC IS DONE IN INTEGERS AND NOT IN `Date`
 *
 * Every calendar bug anyone has ever shipped is one of three things: a month grid that starts on
 * the wrong weekday, a last row that is short by a day, or a February that is the wrong length.
 * All three come from doing calendar maths with a `Date` object, which carries a timezone, a
 * clock, and a set of overflow behaviours nobody remembers.
 *
 * So the unit here is the EPOCH DAY: an integer count of days since 1970-01-01 in the proleptic
 * Gregorian civil calendar. Days are added by adding 1. Weeks are seven of them. The conversions
 * to and from a civil {year, month, day} are Howard Hinnant's `days_from_civil` /
 * `civil_from_days` — exact integer arithmetic, correct across leap years, century non-leap years
 * (1900), quadricentennial leap years (2000), and negative years. No `Date`, no timezone, no
 * daylight saving, in any of it.
 *
 * `Date` appears in exactly one place — {@link epochDayLocal}, where a real timestamp has to be
 * resolved into the civil day a person would say it happened on. That function is the entire
 * timezone surface of the calendar, it is injectable, and {@link epochDayUtc} is the deterministic
 * alternative the tests use. This is NOT a location feature: it is the runtime's own civil day,
 * never a place, never a named zone, never offered as a choice.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * WHY THESE FOUR EVENT KINDS, AND NO FIFTH
 *
 * `BackupData` (lib/backup.ts) carries exactly four record types that are *dated occurrences* —
 * things that happened on a day and can therefore be drawn on one:
 *
 *   checkin   BackupEntry      dateTime  — a mood check-in
 *   journal   BackupJournal    dateTime  — a written entry
 *   sleep     BackupSleepLog   night     — a logged night
 *   selfcheck BackupAssessment dateTime  — a completed self-check, with a score and a band
 *
 * Everything else in the bundle is deliberately NOT an event kind:
 *   · `activities` and `refs` are labels attached to a check-in, not occurrences of their own —
 *     they have no timestamp at all, only the entry's.
 *   · `goals` carry `createdAt` and `targetPerWeek`. A target-per-week drawn on a calendar is a
 *     quota, and a calendar that draws a quota next to what actually happened has built a
 *     compliance score whether or not it prints a number. Goals are excluded on that ground, not
 *     for lack of a field.
 *   · treatments, trackers, trackerLogs, reminders, photos, achievements and thoughtRecords are
 *     not typed in the web layer and are not available to invent from.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * WHY SHAPE AND NOT COLOUR
 *
 * A day cell's marks are 4×11 px at their largest. At that size hue is the least reliable channel
 * there is: it fails for a large minority of readers, it fails on a projector, it fails on a
 * printout, and it fails in a greyscale screenshot. So the four kinds are distinguished by
 * GEOMETRY — filled square, hollow ring, horizontal bar, diagonal slash — drawn by one component,
 * components/calendar/CalendarMark.svelte, in ink. Colour never says which kind a mark is.
 * {@link KIND_SHAPE} is asserted injective in the test suite.
 *
 * The one colour either calendar draws is a mood's, and only the person's own month draws it: a
 * check-in's square is filled in the colour of its mood, with the mood's word beside it, so the
 * square still says "check-in" in greyscale and the word says which mood. The clinician's calendar
 * draws the word alone.
 *
 * The self-check mark is a SLASH and not a check mark. A tick on a mental-health surface reads as
 * "done, and well done" — the software grading someone. The slash says only "this happened".
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * WHAT THIS MODULE REFUSES TO COMPUTE
 *
 * No percentage, no completion rate, no streak, no adherence figure, no average, no best or worst
 * day, and no sleep efficiency (which is a percentage that grades a night). A day with nothing in
 * it has no bucket at all and is drawn blank — absence renders as absence, and never as a failure,
 * because a person who logged nothing for three weeks may have been having the worst month of
 * their life.
 */

import type { BackupData } from '../backup'
import { getInstrument } from '../instruments'
import type { ProvenanceTier } from '../instruments/types'
import { moodWord, type MoodLevel } from '../mood'

/** Mood as a WORD: the bundle's own name for the level first, the app's fixed scale as the fallback. */
export { moodWord }

/* ═══════════════════════════════════════════════════════════════════════════
   Civil-date arithmetic, in integers.
   ═══════════════════════════════════════════════════════════════════════════ */

/** Days since 1970-01-01 in the proleptic Gregorian calendar. The unit of this whole module. */
export type EpochDay = number

/** A calendar day as a person writes it. `month` is 1..12, not 0..11 — that off-by-one is a bug factory. */
export interface CivilDate {
  year: number
  month: number
  day: number
}

export const MS_PER_DAY = 86_400_000

/**
 * Civil date → epoch day (Hinnant's `days_from_civil`).
 *
 * The algorithm shifts the era so that March starts the year, which is what makes the leap day
 * fall at the END of a year and removes every special case for it — including 1900 (not a leap
 * year, being a century that is not a multiple of 400) and 2000 (a leap year, being one).
 */
export function daysFromCivil(year: number, month: number, day: number): EpochDay {
  const y = year - (month <= 2 ? 1 : 0)
  const era = Math.floor((y >= 0 ? y : y - 399) / 400)
  const yoe = y - era * 400 // [0, 399]
  const doy = Math.floor((153 * (month + (month > 2 ? -3 : 9)) + 2) / 5) + day - 1 // [0, 365]
  const doe = yoe * 365 + Math.floor(yoe / 4) - Math.floor(yoe / 100) + doy // [0, 146096]
  return era * 146_097 + doe - 719_468
}

/** Epoch day → civil date (Hinnant's `civil_from_days`); the exact inverse of {@link daysFromCivil}. */
export function civilFromDays(z: EpochDay): CivilDate {
  const shifted = z + 719_468
  const era = Math.floor((shifted >= 0 ? shifted : shifted - 146_096) / 146_097)
  const doe = shifted - era * 146_097 // [0, 146096]
  const yoe = Math.floor((doe - Math.floor(doe / 1460) + Math.floor(doe / 36524) - Math.floor(doe / 146_096)) / 365)
  const y = yoe + era * 400
  const doy = doe - (365 * yoe + Math.floor(yoe / 4) - Math.floor(yoe / 100)) // [0, 365]
  const mp = Math.floor((5 * doy + 2) / 153) // [0, 11]
  const day = doy - Math.floor((153 * mp + 2) / 5) + 1 // [1, 31]
  const month = mp + (mp < 10 ? 3 : -9) // [1, 12]
  return { year: y + (month <= 2 ? 1 : 0), month, day }
}

/** 0 = Sunday … 6 = Saturday. Correct for negative epoch days (1970-01-01 was a Thursday). */
export function weekdaySunday0(z: EpochDay): number {
  return ((z % 7) + 11) % 7
}

/** Days in a month. Derived from the day arithmetic rather than from a table, so it cannot drift from it. */
export function daysInMonth(year: number, month: number): number {
  const next = addMonths(year, month, 1)
  return daysFromCivil(next.year, next.month, 1) - daysFromCivil(year, month, 1)
}

/** Month arithmetic that cannot land on month 0 or 13. */
export function addMonths(year: number, month: number, delta: number): { year: number; month: number } {
  const total = year * 12 + (month - 1) + delta
  return { year: Math.floor(total / 12), month: (((total % 12) + 12) % 12) + 1 }
}

/** Which day a week begins on. Monday by default — the working week a clinician reads in. */
export type WeekStart = 'mon' | 'sun'

/** The first day of the week containing `z`. */
export function startOfWeek(z: EpochDay, weekStart: WeekStart = 'mon'): EpochDay {
  const dow = weekdaySunday0(z)
  return z - (weekStart === 'sun' ? dow : (dow + 6) % 7)
}

/* ═══════════════════════════════════════════════════════════════════════════
   Resolving a timestamp to a day. The one timezone-aware function, isolated.
   ═══════════════════════════════════════════════════════════════════════════ */

/**
 * The civil day this timestamp falls on in the runtime's own local time — which is the day the
 * person would say it happened. Not a place, not a named zone, not a setting.
 */
export function epochDayLocal(ms: number): EpochDay {
  const d = new Date(ms)
  return daysFromCivil(d.getFullYear(), d.getMonth() + 1, d.getDate())
}

/** The UTC civil day. Deterministic everywhere, which is what makes the test suite meaningful. */
export function epochDayUtc(ms: number): EpochDay {
  return Math.floor(ms / MS_PER_DAY)
}

/** How a timestamp is resolved to a day. Injected so the tests do not depend on the machine's clock zone. */
export type DayResolver = (ms: number) => EpochDay

/* ═══════════════════════════════════════════════════════════════════════════
   The month grid.
   ═══════════════════════════════════════════════════════════════════════════ */

export interface MonthCell {
  /** Every cell is a real day — padding cells belong to the adjacent month, they are not holes. */
  day: EpochDay
  date: CivilDate
  /** False for the leading/trailing padding that fills the first and last rows. */
  inMonth: boolean
}

export interface MonthGrid {
  year: number
  month: number
  weekStart: WeekStart
  /** Column headings, already rotated to match `weekStart`. */
  weekdayLabels: string[]
  /** Rows of exactly seven cells. Between four and six rows, never a short one. */
  weeks: MonthCell[][]
}

const WEEKDAY_SHORT = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat']

export const MONTH_NAMES = [
  'January', 'February', 'March', 'April', 'May', 'June',
  'July', 'August', 'September', 'October', 'November', 'December',
]

/**
 * The grid for one month.
 *
 * The two failure modes this shape rules out by construction:
 *   · the grid STARTS at `startOfWeek(the 1st)`, so the first row can only begin on the week's own
 *     first day, whatever weekday the month opens on;
 *   · the grid ENDS at `startOfWeek(the last day) + 7`, so the final row is the week containing the
 *     last day, complete. The classic "last row short by one" comes from computing a fixed six
 *     rows, or from `ceil(days / 7)` without the leading offset; neither appears here.
 * The span is therefore always a whole number of weeks, and every row has seven cells.
 */
export function buildMonthGrid(year: number, month: number, weekStart: WeekStart = 'mon'): MonthGrid {
  const first = daysFromCivil(year, month, 1)
  const last = first + daysInMonth(year, month) - 1
  const gridStart = startOfWeek(first, weekStart)
  const gridEnd = startOfWeek(last, weekStart) + 7 // exclusive

  const weeks: MonthCell[][] = []
  for (let rowStart = gridStart; rowStart < gridEnd; rowStart += 7) {
    const row: MonthCell[] = []
    for (let i = 0; i < 7; i++) {
      const day = rowStart + i
      row.push({ day, date: civilFromDays(day), inMonth: day >= first && day <= last })
    }
    weeks.push(row)
  }

  const offset = weekStart === 'sun' ? 0 : 1
  const weekdayLabels = Array.from({ length: 7 }, (_, i) => WEEKDAY_SHORT[(i + offset) % 7]!)

  return { year, month, weekStart, weekdayLabels, weeks }
}

/* ═══════════════════════════════════════════════════════════════════════════
   The four event kinds and their shapes.
   ═══════════════════════════════════════════════════════════════════════════ */

export type EventKind = 'checkin' | 'journal' | 'sleep' | 'selfcheck'

/** Geometry, not hue. Four shapes that stay apart at 4×11 px and in greyscale. */
export type EventShape = 'square' | 'ring' | 'bar' | 'slash'

/** Stable draw and legend order. Also the order marks appear inside a day cell. */
export const EVENT_KINDS: EventKind[] = ['checkin', 'journal', 'sleep', 'selfcheck']

export const KIND_SHAPE: Record<EventKind, EventShape> = {
  checkin: 'square',
  journal: 'ring',
  sleep: 'bar',
  selfcheck: 'slash',
}

export const KIND_LABEL: Record<EventKind, string> = {
  checkin: 'Check-in',
  journal: 'Journal',
  sleep: 'Sleep log',
  selfcheck: 'Self-check',
}

/** The legend's words for each shape. Names the geometry, so the legend works without colour too. */
export const SHAPE_LABEL: Record<EventShape, string> = {
  square: 'filled square',
  ring: 'hollow ring',
  bar: 'horizontal bar',
  slash: 'diagonal slash',
}

/* ═══════════════════════════════════════════════════════════════════════════
   Events.
   ═══════════════════════════════════════════════════════════════════════════ */

/**
 * A number an agenda row shows, and where it came from.
 *
 * Provenance is per row, on every row that carries a measure. `tier` is the instrument's own
 * clinical-honesty tier and is non-null ONLY when the self-check resolves in the shipped catalog;
 * an unrecognised key gets `null` rather than a guessed tier, because inventing a provenance is
 * worse than admitting there isn't one. `source` is the plain-words fallback for measures that are
 * not instrument output at all — a mood level and a night's length are self-reports on the app's
 * own scales, and dressing them in an instrument badge would be a claim nobody made.
 */
export interface EventMeasure {
  label: string
  value: string
  tier: ProvenanceTier | null
  source: string
}

export interface CalendarEvent {
  kind: EventKind
  day: EpochDay
  /**
   * Epoch millis of the underlying record; drives the within-day ordering, and the time a day's
   * list shows for every kind but a sleep log, which shows its bed and wake times ({@link eventTime}).
   */
  at: number
  /** Stable across rebuilds — `kind:id` — so a keyed each block does not re-create rows. */
  key: string
  /** The record's own row id, which orders records made at the same moment, as the phone does. */
  id: number
  title: string
  detail: string
  /** Null where the row genuinely carries no measure (a journal entry is words, not a number). */
  measure: EventMeasure | null
  /**
   * A check-in's mood level, 1–5; null on every other kind, and on a check-in whose level cannot
   * be read. Only the person's own month draws it, as their colour for that mood beside its word.
   */
  level: MoodLevel | null
  /** A self-check's band word as the app wrote it; null on every other kind, and where it has none. */
  band: string | null
  /** A sleep log's bed and wake times, epoch millis; null on every other kind. */
  sleep: { bedTime: number; wakeTime: number } | null
}

const SELF_REPORT_MOOD = 'Self-reported, the app’s own 1–5 mood scale'
const SELF_REPORT_SLEEP = 'Self-reported sleep log'
const SELF_CHECK_SOURCE = 'Self-check, scored in the app'

/** A record's id as a number, for ordering; a malformed id orders as 0 rather than breaking the sort. */
const rowId = (id: unknown) => (typeof id === 'number' && Number.isFinite(id) ? id : 0)

/** Trimmed text, or null where there is none — including where the file holds something that is not text. */
const textOrNull = (value: unknown) => (typeof value === 'string' && value.trim() ? value.trim() : null)

/** A check-in's level on the 1–5 scale, or null where the file holds no number to read. */
function levelOf(value: unknown): MoodLevel | null {
  if (typeof value !== 'number' || !Number.isFinite(value)) return null
  return Math.min(5, Math.max(1, Math.round(value))) as MoodLevel
}

/** "7h 20m" / "45m" / "0m". Never a percentage: sleep efficiency would be a grade for a night. */
export function formatDuration(ms: number): string {
  if (!Number.isFinite(ms) || ms <= 0) return '0m'
  const totalMin = Math.round(ms / 60_000)
  const h = Math.floor(totalMin / 60)
  const m = totalMin % 60
  return h > 0 ? `${h}h ${m}m` : `${m}m`
}

/** Zero-padded 24-hour clock. `zone: 'utc'` exists so the tests assert an exact string. */
export function formatClock(ms: number, zone: 'local' | 'utc' = 'local'): string {
  const d = new Date(ms)
  const h = zone === 'utc' ? d.getUTCHours() : d.getHours()
  const m = zone === 'utc' ? d.getUTCMinutes() : d.getMinutes()
  return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}`
}

/**
 * The time a record shows in a day's list: "09:14"; a sleep log's bed and wake times, "23:10–07:05".
 *
 * A sleep log is never timed by `at`. Its `at` is the UTC midnight of its night, a marker for
 * ordering and not a time anybody recorded, and shown as a clock it reads as the hour they went to
 * bed — "02:00" in Central Europe (#416). A night whose file holds no bed or wake time shows no
 * time at all rather than one made up. Both calendars' day lists read this.
 */
export function eventTime(ev: CalendarEvent, zone: 'local' | 'utc' = 'local'): string {
  if (ev.kind !== 'sleep') return formatClock(ev.at, zone)
  const s = ev.sleep
  if (!s || !Number.isFinite(s.bedTime) || !Number.isFinite(s.wakeTime)) return ''
  return `${formatClock(s.bedTime, zone)}–${formatClock(s.wakeTime, zone)}`
}

/** "August 2026". */
export function formatMonthTitle(year: number, month: number): string {
  return `${MONTH_NAMES[month - 1]} ${year}`
}

/** "Sun 16 Aug 2026" — weekday derived from the epoch day, so it cannot disagree with the grid. */
export function formatDayLong(day: EpochDay): string {
  const { year, month, day: dom } = civilFromDays(day)
  return `${WEEKDAY_SHORT[weekdaySunday0(day)]} ${dom} ${MONTH_NAMES[month - 1]!.slice(0, 3)} ${year}`
}

/**
 * Every dated occurrence in the bundle, as one flat list, sorted oldest first.
 *
 * Records with a non-finite timestamp are dropped rather than bucketed into 1970: a corrupt row
 * silently landing on the first day of the epoch is worse than a row that is not drawn.
 */
export function buildEvents(data: BackupData, toDay: DayResolver = epochDayLocal): CalendarEvent[] {
  const out: CalendarEvent[] = []

  for (const e of data.entries ?? []) {
    if (!Number.isFinite(e.dateTime)) continue
    const word = moodWord(e.moodLevel, data.moodLabels)
    out.push({
      kind: 'checkin',
      day: toDay(e.dateTime),
      at: e.dateTime,
      key: `checkin:${e.id}`,
      id: rowId(e.id),
      title: 'Check-in',
      detail: e.note?.trim() ?? '',
      measure: { label: 'Mood', value: word, tier: null, source: SELF_REPORT_MOOD },
      level: levelOf(e.moodLevel),
      band: null,
      sleep: null,
    })
  }

  for (const j of data.journal ?? []) {
    if (!Number.isFinite(j.dateTime)) continue
    out.push({
      kind: 'journal',
      day: toDay(j.dateTime),
      at: j.dateTime,
      key: `journal:${j.id}`,
      id: rowId(j.id),
      title: j.title?.trim() ? j.title.trim() : 'Journal entry',
      detail: '',
      // A journal entry is words. There is no number on it, so there is no measure and no
      // provenance badge — and that absence is what proves the badge means something elsewhere.
      measure: null,
      level: null,
      band: null,
      sleep: null,
    })
  }

  for (const s of data.sleepLogs ?? []) {
    if (!Number.isFinite(s.night)) continue
    const inBed = s.wakeTime - s.bedTime
    out.push({
      kind: 'sleep',
      // `night` is ALREADY an epoch day and must not go through the resolver.
      //
      // `SleepLog.night` is `LocalDate.toEpochDay()` — "epoch-day of the morning you woke", the
      // column the app groups one record per night by (app/.../entity/SleepLog.kt). It arrives
      // here unchanged on both ingestion paths. Passing it to `toDay`, which expects millis, read
      // 20681 as 20.681 seconds after the epoch and put every sleep log a person has ever
      // recorded on 1 January 1970 — the bar shape and its legend row dead, "Time in bed" never
      // rendered, sleep absent from the ribbon, and every sleep event sorted to the head of the
      // list because 20681 is smaller than any real timestamp.
      day: s.night,
      // Ordered by the night's own marker, not by wake time, so a night sorts with its date.
      // Multiplied to millis so it is comparable with every other kind's `at`; unscaled, an epoch
      // day sorts below all of them.
      at: s.night * MS_PER_DAY,
      key: `sleep:${s.id}`,
      id: rowId(s.id),
      title: 'Sleep log',
      detail: s.note?.trim() ?? '',
      measure: {
        label: 'Time in bed',
        value: formatDuration(inBed),
        tier: null,
        source: SELF_REPORT_SLEEP,
      },
      level: null,
      band: null,
      sleep: { bedTime: s.bedTime, wakeTime: s.wakeTime },
    })
  }

  for (const a of data.assessments ?? []) {
    if (!Number.isFinite(a.dateTime)) continue
    const def = getInstrument(a.key)
    out.push({
      kind: 'selfcheck',
      day: toDay(a.dateTime),
      at: a.dateTime,
      key: `selfcheck:${a.id}`,
      id: rowId(a.id),
      title: def?.title ?? a.key,
      detail: '',
      measure: {
        label: 'Score',
        // The band label travels with the score, always. A raw sum on its own invites reading a
        // cut-off that does not exist; the band is the descriptive reading the app actually made.
        value: a.bandLabel?.trim() ? `${a.score} · ${a.bandLabel.trim()}` : String(a.score),
        tier: def?.provenance.tier ?? null,
        source: SELF_CHECK_SOURCE,
      },
      level: null,
      band: textOrNull(a.bandLabel),
      sleep: null,
    })
  }

  out.sort((x, y) => x.at - y.at || x.key.localeCompare(y.key))
  return out
}

/** The fixed phrase for absence. One spelling, so it cannot drift into a verdict. */
export const NOTHING_RECORDED = 'nothing recorded'

/* ═══════════════════════════════════════════════════════════════════════════
   Bucketing.
   ═══════════════════════════════════════════════════════════════════════════ */

export interface DayBucket {
  day: EpochDay
  events: CalendarEvent[]
  /** The distinct kinds present, in {@link EVENT_KINDS} order. One mark per kind in the cell. */
  kinds: EventKind[]
  /** Per-kind counts, so a day with three check-ins can say so without drawing three squares. */
  counts: Record<EventKind, number>
}

/** Every kind at nought: the starting point of a bucket or a week, never a thing that is drawn. */
export function zeroCounts(): Record<EventKind, number> {
  return { checkin: 0, journal: 0, sleep: 0, selfcheck: 0 }
}

/** Events grouped by day. Days with nothing are simply absent from the map — absence is absence. */
export function bucketByDay(events: CalendarEvent[]): Map<EpochDay, DayBucket> {
  const map = new Map<EpochDay, DayBucket>()
  for (const ev of events) {
    let bucket = map.get(ev.day)
    if (!bucket) {
      bucket = { day: ev.day, events: [], kinds: [], counts: zeroCounts() }
      map.set(ev.day, bucket)
    }
    bucket.events.push(ev)
    bucket.counts[ev.kind]++
  }
  for (const bucket of map.values()) {
    bucket.events.sort((a, b) => a.at - b.at || a.key.localeCompare(b.key))
    bucket.kinds = EVENT_KINDS.filter((k) => bucket.counts[k] > 0)
  }
  return map
}
