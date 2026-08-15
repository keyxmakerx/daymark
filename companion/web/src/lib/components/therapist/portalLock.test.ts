import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { isLive, touch, DEFAULT_IDLE_MS, type SessionInfo } from '../../therapist/session'

/*
 * THE PORTAL LOCK, WHICH DID NOT LOCK.
 *
 * The idle/absolute guard was written as:
 *
 *     const live = $derived(ctx ? isLive(ctx.session) : false)
 *
 * `isLive` reads `Date.now()` inside itself, so the only dependency `$derived` could track was
 * `ctx` — written exactly twice, at unlock and at logout. The guard was evaluated once, at unlock,
 * when it is true by construction, and never again. The portal never locked, `zeroize` was never
 * reached on the idle path, and the therapist's unwrapped reading keys and the decrypted share
 * stayed in memory until the tab closed.
 *
 * The second half is worse in its way: `touch()` — which pushes the idle deadline forward on
 * activity — had NO production caller. So fixing the clock alone would have logged a therapist out
 * fifteen minutes after unlock while they were actively reading. Both halves or neither.
 *
 * There is no DOM harness in this project (vite.config.ts sets `environment: 'node'`), so the
 * reactive wiring is asserted against the source and the time arithmetic is executed.
 */

const SOURCE = readFileSync(fileURLToPath(new URL('./TherapistPortal.svelte', import.meta.url)), 'utf8')

/**
 * The source with comments removed.
 *
 * Needed for the negative assertions, and the reason is worth keeping: the comment above the guard
 * quotes the broken line verbatim so the next reader knows what went wrong — and a `not.toMatch`
 * over the raw file duly found it there and failed. A guard that cannot tell an explanation from
 * the thing it explains would push you to delete the explanation.
 */
const CODE = SOURCE.replace(/\/\*[\s\S]*?\*\//g, '')
  .replace(/<!--[\s\S]*?-->/g, '')
  .split('\n')
  .filter((l) => !l.trimStart().startsWith('//'))
  .join('\n')

describe('the source is what we think it is', () => {
  it('was read and still contains the guard', () => {
    // Guards every assertion below: an unreadable file would make them all pass.
    expect(SOURCE.length).toBeGreaterThan(1000)
    expect(SOURCE).toContain('isLive')
    expect(SOURCE).toContain('logout()')
  })

  it('the comment stripper strips comments and keeps code', () => {
    // Otherwise the negative assertions below could pass by reading an empty string.
    expect(CODE).toContain('isLive')
    expect(CODE).toContain('$derived')
    expect(SOURCE).toContain('/*')
    expect(CODE).not.toContain('/*')
    expect(CODE.length).toBeGreaterThan(600)
  })
})

describe('time is a value the reactive system can see', () => {
  it('passes an explicit clock into isLive rather than relying on its default', () => {
    // The whole bug in one line. `isLive(ctx.session)` lets Date.now() hide inside the callee,
    // where no reactive system can observe it changing.
    expect(CODE).toMatch(/isLive\(\s*ctx\.session\s*,\s*\w+\s*\)/)
    expect(CODE).not.toMatch(/isLive\(\s*ctx\.session\s*\)/)
  })

  it('the check is not vacuous — it recognises the broken form', () => {
    const broken = 'const live = $derived(ctx ? isLive(ctx.session) : false)'
    expect(/isLive\(\s*ctx\.session\s*\)/.test(broken)).toBe(true)
    expect(/isLive\(\s*ctx\.session\s*,\s*\w+\s*\)/.test(broken)).toBe(false)
  })

  it('drives that clock from an interval, and clears it', () => {
    expect(CODE).toMatch(/setInterval\(/)
    // A timer that outlives the component is a leak and keeps a logged-out portal ticking.
    expect(CODE).toMatch(/clearInterval\(/)
  })

  it('still logs out — and zeroizes — when the session stops being live', () => {
    expect(CODE).toMatch(/if \(ctx && !live\) logout\(\)/)
    expect(CODE).toContain('zeroize(ctx.keys)')
  })
})

describe('activity actually refreshes the idle deadline', () => {
  it('calls touch, which previously had no production caller anywhere', () => {
    expect(CODE).toMatch(/\btouch\(/)
    expect(CODE).toMatch(/import \{[^}]*\btouch\b[^}]*\} from '\.\.\/\.\.\/therapist\/session'/)
  })

  it('observes real user activity, not just a timer', () => {
    expect(CODE).toMatch(/onpointerdown=\{noteActivity\}/)
    expect(CODE).toMatch(/onkeydown=\{noteActivity\}/)
  })

  it('throttles, so a keystroke does not reassign the session', () => {
    expect(CODE).toMatch(/lastTouch/)
  })
})

describe('the time arithmetic itself', () => {
  const session = (idle: number, absolute: number): SessionInfo => ({
    relRef: 'r',
    credentialKind: 'totp',
    csrf: 'c',
    idleExpiresAt: idle,
    absoluteExpiresAt: absolute,
  })

  it('is live only inside both deadlines', () => {
    expect(isLive(session(1_500, 2_000), 1_000)).toBe(true)
    expect(isLive(session(1_500, 2_000), 1_600)).toBe(false) // idle passed
    expect(isLive(session(1_500, 2_000), 2_100)).toBe(false) // absolute passed
  })

  it('activity moves the idle deadline and never the absolute one', () => {
    /*
     * The property that keeps the refresh honest. If `touch` moved `absoluteExpiresAt` too, a
     * therapist who kept typing would hold a session open forever and the server's hard cap would
     * mean nothing — the idle timer would have become a way to defeat the absolute one.
     */
    const before = session(1_500, 2_000)
    const after = touch(before, DEFAULT_IDLE_MS, 1_400)
    expect(after.idleExpiresAt).toBe(1_400 + DEFAULT_IDLE_MS)
    expect(after.absoluteExpiresAt).toBe(before.absoluteExpiresAt)
  })

  it('cannot be kept alive past the absolute cap by any amount of activity', () => {
    let s = session(1_500, 2_000)
    // Touch every 100ms forever; the absolute deadline must still end it.
    for (let now = 1_000; now < 5_000; now += 100) s = touch(s, DEFAULT_IDLE_MS, now)
    expect(isLive(s, 2_100)).toBe(false)
  })
})
