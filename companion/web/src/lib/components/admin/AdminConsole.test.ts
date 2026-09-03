/*
 * The admin console's structure, asserted over its source.
 *
 * Every sentence on the console comes from lib/admin/health.ts and lib/admin/chainHead.ts, and
 * those modules carry the tests for the words. What is left to check is composition — the
 * index lands on the sections it names, the standing facts are folded and not removed, the
 * masthead is the shared one, and the fact panels are drawn the way the design notes define a
 * callout. There is no component-rendering harness in this project (vite.config.ts sets
 * `environment: 'node'`), so, as portalWiring.test.ts and setupEntry.test.ts do, this reads the
 * source and asserts over the markup and the style block.
 *
 * The contrast check at the bottom is the one that is not a grep: it resolves the tokens the
 * style block names through app.css's dark palette — the palette this route pins at the
 * document element — and computes the WCAG ratio, so a token swap that looks harmless in the
 * diff fails here with a number.
 *
 * Every absence assertion is paired with a control proving the same search finds the thing when
 * it IS present.
 */
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

const read = (rel: string) => readFileSync(resolve(process.cwd(), rel), 'utf8')

const SOURCE = read('src/lib/components/admin/AdminConsole.svelte')
const APP_CSS = read('src/app.css')
const OWNER_APP = read('src/App.svelte')

const SCRIPT = SOURCE.match(/<script[^>]*>([\s\S]*?)<\/script>/)?.[1] ?? ''
const STYLE = (SOURCE.match(/<style>([\s\S]*?)<\/style>/)?.[1] ?? '').replace(/\/\*[\s\S]*?\*\//g, '')

/** The markup a person actually gets: script and style gone, comments gone. */
const markupOf = (src: string) =>
  src
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/<script[\s\S]*?<\/script>/g, '')
    .replace(/<style[\s\S]*?<\/style>/g, '')

const MARKUP = markupOf(SOURCE)

/** The declarations of the first rule whose selector is exactly `selector`, or null. */
function declarationsOf(selector: string): string | null {
  const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  return new RegExp(`(?:^|[\\n}])\\s*${escaped}\\s*\\{([^{}]*)\\}`).exec(STYLE)?.[1] ?? null
}

/** The `{#each STANDING_FACTS …}` loop body: what each fact renders. */
const FACT_OPEN = '{#each STANDING_FACTS as fact (fact.id)}'
const FACT = MARKUP.slice(
  MARKUP.indexOf(FACT_OPEN) + FACT_OPEN.length,
  MARKUP.indexOf('{/each}', MARKUP.indexOf(FACT_OPEN)),
)

/** The ids the script's SECTIONS constant lists, in order: the array literal after its `= [`,
    up to the `]` that closes it (the type annotation before it carries a `[]` of its own). */
const SECTIONS_SRC = SCRIPT.slice(SCRIPT.indexOf('const SECTIONS'))
const SECTIONS_LITERAL = SECTIONS_SRC.slice(SECTIONS_SRC.indexOf('= [') + 3)
const SECTION_IDS = [
  ...SECTIONS_LITERAL.slice(0, SECTIONS_LITERAL.indexOf(']')).matchAll(/id: '([a-z-]+)'/g),
].map((m) => m[1]!)

describe('the masthead is the shared one', () => {
  it('renders the wordmark and tagline in the brand block App.svelte uses, above the page title', () => {
    // The pattern, proven to be the one the owner app renders rather than a look-alike.
    expect(OWNER_APP).toContain('<span class="mark" aria-hidden="true"></span>')
    expect(OWNER_APP).toContain('<p class="muted tagline">')

    const brand = MARKUP.indexOf('<div class="brand">')
    expect(brand).toBeGreaterThan(-1)
    const block = MARKUP.slice(brand, MARKUP.indexOf('<PageHeader'))
    expect(block).toContain('<span class="mark" aria-hidden="true"></span>')
    expect(block).toContain('<p class="wordmark">Daymark Companion</p>')
    expect(block).toContain('<p class="muted tagline">Server operations</p>')
  })

  it('leaves PageHeader as the only h1 on the page', () => {
    // App.svelte spells its wordmark as an <h1> because nothing else on that screen is one;
    // here PageHeader already emits the page's <h1>, so the wordmark is a <p>.
    expect(MARKUP).not.toMatch(/<h1\b/)
    expect(MARKUP).toContain('<PageHeader title="Admin console">')
    // Control: the search finds an h1 when there is one.
    expect(/<h1\b/.test(MARKUP + '<h1>x</h1>')).toBe(true)
  })
})

