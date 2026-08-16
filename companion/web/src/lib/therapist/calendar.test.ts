/*
 * Tests for the calendar's pure module.
 *
 * THE RULE THIS FILE IS WRITTEN UNDER. A test that cannot fail is worse than no test, and the
 * shape most likely to be vacuous here is "X is absent" — an empty grid, a week with nothing in
 * it, a dropped record, a missing measure. Every one of those below is preceded by an assertion
 * that the same detector SEES X when X is present. Where that pairing is not obvious the comment
 * says which line is the witness.
 *
 * The second rule: no test may depend on the machine's clock zone. All bucketing assertions use
 * the injected `epochDayUtc` resolver; the two places that must exercise local time build their
 * timestamps from LOCAL components (`new Date(y, m, d, ...)`) and compare against the same local
 * civil date, which holds in every zone.
 */

import { describe, it, expect } from 'vitest'
import type { BackupData } from '../backup'
import {
  MS_PER_DAY,
  EVENT_KINDS,
  KIND_SHAPE,
  KIND_LABEL,
  SHAPE_LABEL,
  MONTH_NAMES,
  NOTHING_RECORDED,
  addMonths,
  bucketByDay,
  buildEvents,
  buildMonthGrid,
  buildRibbon,
  civilFromDays,
  daysFromCivil,
  daysInMonth,
  describeCounts,
  describeDay,
  describeWeek,
  epochDayLocal,
  epochDayUtc,
  formatClock,
  formatDayLong,
  formatDayShort,
  formatDuration,
  formatMonthTitle,
  moodWord,
  startOfWeek,
  weekdaySunday0,
  type CalendarEvent,
  type EpochDay,
  type WeekStart,
} from './calendar'

/* ── fixtures ─────────────────────────────────────────────────────────────── */

/** 2026-08-16 (a Sunday) at 00:00 UTC. Every ms fixture is an offset from this. */
const AUG_16_2026 = 20_681 as EpochDay
const AUG_16_MS = AUG_16_2026 * MS_PER_DAY

/** A minimally-valid bundle; each test overrides only the arrays it cares about. */
function bundle(over: Partial<BackupData> = {}): BackupData {
  return {
    version: 3,
    exportedAt: AUG_16_MS,
    entries: [],
    activities: [],
    refs: [],
    journal: [],
    goals: [],
    sleepLogs: [],
    assessments: [],
    moodLabels: {},
    moodColors: {},
    ...over,
  }
}

const at = (day: EpochDay, hour = 12) => day * MS_PER_DAY + hour * 3_600_000

/* ═══════════════════════════════════════════════════════════════════════════
   Civil-date arithmetic.
   ═══════════════════════════════════════════════════════════════════════════ */

