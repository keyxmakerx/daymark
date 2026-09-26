import { describe, it, expect } from 'vitest'
import { readFileSync, readdirSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { parse } from 'svelte/compiler'
import { ownMoodColours, ownMoodFill } from '../mood'
import { bundleToBackupData } from '../therapist/shareClient'
import type { ShareBundle } from '../share/sharecrypto'

/**
 * A PERSON'S OWN MOOD COLOURS FILL A MOOD MARK AND NOTHING ELSE (#280).
 *
 * The phone lets a person recolour their moods, and the web now draws their own data in those
 * colours. A colour a person picked is their data, held to what the ramp is held to
 * (docs/COMPANION_DESIGN_SYSTEM.md §2.3.1) and one thing more: it can be any colour at all,
 * including the greens the tree-wide suite keeps out of every interface state. So it may fill a
 * mood mark — the square or bar that stands for one mood, beside that mood's word — and it may
 * reach nothing else: no button, callout, tab, focus ring, link, banner, text or border.
 *
 * THE ROAD IN, AND WHY IT IS ONE ROAD. The colours arrive as integers in the backup's
 * `moodColors`. `ownMoodColours` (lib/mood.ts) turns them into an opaque palette, and the only way
 * to get a colour back out is `ownMoodFill`. Its value is written to one scoped custom property,
 * `--mood-fill`, set inline on the mood mark itself — a leaf, so nothing can inherit it — and read
 * by stylesheet rules whose subject is a mood mark, as its fill. This suite holds every step:
 *
 *   (a) a backup's `moodColors` is read only through `ownMoodColours`;
 *   (b) `ownMoodFill` is called only as the value of a `style:--mood-fill` directive;
 *   (c) the element carrying that directive is a mood mark: a non-interactive leaf, beside its word;
 *   (d) every rule naming `--mood-fill` has a mood mark as its subject, no interface state in its
 *       selector, and spends the property on a fill;
 *   (e) the clinician's view of a share draws the shipped scale.
 *
 * Each detector is first shown a planted breach, in a copy of a real file, and must see it.
 *
 * WHY IT PARSES THE MARKUP. Which element a directive sits on, whether that element has children
 * and who its siblings are, are questions about the tree, and a grep cannot answer them. The
 * Svelte compiler's own parser can. Comments are blanked to spaces first, so offsets still line up
 * with the source and a sentence that quotes a breach is not mistaken for one.
 */

/* ═══════════════════════════════════════════════════════════════════════════
   The tree.
   ═══════════════════════════════════════════════════════════════════════════ */

/** This file is at src/lib/components/, so two levels up is src/. */
const SRC = fileURLToPath(new URL('../../', import.meta.url))
const rel = (abs: string) => 'src/' + abs.slice(SRC.length)

function walk(dir: string): string[] {
  const out: string[] = []
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const path = dir + entry.name
    if (entry.isDirectory()) out.push(...walk(path + '/'))
    else if (/\.(svelte|ts|css)$/.test(entry.name) && !/\.test\.ts$/.test(entry.name)) out.push(path)
  }
  return out.sort()
}

const TREE = new Map(walk(SRC).map((f) => [rel(f), readFileSync(f, 'utf8')]))
const read = (path: string) => {
  const src = TREE.get(path)
  if (src === undefined) throw new Error(`${path} is not in the tree`)
  return src
}

const DASHBOARD = 'src/lib/components/Dashboard.svelte'
const MOOD_MARK = 'src/lib/components/calendar/MoodMark.svelte'
const OWNER_CONSOLE = 'src/lib/components/owner/OwnerConsole.svelte'
const CLINICIAN_VIEW = 'src/lib/components/therapist/SharedDataView.svelte'

/**
 * Comments replaced by spaces of the same length, so every offset still points at the source. In a
 * component, each kind is blanked only where it is a comment — `<!-- -->` in markup, `/* *\/` and
 * `//` in the script, `/* *\/` in the style — so text in the markup that happens to hold `//` is
 * left alone and the markup still parses.
 */
