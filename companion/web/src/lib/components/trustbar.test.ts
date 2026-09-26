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
    // This used to read `toMatch(/--mood-3/)`: the strip proved it was not green by being
    // painted the MIDDLE of the mood ramp instead. That was the same category error one step
    // down. The ramp encodes a person's reported experience and nothing else, and the posture
    // of a browser tab is not that person's experience — so the strip now names no step of the
    // ramp at all, which is strictly stronger than pinning it to the middle one.
    expect(trustBarCode).not.toMatch(/--mood-/)
    // Positive half, so this cannot pass by the strip losing its background entirely: it is
    // painted the quiet chrome ground, the machine describing itself.
    expect(trustBarCode).toMatch(/background:\s*var\(--chrome\)/)
  })

  it('does not decide its posture from network state', () => {
    // Being offline this instant says nothing about whether a surface would call out.
    expect(trustBarCode).not.toMatch(/navigator\.onLine/)
    expect(trustBarCode).not.toMatch(/\bonline\b/)
  })

  it('states a different posture for each of the four surfaces', () => {
    expect(trustBarCode).toContain("surface === 'local'")
    expect(trustBarCode).toContain("surface === 'setup'")
    expect(trustBarCode).toContain("surface === 'sync'")
    // The three server-touching postures must say so out loud.
    expect(trustBarCode).toMatch(/talks to your server/i)
    expect(trustBarCode).toMatch(/sends data to your server/i)
    expect(trustBarCode).toMatch(/reads your server’s configuration/i)
  })

  it('the setup posture says what the one request carries, and when it stops', () => {
    /*
     * THE SURFACE THIS STRIP GOT WRONG MOST RECENTLY. A configuration probe was added to
     * App.svelte on load, and the first-run screen said in its own copy that it was reading
     * /v1/config — while this strip, one element above it, went on printing the `local` sentence
     * "sends nothing". Both were on screen at once.
     *
     * The posture that fixes it has to earn its place: a vaguer "this page may contact the
     * server" would be honest and useless. It states what is asked, that nothing about the person
     * goes with it, and that it stops — because the reader's actual question is whether the
     * offline viewer they are about to use phones home.
     */
    const setupBranch = trustBarCode.slice(
      trustBarCode.indexOf("surface === 'setup'"),
      trustBarCode.indexOf("surface === 'sync'"),
    )
    expect(setupBranch.length).toBeGreaterThan(100) // the branch was really found
    expect(setupBranch).toMatch(/sends nothing about\s+you or your journal/i)
    expect(setupBranch).toMatch(/stops asking on\s+load/i)
    // And it does not borrow the promise it exists to stop overstating.
    expect(setupBranch).not.toMatch(/reads your backup in the browser/i)
  })

  it('App.svelte derives the surface from the active tab AND from whether it is reading', () => {
    expect(app).toMatch(/<TrustBar surface=\{trustSurface\} \/>/)
    /*
     * The mapping itself now lives in lib/trust/posture.ts, where posture.test.ts asserts the
     * rule over every surface: a load that reaches the network is never described as sending
     * nothing. It was a ternary here that named three tabs and let everything else fall through
     * to `local`, which is how a request belonging to no tab at all became invisible to it.
     */
    expect(app).toContain('trustPostureFor(source, readingConfiguration)')
    expect(app, 'the posture is hand-rolled in App.svelte again').not.toMatch(
      /source === 'sync' \? 'sync'/,
    )
    // The second argument must be the real predicate the configuration read is gated on, not a
    // flag of App.svelte's own that could drift away from it.
    expect(app).toContain('shouldReadConfiguration({ session: sessionShape, stored })')
    expect(app).toContain('startConfigurationRead({ session: sessionShape, stored }')
    // The local promise is only true if this file makes no request of its own. It held the one
    // unguarded fetch in the tree, which is the defect above.
    expect(app, 'App.svelte fetches directly again').not.toMatch(/(?<![\w.])fetch\s*\(/)
  })
})

/**
 * One posture's sentence as it reads: the markup between its `{#if}`/`{:else if}` and the next
 * branch, tags removed without a space (so `<strong>…</strong> This` reads as it renders) and
 * whitespace collapsed. Takes the source as an argument so a planted copy runs through it too.
 */
function branchProse(code: string, open: string, close: string): string {
  const from = code.indexOf(open)
  const to = code.indexOf(close, from + open.length)
  if (from < 0 || to < 0) return ''
  return code
    .slice(from + open.length, to)
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/<[^>]*>/g, '')
    .replace(/\s+/g, ' ')
    .trim()
}

describe('the local posture asks for nothing a person cannot do (#252)', () => {
  const OPEN = "{#if surface === 'local'}"
  const CLOSE = "{:else if surface === 'setup'}"
  const NOW =
    'Meant to run offline. This tab works on what is already in this browser and sends nothing — ' +
    'but a page cannot prove that about itself, and nothing yet lets you check this build against a ' +
    'published value.'
  /*
   * Retired: a check against a value no release publishes, before unlocking a backup no local tab
   * can open. The drop zone reads plain JSON, and "your backup" was wrong on the other three local
   * surfaces (self-checks, the tool builder, the practice panel).
   */
  const RETIRED = [
    'This tab reads your backup in the browser',
    "Verify this build's integrity before you unlock an encrypted backup.",
  ]

  it('says the one thing that is true today, whole', () => {
    expect(branchProse(trustBarCode, OPEN, CLOSE)).toBe(NOW)
  })

  it('no longer asks for a check, or names an encrypted backup', () => {
    const local = branchProse(trustBarCode, OPEN, CLOSE)
    expect(local.length).toBeGreaterThan(100) // the branch was really found
    for (const sentence of RETIRED) expect(local).not.toContain(sentence)
    expect(local).not.toMatch(/\bverify\b|\bunlock\b|encrypted backup/i)
    // Control: the retired sentences, planted back into the real branch, are seen by the same
    // extraction and the same patterns.
    const planted = trustBarCode.replace(
      'and nothing yet lets you',
      "and nothing yet lets you. This tab reads your backup in the browser. Verify this build's integrity before you unlock an encrypted backup.",
    )
    expect(planted).not.toBe(trustBarCode)
    const seen = branchProse(planted, OPEN, CLOSE)
    for (const sentence of RETIRED) expect(seen).toContain(sentence)
    expect(seen).toMatch(/\bverify\b|\bunlock\b|encrypted backup/i)
  })
})
