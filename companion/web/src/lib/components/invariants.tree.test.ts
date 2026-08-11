import { describe, it, expect } from 'vitest'
import { readFileSync, readdirSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

/**
 * The invariant suite for the WHOLE component tree.
 *
 * WHY THIS FILE EXISTS SEPARATELY FROM ui/invariants.test.ts. That suite polices
 * src/lib/components/ui/ — the twelve primitives written during the redesign — and it does it by
 * reading its own directory. The token migration then made a claim about every other file in the
 * product: that the mood ramp had been pulled out of banners, errors, selected tabs, hovers and
 * brand marks everywhere, not just in the new primitives. A claim about the tree needs a guard
 * over the tree, and a directory-scoped `readdirSync` cannot become one. So this suite walks all
 * of src/.
 *
 * WHY IT IS NOT IN ui/. The ui/ suite enumerates its own directory and asserts that nothing there
 * but BandTag names a mood token. A tree-wide suite necessarily names every token it polices, so
 * dropping it into ui/ would make it a subject of that suite and fail it. It lives one level up,
 * beside the component tree it is about.
 *
 * WHY IT READS SOURCE. There is no component-rendering harness here, and every property below is
 * a property of the source text rather than of a rendered tree: "no file outside the charts names
 * a mood token", "no style block contains a hex", "this sentence still says exactly this". For
 * those, reading the files is the direct test, not a proxy for one.
 *
 * WHY EVERY TEST FIRST ASSERTS ITS SUBJECT EXISTS. A grep-shaped guard has one failure mode worse
 * than all the others: it goes green because it matched nothing. Rename a directory, move a
 * banner, change an extension, and a suite full of `expect(offenders).toEqual([])` reports success
 * on the empty set — and, being green, removes the appetite to write a real one. So each block
 * below opens by proving its subject is there: the walk found files, the allowlist entries are all
 * live, the style blocks parsed, the copy file was read, the detector detects. Every list this
 * suite filters is asserted non-empty before it is filtered.
 *
 * WHY COMMENTS ARE STRIPPED BEFORE MATCHING. The migration left an explanation in nearly every
 * file it touched, and an honest explanation has to spell the thing it removed: "it was
 * --mood-5-wash", "this used to be green", "the old rule read `var(--accent, var(--mood-5))`".
 * A guard run over raw text would be satisfied by the explanation of the rule instead of by the
 * rule — the exact vacuity described above, wearing a passing test as a disguise. The structural
 * assertions therefore run over code with comments removed: what actually ships to the browser.
 * The copy assertions in (e) run the other way round, over rendered prose with the script and
 * style blocks removed, so a sentence quoted in a comment cannot stand in for the sentence a
 * person reads.
 */

/* ═══════════════════════════════════════════════════════════════════════════
   Walking the tree.
   ═══════════════════════════════════════════════════════════════════════════ */

/** This file is at src/lib/components/, so two levels up is src/. */
const SRC = fileURLToPath(new URL('../../', import.meta.url))

/** Paths are reported as `src/...` so a failure names something you can open. */
const rel = (abs: string) => 'src/' + abs.slice(SRC.length)

function walk(dir: string): string[] {
  const out: string[] = []
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const path = dir + entry.name
    if (entry.isDirectory()) out.push(...walk(path + '/'))
    else if (/\.(svelte|ts|css)$/.test(entry.name)) out.push(path)
  }
  return out.sort()
}

const files = walk(SRC)
const source = new Map<string, string>(files.map((f) => [rel(f), readFileSync(f, 'utf8')]))
const paths = [...source.keys()]
const components = paths.filter((p) => p.endsWith('.svelte'))

const APP_CSS = 'src/app.css'
const css = source.get(APP_CSS)?.replace(/\/\*[\s\S]*?\*\//g, '') ?? ''

/**
 * Code with commentary removed: HTML/Svelte comments, CSS and block comments, line comments.
 * `(?<!:)` before `//` so `https://` survives. Applied in that order because a `<!-- -->` may
 * wrap anything and a `/* *\/` may not appear inside one meaningfully.
 */
const codeOnly = (src: string) =>
  src
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/(?<!:)\/\/[^\n]*/g, '')