function blankComments(src: string, path = '.svelte'): string {
  const spaces = (m: string) => m.replace(/[^\n]/g, ' ')
  const block = (code: string) => code.replace(/\/\*[\s\S]*?\*\//g, spaces)
  const script = (code: string) => block(code).replace(/(?<![:'"`\\])\/\/[^\n]*/g, spaces)
  if (path.endsWith('.css')) return block(src)
  if (!path.endsWith('.svelte')) return script(src)
  return src
    .replace(/<!--[\s\S]*?-->/g, spaces)
    .replace(/(<script[^>]*>)([\s\S]*?)(<\/script>)/g, (_, open: string, body: string, close: string) => open + script(body) + close)
    .replace(/(<style[^>]*>)([\s\S]*?)(<\/style>)/g, (_, open: string, body: string, close: string) => open + block(body) + close)
}

/** Style blocks and .css files, comments removed: what the browser gets. */
function stylesheetOf(path: string, src: string): string {
  const css = path.endsWith('.css') ? src : [...src.matchAll(/<style[^>]*>([\s\S]*?)<\/style>/g)].map((m) => m[1]).join('\n')
  return css.replace(/\/\*[\s\S]*?\*\//g, '')
}

/* ═══════════════════════════════════════════════════════════════════════════
   Reading the markup.
   ═══════════════════════════════════════════════════════════════════════════ */

/* The parts of Svelte's modern AST this suite reads. */
interface AstNode {
  type: string
  name?: string
  start: number
  end: number
  attributes?: AstNode[]
  fragment?: { nodes: AstNode[] }
  value?: unknown
  data?: string
  [key: string]: unknown
}

interface Placed {
  node: AstNode
  /** Every node in the same fragment, the node included: what it sits beside. */
  siblings: AstNode[]
}

/** Every element, component and block in a file's markup, each with the fragment it sits in. */
function placedNodes(src: string): Placed[] {
  const root = parse(blankComments(src), { modern: true }) as unknown as { fragment: { nodes: AstNode[] } }
  const out: Placed[] = []
  const SKIP = new Set(['expression', 'attributes', 'value', 'metadata', 'loc', 'context', 'key', 'index', 'test'])
  const visit = (value: unknown) => {
    if (Array.isArray(value)) return value.forEach(visit)
    if (typeof value !== 'object' || value === null) return
    const node = value as AstNode
    if (node.type === 'Fragment') {
      const siblings = (node as unknown as { nodes: AstNode[] }).nodes
      for (const child of siblings) {
        out.push({ node: child, siblings })
        visit(child)
      }
      return
    }
    for (const [key, child] of Object.entries(node)) if (!SKIP.has(key)) visit(child)
  }
  visit(root.fragment)
  return out
}

const attr = (el: AstNode, name: string) => el.attributes?.find((a) => a.type === 'Attribute' && a.name === name)

/** A class attribute's tokens when it is plain text; null when any part of it is computed. */
function staticClasses(el: AstNode): string[] | null {
  const cls = attr(el, 'class')
  if (!cls) return []
  const parts = cls.value as AstNode[] | true
  if (!Array.isArray(parts) || parts.some((p) => p.type !== 'Text')) return null
  return parts.map((p) => p.data ?? '').join(' ').split(/\s+/).filter(Boolean)
}

const hasClass = (el: AstNode, token: string) => (staticClasses(el) ?? []).includes(token)
const isElement = (n: AstNode) => n.type === 'RegularElement'
const moodFillDirectives = (el: AstNode) =>
  (el.attributes ?? []).filter((a) => a.type === 'StyleDirective' && a.name === '--mood-fill')

/** A mood mark carries nothing a person can operate, and holds nothing that could inherit its colour. */
function markProblems(el: AstNode): string[] {
  const problems: string[] = []
  if (!['span', 'rect'].includes(el.name ?? '')) problems.push(`is a <${el.name}>, not a <span> or <rect>`)
  if (!hasClass(el, 'mood-mark')) problems.push('does not carry the plain class "mood-mark"')
  for (const a of el.attributes ?? []) {
    const n = a.name ?? ''
    if (a.type === 'OnDirective' || a.type === 'BindDirective' || /^on/i.test(n) || ['role', 'tabindex', 'href', 'contenteditable'].includes(n)) {
      problems.push(`can be operated (${n || a.type})`)
    }
  }
  const children = (el.fragment?.nodes ?? []).filter((c) => !(c.type === 'Text' && !(c.data ?? '').trim()))
  if (children.length > 0) problems.push('has children, which would inherit its colour')
  if (el.name === 'span') {
    const hidden = attr(el, 'aria-hidden')
    const text = Array.isArray(hidden?.value) ? (hidden!.value as AstNode[]).map((p) => p.data).join('') : null
    if (text !== 'true') problems.push('is not aria-hidden: its word, beside it, is what is read')
  }
  return problems
}

interface MarkupFinding {
  where: string
  what: string
}

/** (b) and (c): where a person's colour is written into the markup, and what it is written on. */
function markupFindings(path: string, src: string): MarkupFinding[] {
  if (!path.endsWith('.svelte')) return []
  const findings: MarkupFinding[] = []
  const blank = blankComments(src)
  const placed = placedNodes(src)

  // Every place `--mood-fill` is set inline, and the value ranges that may hold `ownMoodFill(`.
  const ranges: [number, number][] = []
  for (const { node, siblings } of placed) {
    if (!isElement(node)) continue
    const directives = moodFillDirectives(node)
    for (const d of directives) {
      const v = d.value as { start: number; end: number } | true
      if (typeof v === 'object') ranges.push([v.start, v.end])
    }
    if (directives.length > 0) {
      for (const problem of markProblems(node)) findings.push({ where: path, what: `style:--mood-fill on an element that ${problem}` })
    }
    // A mood mark always sits beside its word, so its colour is never the only signal.
    if (hasClass(node, 'mood-mark') && !siblings.some((s) => s !== node && isElement(s) && hasClass(s, 'mood-word'))) {
      findings.push({ where: path, what: `a mood mark at ${node.start} has no .mood-word beside it` })
    }
    // Nowhere else may the property be written: not in a style attribute, not by another name.
    const style = attr(node, 'style')
    if (style && /--mood-fill|ownMoodFill|moodColors/.test(blank.slice(style.start, style.end))) {
      findings.push({ where: path, what: `a style attribute at ${style.start} writes a mood fill` })
    }
  }

  // (b) Every call of ownMoodFill is the value of one of those directives.
  for (const m of blank.matchAll(/\bownMoodFill\s*\(/g)) {
    const at = m.index!
    if (!ranges.some(([s, e]) => at >= s && at < e)) {
      findings.push({ where: path, what: `ownMoodFill called at ${at}, outside a style:--mood-fill directive` })
    }
  }
  return findings
}

/** (b) in modules: outside lib/mood.ts, which defines it, no module calls ownMoodFill at all. */
function moduleFindings(path: string, src: string): MarkupFinding[] {
  if (!path.endsWith('.ts') || path === 'src/lib/mood.ts') return []
  return /\bownMoodFill\s*\(/.test(blankComments(src, path)) ? [{ where: path, what: 'a module calls ownMoodFill' }] : []
}

/**
 * (a) A backup's `moodColors` is read only through ownMoodColours. Allowed besides: the backup's
 * own type and parser, and the share adapter, which sets it empty.
 */
const MOOD_COLORS_OWNERS = new Set(['src/lib/backup.ts', 'src/lib/therapist/shareClient.ts'])

function rawColourReads(path: string, src: string): MarkupFinding[] {
  if (MOOD_COLORS_OWNERS.has(path) || path === 'src/lib/mood.ts' || path.endsWith('.css')) return []
  const blank = blankComments(src, path)
  const out: MarkupFinding[] = []
  for (const m of blank.matchAll(/\bmoodColors\b/g)) {
    const before = blank.slice(Math.max(0, m.index! - 200), m.index!)
    if (!/\bownMoodColours\s*\([^()]*$/.test(before)) out.push({ where: path, what: `moodColors read at ${m.index} without ownMoodColours` })
  }
  return out
}

/* ═══════════════════════════════════════════════════════════════════════════
   Reading the stylesheets.
   ═══════════════════════════════════════════════════════════════════════════ */

/**
 * Interface state and status chrome, by selector. Anything a person operates or that reports on
 * the interface — hover, focus, selection, today, tabs, callouts, banners, strips, pills, chips,
 * links, buttons, summaries, form fields, navigation — and `:global`, which could reach past the
 * component the rule lives in.
 */
const CHROME_OR_STATE =
  /:hover|:focus|:active|:checked|:disabled|:visited|:target|:global|\.active\b|\.selected\b|\.current\b|\.chosen\b|\.picked\b|\.today\b|\.anchor\b|\.on\b|\.error\b|\.warn|\.invalid\b|\.success\b|\.tab|\.callout|\.banner|\.strip|\.pill|\.chip|\.btn|\.link|\.nav|\.focus|\[aria-|(^|[\s>+~(])(button|a|summary|input|select|textarea|label|nav|details)(?=$|[\s.:#[>+~)])/i

/** The properties that fill a shape. A person's colour is spent on these and nothing else. */
const FILLS = new Set(['fill', 'background', 'background-color'])

/** The one value a stylesheet may give `--mood-fill`: a step of the shipped ramp. */
const RAMP_STEP = /^var\(--mood-[1-5]\)$/

function styleFindings(path: string, src: string): MarkupFinding[] {
  const css = stylesheetOf(path, src)
  const out: MarkupFinding[] = []
  for (const m of css.matchAll(/([^{}]+)\{([^{}]*)\}/g)) {
    const body = m[2]!
    if (!/--mood-fill/.test(body)) continue
    const selectorList = m[1]!.trim().replace(/\s+/g, ' ')
    for (const selector of selectorList.split(',').map((s) => s.trim())) {
      const subject = selector.split(/[\s>+~]+/).pop() ?? ''
      if (!/\.mood-mark(?![\w-])/.test(subject)) out.push({ where: path, what: `"${selector}" names --mood-fill, and its subject is not a mood mark` })
      if (CHROME_OR_STATE.test(selector)) out.push({ where: path, what: `"${selector}" names --mood-fill under interface state or chrome` })
    }
    for (const decl of body.split(';')) {
      const d = /^\s*([\w-]+)\s*:\s*([\s\S]*?)\s*$/.exec(decl)
      if (!d) continue
      const [, prop, value] = d
      if (prop === '--mood-fill' && !RAMP_STEP.test(value!)) out.push({ where: path, what: `"${selectorList}" sets --mood-fill to ${value}, not a step of the ramp` })
      if (prop !== '--mood-fill' && /--mood-fill/.test(value!) && !FILLS.has(prop!)) out.push({ where: path, what: `"${selectorList}" spends --mood-fill on ${prop}` })
    }
  }
  return out
}

/* ═══════════════════════════════════════════════════════════════════════════
   The whole tree, run through every detector.
   ═══════════════════════════════════════════════════════════════════════════ */

/** Every detector reads one file at a time, so a file's findings are its own. */
const fileFindings = (path: string, src: string): MarkupFinding[] => [
  ...markupFindings(path, src),
  ...moduleFindings(path, src),
  ...rawColourReads(path, src),
  ...styleFindings(path, src),
]

const allFindings = (tree: Map<string, string>) => [...tree].flatMap(([path, src]) => fileFindings(path, src))

const plant = (path: string, replace: (src: string) => string) => {
  const planted = replace(read(path))
  expect(planted, `the plant in ${path} changed nothing`).not.toBe(read(path))
  return new Map([...TREE, [path, planted]])
}

const findingsIn = (tree: Map<string, string>, path: string) => fileFindings(path, tree.get(path) ?? '')

/* ═══════════════════════════════════════════════════════════════════════════ */

describe('the suite has a subject', () => {
  it('walked the tree and found where a person’s colours are drawn', () => {
    expect(TREE.size).toBeGreaterThan(150)
    expect(TREE.has('src/app.css')).toBe(true)
    expect(read('src/lib/mood.ts')).toMatch(/export function ownMoodFill\(/)
    // The directive is really there to police, on a mood mark, in the dashboard's distribution.
    const marks = placedNodes(read(DASHBOARD)).filter(({ node }) => isElement(node) && moodFillDirectives(node).length > 0)
    expect(marks.length).toBe(1)
    expect(marks[0]!.node.name).toBe('rect')
    expect(hasClass(marks[0]!.node, 'mood-mark')).toBe(true)
    // And the stylesheet side: the dashboard's rules that set and read it were parsed.
    const css = stylesheetOf(DASHBOARD, read(DASHBOARD))
    expect(css).toMatch(/\.dist \.mood-mark\[data-level='1'\] \{ --mood-fill: var\(--mood-1\); \}/)
    expect(css).toMatch(/\.dist \.mood-mark \{ fill: var\(--mood-fill\); \}/)
    // The month's mood square (#335): one directive, on a span, beside its word.
    const squares = placedNodes(read(MOOD_MARK)).filter(({ node }) => isElement(node) && moodFillDirectives(node).length > 0)
    expect(squares.map(({ node }) => node.name)).toEqual(['span'])
    expect(squares[0]!.siblings.some((s) => isElement(s) && hasClass(s, 'mood-word'))).toBe(true)
    expect(stylesheetOf(MOOD_MARK, read(MOOD_MARK))).toMatch(/\.mood-mark \{[^}]*background: var\(--mood-fill\);/)
  })

  it('the comment blanker keeps offsets and drops only commentary', () => {
    const src = read(DASHBOARD)
    const blank = blankComments(src)
    expect(blank.length).toBe(src.length)
    expect(src).toMatch(/ownMoodColour\.tree\.test\.ts holds/) // a comment naming this suite
    expect(blank).not.toMatch(/ownMoodColour\.tree\.test\.ts holds/)
    expect(blank).toContain('style:--mood-fill={ownMoodFill(m.level, palette)}')
  })
})

describe('a person’s own colours reach a mood mark’s fill and nothing else', () => {
  it('nothing in the tree breaks the road (a)–(d)', () => {
    expect(allFindings(TREE)).toEqual([])
  })

  it('(d) sees a colour on status chrome, on interface state, on text and on an edge (positive control)', () => {
    const cases: [string, (s: string) => string, RegExp][] = [
      // A selected tab painted in a person's colour: chrome, and not a mood mark.
      [OWNER_CONSOLE, (s) => s.replace('</style>', '.tabs button.active { background: var(--mood-fill); }\n</style>'), /interface state or chrome/],
      // A mood mark that changes colour on hover: interface state on a mark.
      [DASHBOARD, (s) => s.replace('</style>', ".dist .mood-mark:hover { fill: var(--mood-fill); }\n</style>"), /interface state or chrome/],
      // The mood's word drawn in the mood's colour: not a mood mark.
      [DASHBOARD, (s) => s.replace('</style>', '.dist .mood-word { fill: var(--mood-fill); }\n</style>'), /subject is not a mood mark/],
      // The mark's edge instead of its fill.
      [DASHBOARD, (s) => s.replace('.dist .mood-mark { fill: var(--mood-fill); }', '.dist .mood-mark { fill: var(--mood-fill); stroke: var(--mood-fill); }'), /spends --mood-fill on stroke/],
      // A stylesheet giving the property a colour of its own.
      [DASHBOARD, (s) => s.replace("--mood-fill: var(--mood-1);", '--mood-fill: var(--indigo);'), /not a step of the ramp/],
      // The month's mood word written in the mood's colour.
      [MOOD_MARK, (s) => s.replace('color: var(--ink-text);', 'color: var(--mood-fill);'), /subject is not a mood mark|spends --mood-fill on color/],
    ]
    for (const [path, replace, expected] of cases) {
      const found = findingsIn(plant(path, replace), path).map((f) => f.what)
      expect(found.some((w) => expected.test(w)), `${path}: ${found.join(' | ') || 'nothing found'}`).toBe(true)
    }
  })

  it('(b) and (c) see a colour written on chrome, outside the property, or away from its word (positive control)', () => {
    const cases: [string, (s: string) => string, RegExp][] = [
      // The property on a button: an operable element, with a child that would inherit it.
      [OWNER_CONSOLE, (s) => s.replace('<button class="lock"', '<button class="mood-mark" style:--mood-fill={ownMoodFill(1, palette)}>x</button>\n      <button class="lock"'), /can be operated|not a <span>|has children/],
      // A person's colour as a text colour, written straight into a style directive.
      [DASHBOARD, (s) => s.replace('<span class="h">Journal</span>', '<span class="h" style:color={ownMoodFill(1, palette)}>Journal</span>'), /outside a style:--mood-fill directive/],
      // The same through a style attribute.
      [DASHBOARD, (s) => s.replace('<span class="h">Journal</span>', '<span class="h" style="--mood-fill: {ownMoodFill(1, palette)}">Journal</span>'), /style attribute|outside a style:--mood-fill/],
      // A mood mark with its word taken away.
      [DASHBOARD, (s) => s.replace('class="lbl mood-word"', 'class="lbl"'), /no \.mood-word beside it/],
      // The month's square moved onto its wrapper, where the word would inherit it.
      [MOOD_MARK, (s) => s.replace('<span class="mood">', '<span class="mood" style:--mood-fill={ownMoodFill(level, palette)}>'), /has children|not carry the plain class/],
      // A call in the script, where it could go anywhere.
      [DASHBOARD, (s) => s.replace('const s = $derived(summarize(data))', 'const first = ownMoodFill(1, palette)\n  const s = $derived(summarize(data))'), /outside a style:--mood-fill directive/],
    ]
    for (const [path, replace, expected] of cases) {
      const found = findingsIn(plant(path, replace), path).map((f) => f.what)
      expect(found.some((w) => expected.test(w)), `${path}: ${found.join(' | ') || 'nothing found'}`).toBe(true)
    }
  })

  it('(a) sees a backup’s colours read around the palette (positive control)', () => {
    const planted = plant(DASHBOARD, (s) => s.replace('const s = $derived(summarize(data))', "const raw = data.moodColors?.['3']\n  const s = $derived(summarize(data))"))
    const found = findingsIn(planted, DASHBOARD).map((f) => f.what)
    expect(found.filter((w) => /moodColors read at \d+ without ownMoodColours/.test(w)).length).toBe(1)
    // And in a module.
    const inModule = new Map([...TREE, ['src/lib/stats.ts', `${read('src/lib/stats.ts')}\nexport const leak = (d: { moodColors?: unknown }) => d.moodColors\n`]])
    expect(findingsIn(inModule, 'src/lib/stats.ts').length).toBeGreaterThan(0)
  })
})

describe('(e) the clinician’s view of a share draws the shipped scale', () => {
  const tags = (src: string) =>
    blankComments(src)
      .replace(/<script[\s\S]*?<\/script>/g, '')
      .match(/<Dashboard\b[^>]*>/g) ?? []
  const saysNotOwn = (tag: string) => /\bownData=\{\s*false\s*\}/.test(tag)
  const mountsThatSayNotOwn = (tree: Map<string, string>) =>
    [...tree].filter(([p, src]) => p.endsWith('.svelte') && tags(src).some(saysNotOwn)).map(([p]) => p)
  const mountsThatDoNot = (tree: Map<string, string>) =>
    [...tree].filter(([p, src]) => p.endsWith('.svelte') && tags(src).some((t) => !saysNotOwn(t))).map(([p]) => p)

  it('the clinician’s view, and only it, says the records are not the person’s own', () => {
    expect(tags(read(CLINICIAN_VIEW)).length).toBeGreaterThan(0)
    expect(mountsThatSayNotOwn(TREE)).toEqual([CLINICIAN_VIEW])
    // Every other mount is over a person's own records, and none of them is under therapist/.
    const own = mountsThatDoNot(TREE)
    expect(own).toEqual(expect.arrayContaining(['src/App.svelte', OWNER_CONSOLE]))
    expect(own.filter((p) => p.includes('/therapist/'))).toEqual([])
  })

  it('sees a clinician’s mount that forgets, and an own-data mount that says it (positive control)', () => {
    // The clinician's mount rewritten as it would read without the attribute. Written whole, so
    // the control tests the detector whatever the file holds today.
    const forgot = new Map([...TREE, [CLINICIAN_VIEW, read(CLINICIAN_VIEW).replace(/<Dashboard\b[^>]*>/, '<Dashboard {data} showAverage />')]])
    expect(mountsThatDoNot(forgot)).toContain(CLINICIAN_VIEW)
    expect(mountsThatSayNotOwn(forgot)).not.toContain(CLINICIAN_VIEW)
    const denied = plant(OWNER_CONSOLE, (s) => s.replace('<Dashboard {data} />', '<Dashboard {data} ownData={false} />'))
    expect(mountsThatSayNotOwn(denied)).toContain(OWNER_CONSOLE)
  })

  it('a share carries no colours and no names into the clinician’s view, whatever the bundle holds', () => {
    const bundle = {
      schema: 1,
      shareId: 's1',
      scope: { from: 0, to: 1, recordTypes: ['moods'] },
      ownerFp: 'fp',
      checkIns: [],
      moods: [{ at: 0, level: 3 }],
      // Fields no share format has, planted as a later format might carry them.
      moodColors: { '3': -5351609 },
      moodLabels: { '3': 'Flat' },
    } as unknown as ShareBundle
    const data = bundleToBackupData(bundle)
    expect(data.entries.length).toBe(1) // the adapter really ran over the bundle
    expect(data.moodLabels).toEqual({})
    expect(ownMoodFill(3, ownMoodColours(data.moodColors))).toBeUndefined()
    // Control: a person's own backup carrying the same colour is read.
    expect(ownMoodFill(3, ownMoodColours({ '3': -5351609 }))).toBe('#ae5747')
  })
})
