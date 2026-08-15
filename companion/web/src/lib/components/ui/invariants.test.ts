import { describe, it, expect } from 'vitest'
import { readFileSync, readdirSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

/**
 * The invariant suite for the token layer.
 *
 * WHAT THIS IS FOR. Phase 1 split one palette into two: the mood ramp encodes a person's
 * reported experience, and everything the interface has to say about itself is drawn from
 * chrome, indigo, clay and amber. That split is not enforced by the type system, the compiler
 * or the browser. It is enforced by nothing at all except a shared understanding — which is to
 * say, in six months, by nothing. The realistic failure is not malice: it is someone in a hurry
 * reaching for `var(--mood-5)` because a row needed to look positive, and the reviewer having no
 * reason to know that is the one thing the ramp may never do. These tests are the reason that
 * commit stops.
 *
 * WHY THEY READ SOURCE. There is no component-rendering harness in this project, and the
 * properties here are properties of the source text, not of a rendered tree — "no component
 * outside BandTag names a mood token" is a statement about files. Reading the files is the
 * direct test, not a proxy for one.
 *
 * WHY EVERY TEST FIRST ASSERTS ITS SUBJECT EXISTS. A grep-based guard has one failure mode that
 * matters more than all the others: it passes because it found nothing. Delete BandTag, rename
 * app.css, move the directory — and a suite full of `expect(matches).toEqual([])` goes green
 * while reporting on an empty set. Each test below therefore opens by proving the thing it is
 * about is actually there (the file list is non-empty, the style blocks were found, the token
 * block parsed, the detector detects), and only then asserts the property. A test that cannot
 * fail is worse than no test, because it also removes the appetite to write a real one.
 *
 * WHY THE COMMENTS ARE STRIPPED BEFORE MATCHING. Every file in this directory explains its own
 * invariant in prose, and the prose necessarily spells the thing it forbids: AppShell's header
 * comment contains the literal text `body { position: relative; z-index: 1; isolation:
 * isolate }`, StatusPill's contains "NOT green", BandTag's contains `--mood-1..--mood-5`. A
 * guard that matched raw text would be satisfied by the explanation of the rule rather than by
 * the rule, which is the exact vacuity this file exists to avoid. So the structural assertions
 * run against the `<style>` block with CSS comments removed — what the browser actually gets.
 */

const HERE = fileURLToPath(new URL('.', import.meta.url))
const APP_CSS = fileURLToPath(new URL('../../../app.css', import.meta.url))

const read = (path: string) => readFileSync(path, 'utf8')

/** This file names every token it polices, so it must never be one of its own subjects. */
const SELF = 'invariants.test.ts'

const uiFiles = readdirSync(HERE, { withFileTypes: true })
  .filter((e) => e.isFile() && e.name !== SELF)
  .map((e) => e.name)
  .sort()

const svelteFiles = uiFiles.filter((n) => n.endsWith('.svelte'))

const source = new Map<string, string>(uiFiles.map((n) => [n, read(HERE + n)]))

/** `(?<!:)` so `https://` survives. Mirrors the stripper in trustbar.test.ts. */
const codeOnly = (src: string) =>
  src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/(?<!:)\/\/[^\n]*/g, '')