describe('epoch-day arithmetic', () => {
  it('anchors on known days', () => {
    expect(daysFromCivil(1970, 1, 1)).toBe(0)
    expect(daysFromCivil(2026, 8, 16)).toBe(AUG_16_2026)
    expect(civilFromDays(0)).toEqual({ year: 1970, month: 1, day: 1 })
    expect(civilFromDays(AUG_16_2026)).toEqual({ year: 2026, month: 8, day: 16 })
    // Before the epoch, where a naive `%` or a truncating divide goes wrong.
    expect(daysFromCivil(1969, 12, 31)).toBe(-1)
    expect(civilFromDays(-1)).toEqual({ year: 1969, month: 12, day: 31 })
    expect(civilFromDays(daysFromCivil(1900, 2, 28))).toEqual({ year: 1900, month: 2, day: 28 })
  })

  it('round-trips every day across two century boundaries, one day at a time', () => {
    // The strongest available statement about the pair: walk 1899-01-01 → 2101-01-01 and check
    // the conversion is a bijection AND that consecutive civil days are consecutive integers.
    // A single off-by-one anywhere in the era arithmetic breaks the monotonic step immediately.
    // Mismatches are collected and asserted once rather than through 150k expect() calls; the
    // `steps` count below is what proves the walk happened at all.
    const start = daysFromCivil(1899, 1, 1)
    const end = daysFromCivil(2101, 1, 1)
    expect(end - start).toBeGreaterThan(70_000) // the walk really is long

    const roundTrip = (z: EpochDay) => {
      const c = civilFromDays(z)
      return daysFromCivil(c.year, c.month, c.day)
    }
    // The detector detects: a deliberately wrong day does NOT round-trip to the one asked about.
    expect(roundTrip(start)).toBe(start)
    expect(roundTrip(start + 1)).not.toBe(start)

    const broken: number[] = []
    let steps = 0
    for (let z = start; z < end; z++) {
      if (roundTrip(z) !== z) broken.push(z)
      steps++
    }
    expect(broken).toEqual([])
    expect(steps).toBe(end - start)
  })

  it('gets February right in every kind of year', () => {
    expect(daysInMonth(2023, 2)).toBe(28) // ordinary
    expect(daysInMonth(2024, 2)).toBe(29) // leap
    expect(daysInMonth(1900, 2)).toBe(28) // century, not divisible by 400 — NOT a leap year
    expect(daysInMonth(2000, 2)).toBe(29) // divisible by 400 — a leap year
    expect(daysInMonth(2100, 2)).toBe(28)
    // The other month lengths, so a "February is special-cased" regression is caught here too.
    expect([1, 3, 5, 7, 8, 10, 12].map((m) => daysInMonth(2026, m))).toEqual([31, 31, 31, 31, 31, 31, 31])
    expect([4, 6, 9, 11].map((m) => daysInMonth(2026, m))).toEqual([30, 30, 30, 30])
  })

  it('names the weekday of a day, including before the epoch', () => {
    expect(weekdaySunday0(0)).toBe(4) // 1970-01-01 was a Thursday
    expect(weekdaySunday0(AUG_16_2026)).toBe(0) // 2026-08-16 is a Sunday
    expect(weekdaySunday0(-1)).toBe(3) // 1969-12-31 was a Wednesday
    // Every residue is reachable — a formula that collapsed to one value would pass the three
    // assertions above if they happened to agree.
    expect(new Set([0, 1, 2, 3, 4, 5, 6].map((i) => weekdaySunday0(AUG_16_2026 + i))).size).toBe(7)
  })

  it('walks months without landing on month 0 or 13', () => {
    expect(addMonths(2026, 1, -1)).toEqual({ year: 2025, month: 12 })
    expect(addMonths(2026, 12, 1)).toEqual({ year: 2027, month: 1 })
    expect(addMonths(2026, 8, 0)).toEqual({ year: 2026, month: 8 })
    expect(addMonths(2026, 8, -20)).toEqual({ year: 2024, month: 12 })
    expect(addMonths(2026, 8, 29)).toEqual({ year: 2029, month: 1 })
  })

  it('starts a week on the requested day', () => {
    // 2026-08-16 is a Sunday, so Monday-start rewinds six days and Sunday-start rewinds none.
    expect(startOfWeek(AUG_16_2026, 'mon')).toBe(AUG_16_2026 - 6)
    expect(startOfWeek(AUG_16_2026, 'sun')).toBe(AUG_16_2026)
    expect(weekdaySunday0(startOfWeek(AUG_16_2026 + 3, 'mon'))).toBe(1)
    expect(weekdaySunday0(startOfWeek(AUG_16_2026 + 3, 'sun'))).toBe(0)
    // Idempotent, which is what the grid relies on.
    expect(startOfWeek(startOfWeek(AUG_16_2026 + 4, 'mon'), 'mon')).toBe(startOfWeek(AUG_16_2026 + 4, 'mon'))
  })

  it('resolves a timestamp to a day in UTC and in local time', () => {
    expect(epochDayUtc(AUG_16_MS)).toBe(AUG_16_2026)
    expect(epochDayUtc(AUG_16_MS + MS_PER_DAY - 1)).toBe(AUG_16_2026)
    expect(epochDayUtc(-1)).toBe(-1) // the instant before the epoch is the day before it

    // Zone-independent by construction: the timestamp is built from LOCAL components and compared
    // against the same LOCAL civil date. 23:30 is the hour a UTC bucketer would get wrong for
    // anyone east of Greenwich, which is the point of having both resolvers.
    const lateLocal = new Date(2026, 1, 28, 23, 30, 0, 0).getTime()
    expect(epochDayLocal(lateLocal)).toBe(daysFromCivil(2026, 2, 28))
    const earlyLocal = new Date(2026, 1, 28, 0, 5, 0, 0).getTime()
    expect(epochDayLocal(earlyLocal)).toBe(daysFromCivil(2026, 2, 28))
    // And the two resolvers are the same function only up to a day.
    expect(Math.abs(epochDayLocal(AUG_16_MS) - epochDayUtc(AUG_16_MS))).toBeLessThanOrEqual(1)
  })
})

