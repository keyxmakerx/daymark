/*
 * THE CLINICIAN'S DAY PANEL TIMES A SLEEP LOG BY ITS BED AND WAKE TIMES (#416).
 *
 * A sleep log's `at` is the UTC midnight of its night: a marker that orders it among the day's
 * records, not a time anybody wrote down. The agenda used to print every row's time as
 * `formatClock(event.at)`, so a sleep log read "02:00" in Central Europe, and a clinician could
 * take that for when the person went to bed. Each row now shows `eventTime(event)` from
 * lib/calendar/core.ts, which gives a sleep log its bed and wake times and every other record its
 * own time; therapist/calendar.test.ts holds what it returns. This reads the component, because
 * there is no DOM here, and every absence is asserted after the same reading has seen a planted one.
 */
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

const SCREEN = readFileSync(resolve(process.cwd(), 'src/lib/components/therapist/CalendarScreen.svelte'), 'utf8')

/** What renders: script, style and comments gone. */
const markupOf = (src: string) =>
  src
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/<script[\s\S]*?<\/script>/g, '')
    .replace(/<style[\s\S]*?<\/style>/g, '')

/** Every place the markup prints a record's own instant as a clock time. */
const clocksOffTheInstant = (src: string) => markupOf(src).match(/formatClock\(\s*event\.at\b[^)]*\)/g) ?? []

describe('the clinician’s day panel shows a sleep log’s own times (#416)', () => {
  it('prints each row’s time through eventTime, which times a sleep log by bed and wake', () => {
    expect(markupOf(SCREEN)).toMatch(/<span class="u-mono when">\{eventTime\(event\)\}<\/span>/)
    // Inside the agenda's loop, where `event` is a row, and not somewhere else on the screen.
    const loop = markupOf(SCREEN).indexOf('{#each agenda as event (event.key)}')
    expect(loop).toBeGreaterThan(-1)
    expect(markupOf(SCREEN).indexOf('{eventTime(event)}', loop)).toBeGreaterThan(loop)
  })

  it('never prints a record’s own instant as its time', () => {
    // Control: a row time read off the instant, planted beside the kind's label, is seen.
    const planted = SCREEN.replace(
      '<span class="u-label kind">',
      '<span class="u-mono">{formatClock(event.at)}</span><span class="u-label kind">',
    )
    expect(planted).not.toBe(SCREEN)
    expect(clocksOffTheInstant(planted).length).toBe(clocksOffTheInstant(SCREEN).length + 1)
    expect(clocksOffTheInstant(SCREEN)).toEqual([])
  })
})
