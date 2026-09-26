/*
 * The person's own month, as data (#335): which marks a day draws, in which order, what a day
 * with nothing on it is, what a screen reader hears, and what the day panel lists.
 *
 * Written under the repository's two rules. Every absence is paired with the same detector seeing
 * the thing when it is there. And no assertion depends on the machine's clock zone: days are
 * resolved with the injected `epochDayUtc`, and times are written in UTC.
 */

import { describe, it, expect } from 'vitest'
import type { BackupData, BackupEntry } from '../backup'
import { MS_PER_DAY, bucketByDay, buildEvents, daysFromCivil, epochDayUtc, type EpochDay } from './core'
import {
  MAX_CHECKIN_MARKS,
  buildOwnMonth,
  dayMarks,
  dayRecords,
  describeOwnDay,
  keptCheckins,
  sayMark,
  type DayMark,
  type OwnDay,
} from './ownMonth'

/* ── fixtures ─────────────────────────────────────────────────────────────── */

/** Wednesday 16 September 2026. */
const SEP_16 = daysFromCivil(2026, 9, 16) as EpochDay
const at = (day: EpochDay, hour: number, minute = 0) => day * MS_PER_DAY + hour * 3_600_000 + minute * 60_000

function bundle(over: Partial<BackupData> = {}): BackupData {
  return {
    version: 14,
    exportedAt: at(SEP_16, 12),
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

const entry = (id: number, dateTime: number, moodLevel: number): BackupEntry => ({ id, dateTime, moodLevel, note: '' })

/** The day's events, bucketed exactly as the component buckets them. */
const eventsOn = (data: BackupData, day: EpochDay) => bucketByDay(buildEvents(data, epochDayUtc)).get(day)?.events ?? []

/** A full day: two check-ins made out of order, two journal entries, a night, a self-check. */
const FULL = bundle({
  entries: [entry(1, at(SEP_16, 18), 2), entry(2, at(SEP_16, 8), 4)],
  journal: [
    { id: 7, dateTime: at(SEP_16, 21), title: 'Evening', body: 'words' },
    { id: 8, dateTime: at(SEP_16, 21, 30), title: '', body: 'more words' },
  ],
  sleepLogs: [
    {
      id: 3,
      night: SEP_16,
      bedTime: at(SEP_16 - 1, 23, 10),
      wakeTime: at(SEP_16, 7, 5),
      sleepLatencyMin: 20,
      awakeMin: 10,
      quality: 3,
      note: '',
    },
  ],
  assessments: [{ id: 11, key: 'wellbeing-selfcheck', dateTime: at(SEP_16, 9), score: 12, bandLabel: 'Middling' }],
})

const words = (marks: DayMark[]) => marks.map((m) => (m.mood ? m.mood.word : m.kind))

/* ═══════════════════════════════════════════════════════════════════════════
   What a day draws.
   ═══════════════════════════════════════════════════════════════════════════ */

describe('what a day draws, and in which order', () => {
  it('draws each check-in in the order made, then one shape for each other kind the day holds', () => {
    const marks = dayMarks(eventsOn(FULL, SEP_16), FULL.moodLabels)
    // The 08:00 Good before the 18:00 Bad, though the Bad was stored first; one ring for two
    // journal entries; then the night and the self-check, in the fixed kind order.
    expect(words(marks)).toEqual(['Good', 'Bad', 'journal', 'sleep', 'selfcheck'])
    expect(marks.map((m) => m.kind)).toEqual(['checkin', 'checkin', 'journal', 'sleep', 'selfcheck'])
    expect(marks[0]!.mood).toEqual({ level: 4, word: 'Good' })
    expect(marks[1]!.mood).toEqual({ level: 2, word: 'Bad' })
  })

  it('never blends two moods: every square is one check-in’s own level', () => {
    const marks = dayMarks(eventsOn(FULL, SEP_16), {})
    const levels = marks.filter((m) => m.mood).map((m) => m.mood!.level)
    expect(levels).toEqual([4, 2])
    // Nothing between them, nothing averaged: 3 is the mean of this day and is drawn nowhere.
    expect(levels).not.toContain(3)
  })

  it('orders check-ins made at the same moment as they were made, by id, as the phone does', () => {
    const same = at(SEP_16, 12)
    const data = bundle({ entries: [entry(10, same, 5), entry(9, same, 1)] })
    const events = eventsOn(data, SEP_16)
    // Control: the day's own list, sorted by key, puts 10 first ("checkin:10" < "checkin:9"), so
    // the order below is this module's doing and not the input's.
    expect(events.map((e) => e.id)).toEqual([10, 9])
    expect(words(dayMarks(events, {}))).toEqual(['Awful', 'Rad'])
  })

  it('draws at most six check-ins, keeping the first of every mood the day holds', () => {
    // The phone's own example: seven Goods and then an Awful draw five Goods and the Awful.
    const goods = Array.from({ length: 7 }, (_, i) => entry(i + 1, at(SEP_16, 8 + i), 4))
    const full = bundle({ entries: [...goods, entry(8, at(SEP_16, 20), 1)] })
    const marks = dayMarks(eventsOn(full, SEP_16), {})
    expect(marks.length).toBe(MAX_CHECKIN_MARKS)
    expect(words(marks)).toEqual(['Good', 'Good', 'Good', 'Good', 'Good', 'Awful'])
    // Control: at six, every check-in is drawn, in the order made.
    const six = bundle({ entries: goods.slice(0, 5).concat(entry(8, at(SEP_16, 20), 1)) })
    expect(words(dayMarks(eventsOn(six, SEP_16), {}))).toEqual(['Good', 'Good', 'Good', 'Good', 'Good', 'Awful'])
    expect(dayMarks(eventsOn(six, SEP_16), {}).length).toBe(6)
  })

  it('keeps, past six, only the day’s own check-ins, in the order made, with every mood among them', () => {
    // A fixed-seed sweep of 2,000 days of seven to fourteen check-ins each.
    let seed = 0x0335_2808
    const next = () => (seed = (Math.imul(seed, 1_664_525) + 1_013_904_223) >>> 0)
    let days = 0
    for (let d = 0; d < 2_000; d++) {
      const n = 7 + (next() % 8)
      const levels = Array.from({ length: n }, () => 1 + (next() % 5))
      const indexed = levels.map((level, i) => ({ i, level }))
      const kept = keptCheckins(indexed, (c) => c.level)
      expect(kept.length).toBe(MAX_CHECKIN_MARKS)
      for (let k = 1; k < kept.length; k++) expect(kept[k]!.i).toBeGreaterThan(kept[k - 1]!.i) // order made
      expect(new Set(kept.map((c) => c.level))).toEqual(new Set(levels)) // every mood the day holds
      days++
    }
    expect(days).toBe(2_000)
  })

  it('draws a check-in whose mood the file does not say as the plain square, said as "check-in"', () => {
    const data = bundle({ entries: [entry(1, at(SEP_16, 9), Number.NaN), entry(2, at(SEP_16, 10), 3)] })
    const marks = dayMarks(eventsOn(data, SEP_16), {})
    expect(marks.map((m) => m.kind)).toEqual(['checkin', 'checkin'])
    expect(marks[0]!.mood).toBeNull()
    expect(sayMark(marks[0]!)).toBe('check-in')
    // Control: the readable one carries its mood and says it.
    expect(sayMark(marks[1]!)).toBe('check-in Meh')
  })

  it('uses the person’s own word for a mood, and the shipped one where they kept it', () => {
    const marks = dayMarks(eventsOn(FULL, SEP_16), { '4': 'Steady' })
    expect(words(marks).slice(0, 2)).toEqual(['Steady', 'Bad'])
  })
})

/* ═══════════════════════════════════════════════════════════════════════════
   A day with nothing on it.
   ═══════════════════════════════════════════════════════════════════════════ */

describe('a day with nothing on it', () => {
  const month = buildOwnMonth(bucketByDay(buildEvents(FULL, epochDayUtc)), FULL.moodLabels, 2026, 9, SEP_16 + 10)
  const days = month.weeks.flat().filter((d): d is OwnDay => d !== null)
  const full = days.find((d) => d.day === SEP_16)!
  const empty = days.find((d) => d.day === SEP_16 + 1)!

  it('has no marks, beside a day that has them', () => {
    // Witness first: the day before it draws five marks, so the grid really is carrying marks.
    expect(full.marks.length).toBe(5)
    expect(empty.marks).toEqual([])
    expect(days.filter((d) => d.marks.length > 0).map((d) => d.day)).toEqual([SEP_16])
  })

  it('is the same kind of day as any other: nothing about it says it is empty', () => {
    // The same fields, and none that a renderer could draw an absence from.
    expect(Object.keys(empty).sort()).toEqual(Object.keys(full).sort())
    expect(Object.keys(empty).sort()).toEqual(['day', 'dayOfMonth', 'isToday', 'label', 'marks'])
    expect(empty.isToday).toBe(false)
    expect(empty.dayOfMonth).toBe(17)
  })

  it('is heard as "nothing recorded", and never as a verdict', () => {
    expect(empty.label).toBe('Thu 17 Sep 2026: nothing recorded')
    const VERDICT = /missed|incomplete|no data|no entr|gap|fail|behind|streak|empty|none|skipped|\b0\b/i
    for (const d of days.filter((x) => x.marks.length === 0)) expect(d.label).not.toMatch(VERDICT)
    // Control: the detector sees a verdict when there is one.
    expect('Thu 17 Sep 2026: missed').toMatch(VERDICT)
    expect('Thu 17 Sep 2026: 0 check-ins').toMatch(VERDICT)
  })
})

/* ═══════════════════════════════════════════════════════════════════════════
   What a screen reader hears.
   ═══════════════════════════════════════════════════════════════════════════ */

describe('what a screen reader hears', () => {
  it('hears the date, then each mark in the order drawn, in the person’s own words', () => {
    const marks = dayMarks(eventsOn(FULL, SEP_16), { '4': 'Steady' })
    expect(describeOwnDay(SEP_16, marks, false)).toBe(
      'Wed 16 Sep 2026: check-in Steady, check-in Bad, journal, sleep log, self-check',
    )
  })

  it('hears today as today, whatever the day holds', () => {
    expect(describeOwnDay(SEP_16, [], true)).toBe('Wed 16 Sep 2026, today: nothing recorded')
    const marks = dayMarks(eventsOn(FULL, SEP_16), {})
    expect(describeOwnDay(SEP_16, marks, true)).toMatch(/^Wed 16 Sep 2026, today: check-in Good,/)
  })

  it('hears exactly the marks drawn: never a count and never a summary', () => {
    const many = bundle({ entries: Array.from({ length: 9 }, (_, i) => entry(i + 1, at(SEP_16, 8 + i), 3)) })
    const marks = dayMarks(eventsOn(many, SEP_16), {})
    const heard = describeOwnDay(SEP_16, marks, false)
    expect(heard).toBe(`Wed 16 Sep 2026: ${Array(6).fill('check-in Meh').join(', ')}`)
    expect(heard).not.toMatch(/\b9\b|\bmore\b|\+/)
  })
})

/* ═══════════════════════════════════════════════════════════════════════════
   The month.
   ═══════════════════════════════════════════════════════════════════════════ */

describe('the month', () => {
  const month = buildOwnMonth(bucketByDay(buildEvents(FULL, epochDayUtc)), {}, 2026, 9, SEP_16)

  it('is the month’s own days, with blank space before the first and after the last', () => {
    expect(month.title).toBe('September 2026')
    expect(month.weekdayLabels).toEqual(['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'])
    const cells = month.weeks.flat()
    expect(cells.length % 7).toBe(0)
    const days = cells.filter((d): d is OwnDay => d !== null)
    expect(days.map((d) => d.dayOfMonth)).toEqual(Array.from({ length: 30 }, (_, i) => i + 1))
    // 1 September 2026 is a Tuesday: one blank before it on a Monday-start week, four after the 30th.
    expect(month.weeks[0]![0]).toBeNull()
    expect(month.weeks[0]![1]!.dayOfMonth).toBe(1)
    expect(cells.filter((c) => c === null).length).toBe(5)
  })

  it('rings today, and only today', () => {
    const today = month.weeks.flat().filter((d) => d?.isToday)
    expect(today.map((d) => d!.day)).toEqual([SEP_16])
    // Control: a month that does not hold today rings nothing.
    const august = buildOwnMonth(new Map(), {}, 2026, 8, SEP_16)
    expect(august.weeks.flat().filter((d) => d?.isToday)).toEqual([])
  })

  it('computes no figure about the month or any day in it', () => {
    const FIGURE = /count|total|average|mean|best|worst|score|streak|percent|rate|most|least/i
    const keysOf = (value: unknown): string[] =>
      typeof value !== 'object' || value === null ? [] : Object.entries(value).flatMap(([k, v]) => [k, ...keysOf(v)])
    expect(keysOf(month).filter((k) => FIGURE.test(k))).toEqual([])
    // Control: a figure planted on a day is found.
    expect(keysOf({ ...month, weeks: [[{ ...month.weeks[2]![2]!, count: 2 }]] }).filter((k) => FIGURE.test(k))).toEqual(['count'])
  })
})

/* ═══════════════════════════════════════════════════════════════════════════
   The day panel.
   ═══════════════════════════════════════════════════════════════════════════ */

describe('the day panel lists the chosen day in words', () => {
  const records = dayRecords(eventsOn(FULL, SEP_16), { '2': 'Low' }, 'utc')

  it('lists every record in the order made, each with its kind and its time', () => {
    expect(records.map((r) => [r.kind, r.time])).toEqual([
      ['sleep', '23:10–07:05'], // a night is ordered by its own date, and timed by bed and wake
      ['checkin', '08:00'],
      ['selfcheck', '09:00'],
      ['checkin', '18:00'],
      ['journal', '21:00'],
      ['journal', '21:30'],
    ])
    expect(new Set(records.map((r) => r.key)).size).toBe(records.length)
  })

  it('gives a check-in its mood’s word, and a self-check its name and band word', () => {
    expect(records.filter((r) => r.kind === 'checkin').map((r) => r.mood)).toEqual([
      { level: 4, word: 'Good' },
      { level: 2, word: 'Low' },
    ])
    expect(records.find((r) => r.kind === 'selfcheck')!.text).toBe('Daily wellbeing self-check · Middling')
    // Nothing else carries text of its own here, and no row but a check-in carries a mood.
    expect(records.filter((r) => r.kind !== 'selfcheck').every((r) => r.text === '')).toBe(true)
    expect(records.filter((r) => r.kind !== 'checkin').every((r) => r.mood === null)).toBe(true)
  })

  it('shows no self-check total: the phone’s own day shows none there', () => {
    const selfcheck = records.find((r) => r.kind === 'selfcheck')!
    expect(JSON.stringify(selfcheck)).not.toMatch(/\b12\b/)
    // Control: the total was there to show — the clinician's measure carries it — and was left out.
    const event = eventsOn(FULL, SEP_16).find((e) => e.kind === 'selfcheck')!
    expect(event.measure!.value).toBe('12 · Middling')
  })

  it('invents no band where a self-check has none', () => {
    const plain = bundle({
      assessments: [
        { id: 1, key: 'wellbeing-selfcheck', dateTime: at(SEP_16, 9), score: 4, bandLabel: '  ' },
        { id: 2, key: 'not-in-the-catalog', dateTime: at(SEP_16, 10), score: 4, bandLabel: 'Low' },
      ],
    })
    expect(dayRecords(eventsOn(plain, SEP_16), {}, 'utc').map((r) => r.text)).toEqual([
      'Daily wellbeing self-check',
      'not-in-the-catalog · Low',
    ])
  })

  it('lists every check-in, including those past the six a day’s square draws', () => {
    const many = bundle({ entries: Array.from({ length: 9 }, (_, i) => entry(i + 1, at(SEP_16, 8 + i), 3)) })
    expect(dayRecords(eventsOn(many, SEP_16), {}, 'utc').length).toBe(9)
    expect(dayMarks(eventsOn(many, SEP_16), {}).length).toBe(6)
  })

  it('shows no time for a night whose times the file does not hold, rather than one made up', () => {
    const night = bundle({
      sleepLogs: [{ id: 1, night: SEP_16, bedTime: Number.NaN, wakeTime: at(SEP_16, 7), sleepLatencyMin: 0, awakeMin: 0, quality: 3, note: '' }],
    })
    expect(dayRecords(eventsOn(night, SEP_16), {}, 'utc').map((r) => r.time)).toEqual([''])
    // Control: with both times it shows them.
    expect(records[0]!.time).toBe('23:10–07:05')
  })

  it('is empty for a day with nothing on it, which the panel says in the fixed words', () => {
    expect(dayRecords([], {}, 'utc')).toEqual([])
    expect(records.length).toBe(6)
  })
})

/* ═══════════════════════════════════════════════════════════════════════════
   What the shared events now carry for the person's own month.
   ═══════════════════════════════════════════════════════════════════════════ */

describe('the events carry what the person’s own month draws', () => {
  const events = buildEvents(FULL, epochDayUtc)

  it('a check-in carries its level, a self-check its band, a night its bed and wake times', () => {
    expect(events.filter((e) => e.kind === 'checkin').map((e) => e.level)).toEqual([4, 2])
    expect(events.find((e) => e.kind === 'selfcheck')!.band).toBe('Middling')
    expect(events.find((e) => e.kind === 'sleep')!.sleep).toEqual({ bedTime: at(SEP_16 - 1, 23, 10), wakeTime: at(SEP_16, 7, 5) })
    // And the other kinds carry none of them.
    for (const e of events) {
      if (e.kind !== 'checkin') expect(e.level).toBeNull()
      if (e.kind !== 'selfcheck') expect(e.band).toBeNull()
      if (e.kind !== 'sleep') expect(e.sleep).toBeNull()
    }
  })

  it('reads a level only from a number, and a band only from words', () => {
    const odd = bundle({
      entries: [entry(1, at(SEP_16, 9), 7.4), entry(2, at(SEP_16, 10), Number.NaN)],
      assessments: [{ id: 3, key: 'wellbeing-selfcheck', dateTime: at(SEP_16, 11), score: 1, bandLabel: '' }],
    })
    const built = buildEvents(odd, epochDayUtc)
    expect(built.filter((e) => e.kind === 'checkin').map((e) => e.level)).toEqual([5, null])
    expect(built.find((e) => e.kind === 'selfcheck')!.band).toBeNull()
  })
})