/* ═══════════════════════════════════════════════════════════════════════════
   The month grid.
   ═══════════════════════════════════════════════════════════════════════════ */

/** Structural properties every grid must have, whatever month it is. */
function assertGridWellFormed(year: number, month: number, weekStart: WeekStart) {
  const grid = buildMonthGrid(year, month, weekStart)
  const cells = grid.weeks.flat()
  const label = `${year}-${month} ${weekStart}`

  expect(grid.weeks.length, label).toBeGreaterThanOrEqual(4)
  expect(grid.weeks.length, label).toBeLessThanOrEqual(6)
  for (const row of grid.weeks) expect(row.length, label).toBe(7)

  // Contiguity: the grid is a run of consecutive days with no holes and no repeats.
  for (let i = 1; i < cells.length; i++) expect(cells[i]!.day, label).toBe(cells[i - 1]!.day + 1)

  // The first row begins on the week's own first day — the "starts on the wrong weekday" bug.
  expect(startOfWeek(cells[0]!.day, weekStart), label).toBe(cells[0]!.day)
  // The last row is complete — the "last row short by one" bug.
  expect(cells.length % 7, label).toBe(0)

  const inMonth = cells.filter((c) => c.inMonth)
  expect(inMonth.length, label).toBe(daysInMonth(year, month))
  expect(inMonth[0]!.date, label).toEqual({ year, month, day: 1 })
  expect(inMonth[inMonth.length - 1]!.date, label).toEqual({ year, month, day: daysInMonth(year, month) })
  // In-month cells are one unbroken run; padding never appears in the middle.
  const firstIdx = cells.findIndex((c) => c.inMonth)
  const lastIdx = cells.length - 1 - [...cells].reverse().findIndex((c) => c.inMonth)
  expect(lastIdx - firstIdx + 1, label).toBe(inMonth.length)
  // Padding cells carry real adjacent-month dates rather than blanks, so nothing renders as a hole.
  for (const c of cells) expect(civilFromDays(c.day), label).toEqual(c.date)

  return grid
}

describe('the month grid', () => {
  it('is well-formed for every month of a decade, both week starts', () => {
    // The sweep. 120 months × 2 week starts, each checked against the structural invariants —
    // this is what makes the named cases below regression pins rather than the whole coverage.
    let checked = 0
    for (let y = 2020; y < 2030; y++) {
      for (let m = 1; m <= 12; m++) {
        assertGridWellFormed(y, m, 'mon')
        assertGridWellFormed(y, m, 'sun')
        checked += 2
      }
    }
    expect(checked).toBe(240) // the loop really ran
  })

  it('February 2026 — 28 days opening on a Sunday — is five Monday-start rows', () => {
    const grid = assertGridWellFormed(2026, 2, 'mon')
    expect(grid.weeks.length).toBe(5)
    // Six leading padding days from January, five trailing from March.
    expect(grid.weeks[0]![0]!.date).toEqual({ year: 2026, month: 1, day: 26 })
    expect(grid.weeks[0]!.filter((c) => !c.inMonth).length).toBe(6)
    expect(grid.weeks[4]![6]!.date).toEqual({ year: 2026, month: 3, day: 1 })
  })

  it('February 2015 — 28 days opening on a Sunday — is exactly four Sunday-start rows with no padding', () => {
    // The month that catches a hardcoded six-row grid. Every cell is in-month; there is no
    // padding anywhere. The witness that the padding detector is not simply blind is the
    // February 2026 case above, where it finds eleven padding cells in the same shape of grid.
    const grid = assertGridWellFormed(2015, 2, 'sun')
    expect(grid.weeks.length).toBe(4)
    expect(grid.weeks.flat().filter((c) => !c.inMonth)).toEqual([])
    expect(grid.weeks[0]![0]!.date).toEqual({ year: 2015, month: 2, day: 1 })
    expect(grid.weeks[3]![6]!.date).toEqual({ year: 2015, month: 2, day: 28 })
  })

  it('March 2026 — 31 days opening on a Sunday — needs six Monday-start rows and five Sunday-start rows', () => {
    // The six-row case: a 31-day month starting on the day before the week start is the widest
    // a month grid ever gets, and it is where a `ceil(days / 7)` row count is one row short.
    const mon = assertGridWellFormed(2026, 3, 'mon')
    expect(mon.weeks.length).toBe(6)
    expect(mon.weeks[0]![0]!.date).toEqual({ year: 2026, month: 2, day: 23 })
    expect(mon.weeks[5]![6]!.date).toEqual({ year: 2026, month: 4, day: 5 })

    const sun = assertGridWellFormed(2026, 3, 'sun')
    expect(sun.weeks.length).toBe(5)
    expect(sun.weeks[0]![0]!.date).toEqual({ year: 2026, month: 3, day: 1 })
  })

  it('leap February draws the 29th, and the next year does not', () => {
    const leap = assertGridWellFormed(2024, 2, 'mon')
    const leapDays = leap.weeks.flat().filter((c) => c.inMonth).map((c) => c.date.day)
    expect(leapDays[leapDays.length - 1]).toBe(29)
    const ordinary = assertGridWellFormed(2025, 2, 'mon')
    const ordinaryDays = ordinary.weeks.flat().filter((c) => c.inMonth).map((c) => c.date.day)
    expect(ordinaryDays[ordinaryDays.length - 1]).toBe(28)
    // Both grids exist and differ by exactly one in-month day — so neither assertion above is
    // reading an empty list.
    expect(leapDays.length - ordinaryDays.length).toBe(1)
  })

  it('rotates the weekday headings to match the week start', () => {
    expect(buildMonthGrid(2026, 8, 'mon').weekdayLabels).toEqual(['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'])
    expect(buildMonthGrid(2026, 8, 'sun').weekdayLabels).toEqual(['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'])
    // The headings agree with the cells they sit over, which is the property that actually matters.
    for (const ws of ['mon', 'sun'] as WeekStart[]) {
      const grid = buildMonthGrid(2026, 8, ws)
      grid.weeks[0]!.forEach((cell, i) => {
        expect(grid.weekdayLabels[i]).toBe(['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'][weekdaySunday0(cell.day)])
      })
    }
  })
})