describe('the in-page index lands on the sections it names', () => {
  it('lists the six sections, in page order', () => {
    expect(SECTION_IDS).toEqual([
      'standing-facts',
      'operational-health',
      'authentication-pressure',
      'audit-chain-integrity',
      'server-chain-check',
      'scope',
    ])
  })

  it('is a <nav> named "Sections" that links to each section id', () => {
    const nav = /<nav\b[^>]*>[\s\S]*?<\/nav>/.exec(MARKUP)?.[0]
    expect(nav).toBeDefined()
    expect(nav).toMatch(/aria-label="Sections"/)
    expect(nav).toContain('{#each SECTIONS as section (section.id)}')
    expect(nav).toContain('href={`#${section.id}`}')
    expect(nav).toContain('{section.label}')
  })

  it('every listed id is carried by a <section>, and the sections appear in the listed order', () => {
    let last = -1
    for (const id of SECTION_IDS) {
      const at = MARKUP.search(new RegExp(`<section\\b[^>]*\\bid="${id}"`))
      expect(at, `no <section id="${id}">`).toBeGreaterThan(-1)
      expect(at, `section "${id}" is out of the index's order`).toBeGreaterThan(last)
      last = at
    }
    // Control: an id the index does not list is not on a section either.
    expect(MARKUP.search(/<section\b[^>]*\bid="not-a-section"/)).toBe(-1)
  })

  it('sits under the title and before the first section, on every window width', () => {
    const header = MARKUP.indexOf('</PageHeader>')
    const nav = MARKUP.indexOf('<nav')
    const firstSection = MARKUP.indexOf('<section')
    expect(header).toBeGreaterThan(-1)
    expect(nav).toBeGreaterThan(header)
    expect(firstSection).toBeGreaterThan(nav)
    // The rail is sticky on a wide window, and the sticky rule lives inside a min-width query.
    const wide = /@media \(min-width: [^)]+\)\s*\{([\s\S]*)$/.exec(STYLE)?.[1] ?? ''
    expect(wide).toMatch(/\.sections\s*\{[^}]*position:\s*sticky/)
  })
})

describe('the standing facts fold but do not leave', () => {
  it('each fact is a <details> that renders open, with the title in its summary', () => {
    expect(FACT.length).toBeGreaterThan(0)
    expect(FACT).toMatch(/<details\b[^>]*\bopen[\s>]/)
    expect(FACT).toMatch(/<summary\b[\s\S]*\{fact\.title\}[\s\S]*<\/summary>/)
  })

  it('keeps every line the Callout carried, including the evidence line', () => {
    expect(FACT).toContain('{fact.body}')
    expect(FACT).toContain('{fact.consequence}')
    expect(FACT).toContain('Read it at: {fact.evidence}')
  })

  it('tells a screen reader the severity in words, since the keyline is a colour', () => {
    expect(FACT).toMatch(/<span class="visually-hidden">\{FACT_SEVERITY\[fact\.tone\]\}:<\/span>/)
    expect(SCRIPT).toMatch(/warn: 'Warning'/)
    expect(SCRIPT).toMatch(/critical: 'Needs attention'/)
  })

  it('no longer renders the facts through Callout, which draws the rounded banner', () => {
    expect(FACT).not.toContain('<Callout')
    // Control: Callout is still used elsewhere on the page, so the search can find it.
    expect(MARKUP).toContain('<Callout')
  })
})

describe('the fact panel is the flat callout the design notes define', () => {
  const fact = declarationsOf('.fact')
  const critical = declarationsOf(".fact[data-tone='critical']")
  const title = declarationsOf('.fact-title')

  it('is a 2px keyline in the warn or danger token with the matching wash as tint', () => {
    expect(fact).not.toBeNull()
    expect(fact).toMatch(/border-left:\s*2px\s+dashed\s+var\(--amber\)/)
    expect(fact).toMatch(/background:\s*var\(--amber-wash\)/)
    expect(critical).not.toBeNull()
    expect(critical).toMatch(/border-left-style:\s*solid/)
    expect(critical).toMatch(/border-left-color:\s*var\(--danger\)/)
    expect(critical).toMatch(/background:\s*var\(--danger-wash\)/)
  })

  it('has square corners and no outline around it', () => {
    expect(fact).not.toMatch(/border-radius/)
    expect(critical).not.toMatch(/border-radius/)
    expect(critical).not.toMatch(/(^|[^-])border-color/)
    // Control: the detector catches a radius when one is declared.
    expect(/border-radius/.test(`${fact}border-radius: 1px;`)).toBe(true)
  })

  it('sets the heading in the ordinary ink, not the severity hue', () => {
    expect(title).not.toBeNull()
    expect(title).toMatch(/color:\s*var\(--ink-text\)/)
    expect(title).not.toMatch(/--danger|--clay|--amber/)
  })
})

