import { describe, it, expect, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { CONFIG_TIMEOUT_MS, startConfigurationRead, type ConfigReadDeps } from './configProbe'
import {
  CONFIG_PATH,
  SETUP_CHOICE_VERSION,
  resolveSetup,
  shouldReadConfiguration,
  type ConfigState,
  type ShapeId,
  type StoredChoice,
} from './shape'

/**
 * ═══════════════════════════════════════════════════════════════════════════════════════════
 * DOES OPENING THIS PAGE CONTACT THE SERVER?
 * ═══════════════════════════════════════════════════════════════════════════════════════════
 *
 * THE DEFECT THIS FILE IS THE ANSWER TO. The configuration read began life as an `$effect` in
 * App.svelte that read no reactive state, so it ran once per page load — every load, decided or
 * not. index.html therefore reached the server every time it opened, while the trust strip a few
 * lines below it promised, on the default tab, "This tab reads your backup in the browser and
 * sends nothing". The setup panel underneath the strip said out loud that it was reading
 * /v1/config. The page contradicted itself on screen, and a Solo deployment's server collected
 * the address and the hour of every time the offline viewer was opened.
 *
 * WHY NOTHING CAUGHT IT. The suite runs in node and there is no component-rendering harness, so
 * behaviour that lived inside a `.svelte` effect could only be grepped for — and no grep answers
 * "does this load make a request?". The request moved into a module for precisely this: the
 * question becomes a fetch that counts its calls, and the assertion becomes `toBe(0)`.
 *
 * Every test below fails against the version this replaces. The second one is the defect itself.
 */

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   A fetch that reports what was asked of it.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

interface Recorder {
  deps: ConfigReadDeps
  calls: { url: string; init: RequestInit | undefined }[]
  /** Resolve the outstanding request. Held open so cancellation and timeouts are testable. */
  settle: (res: { ok: boolean; body: string }) => void
  fail: (reason: unknown) => void
  signal: () => AbortSignal | null | undefined
}

function recorder(timeoutMs?: number): Recorder {
  const calls: Recorder['calls'] = []
  let resolveWith: ((v: unknown) => void) | null = null
  let rejectWith: ((e: unknown) => void) | null = null

  const fetchStub = ((url: string, init?: RequestInit) => {
    calls.push({ url, init })
    return new Promise((resolve, reject) => {
      resolveWith = resolve
      rejectWith = reject
      // A real fetch rejects when its signal aborts; the timeout path depends on that, so the
      // stub has to honour it rather than hang forever and pass the test by accident.
      init?.signal?.addEventListener('abort', () => reject(new Error('aborted')))
    })
  }) as unknown as typeof globalThis.fetch

  return {
    deps: { fetch: fetchStub, ...(timeoutMs === undefined ? {} : { timeoutMs }) },
    calls,
    settle: ({ ok, body }) => resolveWith?.({ ok, text: async () => body }),
    fail: (reason) => rejectWith?.(reason),
    signal: () => calls[0]?.init?.signal,
  }
}

const current = (shape: ShapeId): StoredChoice => ({ version: SETUP_CHOICE_VERSION, shape })
const NOTHING_LOCAL = { session: null, stored: null } as const

/** Lets a resolved promise chain run without leaning on timers, which some tests fake. */
const settleMicrotasks = () => Promise.resolve().then(() => Promise.resolve())

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   1. Which loads make a request, which is the whole point.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('the configuration read, and the loads that make no request', () => {
  it('reads configuration when the question is open — the detector detects', async () => {
    // Non-vacuity for every `toBe(0)` below: this harness really does observe a request when one
    // is made, so an assertion that none was made is about the code and not about a broken stub.
    const r = recorder()
    const seen: ConfigState[] = []
    startConfigurationRead(NOTHING_LOCAL, (s) => seen.push(s), r.deps)

    expect(r.calls.length).toBe(1)
    expect(r.calls[0]!.url).toBe(CONFIG_PATH)
    r.settle({ ok: true, body: JSON.stringify({ setupMode: 'paired' }) })
    await settleMicrotasks()
    expect(seen).toEqual([{ kind: 'set', shape: 'paired' }])
  })

  it('makes NO request when this browser already holds the answer', async () => {
    /*
     * THE REGRESSION. A returning Solo owner opens index.html, lands on "Open a backup file", and
     * reads that this tab sends nothing. That sentence is only true if this number is zero.
     */
    const r = recorder()
    const seen: ConfigState[] = []
    const cancel = startConfigurationRead(
      { session: null, stored: current('solo') },
      (s) => seen.push(s),
      r.deps,
    )
    await settleMicrotasks()

    expect(r.calls.length).toBe(0)
    // And it reports nothing rather than inventing an `unreachable` nobody asked for: the caller
    // stays at `reading`, the state that means "nothing has been read", which a resolved local
    // answer walks straight past.
    expect(seen).toEqual([])
    expect(() => cancel()).not.toThrow()
  })

  it('makes no request once a shape has been chosen this visit', async () => {
    // The same load, one click later. In App.svelte this is what cancels an in-flight probe: the
    // effect re-runs with a session answer, and there is nothing left to ask.
    const r = recorder()
    startConfigurationRead({ session: 'practice', stored: null }, () => {}, r.deps)
    await settleMicrotasks()
    expect(r.calls.length).toBe(0)
  })

  it('reads again when the stored answer is from an older set of choices', async () => {
    // A stale answer is not an answer to this build's question, so the question is open and the
    // read is warranted. Skipping it here would pin a machine to a shape nobody chose.
    const r = recorder()
    startConfigurationRead(
      { session: null, stored: { version: SETUP_CHOICE_VERSION - 1, shape: 'solo' } },
      () => {},
      r.deps,
    )
    expect(r.calls.length).toBe(1)
  })

  it('agrees with the rule it is built on, for every combination of inputs', () => {
    /*
     * The request is gated on `shouldReadConfiguration`, and that predicate is the mirror of
     * `resolveSetup`'s local-answer rules. Two statements of one rule drift; this pins them
     * together over the whole input space: a read happens exactly when a resolve that had no
     * configuration to go on would still be ASKING.
     */
    const sessions: (ShapeId | null)[] = [null, 'solo', 'paired', 'practice']
    const storeds: (StoredChoice | null)[] = [
      null,
      current('solo'),
      current('practice'),
      { version: SETUP_CHOICE_VERSION - 1, shape: 'paired' },
      { version: SETUP_CHOICE_VERSION + 1, shape: 'paired' },
    ]
    let asked = 0
    let settled = 0
    for (const session of sessions) {
      for (const stored of storeds) {
        const wouldAsk =
          resolveSetup({ config: { kind: 'unreachable' }, session, stored }).state === 'ask'
        expect(shouldReadConfiguration({ session, stored }), `${session} / ${stored?.version}`).toBe(
          wouldAsk,
        )
        wouldAsk ? asked++ : settled++
      }
    }
    // Both halves of the space were really covered, so this cannot pass by being all one answer.
    expect(asked).toBeGreaterThan(0)
    expect(settled).toBeGreaterThan(0)
  })
})

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   2. What the request carries, and how it ends.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('the request itself', () => {
  it('sends one plain GET and nothing about the person', async () => {
    const r = recorder()
    startConfigurationRead(NOTHING_LOCAL, () => {}, r.deps)

    const init = r.calls[0]!.init!
    expect(init.body ?? null).toBeNull()
    expect(init.method ?? 'GET').toBe('GET')
    // No cookies, no Authorization: this endpoint is unauthenticated and the read must not become
    // a way for the offline surface to identify anybody.
    expect(init.credentials ?? 'omit').not.toBe('include')
    expect(JSON.stringify(init.headers)).not.toMatch(/authorization|cookie|token/i)
    expect(init.cache).toBe('no-store')
  })

  it('treats every failure as the one thing it means to this screen', async () => {
    for (const break_ of ['status', 'transport'] as const) {
      const r = recorder()
      const seen: ConfigState[] = []
      startConfigurationRead(NOTHING_LOCAL, (s) => seen.push(s), r.deps)
      if (break_ === 'status') r.settle({ ok: false, body: 'nope' })
      else r.fail(new TypeError('Failed to fetch'))
      await settleMicrotasks()
      expect(seen, break_).toEqual([{ kind: 'unreachable' }])
    }
  })

  it('reports unreachable rather than throwing where there is no fetch at all', () => {
    // A `file:` origin on an older runtime. The question needs no server, so this is a state and
    // never an error: the person is asked.
    const seen: ConfigState[] = []
    expect(() =>
      startConfigurationRead(NOTHING_LOCAL, (s) => seen.push(s), { fetch: undefined }),
    ).not.toThrow()
    expect(seen).toEqual([{ kind: 'unreachable' }])
  })

  it('delivers nothing after it has been cancelled', async () => {
    // App.svelte returns this canceller as its effect cleanup. A late answer landing on a page
    // that has moved on would rearrange a screen somebody is already reading.
    const r = recorder()
    const seen: ConfigState[] = []
    const cancel = startConfigurationRead(NOTHING_LOCAL, (s) => seen.push(s), r.deps)
    cancel()
    r.settle({ ok: true, body: JSON.stringify({ setupMode: 'solo' }) })
    await settleMicrotasks()
    expect(seen).toEqual([])
    expect(r.signal()!.aborted).toBe(true)
  })

  it('gives up on its own, because an unreachable server must not hold the first screen', async () => {
    vi.useFakeTimers()
    try {
      const r = recorder(CONFIG_TIMEOUT_MS)
      const seen: ConfigState[] = []
      startConfigurationRead(NOTHING_LOCAL, (s) => seen.push(s), r.deps)

      vi.advanceTimersByTime(CONFIG_TIMEOUT_MS - 1)
      expect(r.signal()!.aborted).toBe(false)
      vi.advanceTimersByTime(1)
      expect(r.signal()!.aborted).toBe(true)
      await settleMicrotasks()
      expect(seen).toEqual([{ kind: 'unreachable' }])
    } finally {
      vi.useRealTimers()
    }
  })

  it('bounds the wait at a few seconds — long enough for a machine on the same network', () => {
    expect(CONFIG_TIMEOUT_MS).toBeGreaterThanOrEqual(1000)
    expect(CONFIG_TIMEOUT_MS).toBeLessThanOrEqual(5000)
  })
})

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   3. And that App.svelte still routes through it.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('App.svelte owns no request of its own', () => {
  const app = readFileSync(fileURLToPath(new URL('../../App.svelte', import.meta.url)), 'utf8')

  it('makes every network call through the module that decides whether to make one', () => {
    // Non-vacuity: the file really was read.
    expect(app).toContain('<TrustBar')
    /*
     * The defect in one line. index.html's own component held the only `fetch(` in the tree
     * outside a client module, and it was unguarded. Keeping the surface free of them is what
     * makes "does this load make a request?" a question the tests above can answer — a fetch
     * written back into this file would be invisible to them.
     */
    expect(app, 'App.svelte fetches directly again').not.toMatch(/(?<![\w.])fetch\s*\(/)
    expect(app).toContain('startConfigurationRead({ session: sessionShape, stored }')
  })

  it('hands the read the same inputs the strip uses, so they cannot disagree', () => {
    // If the probe were gated on one expression and the trust strip on another, the two could
    // drift into the exact contradiction this whole slice is about.
    expect(app).toContain('shouldReadConfiguration({ session: sessionShape, stored })')
    expect(app).toContain('trustPostureFor(source, readingConfiguration)')
  })
})