/* ═══════════════════════════════════════════════════════════════════════════
   Kinds and shapes.
   ═══════════════════════════════════════════════════════════════════════════ */

describe('the four event kinds are told apart by geometry', () => {
  it('maps each kind to a distinct shape identifier', () => {
    // The requirement in one line: four kinds, four shapes, no two the same. Without this the
    // greyscale claim is unenforced and a later "just reuse the square" is invisible.
    expect(EVENT_KINDS).toEqual(['checkin', 'journal', 'sleep', 'selfcheck'])
    const shapes = EVENT_KINDS.map((k) => KIND_SHAPE[k])
    expect(shapes).toEqual(['square', 'ring', 'bar', 'slash'])
    expect(new Set(shapes).size).toBe(EVENT_KINDS.length)

    // The witness that the injectivity check can fail: the same Set comparison applied to a map
    // where two kinds collide reports 3, not 4. Without this line `new Set(...).size === length`
    // is a shape nobody has ever seen fail.
    const collided = { checkin: 'square', journal: 'square', sleep: 'bar', selfcheck: 'slash' } as const
    expect(new Set(EVENT_KINDS.map((k) => collided[k])).size).toBe(3)
  })

  it('names every kind and every shape in words, so the legend never needs colour', () => {
    for (const k of EVENT_KINDS) {
      expect(KIND_LABEL[k].length).toBeGreaterThan(2)
      expect(SHAPE_LABEL[KIND_SHAPE[k]].length).toBeGreaterThan(3)
    }
    expect(new Set(EVENT_KINDS.map((k) => KIND_LABEL[k])).size).toBe(4)
    expect(new Set(Object.values(SHAPE_LABEL)).size).toBe(4)
  })
})

/* ═══════════════════════════════════════════════════════════════════════════
   Events.
   ═══════════════════════════════════════════════════════════════════════════ */

