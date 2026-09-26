import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { SHAPES_SERVING, linksTo, type ShapePage } from './pages'
import { LABELS, SHAPE_IDS, opensOnStatement, shapeById, type ShapeId } from './shape'
import {
  AUDIENCES,
  OWNER_ROUTES,
  compactSummary,
  offersRoute,
  shownAudiences,
  shownRoutes,
} from '../onboarding/audience'
import {
  FILE_IS_A_STAND_IN,
  FILE_IS_A_STAND_IN_WITHOUT_OWNER_CONSOLE,
  fileIsAStandIn,
} from '../components/recovery/copy'
import {
  BEFORE_YOU_NEED_IT,
  EMAIL_LABEL,
  EMAIL_MISSING,
  LOAD_FAILED,
  REGISTER,
  REMOVE,
  SAVE_FAILED,
  recoverCardView,
  registeredStatement,
} from '../owner/recoveryEmail'

/*
 * WHAT THIS SUITE IS FOR (#330)
 *
 * The owner's page links two pages a shape can refuse: the clinician console, from the
 * orientation's clinician card and compact line, and the practice console, from the practice
 * panel. A shape that leaves one off answers it 403. So each published shape must link exactly the
 * pages it serves, and no published shape must link them all, because the page cannot tell. The
 * same goes for the one entry point on the owner's page that calls a refusable page's routes: the
 * owner console, for clinicians and shares, whose routes come with the clinician's page.
 *
 * Two things make that more than a restatement of pages.ts. The table is checked against the two
 * the server's own suite proves over real responses (ShapeRoutingTest.kt) — which shapes serve
 * each page, and which switch each group of routes on — so the sides cannot drift apart with both
 * suites green. And the checks run over what the owner's page actually renders — the audiences it
 * shows, the practice anchor, the entry points it offers — rather than over the table alone.
 */

const PAGES: readonly ShapePage[] = ['clinician', 'practice']