/* ---- Contrast, computed rather than asserted by token name ---------------------------- */

/** Every `--name: value;` in the `:root { … }` blocks (primitives, foundations, light roles). */
function scope(css: string, selector: RegExp): Map<string, string> {
  const out = new Map<string, string>()
  for (const block of css.matchAll(selector)) {
    for (const d of block[1]!.matchAll(/(--[\w-]+)\s*:\s*([^;]+);/g)) out.set(d[1]!, d[2]!.trim())
  }
  return out
}

const LIGHT = scope(APP_CSS, /(?<![\w\])]):root\s*\{([^{}]*)\}/g)
const DARK = scope(APP_CSS, /:root\[data-theme="dark"\]\s*\{([^{}]*)\}/g)

/** A token's hex in the dark palette: dark roles first, then the shared/light layer, via aliases. */
function inDark(name: string, seen = new Set<string>()): string {
  if (seen.has(name)) throw new Error(`cycle at ${name}`)
  seen.add(name)
  const raw = DARK.get(name) ?? LIGHT.get(name)
  if (raw === undefined) throw new Error(`${name} is not defined in app.css`)
  const via = /^var\(\s*(--[\w-]+)\s*\)$/.exec(raw)
  return via ? inDark(via[1]!, seen) : raw
}

function luminance(hex: string): number {
  const h = hex.replace('#', '')
  const full = h.length === 3 ? h.split('').map((c) => c + c).join('') : h
  const [r, g, b] = [0, 2, 4].map((i) => {
    const v = parseInt(full.slice(i, i + 2), 16) / 255
    return v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4
  }) as [number, number, number]
  return 0.2126 * r + 0.7152 * g + 0.0722 * b
}

function contrast(a: string, b: string): number {
  const [x, y] = [luminance(a), luminance(b)]
  return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05)
}

/** The token a rule's `color:` names, e.g. `--ink-soft`. */
const colourToken = (decls: string | null) => /color:\s*var\((--[\w-]+)\)/.exec(decls ?? '')?.[1] ?? null
const backgroundToken = (decls: string | null) =>
  /background:\s*var\((--[\w-]+)\)/.exec(decls ?? '')?.[1] ?? null

describe('the "Read it at" line clears AA on both fact panels in the dark palette', () => {
  it('the resolver and the ratio agree with known values', () => {
    expect(contrast('#000000', '#ffffff')).toBeCloseTo(21, 5)
    expect(inDark('--paper-bg')).toMatch(/^#[0-9a-fA-F]{6}$/)
    expect(inDark('--danger-wash')).toBe(inDark('--clay-wash')) // the alias resolves through
    expect(inDark('--paper-bg')).not.toBe(LIGHT.get('--c-paper')) // dark really is dark
    // The pair this test exists for: --text-subtle on the amber wash is UNDER AA in the dark
    // palette, which is why the evidence line does not use it. If the palette moves and this
    // control starts passing, the test still checks the pair that ships.
    expect(contrast(inDark('--text-subtle'), inDark('--amber-wash'))).toBeLessThan(4.5)
  })

  it('evidence text on the warn tint and on the danger tint both measure at least 4.5:1', () => {
    const text = colourToken(declarationsOf('.evidence'))
    const warnFill = backgroundToken(declarationsOf('.fact'))
    const dangerFill = backgroundToken(declarationsOf(".fact[data-tone='critical']"))
    expect(text).not.toBeNull()
    expect(warnFill).not.toBeNull()
    expect(dangerFill).not.toBeNull()
    const onWarn = contrast(inDark(text!), inDark(warnFill!))
    const onDanger = contrast(inDark(text!), inDark(dangerFill!))
    expect(onWarn, `${text} on ${warnFill}: ${onWarn.toFixed(2)}:1`).toBeGreaterThanOrEqual(4.5)
    expect(onDanger, `${text} on ${dangerFill}: ${onDanger.toFixed(2)}:1`).toBeGreaterThanOrEqual(4.5)
  })

  it('the fact heading and body clear AA on both tints as well', () => {
    const heading = colourToken(declarationsOf('.fact-title'))!
    const body = colourToken(declarationsOf('.fact'))!
    for (const fill of ['--amber-wash', '--danger-wash']) {
      expect(contrast(inDark(heading), inDark(fill))).toBeGreaterThanOrEqual(4.5)
      expect(contrast(inDark(body), inDark(fill))).toBeGreaterThanOrEqual(4.5)
    }
  })
})