describe('building events out of a backup bundle', () => {
  const full = bundle({
    entries: [
      { id: 1, dateTime: at(AUG_16_2026, 9), moodLevel: 3, note: 'ok' },
      { id: 2, dateTime: at(AUG_16_2026, 20), moodLevel: 5, note: '' },
    ],
    journal: [{ id: 7, dateTime: at(AUG_16_2026, 21), title: 'A long Sunday', body: 'text' }],
    sleepLogs: [
      {
        id: 3,
        night: at(AUG_16_2026 - 1, 0),
        bedTime: at(AUG_16_2026 - 1, 23),
        wakeTime: at(AUG_16_2026, 6) + 20 * 60_000,
        sleepLatencyMin: 25,
        awakeMin: 15,
        quality: 3,
        note: 'woke twice',
      },
    ],
    assessments: [
      { id: 11, key: 'wellbeing-selfcheck', dateTime: at(AUG_16_2026, 10), score: 12, bandLabel: 'Middling' },
      { id: 12, key: 'not-in-the-catalog', dateTime: at(AUG_16_2026, 11), score: 4, bandLabel: '' },
    ],
    moodLabels: { '3': 'Meh', '5': 'Rad' },
  })

  const events = buildEvents(full, epochDayUtc)

  it('produces one event per dated record, oldest first', () => {
    expect(events.length).toBe(6)
    expect(events.map((e) => e.kind)).toEqual(['sleep', 'checkin', 'selfcheck', 'selfcheck', 'checkin', 'journal'])
    for (let i = 1; i < events.length; i++) expect(events[i]!.at).toBeGreaterThanOrEqual(events[i - 1]!.at)
    expect(new Set(events.map((e) => e.key)).size).toBe(events.length) // keys are unique
  })

  it('buckets a sleep log on its NIGHT, not on the morning it ended', () => {
    // wakeTime is on the 16th; `night` is the 15th. Bucketing by wake time would move every
    // night onto the following day and quietly shift the whole sleep column by one.
    const sleep = events.find((e) => e.kind === 'sleep')!
    expect(sleep.day).toBe(AUG_16_2026 - 1)
    expect(epochDayUtc(at(AUG_16_2026, 6))).toBe(AUG_16_2026) // the wake time really is the next day
  })

  it('renders mood as a word from the bundle’s own labels, with the app scale as the fallback', () => {
    const [first] = events.filter((e) => e.kind === 'checkin')
    expect(first!.measure!.value).toBe('Meh')
    // Supplied labels win; an absent or blank label falls back to the fixed 5-level scale rather
    // than printing a bare number, and neither path emits anything colour-shaped.
    expect(moodWord(3, { '3': 'Flat' })).toBe('Flat')
    expect(moodWord(3, { '3': '   ' })).toBe('Meh')
    expect(moodWord(3, undefined)).toBe('Meh')
    expect(moodWord(1, {})).toBe('Awful')
  })

  it('carries a measure with provenance on rows that have one, and none on rows that do not', () => {
    // The positive half first — otherwise "journal has no measure" is a claim about a builder
    // that might not be producing measures at all.
    const known = events.find((e) => e.key === 'selfcheck:11')!
    expect(known.title).toBe('Daily wellbeing self-check')
    expect(known.measure).not.toBeNull()
    expect(known.measure!.value).toBe('12 · Middling')
    expect(known.measure!.tier).toBe('custom') // the shipped catalog's own tier

    // An unrecognised instrument key gets NO tier rather than a guessed one.
    const unknown = events.find((e) => e.key === 'selfcheck:12')!
    expect(unknown.title).toBe('not-in-the-catalog')
    expect(unknown.measure!.tier).toBeNull()
    expect(unknown.measure!.source.length).toBeGreaterThan(0)
    expect(unknown.measure!.value).toBe('4') // no band label, so no band is invented

    // Sleep carries a duration and no instrument tier.
    const sleep = events.find((e) => e.kind === 'sleep')!
    expect(sleep.measure!.value).toBe('7h 20m')
    expect(sleep.measure!.tier).toBeNull()

    // And the negative: a journal entry is words, so it carries no measure at all.
    const journal = events.find((e) => e.kind === 'journal')!
    expect(journal.measure).toBeNull()
    expect(journal.title).toBe('A long Sunday')
  })

  it('never computes a percentage for a night', () => {
    // Sleep efficiency — time asleep over time in bed — is the obvious thing to add here and it
    // is a grade for a night's sleep. The measure is a duration in words; nothing in the row is
    // a ratio. `sleepLatencyMin` and `awakeMin` are present in the fixture and deliberately unused.
    const sleep = events.find((e) => e.kind === 'sleep')!
    expect(sleep.measure!.label).toBe('Time in bed')
    expect(sleep.measure!.value).not.toMatch(/%/)
    expect(JSON.stringify(sleep)).not.toMatch(/%/)
  })

  it('drops records with an unusable timestamp instead of bucketing them into 1970', () => {
    // Witness first: with a finite timestamp the same record IS built. Only then is the
    // absence of the broken one evidence of anything.
    const good = buildEvents(bundle({ entries: [{ id: 1, dateTime: AUG_16_MS, moodLevel: 3, note: '' }] }), epochDayUtc)
    expect(good.map((e) => e.key)).toEqual(['checkin:1'])

    const bad = buildEvents(
      bundle({
        entries: [{ id: 1, dateTime: Number.NaN, moodLevel: 3, note: '' }],
        journal: [{ id: 2, dateTime: Number.POSITIVE_INFINITY, title: 't', body: 'b' }],
      }),
      epochDayUtc,
    )
    expect(bad).toEqual([])
  })

  it('tolerates a bundle with the optional arrays missing entirely', () => {
    const minimal: BackupData = { version: 1, exportedAt: 0, entries: [], activities: [], refs: [] }
    expect(buildEvents(minimal, epochDayUtc)).toEqual([])
  })

  it('never draws a goal, because a target-per-week on a calendar is a quota', () => {
    // Goals are dated (`createdAt`) and would be easy to plot. They are excluded on purpose:
    // drawing a weekly target beside what actually happened builds a compliance score whether or
    // not a number is printed. The witness that the builder is otherwise working on this bundle
    // is the check-in it does produce.
    const withGoal = bundle({
      entries: [{ id: 1, dateTime: AUG_16_MS, moodLevel: 4, note: '' }],
      goals: [{ id: 9, title: 'Walk', activityId: null, targetPerWeek: 5, createdAt: AUG_16_MS, archived: false }],
    })
    const built = buildEvents(withGoal, epochDayUtc)
    expect(built.map((e) => e.kind)).toEqual(['checkin'])
    expect(JSON.stringify(built)).not.toMatch(/targetPerWeek|Walk/)
  })
})