/** The file a sibling-relative link opens. */
const fileOf = (href: string) => href.replace(/^\.\//, '')

const SERVER_TEST = readFileSync(
  fileURLToPath(new URL('../../../../server/src/test/kotlin/com/daymark/companion/ShapeRoutingTest.kt', import.meta.url)),
  'utf8',
)

/**
 * One of the server suite's tables of which shapes switch something on, read out of its source:
 * `SERVED_IN` for pages and `GROUP_ON_IN` for route groups, each asserted shape by shape against
 * what the server answers.
 */
function serverTable(name: string, key: string): Map<string, Set<ShapeId>> {
  const start = SERVER_TEST.indexOf(`val ${name} = mapOf(`)
  const block = start < 0 ? '' : SERVER_TEST.slice(start, SERVER_TEST.indexOf('\n\n', start))
  const table = new Map<string, Set<ShapeId>>()
  for (const m of block.matchAll(new RegExp(`${key} to setOf\\(([A-Z, ]*)\\)`, 'g'))) {
    table.set(m[1]!, new Set(m[2]!.split(',').map((s) => s.trim().toLowerCase() as ShapeId)))
  }
  return table
}

const SERVED_IN = serverTable('SERVED_IN', '"([a-z]+\\.html)"')
const GROUP_ON_IN = serverTable('GROUP_ON_IN', 'Group\\.([A-Z_]+)')

/** The server's name for the group of routes that comes with each page. */
const PAGE_GROUP: Record<ShapePage, string> = { clinician: 'CLINICIAN', practice: 'PRACTICE' }

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

describe('a page and its routes are switched together, so one table decides both', () => {
  it('reads the server suite’s table of route groups, and finds the two a shape can switch off', () => {
    expect([...GROUP_ON_IN.keys()].sort()).toEqual(['CLINICIAN', 'PRACTICE'])
  })

  it('agrees with it: each page’s shapes are the shapes that switch its routes on', () => {
    for (const page of PAGES) {
      expect(GROUP_ON_IN.get(PAGE_GROUP[page]), page).toEqual(new Set(SHAPES_SERVING[page]))
      expect(SERVED_IN.get(PAGE_FILE[page]), page).toEqual(GROUP_ON_IN.get(PAGE_GROUP[page]))
    }
    // Control: a planted table offering the clinician's routes on solo is seen to disagree.
    const planted = new Set<ShapeId>(['solo', ...SHAPES_SERVING.clinician])
    expect(GROUP_ON_IN.get('CLINICIAN')).not.toEqual(planted)
  })
})

describe('the entry points the owner’s page offers, shape by shape', () => {
  /** The server's route group each offered entry point calls, where a shape can switch it off. */
  const groupsCalled = (published: ShapeId | null) =>
    shownRoutes(published).flatMap((r) => (r.servedWith === null ? [] : [[r.id, PAGE_GROUP[r.servedWith]] as const]))

  it('calls only route groups the published shape switches on', () => {
    // Non-vacuity: with nothing published the owner console is offered, and it calls a group.
    expect(groupsCalled(null)).toEqual([['owner', 'CLINICIAN']])
    for (const shape of SHAPE_IDS) {
      for (const [id, group] of groupsCalled(shape)) {
        expect(GROUP_ON_IN.get(group), `${shape} offers ${id}`).toContain(shape)
      }
    }
  })

  it('withholds nothing whose routes the published shape switches on', () => {
    const gated = OWNER_ROUTES.filter((r) => r.servedWith !== null)
    expect(gated.map((r) => r.id)).toEqual(['owner'])
    for (const shape of SHAPE_IDS) {
      for (const r of gated) {
        const on = GROUP_ON_IN.get(PAGE_GROUP[r.servedWith!])!.has(shape)
        expect(offersRoute(r.id, shape), `${shape}: ${r.id}`).toBe(on)
      }
    }
  })
})

describe('nothing on a published solo page points at the owner console it withholds', () => {
  const OWNER_CONSOLE = /\bowner console\b/i
  /** The console, or the tab inside it that holds the recovery email. */
  const CONSOLE_OR_ITS_TAB = /\bowner console\b|\bnotifications?\b/i

  /** Every sentence and label the "Recover access" card shows in one state. */
  function recoverCardWords(published: ShapeId, connected: boolean, registered: string | null): string[] {
    const view = recoverCardView({ ownerConsoleOffered: offersRoute('owner', published), connected, registered })
    const setup = view.setup
    return [
      ...(view.summary ? [view.summary] : []),
      BEFORE_YOU_NEED_IT,
      ...(setup.kind === 'register'
        ? [setup.lede, EMAIL_LABEL, REGISTER, REMOVE, ...(setup.registered ? [registeredStatement(setup.registered)] : [])]
        : [setup.text, setup.link.label]),
      view.lostHeading,
      ...view.lostLede,
      EMAIL_MISSING,
      LOAD_FAILED,
      SAVE_FAILED,
    ]
  }

  /** The words the owner's page renders from these modules for one published shape. */
  function wordsFor(published: ShapeId) {
    const shape = shapeById(published)
    return [
      ...shownRoutes(published).flatMap((r) => [r.label, r.blurb]),
      ...shownAudiences({ adminLink: true, published }).flatMap((a) => [
        a.question,
        a.who,
        a.entryCondition,
        a.linkLabel ?? '',
      ]),
      compactSummary(published),
      shape.label,
      shape.arrangement,
      shape.summary,
      shape.buildNote,
      opensOnStatement(published),
      fileIsAStandIn(offersRoute('owner', published)),
      ...recoverCardWords(published, false, null),
      ...recoverCardWords(published, true, null),
      ...recoverCardWords(published, true, 'someone@example.org'),
    ]
  }

  it('says nothing about the owner console on solo', () => {
    // Control: on paired the same words do name it — its card, and the paragraph about the key
    // file — so the detector and the word list both see it before it is asserted absent.
    expect(wordsFor('paired').filter((w) => OWNER_CONSOLE.test(w)).length).toBeGreaterThanOrEqual(2)
    expect(wordsFor('solo').filter((w) => OWNER_CONSOLE.test(w))).toEqual([])
  })

  it('nor, on the "Recover access" card, about the console’s Notifications tab', () => {
    // Control: the paired card points at the tab by name, and the retired line did too.
    expect(recoverCardWords('paired', false, null).filter((w) => CONSOLE_OR_ITS_TAB.test(w)).length).toBeGreaterThanOrEqual(2)
    expect('Enter the email you registered for notifications.').toMatch(CONSOLE_OR_ITS_TAB)
    for (const [connected, registered] of [[false, null], [true, null], [true, 'someone@example.org']] as const) {
      expect(recoverCardWords('solo', connected, registered).filter((w) => CONSOLE_OR_ITS_TAB.test(w))).toEqual([])
    }
  })

  it('drops only the sentence about the console from the key file paragraph', () => {
    const sentence =
      'It is also what the owner console opens with: that screen asks for this file and one of ' +
      'these two secrets every visit, because it keeps nothing between them. '
    expect(FILE_IS_A_STAND_IN).toContain(sentence)
    expect(FILE_IS_A_STAND_IN.replace(sentence, '')).toBe(FILE_IS_A_STAND_IN_WITHOUT_OWNER_CONSOLE)
    expect(fileIsAStandIn(true)).toBe(FILE_IS_A_STAND_IN)
    expect(fileIsAStandIn(false)).toBe(FILE_IS_A_STAND_IN_WITHOUT_OWNER_CONSOLE)
  })

  it('hands the owner-console rule down to the paragraph, from App.svelte to the flow that renders it', () => {
    const read = (rel: string) =>
      readFileSync(fileURLToPath(new URL(rel, import.meta.url)), 'utf8')
        .replace(/<!--[\s\S]*?-->/g, ' ')
        .replace(/\/\*[\s\S]*?\*\//g, ' ')
    const app = read('../../App.svelte')
    expect(app).toContain("const ownerConsoleOffered = $derived(offersRoute('owner', published))")
    // The sync card's tag carries the rule; the tag matcher is mustache-aware, because the
    // `onconnected={(c) => …}` handler beside it carries a `>` of its own.
    const syncTag = app.match(/<SyncPanel\b(?:[^>{]|\{[^{}]*\})*>/)?.[0] ?? ''
    expect(syncTag).toContain('onload={loadData}') // the whole tag was read
    expect(syncTag).toContain('{ownerConsoleOffered}')
    expect(read('../components/SyncPanel.svelte')).toContain('<RecoveryPanel {ownerConsoleOffered} />')
    expect(read('../components/recovery/RecoveryPanel.svelte')).toContain('<UseCodeFlow {ownerConsoleOffered} />')
    const flow = read('../components/recovery/UseCodeFlow.svelte')
    expect(flow).toContain('{fileIsAStandIn(ownerConsoleOffered)}')
    // The paragraph as it was, rendered whole whatever the shape, is seen and is gone.
    const WHOLE = /\{FILE_IS_A_STAND_IN\}/
    expect('<p class="para small">{FILE_IS_A_STAND_IN}</p>').toMatch(WHOLE)
    expect(flow).not.toMatch(WHOLE)
  })
})
