/*
 * SESSION TIMERS — the clock behind the Timers screen, with no DOM and no clock of its own.
 *
 * What this module must keep true:
 *
 *  - A timer is measured against the wall clock, never by counting ticks. A browser throttles a
 *    hidden tab's intervals to once a minute or less, so a timer that subtracted one second per
 *    tick would end late by however long the clinician spent in another window — exactly the
 *    session that ran over. A running timer stores the moment it ends; the remaining time is
 *    always `endsAt - now`, and a tick that arrives late still fires the right alert.
 *  - Each alert fires once. `tick` reports a nudge or an end only on the transition, so a late
 *    tick that lands after both moments reports the nudge and the end together, in that order,
 *    and the next tick reports neither.
 *  - Nothing here leaves the device and nothing here names a client. A timer holds a label the
 *    clinician typed, and the Timers screen says it stays in this browser.
 *
 * `now` is always a parameter, so ordering is a node test (timers.test.ts) rather than a wait.
 */

/** How a timer tells the person it is time. The words for each live in copy.ts. */
export type AlertStyle = 'sound' | 'vibrate' | 'watch' | 'screen'

export const ALERT_STYLES: readonly AlertStyle[] = ['sound', 'vibrate', 'watch', 'screen']

export interface Timer {
  id: string
  label: string
  /** The whole length, in milliseconds. Grows when time is added, so the ring stays honest. */
  totalMs: number
  /** Minutes before the end at which to nudge; 0 means no nudge. */
  nudgeMin: number
  style: AlertStyle
  /** While running: the wall-clock moment it ends. While paused or finished: null. */
  endsAt: number | null
  /** While paused: what was left. While running it is recomputed, so it is not trusted then. */
  leftMs: number
  nudged: boolean
  done: boolean
}

export type TimerEvent = { id: string; kind: 'nudge' | 'end' }

export const MAX_MINUTES = 480
const MIN = 60_000

/** The reason a typed length is refused, or null when it is usable. */
export function minutesProblem(raw: string): string | null {
  const n = Number(raw.trim().replace(',', '.'))
  if (raw.trim() === '' || !Number.isFinite(n)) return 'Type the minutes as a number, like 15.'
  if (n <= 0) return 'A timer needs at least a minute.'
  if (n > MAX_MINUTES) return `A timer can run for up to ${MAX_MINUTES} minutes.`
  return null
}

export function parseMinutes(raw: string): number {
  return Number(raw.trim().replace(',', '.'))
}

export function createTimer(
  id: string,
  label: string,
  minutes: number,
  nudgeMin: number,
  style: AlertStyle,
  now: number,
): Timer {
  const totalMs = Math.round(minutes * MIN)
  // A nudge at or past the start would fire the instant the timer began, which is noise.
  const nudge = nudgeMin > 0 && nudgeMin < minutes ? nudgeMin : 0
  return {
    id,
    label: label.trim() || 'Timer',
    totalMs,
    nudgeMin: nudge,
    style,
    endsAt: now + totalMs,
    leftMs: totalMs,
    nudged: nudge === 0,
    done: false,
  }
}

export function remainingMs(t: Timer, now: number): number {
  if (t.done) return 0
  if (t.endsAt === null) return t.leftMs
  return Math.max(0, t.endsAt - now)
}

export function isRunning(t: Timer): boolean {
  return !t.done && t.endsAt !== null
}

/** Advances every timer to `now` and returns what should alert, nudges before ends. */
export function tick(timers: Timer[], now: number): { timers: Timer[]; events: TimerEvent[] } {
  const events: TimerEvent[] = []
  const next = timers.map((t) => {
    if (!isRunning(t)) return t
    const left = remainingMs(t, now)
    let u = t
    if (!u.nudged && left <= u.nudgeMin * MIN) {
      u = { ...u, nudged: true }
      events.push({ id: u.id, kind: 'nudge' })
    }
    if (left <= 0) {
      u = { ...u, done: true, endsAt: null, leftMs: 0 }
      events.push({ id: u.id, kind: 'end' })
    }
    return u
  })
  return { timers: next, events }
}

export function pause(t: Timer, now: number): Timer {
  if (!isRunning(t)) return t
  return { ...t, leftMs: remainingMs(t, now), endsAt: null }
}

export function resume(t: Timer, now: number): Timer {
  if (t.done || t.endsAt !== null) return t
  return { ...t, endsAt: now + t.leftMs }
}

/**
 * Adds minutes, to a running, paused or finished timer. A finished one starts again with the
 * added time, and its nudge is re-armed only if the new remainder is longer than the nudge.
 */
export function addMinutes(t: Timer, minutes: number, now: number): Timer {
  const add = Math.round(minutes * MIN)
  const left = remainingMs(t, now) + add
  const running = t.done || t.endsAt !== null
  return {
    ...t,
    done: false,
    leftMs: left,
    endsAt: running ? now + left : null,
    totalMs: Math.max(t.totalMs, left),
    nudged: t.nudgeMin === 0 ? true : left <= t.nudgeMin * MIN ? t.nudged : false,
  }
}

/** 50:00, 9:05, 1:50:00 — minutes and seconds, with hours only when there are some. */
export function clock(ms: number): string {
  const s = Math.max(0, Math.ceil(ms / 1000))
  const h = Math.floor(s / 3600)
  const m = Math.floor((s % 3600) / 60)
  const ss = String(s % 60).padStart(2, '0')
  return h ? `${h}:${String(m).padStart(2, '0')}:${ss}` : `${m}:${ss}`
}

/** The presets a practice reaches for: label, minutes, nudge minutes. */
export const PRESETS: readonly { label: string; minutes: number; nudgeMin: number }[] = [
  { label: 'Session', minutes: 50, nudgeMin: 10 },
  { label: 'Medication review', minutes: 25, nudgeMin: 5 },
  { label: 'Assessment', minutes: 110, nudgeMin: 15 },
  { label: 'Break', minutes: 10, nudgeMin: 0 },
  { label: 'Writing notes', minutes: 10, nudgeMin: 2 },
]