/* ═══════════════════════════════════════════════════════════════════════════
   Bucketing by day.
   ═══════════════════════════════════════════════════════════════════════════ */

describe('bucketing events by day', () => {
  const events = buildEvents(
    bundle({
      entries: [
        { id: 1, dateTime: at(AUG_16_2026, 18), moodLevel: 2, note: '' },
        { id: 2, dateTime: at(AUG_16_2026, 8), moodLevel: 4, note: '' },
        { id: 3, dateTime: at(AUG_16_2026 + 2, 8), moodLevel: 4, note: '' },
      ],
      assessments: [{ id: 11, key: 'wellbeing-selfcheck', dateTime: at(AUG_16_2026, 9), score: 5, bandLabel: 'Low' }],
    }),
    epochDayUtc,
  )
  const buckets = bucketByDay(events)

  it('groups, orders within the day, and counts per kind', () => {
    expect([...buckets.keys()].sort((a, b) => a - b)).toEqual([AUG_16_2026, AUG_16_2026 + 2])
    const day = buckets.get(AUG_16_2026)!
    expect(day.events.map((e) => e.key)).toEqual(['checkin:2', 'selfcheck:11', 'checkin:1'])
    expect(day.counts).toEqual({ checkin: 2, journal: 0, sleep: 0, selfcheck: 1 })
    // One mark per kind present, in the fixed draw order — never one mark per event.
    expect(day.kinds).toEqual(['checkin', 'selfcheck'])
  })

  it('leaves a day with nothing on it out of the map entirely', () => {
    // Absence is absence: there is no bucket, no zero row, and nothing for a renderer to label.
    // The witness that the gap is real rather than a lookup failure is the day either side of it.
    expect(buckets.has(AUG_16_2026)).toBe(true)
    expect(buckets.has(AUG_16_2026 + 2)).toBe(true)
    expect(buckets.has(AUG_16_2026 + 1)).toBe(false)
  })

  it('is empty for an empty list, and that is not how it passes the tests above', () => {
    expect(bucketByDay([]).size).toBe(0)
    expect(buckets.size).toBe(2)
  })
})

/* ═══════════════════════════════════════════════════════════════════════════
   The twelve-week ribbon.
   ═══════════════════════════════════════════════════════════════════════════ */