const code = new Map<string, string>(paths.map((p) => [p, codeOnly(source.get(p)!)]))

/** The `<style>` block's contents with CSS comments removed. Null when there is no block. */
function styleOf(path: string): string | null {
  const m = /<style[^>]*>([\s\S]*?)<\/style>/.exec(source.get(path) ?? '')
  return m ? m[1]!.replace(/\/\*[\s\S]*?\*\//g, '') : null
}

/**
 * The prose a person actually reads: script and style blocks gone, comments gone, tags removed
 * WITHOUT inserting a space (so `<strong>Non-diagnostic.</strong> Self-checks` reads as one
 * sentence, exactly as it renders), whitespace collapsed so the assertions are indifferent to
 * how the markup happens to wrap.
 */
function proseOf(path: string): string {
  return (source.get(path) ?? '')
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/<script[\s\S]*?<\/script>/g, '')
    .replace(/<style[\s\S]*?<\/style>/g, '')
    .replace(/<[^>]*>/g, '')
    .replace(/\s+/g, ' ')
    .trim()
}

/* ═══════════════════════════════════════════════════════════════════════════
   Green, by hue rather than by blocklist.

   A future success green will not be a hex anyone thought to list, so the detector measures the
   colour instead of recognising it. The window runs from yellow-green to spring green; the
   saturation floor keeps the near-neutral chrome greys (~215°, and barely saturated) out of the
   net. Calibrated in the suite below against real values from this codebase rather than asserted
   here — see "the green detector detects green".
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

function isGreenish(value: string): boolean {
  const c = hsl(value)
  if (!c) return false
  return c.h >= 75 && c.h <= 165 && c.s >= 0.1 && c.l > 0.06 && c.l < 0.94
}

/**
 * Named CSS colours, guarded on both sides against word characters and hyphens so `white-space`,
 * `border-black-friday` and the like cannot trip it. Colour keywords are banned in components for
 * the same reason hexes are: they are outside the token layer and cannot be re-themed.
 */
const NAMED_COLOUR =
  /(?<![\w-])(white|black|red|blue|green|lime|chartreuse|greenyellow|springgreen|seagreen|forestgreen|olivedrab|darkolivegreen|palegreen|lawngreen|grey|gray|silver|orange|purple|yellow|pink|brown|teal|navy|maroon|olive|aqua|fuchsia|crimson|gold|beige|ivory|khaki|salmon|tan|violet|indianred)(?![\w-])/i

const GREEN_WORDS =
  /(?<![\w-])(green|lime|chartreuse|greenyellow|springgreen|seagreen|forestgreen|olivedrab|darkolivegreen|palegreen|lawngreen)(?![\w-])/i