/** The `<style>` block's contents, CSS comments removed. Null when the file has no style block. */
function styleOf(name: string): string | null {
  const m = /<style>([\s\S]*?)<\/style>/.exec(source.get(name) ?? '')
  if (!m) return null
  return m[1]!.replace(/\/\*[\s\S]*?\*\//g, '')
}

/* ═══════════════════════════════════════════════════════════════════════════
   A minimal CSS reader.

   Enough to answer "what does this token resolve to, in this theme" and no more. app.css is
   two-tier by design — a semantic name points at a `--c-*` primitive — so a test that only read
   declarations would be asserting `--chrome: var(--c-chrome-day)`, which is a restatement of the
   file rather than a check on it. Following the chain to the hex is what makes the value
   assertions real, and it is what lets the green detector run against what actually paints.
   ═══════════════════════════════════════════════════════════════════════════ */

const css = read(APP_CSS).replace(/\/\*[\s\S]*?\*\//g, '')

function braceBody(src: string, openAt: number): string {
  let depth = 0
  for (let k = openAt; k < src.length; k++) {
    const ch = src[k]
    if (ch === '{') depth++
    else if (ch === '}') {
      depth--
      if (depth === 0) return src.slice(openAt + 1, k)
    }
  }
  throw new Error(`unbalanced braces in app.css at ${openAt}`)
}

/**
 * Every block whose selector is EXACTLY `selector`. The exactness matters: a naive search for
 * `:root` also finds `:root:not([data-theme="light"])` and `:root[data-theme="dark"]`, which
 * would fold the dark palettes into the light one and make the light assertions meaningless.
 */
function selectorBodies(src: string, selector: string): string[] {
  const out: string[] = []
  let i = 0
  for (;;) {
    const at = src.indexOf(selector, i)
    if (at === -1) return out
    i = at + selector.length
    const before = at === 0 ? '\n' : src[at - 1]!
    if (!/[\s{};,]/.test(before)) continue
    let j = i
    while (j < src.length && /\s/.test(src[j]!)) j++
    if (src[j] !== '{') continue
    out.push(braceBody(src, j))
  }
}

/** The custom-property declarations a block makes in its own right; nested rules are dropped. */
function declarations(body: string): Map<string, string> {
  let flat = body
  for (;;) {
    const next = flat.replace(/\{[^{}]*\}/g, '')
    if (next === flat) break
    flat = next
  }
  const map = new Map<string, string>()
  for (const chunk of flat.split(';')) {
    const m = /^\s*(--[A-Za-z0-9_-]+)\s*:\s*([\s\S]+)$/.exec(chunk)
    if (m) map.set(m[1]!, m[2]!.trim())
  }
  return map
}

function mergedDeclarations(bodies: string[]): Map<string, string> {
  const map = new Map<string, string>()
  for (const body of bodies) for (const [k, v] of declarations(body)) map.set(k, v)
  return map
}

const lightBodies = selectorBodies(css, ':root')
const light = mergedDeclarations(lightBodies)

const mediaMatch = /@media\s*\(\s*prefers-color-scheme\s*:\s*dark\s*\)/.exec(css)
const mediaBody = mediaMatch
  ? braceBody(css, css.indexOf('{', mediaMatch.index + mediaMatch[0].length))
  : ''
const mediaDark = mergedDeclarations(selectorBodies(mediaBody, ':root:not([data-theme="light"])'))
const attrDark = mergedDeclarations(selectorBodies(css, ':root[data-theme="dark"]'))

/**
 * Follow `var()` indirection to a literal. `scope` is the theme's own block, `light` the base —
 * the primitive tier lives in bare `:root`, and a dark block only re-points semantic names at
 * night primitives, so a dark lookup has to fall through to the base to find the hex.
 */
function resolve(name: string, scope: Map<string, string>, seen = new Set<string>()): string | null {
  if (seen.has(name)) return null
  seen.add(name)
  const raw = scope.get(name) ?? light.get(name)
  if (raw === undefined) return null
  const via = /^var\(\s*(--[A-Za-z0-9_-]+)\s*\)$/.exec(raw.trim())
  return via ? resolve(via[1]!, scope, seen) : raw.trim()
}

const inLight = (name: string) => resolve(name, light)
const inMediaDark = (name: string) => resolve(name, mediaDark)
const inAttrDark = (name: string) => resolve(name, attrDark)

/* ═══════════════════════════════════════════════════════════════════════════
   Green.
   ═══════════════════════════════════════════════════════════════════════════ */

function hsl(hex: string): { h: number; s: number; l: number } | null {
  let h = hex.trim().replace(/^#/, '')
  if (h.length === 3 || h.length === 4) h = h.slice(0, 3).split('').map((c) => c + c).join('')
  if (h.length === 8) h = h.slice(0, 6)
  if (!/^[0-9a-fA-F]{6}$/.test(h)) return null
  const r = parseInt(h.slice(0, 2), 16) / 255
  const g = parseInt(h.slice(2, 4), 16) / 255
  const b = parseInt(h.slice(4, 6), 16) / 255
  const max = Math.max(r, g, b)
  const min = Math.min(r, g, b)
  const d = max - min
  const l = (max + min) / 2
  let hue = 0
  if (d !== 0) {
    if (max === r) hue = 60 * (((g - b) / d) % 6)
    else if (max === g) hue = 60 * ((b - r) / d + 2)
    else hue = 60 * ((r - g) / d + 4)
  }
  if (hue < 0) hue += 360
  const s = d === 0 ? 0 : d / (1 - Math.abs(2 * l - 1))
  return { h: hue, s, l }
}

/**
 * Hue-based rather than a blocklist of hex strings, because "a future success green" will not be
 * a hex anyone thought to list. The window is wide (yellow-green through spring green) and the
 * saturation floor keeps the near-neutral chrome greys — which sit at ~215°, nowhere near it —
 * out of the net. Proven against real values in the suite below rather than asserted here.
 */
function isGreenish(value: string): boolean {
  const c = hsl(value)
  if (!c) return false
  return c.h >= 75 && c.h <= 165 && c.s >= 0.1 && c.l > 0.06 && c.l < 0.94
}

const GREEN_WORDS =
  /\b(green|lime|chartreuse|greenyellow|springgreen|seagreen|forestgreen|olivedrab|darkolivegreen|palegreen|lawngreen)\b/i

/* ═══════════════════════════════════════════════════════════════════════════
   The contract.
   ═══════════════════════════════════════════════════════════════════════════ */

const CONTENT_TOKENS = [
  '--paper-bg', '--paper-sheet', '--ink-text', '--ink-soft', '--ink-faint', '--text-subtle',
  '--hairline', '--border-strong', '--ink-accent', '--on-accent', '--focus-ring', '--link',
  '--elevation',
]

const MOOD_SOLID = ['--mood-1', '--mood-2', '--mood-3', '--mood-4', '--mood-5']
const MOOD_WASH = MOOD_SOLID.map((t) => `${t}-wash`)

const SCALE_TOKENS = [
  '--space-1', '--space-2', '--space-3', '--space-4', '--space-5', '--space-6', '--space-8',
  '--radius', '--radius-sm', '--maxw', '--font-text', '--font-display', '--font-mono',
]

const CHROME_TOKENS = ['--chrome', '--chrome-2', '--chrome-hair', '--chrome-ink', '--chrome-soft']
const INDIGO_TOKENS = ['--indigo', '--indigo-deep', '--indigo-wash']
const ALARM_TOKENS = ['--clay', '--clay-wash', '--amber', '--amber-wash']

/** Everything the bare `:root` owes: the complete light palette plus the themeless scales. */
const ALL_TOKENS = [
  ...CONTENT_TOKENS, ...MOOD_SOLID, ...MOOD_WASH, ...SCALE_TOKENS,
  ...CHROME_TOKENS, ...INDIGO_TOKENS, ...ALARM_TOKENS,
]

/**
 * The roles that change with the theme, and therefore must be re-pointed in BOTH dark blocks.
 * The solid mood ramp is deliberately absent — a 3 is a 3 at midnight — and is asserted stable
 * separately. The scales are theme-independent by construction.
 */
const THEMED_TOKENS = [
  '--paper-bg', '--paper-sheet', '--ink-text', '--ink-soft', '--ink-faint', '--text-subtle',
  '--hairline', '--border-strong', '--ink-accent', '--on-accent', '--elevation',
  '--focus-ring', '--link',
  ...CHROME_TOKENS, ...INDIGO_TOKENS, ...ALARM_TOKENS, ...MOOD_WASH,
]

/**
 * Four of these are NOT the values the design brief first specified, and must not be "corrected"
 * back to them: --chrome-soft, --clay and --amber (light) and --clay (dark) were each darkened or
 * lightened until they cleared WCAG AA against the ground the product actually renders them on.
 * The arithmetic, the failing ground and the resulting ratio are recorded in app.css under
 * CONTRAST CORRECTIONS. Asserting the pre-correction values here would turn this suite into a
 * ratchet pulling a measured accessibility defect back in.
 */
const LIGHT_VALUES: Record<string, string> = {
  '--chrome': '#e4e7ec', '--chrome-2': '#d8dce4', '--chrome-hair': '#c6ccd6',
  '--chrome-ink': '#38414e', '--chrome-soft': '#586170', // was #626d7d — 4.23:1 on --chrome
  '--indigo': '#3f5f7f', '--indigo-deep': '#2f4a64', '--indigo-wash': '#dde5ee',
  // clay was #a8574a — 3.93:1 on --clay-wash. clay-wash was #f2ded9 — 4.46:1 against the
  // --ink-soft body copy Callout[critical] renders on it, which is under AA on the ground
  // every error banner in both apps uses.
  '--clay': '#9a5044', '--clay-wash': '#f3e0db',
  '--amber': '#866122', '--amber-wash': '#f0e6d1', // amber was #9c7128 — 3.53:1 on --amber-wash
}

const DARK_VALUES: Record<string, string> = {
  '--chrome': '#1e232b', '--chrome-2': '#262c36', '--chrome-hair': '#333b46',
  '--chrome-ink': '#c8cfd9', '--chrome-soft': '#8b95a3',
  '--indigo': '#7fa3c8', '--indigo-deep': '#9dbbd9', '--indigo-wash': '#22303e',
  '--clay': '#cb8473', '--clay-wash': '#3a2c28', // clay was #c9806f — 4.33:1 on --clay-wash
  '--amber': '#cfa458', '--amber-wash': '#3a3128',
}

/* ═══════════════════════════════════════════════════════════════════════════ */

describe('the suite has a subject', () => {
  it('found the ui/ directory, its components and app.css', () => {
    // Guard the guards. Every assertion below is a filter over these; if the directory moved or
    // the glob went empty they would all pass while checking nothing at all.
    expect(uiFiles.length).toBeGreaterThanOrEqual(10)
    expect(svelteFiles.length).toBeGreaterThanOrEqual(10)
    expect(svelteFiles).toContain('BandTag.svelte')
    expect(svelteFiles).toContain('StatusPill.svelte')
    expect(svelteFiles).toContain('AppShell.svelte')
    expect(svelteFiles).toContain('PageHeader.svelte')
    expect(uiFiles).not.toContain(SELF)
    expect(css.length).toBeGreaterThan(2000)
  })
})

describe('(a) app.css defines the token contract in all three theme states', () => {
  it('parsed a real token block, not an empty one', () => {
    expect(lightBodies.length).toBeGreaterThan(0)
    expect(light.size).toBeGreaterThan(50)
    expect(mediaMatch).not.toBeNull()
    // The media query must be guarded so an explicit light choice can win against the system
    // preference; an unguarded `:root` inside it would strand anyone who chose light.
    expect(mediaBody).toMatch(/:root:not\(\[data-theme="light"\]\)/)
    expect(mediaDark.size).toBeGreaterThan(20)
    expect(attrDark.size).toBeGreaterThan(20)
  })

  it('the bare :root defines every token in the contract', () => {
    const missing = ALL_TOKENS.filter((t) => inLight(t) === null)
    expect(missing).toEqual([])
  })

  it('both dark blocks redefine every themed role', () => {
    expect(THEMED_TOKENS.filter((t) => !mediaDark.has(t))).toEqual([])
    expect(THEMED_TOKENS.filter((t) => !attrDark.has(t))).toEqual([])
  })

  it('the two dark blocks agree, so the toggle wins in both directions', () => {
    // Different sets here means the in-UI toggle and the system preference render different
    // products, and the difference only shows on whichever token was forgotten.
    expect([...attrDark.keys()].sort()).toEqual([...mediaDark.keys()].sort())
    const disagree = THEMED_TOKENS.filter((t) => inMediaDark(t) !== inAttrDark(t))
    expect(disagree).toEqual([])
  })

  it('no colour is defined only inside a media or [data-theme] block', () => {
    // COMPANION_WEB_REDESIGN_PLAN.md §1c. A role that exists only in the dark blocks is
    // undefined in the default theme, and the component using it renders unstyled in daylight.
    const orphans = [...mediaDark.keys(), ...attrDark.keys()].filter((t) => !light.has(t))
    expect(orphans).toEqual([])
  })

  it('the chrome, indigo, clay and amber tokens carry their contract values', () => {
    const wrong: string[] = []
    for (const [token, expected] of Object.entries(LIGHT_VALUES)) {
      const got = inLight(token)?.toLowerCase() ?? null
      if (got !== expected) wrong.push(`light ${token}: ${got} != ${expected}`)
    }
    for (const [token, expected] of Object.entries(DARK_VALUES)) {
      const viaMedia = inMediaDark(token)?.toLowerCase() ?? null
      const viaAttr = inAttrDark(token)?.toLowerCase() ?? null
      if (viaMedia !== expected) wrong.push(`media dark ${token}: ${viaMedia} != ${expected}`)
      if (viaAttr !== expected) wrong.push(`attr dark ${token}: ${viaAttr} != ${expected}`)
    }
    expect(wrong).toEqual([])
  })

  it('the solid mood ramp is identical in every theme — a 3 is a 3 at midnight', () => {
    const drifted = MOOD_SOLID.filter(
      (t) => inMediaDark(t) !== inLight(t) || inAttrDark(t) !== inLight(t),
    )
    expect(drifted).toEqual([])
    expect(inLight('--mood-3')).toMatch(/^#[0-9a-fA-F]{6}$/)
  })
})

describe('(b) no component hardcodes a colour', () => {
  const HEX = /#[0-9a-fA-F]{3,8}\b/

  it('the hex detector works and every component has a style block to check', () => {
    expect(HEX.test('color: #ff0000;')).toBe(true)
    expect(HEX.test('color: var(--ink-text);')).toBe(false)
    const blockless = svelteFiles.filter((n) => styleOf(n) === null)
    expect(blockless).toEqual([])
    const total = svelteFiles.reduce((n, f) => n + (styleOf(f)?.length ?? 0), 0)
    expect(total).toBeGreaterThan(2000)
  })

  it('styles are written in tokens only', () => {
    // A hex at this layer defeats theming silently: it looks right in whichever theme the author
    // had open and is wrong in the other, and no amount of remapping at :root can reach it.
    // Banned in comments too — a documented raw value is a raw value the next reader will paste.
    const offenders = svelteFiles
      .map((name) => [name, styleOf(name)!.match(new RegExp(HEX, 'g'))] as const)
      .filter(([, hits]) => hits !== null)
      .map(([name, hits]) => `${name}: ${hits!.join(', ')}`)
    expect(offenders).toEqual([])
  })
})

describe('(c) the mood ramp is data, and BandTag is its only consumer', () => {
  it('BandTag actually uses the ramp', () => {
    // Without this the whole test passes by deleting BandTag, which is the failure mode that
    // would hurt most: the ramp would then be unused, unowned, and free for anything.
    const band = styleOf('BandTag.svelte')
    expect(band).not.toBeNull()
    const missing = MOOD_SOLID.filter((t) => !band!.includes(`var(${t})`))
    expect(missing).toEqual([])
  })

  it('nothing else in ui/ names a mood token', () => {
    // Comments are stripped first: every file here explains the invariant in prose, and BandTag's
    // own header spells `--mood-1..--mood-5`. What matters is what ships to the browser.
    const offenders = uiFiles
      .filter((n) => n !== 'BandTag.svelte')
      .filter((n) => /--mood-/.test(codeOnly(source.get(n)!)))
    expect(offenders).toEqual([])
  })

  it('interface state never borrows the ramp for a positive signal', () => {
    // --mood-4 and --mood-5 are the green end. They are the tokens a hurried commit reaches for
    // when a row needs to look "good", which is precisely the claim this product does not make.
    const offenders = uiFiles
      .filter((n) => n !== 'BandTag.svelte')
      .filter((n) => /--mood-[45]/.test(source.get(n)!))
    expect(offenders).toEqual([])
  })
})

describe('(d) there is no success/green anywhere in the system', () => {
  it('the green detector detects green', () => {
    // Non-vacuity for the two tests below. Proven against the ramp's own greens, so the detector
    // is calibrated on real values from this codebase rather than on a textbook #00ff00.
    expect(isGreenish('#00ff00')).toBe(true)
    expect(isGreenish('#2ecc71')).toBe(true)
    expect(isGreenish(inLight('--mood-5')!)).toBe(true)
    expect(isGreenish(inLight('--mood-4')!)).toBe(true)
    expect(isGreenish(inLight('--indigo')!)).toBe(false)
    expect(isGreenish(inLight('--clay')!)).toBe(false)
    expect(isGreenish(inLight('--amber')!)).toBe(false)
    expect(isGreenish(inLight('--chrome')!)).toBe(false)
    expect(GREEN_WORDS.test('background: seagreen;')).toBe(true)
    expect(GREEN_WORDS.test('background: var(--clay);')).toBe(false)
  })

  it('app.css declares no success token under any name', () => {
    const declared = new Set<string>()
    for (const m of css.matchAll(/(--[A-Za-z0-9_-]+)\s*:/g)) declared.add(m[1]!)
    expect(declared.size).toBeGreaterThan(80) // the file really was read
    expect(declared.has('--clay')).toBe(true)
    // Named, not valued: --mood-5 is green and stays green, because it means "this person
    // reported a good day", not "the system is fine". The ban is on a token whose NAME asserts
    // health — the trust strip may never be painted green (COMPANION_UX.md §496).
    const success = [...declared].filter((n) =>
      /^--(success|ok|okay|positive|good|healthy|safe|pass|passing|valid|verified|secure|green)(-|$)/.test(n),
    )
    expect(success).toEqual([])
  })

  it('no ui/ component paints a green literal', () => {
    const offenders: string[] = []
    for (const name of uiFiles) {
      const code = codeOnly(source.get(name)!)
      for (const hit of code.match(/#[0-9a-fA-F]{3,8}\b/g) ?? []) {
        if (isGreenish(hit)) offenders.push(`${name}: ${hit}`)
      }
      const word = GREEN_WORDS.exec(code)
      if (word) offenders.push(`${name}: ${word[0]}`)
    }
    expect(offenders).toEqual([])
  })

  it('no token the status ramp is built from resolves to a green hue', () => {
    // COMPANION_WEB_REDESIGN_PLAN.md §1c, asserted against values so a "success green" cannot be
    // reintroduced by quietly re-pointing a token that already has an innocent name.
    const statusTokens = [
      ...CHROME_TOKENS, ...INDIGO_TOKENS, ...ALARM_TOKENS,
      '--ink-text', '--ink-soft', '--paper-sheet', '--border-strong', '--hairline',
    ]
    const offenders: string[] = []
    for (const token of statusTokens) {
      for (const [theme, value] of [
        ['light', inLight(token)],
        ['media dark', inMediaDark(token)],
        ['attr dark', inAttrDark(token)],
      ] as const) {
        expect(value, `${token} is undefined in ${theme}`).not.toBeNull()
        if (isGreenish(value!)) offenders.push(`${theme} ${token}: ${value}`)
      }
    }
    expect(offenders).toEqual([])
  })
})

describe("(e) StatusPill's 'completed' is ink, not a tick", () => {
  const pill = styleOf('StatusPill.svelte')
  const rule = pill ? selectorBodies(pill, ".pill[data-status='completed']")[0] ?? null : null

  it("the 'completed' variant exists and is a real lifecycle state", () => {
    expect(pill).not.toBeNull()
    expect(rule).not.toBeNull()
    expect(rule!.trim().length).toBeGreaterThan(0)
    expect(read(HERE + 'status.ts')).toMatch(/'completed'/)
  })

  it('it resolves to the ink token', () => {
    // Green would be a claim — that we checked, that it held, that you may stop reading. Ink is
    // emphatic without asserting anything about the quality of what was completed.
    expect(rule).toMatch(/background:\s*var\(--ink-text\)/)
    expect(rule).toMatch(/border-color:\s*var\(--ink-text\)/)
    expect(rule).toMatch(/color:\s*var\(--paper-sheet\)/)
    expect(rule).not.toMatch(/--mood-/)
    expect(isGreenish(inLight('--ink-text')!)).toBe(false)
    expect(isGreenish(inMediaDark('--ink-text')!)).toBe(false)
    expect(isGreenish(inAttrDark('--ink-text')!)).toBe(false)
  })

  it('carries its meaning in form and words, not in hue alone', () => {
    // Seven states is more than any ramp can separate safely. The written label is always
    // rendered, and the states that negate each other are told apart by texture and a strike.
    expect(source.get('StatusPill.svelte')).toMatch(/STATUS_LABEL\[status\]/)
    expect(pill).toMatch(/text-decoration:\s*line-through/)
    expect(pill).toMatch(/repeating-linear-gradient/)
    expect(pill).toMatch(/prefers-reduced-motion/)
  })
})

describe('(f) the stacking-context fix survives', () => {
  // A hovered calendar cell lifts itself with z-index so its shadow can rise above its
  // neighbours; without contexts of their own the rail and header lose to it and the shadow
  // paints across the navigation and the page title. This re-breaks silently — it shows only
  // while hovering one kind of cell on one screen — so nothing catches it but this test.
  // (COMPANION_WEB_REDESIGN_PLAN.md §1d: topbar 6, rail 7, body 1 + isolate.)
  const shell = styleOf('AppShell.svelte')
  const head = styleOf('PageHeader.svelte')
  const ruleIn = (block: string | null, selector: string) =>
    block ? selectorBodies(block, selector)[0] ?? null : null
  const zOf = (rule: string | null) => {
    const m = rule ? /z-index:\s*(-?\d+)/.exec(rule) : null
    return m ? Number(m[1]) : null
  }

  const rail = ruleIn(shell, '.rail')
  const header = ruleIn(shell, '.header')
  const body = ruleIn(shell, '.body')
  const pageHeader = ruleIn(head, '.page-header')

  it('the three AppShell rules and the PageHeader rule are present', () => {
    // Read from the style block with comments stripped: AppShell's own documentation quotes the
    // declarations verbatim, so a raw-text search would be satisfied by the explanation.
    for (const [name, rule] of Object.entries({ rail, header, body, pageHeader })) {
      expect(rule, `${name} rule missing`).not.toBeNull()
      expect(rule!.trim().length).toBeGreaterThan(0)
    }
  })

  it('the body isolates its descendants', () => {
    // The half that cannot be replaced by a larger z-index elsewhere: isolation forces every
    // z-index inside the body to resolve against the body's own root, so no descendant can
    // escape it — not a cell at 10, not a dropdown at 9999.
    expect(body).toMatch(/isolation:\s*isolate/)
    expect(body).toMatch(/position:\s*relative/)
    expect(zOf(body)).toBe(1)
  })

  it('rail and header sit above the body, and the rail above the header', () => {
    expect(rail).toMatch(/position:\s*relative/)
    expect(header).toMatch(/position:\s*relative/)
    expect(pageHeader).toMatch(/position:\s*relative/)
    expect(zOf(rail)).toBe(7)
    expect(zOf(header)).toBe(6)
    expect(zOf(pageHeader)).toBe(6)
    // Asserted as an ordering too, so renumbering the whole stack stays legal and inverting it
    // does not.
    expect(zOf(rail)!).toBeGreaterThan(zOf(header)!)
    expect(zOf(header)!).toBeGreaterThan(zOf(body)!)
  })
})