describe('the twelve-week ribbon', () => {
  const anchor = AUG_16_2026 // a Sunday; the Monday-start week began six days earlier
  const events = buildEvents(
    bundle({
      entries: [
        { id: 1, dateTime: at(anchor, 9), moodLevel: 3, note: '' }, // anchor week
        { id: 2, dateTime: at(anchor - 1, 9), moodLevel: 3, note: '' }, // anchor week, day before
        { id: 3, dateTime: at(anchor - 14, 9), moodLevel: 3, note: '' }, // two weeks back
      ],
      journal: [{ id: 4, dateTime: at(anchor - 14, 20), title: 'j', body: '' }],
      assessments: [{ id: 5, key: 'wellbeing-selfcheck', dateTime: at(anchor - 70, 9), score: 3, bandLabel: 'Low' }],
    }),
    epochDayUtc,
  )
  const ribbon = buildRibbon(events, anchor, 'mon')

  it('is twelve contiguous weeks, oldest first, ending with the anchor’s own week', () => {
    expect(ribbon.length).toBe(12)
    for (let i = 1; i < ribbon.length; i++) expect(ribbon[i]!.start).toBe(ribbon[i - 1]!.start + 7)
    for (const w of ribbon) {
      expect(w.end - w.start).toBe(6)
      expect(startOfWeek(w.start, 'mon')).toBe(w.start)
    }
    const last = ribbon[11]!
    expect(anchor).toBeGreaterThanOrEqual(last.start)
    expect(anchor).toBeLessThanOrEqual(last.end)
    expect(ribbon[0]!.start).toBe(last.start - 77)
  })

  it('counts what happened per week without turning it into a figure', () => {
    const last = ribbon[11]!
    expect(last.counts).toEqual({ checkin: 2, journal: 0, sleep: 0, selfcheck: 0 })
    expect(last.kinds).toEqual(['checkin'])
    expect(last.daysWithAny).toBe(2)

    const twoBack = ribbon[9]!
    expect(twoBack.counts.checkin).toBe(1)
    expect(twoBack.counts.journal).toBe(1)
    expect(twoBack.kinds).toEqual(['checkin', 'journal'])
    expect(twoBack.daysWithAny).toBe(1) // two events, one day — a count of days, not of events
  })

  it('reports a week with nothing in it as blank, never as a gap with a verdict on it', () => {
    // Witness first: weeks 10 and 12 are non-empty, so `empty` is a property this ribbon can and
    // does set both ways. Only then does an empty week mean anything.
    expect(ribbon[9]!.empty).toBe(false)
    expect(ribbon[11]!.empty).toBe(false)
    const blank = ribbon[10]!
    expect(blank.empty).toBe(true)
    expect(blank.kinds).toEqual([])
    expect(blank.daysWithAny).toBe(0)
    expect(Object.values(blank.counts).every((n) => n === 0)).toBe(true)
    expect(ribbon.filter((w) => w.empty).length).toBe(9)
  })

  it('does not count the days of the current week that have not happened yet', () => {
    // The trailing week is nearly always partial. Reporting it as seven days with two marks is
    // what turns a Tuesday into a failed week. Anchored on a Sunday with a Monday-start week,
    // all seven days have happened; anchored on the Wednesday, only three have.
    expect(ribbon[11]!.daysObserved).toBe(7)
    for (const w of ribbon.slice(0, 11)) expect(w.daysObserved).toBe(7)

    const midWeek = buildRibbon(events, anchor - 4, 'mon') // the Wednesday of the same week
    expect(midWeek[11]!.daysObserved).toBe(3)
    expect(midWeek[11]!.start).toBe(ribbon[11]!.start) // still the same week
  })

  it('ignores events outside the window, and the window is real', () => {
    // The self-check ten weeks back IS inside the twelve — that is the witness. An event a year
    // back is not, and drops out rather than being clamped onto the first column.
    expect(ribbon[1]!.counts.selfcheck).toBe(1)
    const far = buildEvents(
      bundle({ entries: [{ id: 99, dateTime: at(anchor - 400, 9), moodLevel: 3, note: '' }] }),
      epochDayUtc,
    )
    const outside = buildRibbon(far, anchor, 'mon')
    expect(outside.every((w) => w.empty)).toBe(true)
    // …and the same event with a nearer date lands, so `every empty` is not the only outcome.
    const near: CalendarEvent[] = far.map((e) => ({ ...e, day: anchor - 3 }))
    expect(buildRibbon(near, anchor, 'mon').filter((w) => !w.empty).length).toBe(1)
  })

  it('exposes no rate, percentage, score or streak on a week', () => {
    // A structural guard on the shape itself. The moment someone adds `completion` or `rate` here
    // the ribbon has become a compliance score, and this is the line that says so out loud.
    expect(Object.keys(ribbon[0]!).sort()).toEqual(
      ['counts', 'daysObserved', 'daysWithAny', 'empty', 'end', 'kinds', 'start'].sort(),
    )
    expect(JSON.stringify(ribbon)).not.toMatch(/percent|rate|streak|score|complet|adheren/i)
  })

  it('honours a Sunday-start week and a different window length', () => {
    const sun = buildRibbon(events, anchor, 'sun', 4)
    expect(sun.length).toBe(4)
    for (const w of sun) expect(weekdaySunday0(w.start)).toBe(0)
    // The anchor is itself a Sunday, so a Sunday-start window puts it alone in the final column.
    expect(sun[3]!.start).toBe(anchor)
    expect(sun[3]!.daysObserved).toBe(1)
    expect(sun[3]!.counts.checkin).toBe(1) // only the anchor-day check-in
  })
})

