/*
 * EVERY CLIENT CALLS ITS FETCH DETACHED (#494).
 *
 * A browser's `fetch` refuses any receiver but the window: stored on an object and called as
 * `this.doFetch(...)`, it throws "Illegal invocation". Node's fetch does not care, so a client that
 * calls it as a method passes every test here and fails every request in Chrome, Firefox and
 * Safari. That shipped twice (sync/portal.ts, then the practice sign-in). So:
 *
 *   1. Behaviour: each client is handed a fake that throws exactly as a browser does whenever it
 *      is called with a receiver, and must still reach it. A positive control proves the fake
 *      really refuses a method call.
 *   2. Structure: no source file outside tests calls `this.doFetch(` as a method, and the scan is
 *      shown to see a planted one.
 */
import { readFileSync, readdirSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'
import { PortalClient as TherapistPortalClient } from './therapist/session'
import { PortalClient as OwnerPortalClient } from './sync/portal'
import { SyncClient } from './sync/client'
import { PracticeClient } from './practice/client'

/** A fetch that behaves as a browser's does about its receiver, and records that it was reached. */
function browserLikeFetch() {
  const seen: string[] = []
  const f = function (this: unknown, input: RequestInfo | URL): Promise<Response> {
    if (this !== undefined && this !== globalThis) {
      throw new TypeError("Failed to execute 'fetch' on 'Window': Illegal invocation")
    }
    seen.push(String(input))
    return Promise.resolve(new Response('{}', { status: 200, headers: { 'content-type': 'application/json' } }))
  }
  return { f: f as unknown as typeof fetch, seen }
}

describe('the browser-like fake (positive control)', () => {
  it('refuses a method call, as a browser does', () => {
    const { f, seen } = browserLikeFetch()
    const holder = { doFetch: f }
    expect(() => holder.doFetch('https://example.invalid/x')).toThrow(/Illegal invocation/)
    expect(seen).toEqual([])
  })
  it('accepts a detached call', async () => {
    const { f, seen } = browserLikeFetch()
    await f('https://example.invalid/x')
    expect(seen).toEqual(['https://example.invalid/x'])
  })
})

describe('each client reaches a bare, unbound fetch', () => {
  it('the clinician and practice sign-in client', async () => {
    const { f, seen } = browserLikeFetch()
    await new TherapistPortalClient('https://example.invalid', f).loginTotp('member-1', '123456')
    expect(seen.length).toBeGreaterThan(0)
  })
  it('the owner console client', async () => {
    const { f, seen } = browserLikeFetch()
    await new OwnerPortalClient('https://example.invalid', 'token', f).getConfig()
    expect(seen).toEqual(['https://example.invalid/v1/config'])
  })
  it('the sync client', async () => {
    const { f, seen } = browserLikeFetch()
    await new SyncClient('https://example.invalid', 'token', f).listLineages().catch(() => undefined)
    expect(seen.length).toBeGreaterThan(0)
  })
  it('the practice client', async () => {
    const { f, seen } = browserLikeFetch()
    const result = await new PracticeClient('https://example.invalid', f).roster('org-1')
    expect(seen.length).toBeGreaterThan(0)
    if (!result.ok) expect(result.failure.kind).not.toBe('no-answer')
  })
})

const SRC = fileURLToPath(new URL('..', import.meta.url))

function sourceFiles(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((e) => {
    const p = join(dir, e.name)
    if (e.isDirectory()) return sourceFiles(p)
    if (!/\.(ts|svelte)$/.test(e.name) || /\.test\.ts$/.test(e.name)) return []
    return [p]
  })
}

/** A method call on a stored fetch, ignoring comments. */
const METHOD_CALL = /this\.(doFetch|fetchImpl|fetch)\s*\(/

function codeLines(text: string): string[] {
  return text
    .split('\n')
    .map((l) => l.replace(/\/\/.*$/, ''))
    .filter((l) => !/^\s*(\*|\/\*)/.test(l))
}

describe('no source file calls a stored fetch as a method', () => {
  it('sees a planted method call (positive control)', () => {
    const planted = '  private async req() {\n    return this.doFetch(this.base + path)\n  }'
    expect(codeLines(planted).some((l) => METHOD_CALL.test(l))).toBe(true)
  })
  it('scans a non-trivial number of files', () => {
    expect(sourceFiles(SRC).length).toBeGreaterThan(50)
  })
  it('finds none', () => {
    const offenders = sourceFiles(SRC).flatMap((p) =>
      codeLines(readFileSync(p, 'utf8'))
        .filter((l) => METHOD_CALL.test(l))
        .map((l) => `${p.slice(SRC.length)}: ${l.trim()}`),
    )
    expect(offenders).toEqual([])
  })
})
