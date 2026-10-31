import { describe, it, expect } from 'vitest'
import {
  createTimer, tick, pause, resume, addMinutes, remainingMs, clock, minutesProblem, isRunning,
} from './timers'
import { deliver, type AlertPorts } from './alerts'

const MIN = 60_000
const T0 = 1_800_000_000_000

describe('a timer is measured against the wall clock', () => {
  it('reports the right remainder after a tick that arrives late', () => {
    const t = createTimer('a', 'Session', 50, 10, 'screen', T0)
    // A hidden tab may not tick for minutes; the remainder is still exact.
    expect(remainingMs(t, T0 + 7 * MIN)).toBe(43 * MIN)
  })

  it('fires the nudge and the end together, in order, when one late tick passes both', () => {
    const t = createTimer('a', 'Session', 50, 10, 'screen', T0)
    const r = tick([t], T0 + 60 * MIN)
    expect(r.events).toEqual([{ id: 'a', kind: 'nudge' }, { id: 'a', kind: 'end' }])
    // Positive control for the "fires once" assertion below: something did fire here.
    expect(r.events.length).toBeGreaterThan(0)
    expect(tick(r.timers, T0 + 61 * MIN).events).toEqual([])
  })

  it('nudges once, at the nudge, and not before', () => {
    const t = createTimer('a', 'Session', 50, 10, 'screen', T0)
    expect(tick([t], T0 + 39 * MIN).events).toEqual([])
    const r = tick([t], T0 + 40 * MIN)
    expect(r.events).toEqual([{ id: 'a', kind: 'nudge' }])
    expect(tick(r.timers, T0 + 41 * MIN).events).toEqual([])
  })

  it('drops a nudge that would fire at or before the start', () => {
    const t = createTimer('a', 'Break', 10, 10, 'screen', T0)
    expect(t.nudgeMin).toBe(0)
    expect(tick([t], T0 + 1).events).toEqual([])
    // Control: the same length with a usable nudge does nudge.
    const u = createTimer('b', 'Break', 10, 2, 'screen', T0)
    expect(tick([u], T0 + 8 * MIN).events).toEqual([{ id: 'b', kind: 'nudge' }])
  })
})

describe('pause, resume and more time', () => {
  it('a paused timer does not run down and does not fire', () => {
    const p = pause(createTimer('a', 'S', 10, 0, 'screen', T0), T0 + 3 * MIN)
    expect(isRunning(p)).toBe(false)
    expect(remainingMs(p, T0 + 100 * MIN)).toBe(7 * MIN)
    expect(tick([p], T0 + 100 * MIN).events).toEqual([])
    const r = resume(p, T0 + 100 * MIN)
    expect(remainingMs(r, T0 + 105 * MIN)).toBe(2 * MIN)
    expect(tick([r], T0 + 107 * MIN).events).toEqual([{ id: 'a', kind: 'end' }])
  })

  it('five more minutes restarts a finished timer, and re-arms a nudge only when it fits', () => {
    const t = createTimer('a', 'S', 50, 10, 'screen', T0)
    const done = tick([t], T0 + 50 * MIN).timers[0]
    expect(done.done).toBe(true)
    const more = addMinutes(done, 5, T0 + 50 * MIN)
    expect(more.done).toBe(false)
    expect(remainingMs(more, T0 + 50 * MIN)).toBe(5 * MIN)
    // 5 left is under the 10-minute nudge, so the nudge stays spent.
    expect(tick([more], T0 + 51 * MIN).events).toEqual([])
    const lots = addMinutes(done, 30, T0 + 50 * MIN)
    expect(lots.nudged).toBe(false)
    expect(tick([lots], T0 + 70 * MIN).events).toEqual([{ id: 'a', kind: 'nudge' }])
  })
})

describe('typed minutes', () => {
  it('accepts a number, with a comma for a decimal point', () => {
    expect(minutesProblem('15')).toBeNull()
    expect(minutesProblem('2,5')).toBeNull()
  })
  it('refuses what is not a usable length', () => {
    for (const bad of ['', 'ten', '0', '-3', '481']) expect(minutesProblem(bad)).not.toBeNull()
  })
})

describe('clock', () => {
  it('reads minutes and seconds, with hours only when there are some', () => {
    expect(clock(50 * MIN)).toBe('50:00')
    expect(clock(9 * MIN + 4_100)).toBe('9:05')
    expect(clock(110 * MIN)).toBe('1:50:00')
    expect(clock(-5)).toBe('0:00')
  })
})

describe('an alert is never silently lost', () => {
  const ports = (ok: boolean): AlertPorts & { calls: string[] } => {
    const calls: string[] = []
    return {
      calls,
      chime: () => (calls.push('chime'), ok),
      vibrate: () => (calls.push('vibrate'), ok),
      notify: () => (calls.push('notify'), ok),
    }
  }
  it('uses the chosen channel and reports no fallback when it works', () => {
    for (const [style, call] of [['sound', 'chime'], ['vibrate', 'vibrate'], ['watch', 'notify']] as const) {
      const p = ports(true)
      expect(deliver(p, style, 't', 'b').fellBack).toBeNull()
      expect(p.calls).toEqual([call])
    }
  })
  it('names the fallback when the device cannot do it', () => {
    expect(deliver(ports(false), 'sound', 't', 'b').fellBack).toBe('sound')
    expect(deliver(ports(false), 'vibrate', 't', 'b').fellBack).toBe('vibrate')
    expect(deliver(ports(false), 'watch', 't', 'b').fellBack).toBe('watch')
  })
  it('screen only touches no device channel', () => {
    const p = ports(true)
    expect(deliver(p, 'screen', 't', 'b').fellBack).toBeNull()
    expect(p.calls).toEqual([])
  })
})