const HEX = /#[0-9a-fA-F]{3,8}\b/
const COLOUR_FN = /(?<![\w-])(rgba?|hsla?|hwb|lab|lch|oklab|oklch|color)\s*\(/i

/* ═══════════════════════════════════════════════════════════════════════════
   The two allowlists.
   ═══════════════════════════════════════════════════════════════════════════ */

/**
 * Every file permitted to name a mood token, and what it renders. Derived by reading each site,
 * not by copying the migration's word for it: in each case the mark is a number a person
 * reported, or a band their own answers scored into, drawn on the ramp that IS that scale's
 * legend. The classification rule allows exactly two answers — DATA, keep; STATE, migrate — and
 * every one of these is DATA. Anything not on this list that names a mood token in code is,
 * by construction, interface state wearing a person's colours.
 */
const MOOD_DATA_FILES: Record<string, string> = {
  'src/app.css':
    'the definition site: the ramp and its washes are declared here and nowhere else',
  'src/lib/mood.ts':
    'the 5-level mood scale itself (Awful..Rad), mapping a reported level to its token',
  'src/lib/charts/Sparkline.svelte':
    "the plotted line IS the person's daily mood average, on the ramp's own 1..5 scale",
  'src/lib/components/Overview.svelte':
    'mood-distribution bars: one bar per level, counting that person’s own entries',
  'src/lib/components/Dashboard.svelte':
    'mood distribution, activity-association deltas and the self-check trend line — all charts of a person’s own series',
  'src/lib/components/QuestionnaireRunner.svelte':
    'the result edge is the band this person’s own answers scored into, beside the band label in words',
  'src/lib/components/ui/BandTag.svelte':
    'the band tag’s bar: the score band, on the ramp, with the band named in text next to it',
}

/**
 * The files that name these tokens in order to police them. A test that forbids a string has to
 * contain the string; exempting them is not a loophole so long as the exemption is proven live —
 * see "the exemptions are earned", which fails if one of these stops naming what it guards.
 */
const POLICING_FILES = [
  'src/lib/components/ui/invariants.test.ts',
  'src/lib/components/trustbar.test.ts',
  'src/lib/components/invariants.tree.test.ts',
]

const SELF = 'src/lib/components/invariants.tree.test.ts'

/* ═══════════════════════════════════════════════════════════════════════════ */

describe('the tree-wide suite has a subject', () => {
  it('walked src/ and found the tree it is about', () => {
    // Guard the guards. Every assertion in this file is a filter over `paths`; if the walk
    // silently returned nothing, all of them would pass while checking nothing at all.
    expect(paths.length).toBeGreaterThan(60)
    expect(components.length).toBeGreaterThan(30)
    expect(paths).toContain(APP_CSS)
    expect(paths).toContain(SELF)
    // Files from every corner of the tree, so a suite that lost a directory is caught here
    // rather than by an empty offender list further down.
    expect(components).toContain('src/App.svelte')
    expect(components).toContain('src/lib/charts/Sparkline.svelte')
    expect(components).toContain('src/lib/components/TrustBar.svelte')
    expect(components).toContain('src/lib/components/owner/OwnerConsole.svelte')
    expect(components).toContain('src/lib/components/therapist/TherapistPortal.svelte')
    expect(components).toContain('src/lib/components/ui/BandTag.svelte')
    expect(css.length).toBeGreaterThan(2000)
  })

  it('the comment stripper removes commentary and leaves the code', () => {
    // Non-vacuity for every structural test below, which all run over `code`. If the stripper
    // ever ate the markup, the offender lists would empty out and the suite would go quiet.
    const band = code.get('src/lib/components/ui/BandTag.svelte')!
    expect(band).toContain('var(--mood-1)')
    expect(band).toContain('<style>')
    // The witness for the other direction: this banner's style comment explains that it used to
    // be washed in --mood-5-wash, and its code names no mood token at all. Raw text and code
    // must therefore disagree about it — which is the whole reason the assertions below run
    // over `code` and not over `source`.
    const banner = 'src/lib/components/owner/NonDiagnosticBanner.svelte'
    expect(source.get(banner)!).toContain('--mood-5-wash')
    expect(code.get(banner)!).not.toContain('--mood-')
    // And it really is removing bulk, not passing everything through unchanged.
    const removed = paths.reduce((n, p) => n + source.get(p)!.length - code.get(p)!.length, 0)
    expect(removed).toBeGreaterThan(20000)
  })

  it('the exemptions are earned', () => {
    // An exemption list is the other way a guard goes vacuous: allowlist everything and the test
    // is a no-op. So every entry must be a real file that really does name what it is exempt for.
    for (const path of POLICING_FILES) {
      expect(paths, `${path} is exempt but does not exist`).toContain(path)
      expect(code.get(path)!, `${path} is exempt but polices nothing`).toMatch(/--mood-/)
    }
    for (const path of Object.keys(MOOD_DATA_FILES)) {
      expect(paths, `${path} is allowlisted but does not exist`).toContain(path)
      expect(code.get(path)!, `${path} is allowlisted but uses no mood token`).toMatch(/--mood-/)
    }
  })
})

describe('(a) the mood ramp is a person’s data, and only the data surfaces may name it', () => {
  it('the data surfaces still draw the ramp they are allowlisted for', () => {
    // Without this the whole test passes by deleting the charts: the ramp would be unused,
    // unowned, and free for anything. Asserted against the full ramp, so losing a step is caught.
    const band = styleOf('src/lib/components/ui/BandTag.svelte')
    expect(band).not.toBeNull()
    for (const n of [1, 2, 3, 4, 5]) expect(band!).toContain(`var(--mood-${n})`)

    for (const chart of ['src/lib/components/Overview.svelte', 'src/lib/components/Dashboard.svelte']) {
      const style = styleOf(chart)
      expect(style, `${chart} has no style block`).not.toBeNull()
      for (const n of [1, 2, 3, 4, 5]) expect(style!, chart).toContain(`var(--mood-${n})`)
    }
    expect(styleOf('src/lib/charts/Sparkline.svelte')).toContain('var(--mood-')
    expect(styleOf('src/lib/components/QuestionnaireRunner.svelte')).toContain('var(--mood-')
    // The scale model is the ramp's other legitimate home: it maps a reported level to a token.
    expect(code.get('src/lib/mood.ts')!).toContain("varName: '--mood-1'")
  })

  it('nothing else in the tree names a mood token', () => {
    // THE tree-wide claim. Every remaining `var(--mood-*)` in the product is a chart, a band or
    // a score; a banner, a hover, a selected tab or an error that reaches for the ramp is
    // interface state wearing a person's reported experience, and lands here.
    const allowed = new Set([...Object.keys(MOOD_DATA_FILES), ...POLICING_FILES])
    const offenders = paths.filter((p) => !allowed.has(p) && /--mood-/.test(code.get(p)!))
    expect(offenders).toEqual([])
  })

  it('and inside the data files, the ramp paints data rather than the state around it', () => {
    // The hole a file-level allowlist leaves open. Dashboard is allowed to name the ramp because
    // it draws a person's own series — which does nothing to stop `.tab.active { background:
    // var(--mood-5) }` being added to the same style block tomorrow, in the same file, under the
    // same permission. So the allowlist is checked at the rule, not at the file: a rule that
    // names a mood token may not be selected on an interface state.
    const STATE_SELECTOR = /:hover|:focus|:active|:checked|:disabled|\.active|\.selected|\.current|\.on\b|\.error|\.warn|\.invalid|\.success|\[aria-(pressed|selected|current|invalid|expanded)/i
    expect(STATE_SELECTOR.test('.seg.active')).toBe(true)
    expect(STATE_SELECTOR.test('.controls button:hover')).toBe(true)
    expect(STATE_SELECTOR.test(".bar[data-level='5']")).toBe(false)

    const offenders: string[] = []
    let moodRules = 0
    for (const path of Object.keys(MOOD_DATA_FILES)) {
      const style = path.endsWith('.svelte') ? styleOf(path) : null
      if (style === null) continue
      for (const m of style.matchAll(/([^{}]+)\{([^{}]*)\}/g)) {
        if (!/--mood-/.test(m[2]!)) continue
        moodRules++
        const selector = m[1]!.trim().replace(/\s+/g, ' ')
        if (STATE_SELECTOR.test(selector)) offenders.push(`${path}: ${selector}`)
      }
    }
    // Non-vacuity: the rule splitter really did find the ramp's rules. Every mood declaration in
    // the tree lives in one of these files, so a collapse here would empty the check silently.
    expect(moodRules).toBeGreaterThanOrEqual(15)
    expect(offenders).toEqual([])
  })
})

describe('(b) there is no success token and no green anywhere in the system', () => {
  it('the detectors detect', () => {
    // Non-vacuity for the three tests below, calibrated on real values from this codebase rather
    // than on a textbook #00ff00 — the ramp's own greens must trip it and the palette must not.
    expect(isGreenish('#00ff00')).toBe(true)
    expect(isGreenish('#2ecc71')).toBe(true)
    expect(isGreenish('#8fa268')).toBe(true) // --c-mood-4, the ramp's "good"
    expect(isGreenish('#5e8a66')).toBe(true) // --c-mood-5, the ramp's "rad"
    expect(isGreenish('#3f5f7f')).toBe(false) // indigo
    expect(isGreenish('#9a5044')).toBe(false) // clay
    expect(isGreenish('#866122')).toBe(false) // amber
    expect(isGreenish('#e4e7ec')).toBe(false) // chrome
    expect(GREEN_WORDS.test('background: seagreen;')).toBe(true)
    expect(GREEN_WORDS.test('background: var(--clay);')).toBe(false)
    expect(NAMED_COLOUR.test('color: white;')).toBe(true)
    expect(NAMED_COLOUR.test('white-space: nowrap;')).toBe(false)
  })

  it('no file declares or references a token whose name claims health', () => {
    // Named, not valued: --mood-5 is green and stays green, because it means "this person
    // reported a good day", not "the system is fine". What is banned is a token whose NAME
    // asserts that something checked out — a green tick is a claim this product cannot make,
    // and the trust strip in particular may never be painted green (COMPANION_UX.md 10.1).
    const CLAIMS_HEALTH =
      /^--(success|ok|okay|positive|good|healthy|safe|pass|passing|valid|verified|secure|trusted|green)(-|$)/

    const declared = new Set<string>()
    for (const path of paths) {
      for (const m of code.get(path)!.matchAll(/(--[A-Za-z0-9_-]+)\s*:/g)) declared.add(m[1]!)
    }
    expect(declared.size).toBeGreaterThan(80) // app.css really was read
    expect(declared.has('--clay')).toBe(true)
    expect(declared.has('--amber')).toBe(true)
    expect([...declared].filter((n) => CLAIMS_HEALTH.test(n))).toEqual([])

    const referenced = new Set<string>()
    for (const path of paths) {
      for (const m of code.get(path)!.matchAll(/var\(\s*(--[A-Za-z0-9_-]+)/g)) referenced.add(m[1]!)
    }
    expect(referenced.size).toBeGreaterThan(20)
    expect([...referenced].filter((n) => CLAIMS_HEALTH.test(n))).toEqual([])
  })

  it('no component paints a green literal', () => {
    const subjects = components.filter((p) => !POLICING_FILES.includes(p))
    expect(subjects.length).toBeGreaterThan(30)
    const offenders: string[] = []
    for (const path of subjects) {
      const src = code.get(path)!
      for (const hit of src.match(new RegExp(HEX, 'g')) ?? []) {
        if (isGreenish(hit)) offenders.push(`${path}: ${hit}`)
      }
      const word = GREEN_WORDS.exec(src)
      if (word) offenders.push(`${path}: ${word[0]}`)
    }
    expect(offenders).toEqual([])
  })

  it('the only green values in the palette are the two top steps of the mood ramp', () => {
    // The value-level half, so a success green cannot arrive by quietly re-pointing a token that
    // already has an innocent name. Every primitive in app.css is a literal; every semantic role
    // is a `var()` at one of them — so scanning the literals covers the whole palette.
    const literals = [...css.matchAll(/(--[A-Za-z0-9_-]+)\s*:\s*(#[0-9a-fA-F]{3,8})\s*;/g)]
    expect(literals.length).toBeGreaterThan(40) // the primitives really were parsed
    const green = literals.filter((m) => isGreenish(m[2]!)).map((m) => m[1]!)
    expect(green.length).toBeGreaterThan(0) // the ramp's greens are still there to find
    expect(green.filter((n) => !/^--c-mood-[45]/.test(n))).toEqual([])
  })
})

describe('(c) every token a component references is a token app.css defines', () => {
  /** Names declared anywhere in app.css — primitives and semantic roles alike. */
  const defined = new Set<string>()
  for (const m of css.matchAll(/(--[A-Za-z0-9_-]+)\s*:/g)) defined.add(m[1]!)

  /** A component may also declare a custom property in its own style block and then use it. */
  const localDefs = (path: string) =>
    new Set([...code.get(path)!.matchAll(/(--[A-Za-z0-9_-]+)\s*:/g)].map((m) => m[1]!))

  const subjects = paths.filter((p) => !POLICING_FILES.includes(p) && p !== APP_CSS)

  it('parsed app.css and found real references to check', () => {
    expect(defined.size).toBeGreaterThan(80)
    expect(defined.has('--chrome')).toBe(true)
    expect(defined.has('--indigo')).toBe(true)
    expect(defined.has('--mood-3')).toBe(true)
    expect(defined.has('--nonexistent-token')).toBe(false) // the set is not "everything"
    const refs = subjects.reduce(
      (n, p) => n + (code.get(p)!.match(/var\(\s*--/g) ?? []).length,
      0,
    )
    expect(refs).toBeGreaterThan(200)
  })

  it('a silent fallback onto an undefined token is what this test is shaped like', () => {
    // The live bug this exists for: ToolBuilder's selected segment read
    // `var(--accent, var(--mood-5))`. `--accent` is defined nowhere, so the fallback always won
    // and the selected tab — pure interface state — was painted in the top step of a person's
    // mood ramp. Nothing failed, nothing warned, and the UI simply rendered a lie. The whole
    // point of the fallback form is that it does not complain, so the source has to.
    const sample = '.seg { background: var(--accent, var(--mood-5)); color: var(--chrome); }'
    const undefinedRefs = [...sample.matchAll(/var\(\s*(--[A-Za-z0-9_-]+)/g)]
      .map((m) => m[1]!)
      .filter((n) => !defined.has(n))
    expect(undefinedRefs).toEqual(['--accent'])
  })

  it('no component references a token app.css does not define', () => {
    const offenders: string[] = []
    for (const path of subjects) {
      const local = localDefs(path)
      for (const m of code.get(path)!.matchAll(/var\(\s*(--[A-Za-z0-9_-]+)\s*(,)?/g)) {
        const name = m[1]!
        if (defined.has(name) || local.has(name)) continue
        // Reported with the shape, because the fallback form is the dangerous one: without a
        // fallback the property is simply dropped and the bug is visible; with one, the UI
        // renders confidently in whatever colour the author reached for.
        offenders.push(`${path}: var(${name}${m[2] ? ', …) — silent fallback' : ')'}`)
      }
    }
    expect(offenders).toEqual([])
  })

  it('no component reaches past the semantic layer into a --c-* primitive', () => {
    // app.css: "A component that reaches for a --c-* primitive has skipped the layer where the
    // meaning lives, and should be treated as a bug." It also defeats theming, since the
    // primitives are per-theme by name rather than remapped at :root.
    expect([...defined].filter((n) => n.startsWith('--c-')).length).toBeGreaterThan(30)
    const offenders = subjects.filter((p) => /var\(\s*--c-/.test(code.get(p)!))
    expect(offenders).toEqual([])
  })
})

describe('(d) no component hardcodes a colour', () => {
  const subjects = components.filter((p) => !POLICING_FILES.includes(p))

  it('every component has a style block, and the detectors detect', () => {
    expect(subjects.length).toBeGreaterThan(30)
    const blockless = subjects.filter((p) => styleOf(p) === null)
    expect(blockless).toEqual([])
    const total = subjects.reduce((n, p) => n + (styleOf(p)?.length ?? 0), 0)
    expect(total).toBeGreaterThan(20000)
    expect(HEX.test('color: #ff0000;')).toBe(true)
    expect(HEX.test('color: var(--ink-text);')).toBe(false)
    expect(COLOUR_FN.test('background: rgba(0, 0, 0, 0.5);')).toBe(true)
    expect(COLOUR_FN.test('transform: translate(0, 0);')).toBe(false)
  })

  it('styles are written in tokens only, tree-wide', () => {
    // A literal at this layer defeats theming silently: it looks right in whichever theme the
    // author had open and is wrong in the other, and no amount of remapping at :root can reach
    // it. Banned in CSS comments too — a documented raw value is a raw value the next reader
    // pastes. `transparent` and `currentColor` stay legal: neither asserts a colour.
    const offenders: string[] = []
    for (const path of subjects) {
      const style = styleOf(path)!
      for (const hit of style.match(new RegExp(HEX, 'g')) ?? []) offenders.push(`${path}: ${hit}`)
      const fn = COLOUR_FN.exec(style)
      if (fn) offenders.push(`${path}: ${fn[0]}`)
      const named = NAMED_COLOUR.exec(style)
      if (named) offenders.push(`${path}: ${named[0]}`)
    }
    expect(offenders).toEqual([])
  })
})

/* ═══════════════════════════════════════════════════════════════════════════
   (e) The fixed copy.

   These sentences are not server-supplied and not editorial. They are the product's standing
   statements about what it is and what it cannot promise: non-diagnostic framing, the assurance
   caveats on every path where keys touch a served page, the provenance tiers, and the audit
   log's admission that it cannot prove its own completeness. The migration was allowed to
   restyle their containers — chrome for the information, amber for the genuine warnings, clay
   for failure — and was not allowed to touch a word. This block is the difference between those
   two, asserted against the rendered prose (script and style stripped) so a sentence surviving
   only in a comment cannot pass for a sentence a person reads.
   ═══════════════════════════════════════════════════════════════════════════ */

const FIXED_COPY: { path: string; label: string; sentences: string[] }[] = [
  {
    path: 'src/lib/components/owner/NonDiagnosticBanner.svelte',
    label: 'non-diagnostic banner (owner)',
    sentences: [
      'Non-diagnostic. Self-checks are self-tracking tools, not medical assessments. Scores and bands are descriptive, not clinical thresholds. Anything a therapist assigns or shares here is guidance from your real clinician — never a diagnosis.',
    ],
  },
  {
    path: 'src/lib/components/therapist/NonDiagnosticBanner.svelte',
    label: 'non-diagnostic banner (therapist)',
    sentences: [
      'Non-diagnostic. Self-checks are self-tracking tools, not medical assessments. Scores and bands are descriptive, not clinical thresholds. Anything you assign, plan, or read here is guidance between you and this person — never a diagnosis.',
    ],
  },
  {
    path: 'src/App.svelte',
    label: 'non-diagnostic note (viewer shell)',
    sentences: [
      'Non-diagnostic: Daymark is a self-tracking and journaling tool. Nothing here is a medical assessment.',
    ],
  },
  {
    path: 'src/lib/components/Assessments.svelte',
    label: 'non-diagnostic lead (self-checks menu)',
    sentences: [
      'Everything runs and stays on this device — non-diagnostic, license-clean, and never uploaded.',
    ],
  },
  {
    path: 'src/lib/components/AttentionTask.svelte',
    label: 'non-diagnostic caveats (attention task)',
    sentences: [
      'This is a self-observation exercise, not a diagnostic test. Browser timing is imperfect; the results say how precise this run’s clock was.',
      'Counts (omissions/commissions/accuracy) are robust; reaction-time figures are within-session, same-machine only, and are not comparable across devices or clinical.',
    ],
  },
  {
    path: 'src/lib/components/therapist/GamePlanAuthor.svelte',
    label: 'non-diagnostic-by-framing disclaimer (game plan)',
    sentences: [
      'Guidance, not treatment. A game plan is a written set of suggestions between you and this person. It is not a clinical treatment plan, prescription, or diagnosis.',
    ],
  },
  {
    path: 'src/lib/components/SyncPanel.svelte',
    label: 'lower-assurance banner (sync)',
    sentences: [
      'Lower-assurance path. Decrypting in the browser is convenient but the page is served by the server it talks to; a malicious server could tamper with it. Your phone (the future Sync flavor) is the trusted, secret-handling path. Use a passphrase you are comfortable entering here, and verify the released image digest.',
    ],
  },
  {
    path: 'src/lib/components/owner/LowerAssuranceBanner.svelte',
    label: 'lower-assurance banner (owner console)',
    sentences: [
      'Lower-assurance path. The owner console holds your private keys in this browser to open sealed items and sign grants. This is a convenience path — the page is served by the server it talks to, so a tampered page could misbehave. Keys stay in memory and are dropped when you lock. Verify the released image digest; your phone remains the trusted, secret-handling path.',
    ],
  },
  {
    path: 'src/lib/components/therapist/LowerAssuranceBanner.svelte',
    label: 'lower-assurance banner (therapist portal, TOTP)',
    sentences: [
      'Lower-assurance path (TOTP). Your reading key is unwrapped in this browser under a passphrase. Because the page is served by the server it talks to, a tampered page could capture your passphrase or keys — this is a convenience path, not a zero-knowledge guarantee. Keys are held in memory only and wiped when you log out or go idle. Verify the released image digest; a hardware passkey (WebAuthn) is the stronger path when available.',
    ],
  },
  {
    path: 'src/lib/components/owner/AuditCaveat.svelte',
    label: 'audit log caveat',
    sentences: [
      'Metadata only, and not provably complete. This log shows event types and timestamps — never message content. Entries are chained so a tampered or reordered entry is detectable, but a dishonest server could still withhold the newest entries entirely; there is no proof this list is exhaustive. IP address is not recorded unless the operator explicitly enabled it.',
    ],
  },
  {
    path: 'src/lib/components/owner/ShareBuilder.svelte',
    label: 'scores-and-bands-only framing (share builder)',
    sentences: [
      'Self-checks are reduced to scores and bands only — never raw answers.',
    ],
  },
  {
    path: 'src/lib/components/therapist/SharedDataView.svelte',
    label: 'scores-and-bands-only framing (shared data)',
    sentences: ['Curated view: scores and bands only.'],
  },
]

describe('(e) the fixed honesty copy is intact', () => {
  it('the prose extractor reads what renders, not what is commented', () => {
    // Non-vacuity, and the load-bearing half of it: every banner below carries a header comment
    // that quotes the copy's history. If the extractor let script or style through, "the
    // sentence is still there" would be satisfied by the note explaining the sentence.
    const banner = proseOf('src/lib/components/owner/NonDiagnosticBanner.svelte')
    expect(banner.startsWith('Non-diagnostic.')).toBe(true)
    expect(banner).not.toContain('<strong>')
    expect(banner).not.toContain('background:')
    expect(banner).not.toContain('mood-5-wash') // the header comment names it; the prose must not
    // Tag removal must not insert a space, or every `<strong>`-opened sentence would be broken.
    expect(banner).toContain('Non-diagnostic. Self-checks')
    // And the whole set really was read.
    for (const { path } of FIXED_COPY) {
      expect(paths, `${path} is missing`).toContain(path)
      expect(proseOf(path).length, `${path} rendered no prose`).toBeGreaterThan(80)
    }
    expect(FIXED_COPY.length).toBeGreaterThanOrEqual(12)
  })

  for (const { path, label, sentences } of FIXED_COPY) {
    it(`${label} is verbatim`, () => {
      const prose = proseOf(path)
      for (const sentence of sentences) {
        // Not a keyword match: the whole sentence, character for character. Softening a hedge,
        // trimming a clause or "tightening" the wording is the failure this catches, and each of
        // those would leave a keyword search perfectly happy.
        expect(prose, `${path}: fixed copy changed`).toContain(sentence)
      }
    })
  }

  it('the provenance disclaimers are verbatim', () => {
    // These are constants rather than markup — the runner, the summary and the builder all render
    // the same strings — so they are asserted in the module that owns them.
    const prov = source.get('src/lib/instruments/provenance.ts')
    expect(prov, 'provenance.ts is missing').toBeDefined()
    expect(prov!.length).toBeGreaterThan(500)
    expect(prov!).toContain(
      "'Custom-made — a personal reflection tool, not a validated or clinical instrument. Not for diagnosis.'",
    )
    expect(prov!).toContain(
      '`Adapted from ${p.basedOn?.trim() || \'an evidence-based method\'} — not the original validated instrument.`',
    )
    // The tiers themselves, and the rule that a Validated tool shows a citation rather than a
    // warning. Losing a tier would quietly promote a custom tool to an unlabelled one.
    expect(prov!).toContain("validated: 'Validated'")
    expect(prov!).toContain("adapted: 'Adapted'")
    expect(prov!).toContain("custom: 'Custom'")
  })

  it('the curated-share rule survives where it is enforced, not only where it is shown', () => {
    // "Scores and bands only, never raw entries" is a property of the share pipeline, not a
    // sentence in a banner. Asserted at the capability description the owner consents to.
    const describe_ = source.get('src/lib/assignments/describe.ts')
    expect(describe_, 'describe.ts is missing').toBeDefined()
    expect(describe_!).toContain(
      'Read the curated data you choose to share (scores and bands only — never raw entries).',
    )
  })
})