/* ═══════════════════════════════════════════════════════════════════════════
   The words a screen reader gets in place of the shapes.
   ═══════════════════════════════════════════════════════════════════════════ */

describe('describing a day and a week in words', () => {
  const counts = { checkin: 2, journal: 0, sleep: 1, selfcheck: 0 }

  it('lists what is present, pluralised, in draw order', () => {
    expect(describeCounts(counts)).toBe('2 check-ins, 1 sleep log')
    expect(describeCounts({ checkin: 1, journal: 1, sleep: 1, selfcheck: 1 })).toBe(
      '1 check-in, 1 journal, 1 sleep log, 1 self-check',
    )
    expect(describeCounts({ checkin: 0, journal: 0, sleep: 0, selfcheck: 0 })).toBe('')
  })

  it('says only that nothing was recorded, never that anything was missed', () => {
    // The witness that the describer says something when there IS something: the line above.
    // What matters here is the exact vocabulary of absence — a screen-reader user must get the
    // same neutral reading a sighted user gets from a blank cell, not a verdict nobody drew.
    const day = daysFromCivil(2026, 8, 16)
    expect(describeDay(day, undefined)).toBe('Sun 16 Aug 2026: nothing recorded')
    expect(describeDay(day, { day, events: [], kinds: ['checkin'], counts })).toBe(
      'Sun 16 Aug 2026: 2 check-ins, 1 sleep log',
    )
    const blankWeek = buildRibbon([], day, 'mon')[11]!
    expect(describeWeek(blankWeek)).toBe('Week of Mon 10 Aug 2026: nothing recorded')

    for (const s of [describeDay(day, undefined), describeWeek(blankWeek), NOTHING_RECORDED]) {
      expect(s).not.toMatch(/missed|incomplete|no data|gap|fail|behind|streak|\b0 [a-z]/i)
    }
  })
})

/* ═══════════════════════════════════════════════════════════════════════════
   Formatting.
   ═══════════════════════════════════════════════════════════════════════════ */

describe('formatting', () => {
  it('writes durations in hours and minutes, never as a ratio', () => {
    expect(formatDuration(0)).toBe('0m')
    expect(formatDuration(-5)).toBe('0m')
    expect(formatDuration(Number.NaN)).toBe('0m')
    expect(formatDuration(45 * 60_000)).toBe('45m')
    expect(formatDuration(7 * 3_600_000 + 20 * 60_000)).toBe('7h 20m')
    expect(formatDuration(8 * 3_600_000)).toBe('8h 0m')
  })

  it('writes a zero-padded 24-hour clock', () => {
    // Asserted in UTC so the string is exact everywhere; the local branch is asserted by shape,
    // which is all that is true in an unknown zone.
    expect(formatClock(AUG_16_MS, 'utc')).toBe('00:00')
    expect(formatClock(AUG_16_MS + 9 * 3_600_000 + 5 * 60_000, 'utc')).toBe('09:05')
    expect(formatClock(AUG_16_MS + 23 * 3_600_000 + 59 * 60_000, 'utc')).toBe('23:59')
    expect(formatClock(AUG_16_MS)).toMatch(/^([01]\d|2[0-3]):[0-5]\d$/)
  })

  it('writes month and day headings from the epoch day, not from a Date', () => {
    expect(formatMonthTitle(2026, 8)).toBe('August 2026')
    expect(formatMonthTitle(2024, 2)).toBe('February 2024')
    expect(formatDayLong(AUG_16_2026)).toBe('Sun 16 Aug 2026')
    expect(formatDayLong(daysFromCivil(2024, 2, 29))).toBe('Thu 29 Feb 2024')
    expect(formatDayShort(AUG_16_2026)).toBe('16 Aug')
    expect(MONTH_NAMES.length).toBe(12)
  })
})
