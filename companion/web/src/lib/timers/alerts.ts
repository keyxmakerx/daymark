/*
 * How a timer reaches the person: the device's chime, buzz and notification, behind ports so the
 * choice of fallback is a node test (alerts.test.ts).
 *
 * The rule it keeps: an alert is never silently lost. When the device cannot do what was chosen
 * — no vibration motor, notifications refused, no audio — the banner still shows and says what
 * happened instead (FELL_BACK in copy.ts). Every style shows the banner; the banner is the floor.
 */
import type { AlertStyle } from './timers'

export interface AlertPorts {
  chime(): boolean
  vibrate(pattern: number[]): boolean
  notify(title: string, body: string): boolean
}

/** What fired, and what the banner should say about any fallback. */
export function deliver(
  ports: AlertPorts,
  style: AlertStyle,
  title: string,
  body: string,
): { fellBack: 'vibrate' | 'watch' | 'sound' | null } {
  if (style === 'sound') return { fellBack: ports.chime() ? null : 'sound' }
  if (style === 'vibrate') return { fellBack: ports.vibrate([180, 90, 180]) ? null : 'vibrate' }
  if (style === 'watch') return { fellBack: ports.notify(title, body) ? null : 'watch' }
  return { fellBack: null }
}

/* ── The browser's ports ────────────────────────────────────────────────────────────────────── */

let ctx: AudioContext | null = null

/** Two soft sine notes. No file to fetch, so the strict CSP needs no media source. */
export function browserChime(): boolean {
  try {
    const C = globalThis.AudioContext
    if (!C) return false
    ctx ??= new C()
    const c = ctx
    for (const [freq, delay] of [
      [660, 0],
      [880, 0.22],
    ] as const) {
      const o = c.createOscillator()
      const g = c.createGain()
      const t = c.currentTime + delay
      o.type = 'sine'
      o.frequency.value = freq
      g.gain.setValueAtTime(0.0001, t)
      g.gain.exponentialRampToValueAtTime(0.18, t + 0.03)
      g.gain.exponentialRampToValueAtTime(0.0001, t + 0.9)
      o.connect(g).connect(c.destination)
      o.start(t)
      o.stop(t + 1)
    }
    return true
  } catch {
    return false
  }
}

export function browserVibrate(pattern: number[]): boolean {
  try {
    return typeof navigator !== 'undefined' && typeof navigator.vibrate === 'function'
      ? navigator.vibrate(pattern)
      : false
  } catch {
    return false
  }
}

export function browserNotify(title: string, body: string): boolean {
  try {
    if (typeof Notification === 'undefined' || Notification.permission !== 'granted') return false
    new Notification(title, { body, silent: false })
    return true
  } catch {
    return false
  }
}

/** Asked only when the person picks "Tap on my watch", never on load. */
export async function askForNotifications(): Promise<boolean> {
  try {
    if (typeof Notification === 'undefined') return false
    if (Notification.permission === 'granted') return true
    if (Notification.permission === 'denied') return false
    return (await Notification.requestPermission()) === 'granted'
  } catch {
    return false
  }
}

export const browserPorts: AlertPorts = {
  chime: browserChime,
  vibrate: browserVibrate,
  notify: browserNotify,
}
