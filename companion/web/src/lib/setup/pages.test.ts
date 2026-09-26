import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { SHAPES_SERVING, linksTo, type ShapePage } from './pages'
import { LABELS, SHAPE_IDS, type ShapeId } from './shape'
import { AUDIENCES, shownAudiences } from '../onboarding/audience'

/*
 * WHAT THIS SUITE IS FOR (#330)
 *
 * The owner's page links two pages a shape can refuse: the clinician console, from the
 * orientation's clinician card and compact line, and the practice console, from the practice
 * panel. A shape that leaves one off answers it 403. So each published shape must link exactly the
 * pages it serves, and no published shape must link them all, because the page cannot tell.
 *
 * Two things make that more than a restatement of pages.ts. The table is checked against the one
 * the server's own suite proves over real responses (ShapeRoutingTest.kt), so the two sides cannot
 * drift apart with both suites green. And the check runs over the links the owner's page actually
 * renders — the audiences it shows and the practice anchor — rather than over the table alone.
 */

const PAGES: readonly ShapePage[] = ['clinician', 'practice']

/** The file a sibling-relative link opens. */
const fileOf = (href: string) => href.replace(/^\.\//, '')

/**
 * The server suite's table of which shapes serve each page, read out of its source: the table
 * ShapeRoutingTest asserts, shape by shape, against what the server answers for each page.
 */
function serverTable(): Map<string, Set<ShapeId>> {
  const path = new URL('../../../../server/src/test/kotlin/com/daymark/companion/ShapeRoutingTest.kt', import.meta.url)
  const source = readFileSync(fileURLToPath(path), 'utf8')
  const start = source.indexOf('val SERVED_IN = mapOf(')
  const block = start < 0 ? '' : source.slice(start, source.indexOf('\n\n', start))
  const table = new Map<string, Set<ShapeId>>()
  for (const m of block.matchAll(/"([a-z]+\.html)" to setOf\(([A-Z, ]*)\)/g)) {
    table.set(m[1]!, new Set(m[2]!.split(',').map((s) => s.trim().toLowerCase() as ShapeId)))
  }
  return table
}

const SERVED_IN = serverTable()

/** The file each gated page is, as the owner's page links it. */
const PAGE_FILE: Record<ShapePage, string> = {
  clinician: fileOf(AUDIENCES.find((a) => a.page === 'clinician')?.href ?? ''),
  practice: fileOf(LABELS.practiceConsoleHref),
}

/** The pages on which [table] and the server's table disagree. */
function disagreements(table: Readonly<Record<ShapePage, readonly ShapeId[]>>): ShapePage[] {
  return PAGES.filter((page) => {
    const server = [...(SERVED_IN.get(PAGE_FILE[page]) ?? [])].sort()
    return JSON.stringify([...table[page]].sort()) !== JSON.stringify(server)
  })
}

/** Every link to another page of the bundle the owner's page renders, for one published shape. */
function linksRendered(published: ShapeId | null): string[] {
  return [
    // adminLink on, so the operator's card is counted too: the deployment may turn it on.
    ...shownAudiences({ adminLink: true, published }).flatMap((a) => (a.href === null ? [] : [a.href])),
    ...(linksTo('practice', published) ? [LABELS.practiceConsoleHref] : []),
  ]
}

describe('which pages a published shape lets the owner’s page link', () => {
  it('links every page when no shape was published, because the page cannot tell', () => {
    for (const page of PAGES) expect(linksTo(page, null), page).toBe(true)
  })

  it('solo links neither the clinician console nor the practice console', () => {
    // Control: the same two pages are linked with nothing published, so the refusal below is the
    // shape's doing and not a rule that never links them.
    expect(PAGES.every((page) => linksTo(page, null))).toBe(true)
    expect(linksTo('clinician', 'solo')).toBe(false)
    expect(linksTo('practice', 'solo')).toBe(false)
  })

  it('paired links the clinician console and not the practice console', () => {
    expect(linksTo('clinician', 'paired')).toBe(true)
    // Control: the practice console is linked where the shape serves it, so false here is paired.
    expect(linksTo('practice', 'practice')).toBe(true)
    expect(linksTo('practice', 'paired')).toBe(false)
  })

  it('practice links both', () => {
    expect(linksTo('clinician', 'practice')).toBe(true)
    expect(linksTo('practice', 'practice')).toBe(true)
  })

  it('names each shape it serves at most once, and only the three shapes', () => {
    for (const page of PAGES) {
      const shapes = SHAPES_SERVING[page]
      expect(new Set(shapes).size, page).toBe(shapes.length)
      for (const id of shapes) expect(SHAPE_IDS, `${page}: ${id}`).toContain(id)
    }
  })
})

describe('the table is the server’s, not a copy that agrees with itself', () => {
  it('reads the server suite’s table, and finds every page of the bundle in it', () => {
    // Non-vacuity for the comparison below: a parser that found nothing would agree with anything.
    expect([...SERVED_IN.keys()].sort()).toEqual(['admin.html', 'index.html', 'practice.html', 'therapist.html'])
    expect(PAGE_FILE).toEqual({ clinician: 'therapist.html', practice: 'practice.html' })
  })

  it('agrees with it on both pages a shape can refuse', () => {
    expect(disagreements(SHAPES_SERVING)).toEqual([])
    // Control: the practice console linked on paired too — the mistake this suite exists to catch
    // — is seen as a disagreement, and only on that page.
    const planted = { ...SHAPES_SERVING, practice: ['paired', 'practice'] as const }
    expect(disagreements(planted)).toEqual(['practice'])
  })

  it('every other page the owner’s page links is one every shape serves, so it needs no rule', () => {
    const ungated = AUDIENCES.filter((a) => a.href !== null && a.page === null)
    // Non-vacuity: the server console's card is such a link.
    expect(ungated.map((a) => a.id)).toEqual(['operator'])
    for (const a of ungated) expect(SERVED_IN.get(fileOf(a.href!)), a.id).toEqual(new Set(SHAPE_IDS))
  })
})

describe('the links the owner’s page renders, shape by shape', () => {
  it('opens only pages the published shape serves', () => {
    for (const shape of SHAPE_IDS) {
      for (const href of linksRendered(shape)) {
        expect(SERVED_IN.get(fileOf(href)), `${shape} links ${href}`).toContain(shape)
      }
    }
  })

  it('withholds nothing a published shape serves, and nothing at all when none was published', () => {
    // Non-vacuity: with nothing published, both gated pages really are linked.
    const unknown = linksRendered(null)
    expect(unknown).toContain(`./${PAGE_FILE.clinician}`)
    expect(unknown).toContain(`./${PAGE_FILE.practice}`)
    for (const shape of SHAPE_IDS) {
      for (const page of PAGES) {
        const served = SERVED_IN.get(PAGE_FILE[page])!.has(shape)
        expect(linksRendered(shape).includes(`./${PAGE_FILE[page]}`), `${shape}: ${page}`).toBe(served)
      }
    }
  })
})
