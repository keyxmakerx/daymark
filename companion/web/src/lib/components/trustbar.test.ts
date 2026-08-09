import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

/**
 * Honesty gate for the trust strip, in the same spirit as the instrument provenance gate.
 *
 * The strip claimed, on every surface, that the viewer "makes no network requests" and that
 * "your data never leaves this device". Three of the six tabs contradict that: Connect to sync
 * fetches from the server, Owner console talks to it, and Recover access POSTs the email address
 * typed into the form directly beneath the sentence — and App.svelte opens on Recover access by
 * default when the user arrives from an emailed recovery link.
 *
 * There is no component-rendering harness in this project, so this reads the source. That is
 * enough for the property that matters: the claim must not be a constant, and the absolute
 * phrasings must not come back. A regression here is someone re-typing a sentence, not a subtle
 * runtime path, so a source assertion catches the realistic failure.
 */
const read = (rel: string) =>
  readFileSync(fileURLToPath(new URL(rel, import.meta.url)), 'utf8')

/**
 * Comments quote the removed sentences on purpose — that is how the next reader learns why the
 * copy is worded as it is. So the "must not appear" assertions have to run against code and
 * markup only, or the explanation would trip the gate that the explanation exists to support.
 * `(?<!:)` keeps `https://` intact.
 */
const codeOnly = (src: string) =>
  src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/(?<!:)\/\/[^\n]*/g, '')

const trustBar = read('./TrustBar.svelte')
const trustBarCode = codeOnly(trustBar)
const app = read('../../App.svelte')

describe('trust strip — honesty gate', () => {
  it('the comment-stripper leaves the rendered copy alone', () => {
    // Guard the guard: if codeOnly ever ate the markup, every assertion below would pass
    // vacuously and the gate would be decorative.
    expect(trustBarCode).toMatch(/<aside class="trust"/)
    expect(trustBarCode).toMatch(/Meant to run offline/)
    expect(trustBar.length - trustBarCode.length).toBeGreaterThan(200) // comments were present
  })

  it('never claims the page makes no network requests', () => {
    // Unqualified and false on half the tabs. The replacement says what the *current* surface
    // does, and points at integrity verification rather than asking you to take the page's word.
    expect(trustBarCode).not.toMatch(/makes no network requests/i)
    expect(trustBarCode).not.toMatch(/never leaves this device/i)
  })

  it('is not painted green — no surface here earns the locked-trust colour', () => {
    // COMPANION_UX.md 10.1: served portal JS is lower-assurance regardless of network state,
    // "so green would overclaim". The old code used --mood-5 (green) whenever navigator.onLine
    // was false, which is the reasoning that rule exists to reject.
    expect(trustBarCode).not.toMatch(/--mood-5/)
    expect(trustBarCode).toMatch(/--mood-3/)
  })

  it('does not decide its posture from network state', () => {
    // Being offline this instant says nothing about whether a surface would call out.
    expect(trustBarCode).not.toMatch(/navigator\.onLine/)
    expect(trustBarCode).not.toMatch(/\bonline\b/)
  })

  it('states a different posture for each of the three surfaces', () => {
    expect(trustBarCode).toContain("surface === 'local'")
    expect(trustBarCode).toContain("surface === 'sync'")
    // The two server-touching postures must say so out loud.
    expect(trustBarCode).toMatch(/talks to your server/i)
    expect(trustBarCode).toMatch(/sends data to your server/i)
  })

  it('App.svelte derives the surface from the active tab, not a constant', () => {
    expect(app).toMatch(/<TrustBar surface=\{trustSurface\} \/>/)
    // The three tabs that reach the network must not resolve to the "nothing leaves" copy.
    expect(app).toMatch(/source === 'sync' \? 'sync'/)
    expect(app).toMatch(/source === 'owner' \|\| source === 'recover' \? 'account'/)
  })
})
