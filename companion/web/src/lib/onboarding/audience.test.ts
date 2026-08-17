import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import * as onboarding from './audience'
import {
  ADMIN_NOT_LINKED,
  AUDIENCES,
  COMPACT_SUMMARY,
  GROUP_HEADING,
  LABELS,
  PROBES_ARE_PUBLIC,
  GROUP_NOTE,
  GROUP_ORDER,
  NEEDS_LABEL,
  ORIENTATION_LEDE,
  ORIENTATION_PROBES,
  ORIENTATION_STORAGE_KEY,
  ORIENTATION_TITLE,
  ORIENTATION_VERSION,
  OWNER_ROUTES,
  REACH_SILENT_ON,
  REACH_WORD,
  STORAGE_REFUSED,
  WHAT_IS_STORED,
  WHAT_THIS_PAGE_IS,
  WHY_SO_FEW_CHECKS,
  forgetDismissal,
  orientationEndpoints,
  orientationView,
  rankOwnerRoutes,
  readDismissedVersion,
  readServerReach,
  rememberDismissal,
  routeNoteFor,
  showsReachWhenCompact,
  type OrientationStorage,
  type OwnerRoute,
  type OwnerRouteId,
  type ReachState,
  type RouteGroup,
} from './audience'
import { ENDPOINTS, readProbe, type ProbeId, type ProbeReading, type ProbeResponse } from '../admin/health'

/*
 * ═══════════════════════════════════════════════════════════════════════════════════════════
 * WHAT THIS SUITE IS FOR
 * ═══════════════════════════════════════════════════════════════════════════════════════════
 *
 * Most of what the orientation screen promises is an ABSENCE — no configuration checklist, no
 * reassurance, no green tick, no probe that touches an authenticated route, no stored record of
 * who the reader is. An absence is the easiest thing in the world to test vacuously: a regex that
 * never matched, a corpus that turned out empty, an allowlist that allowed everything.
 *
 * So the convention from lib/admin/health.test.ts is kept: EVERY absence assertion is preceded,
 * in the same test, by a presence assertion. The detector is shown catching a planted example
 * before it is turned loose on the module, and every list is shown non-empty before it is
 * filtered. Where the claim is "X is not there", the test first proves it would have seen X.
 *
 * The one cross-file assertion — that the route ids really are App.svelte's `Source` union — reads
 * App.svelte rather than restating it, because a hand-copied list agrees with itself forever.
 */

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   Fixtures.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

const response = (status: number, body: string): ProbeResponse => ({ kind: 'response', status, body })
const transport = (error: string): ProbeResponse => ({ kind: 'transport', error })

const OK = '{"ok":true}'
const NOT_OK = '{"ok":false}'

/** Readings built through the real readProbe, so this suite cannot invent a ProbeReading shape. */
const reading = (id: ProbeId, res: ProbeResponse): ProbeReading => readProbe(id, res)

const bothAnswering = (): ProbeReading[] => [
  reading('liveness', response(200, OK)),
  reading('writability', response(200, OK)),
]

/** A Storage stand-in with the entries visible, so a test can assert on what was written. */
function memoryStorage(initial: Record<string, string> = {}) {
  const entries: Record<string, string> = { ...initial }
  const store: OrientationStorage & { entries: Record<string, string> } = {
    entries,
    getItem: (k) => (k in entries ? entries[k]! : null),
    setItem: (k, v) => {
      entries[k] = v
    },
    removeItem: (k) => {
      delete entries[k]
    },
  }
  return store
}

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   The suite has a subject.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('the suite has a subject', () => {
  it('the module exports the catalogues every assertion below filters', () => {
    // Guard the guards. Nearly every test here is a filter or a lookup over one of these; if a
    // catalogue silently emptied, the filters would all pass while checking nothing.
    expect(AUDIENCES.length).toBe(3)
    expect(OWNER_ROUTES.length).toBe(6)
    expect(GROUP_ORDER.length).toBe(2)
    expect(ORIENTATION_PROBES.length).toBe(2)
    expect(ENDPOINTS.length).toBe(3) // the module reuses admin/health's catalogue, not a copy
    expect(Object.keys(REACH_WORD).length).toBe(5)
  })
})

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   1. The three audiences.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('the three audiences', () => {
  it('the owner comes first, on this page, and the other two are elsewhere', () => {
    expect(AUDIENCES.map((a) => a.id)).toEqual(['owner', 'clinician', 'operator'])

    const owner = AUDIENCES[0]!
    // The owner's route is the default and is NOT a link: they are already on it. A link here
    // would imply this page is somewhere else, which is the confusion the screen exists to end.
    expect(owner.href).toBeNull()

    // The other two are separate pages, and the hrefs are sibling-relative so they survive being
    // served under a base path.
    expect(AUDIENCES.find((a) => a.id === 'clinician')!.href).toBe('./therapist.html')
    expect(AUDIENCES.find((a) => a.id === 'operator')!.href).toBe('./admin.html')
  })

  it('a linked audience has something to call the link, and the owner has none', () => {
    for (const a of AUDIENCES) {
      // Paired, so a link can never render as "Open null" and a card for the page you are on can
      // never sprout a link to itself.
      expect(a.href === null, `${a.id} href/linkLabel disagree`).toBe(a.linkLabel === null)
      if (a.linkLabel !== null) expect(a.linkLabel.length, a.id).toBeGreaterThan(8)
    }
    expect(AUDIENCES.filter((a) => a.linkLabel !== null).length).toBe(2)
  })

  it('every audience says who it is and what it needs before clicking', () => {
    for (const a of AUDIENCES) {
      expect(a.question.length, a.id).toBeGreaterThan(20)
      expect(a.who.length, a.id).toBeGreaterThan(40)
      expect(a.entryCondition.length, a.id).toBeGreaterThan(40)
      // First person: the reader recognises themselves, rather than decoding a feature name.
      expect(a.question.startsWith('I '), a.id).toBe(true)
    }
  })

  it('the clinician is told they need an invitation, here rather than after signing in', () => {
    const clinician = AUDIENCES.find((a) => a.id === 'clinician')!
    // The whole point of naming this audience: an uninvited clinician learns it on this screen.
    expect(clinician.entryCondition).toContain('invitation')
    expect(clinician.entryCondition).toContain('no sign-up')
  })

  it('the operator is told the console holds no credential, in as many words', () => {
    const operator = AUDIENCES.find((a) => a.id === 'operator')!
    // admin.html mounts AdminConsole with no credential (src/admin.ts) and this build defines no
    // administrator identity (Config.kt). Papering over that on a page anyone can open would be
    // the worst available combination: a link, plus the impression it is guarded.
    expect(operator.entryCondition).toContain('no credential')
    expect(operator.entryCondition).toContain('anyone who can reach it can open it')
    // And it must not imply the console shows anyone's data, because it shows none.
    expect(operator.who).toContain('no names')
  })
})

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   2. The owner's six entry points.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('the owner routes', () => {
  const APP = fileURLToPath(new URL('../../App.svelte', import.meta.url))

  it('the ids are exactly App.svelte’s Source union, read from App.svelte', () => {
    // A restated list agrees with itself forever. This reads the real declaration, so renaming a
    // tab in App.svelte without touching this model fails here instead of rendering a dead button.
    const src = readFileSync(APP, 'utf8')
    const decl = /type Source = ([^\n]+)/.exec(src)
    expect(decl, 'App.svelte no longer declares `type Source =` — this cross-check went blind').not.toBeNull()

    const declared = [...decl![1]!.matchAll(/'([a-z]+)'/g)].map((m) => m[1]!)
    // Non-vacuity: the extraction really pulled ids out, rather than matching an empty set.
    expect(declared.length).toBe(6)
    expect(declared).toContain('file')

    expect([...OWNER_ROUTES.map((r) => r.id)].sort()).toEqual([...declared].sort())
  })

  it('every route says what it is in a phrase, not a bare verb', () => {
    /*
     * A MINIMUM WORD COUNT, NOT A MINIMUM LENGTH — and an upper bound as well.
     *
     * This asserted `blurb.length > 80`, which is a proxy for "is a sentence" that stops being
     * one the moment the goal changes. It did: the maintainer's reaction to the finished screen
     * was "SOOO much text it's just so hard to get it all", and the fix was to cut every blurb to
     * a single line. The floor then failed six perfectly good one-liners for the crime of being
     * short, so the rule was actively defending the problem.
     *
     * What the rule is FOR is that a blurb adds meaning the label does not already carry — the
     * original sin was "Build a tool", which told a reader nothing. So: several words, a real
     * sentence, and not a restatement of the label. Plus a ceiling, because on this screen too
     * long is now the likelier failure and nothing was watching for it.
     */
    for (const r of OWNER_ROUTES) {
      expect(r.label.length, r.id).toBeGreaterThan(12)
      const words = r.blurb.trim().split(/\s+/).length
      expect(words, `${r.id}: blurb is too terse to add anything`).toBeGreaterThanOrEqual(6)
      expect(words, `${r.id}: blurb is a paragraph; this screen is a menu`).toBeLessThanOrEqual(14)
      expect(r.blurb.trim().endsWith('.'), `${r.id}: blurb is not a sentence`).toBe(true)
      expect(
        r.blurb.toLowerCase().includes(r.label.toLowerCase()),
        `${r.id}: blurb only repeats the label`,
      ).toBe(false)
      expect(NEEDS_LABEL[r.needs], r.id).toBeTruthy()
    }
  })

  it('arriving beats occasional, and the two arrivals are the backup and the server', () => {
    const arrive = OWNER_ROUTES.filter((r) => r.group === 'arrive').map((r) => r.id)
    const occasional = OWNER_ROUTES.filter((r) => r.group === 'occasional').map((r) => r.id)

    // The ranking the brief asked for: opening a backup and connecting to sync are what someone
    // arrives to do; building a tool and recovering access are occasional.
    expect(arrive).toContain('file')
    expect(arrive).toContain('sync')
    expect(occasional).toContain('build')
    expect(occasional).toContain('recover')

    const byId = new Map(OWNER_ROUTES.map((r) => [r.id, r]))
    expect(byId.get('file')!.order).toBeLessThan(byId.get('sync')!.order)
  })

  it('needs is right per route, because the reach note hangs off it', () => {
    const needs = Object.fromEntries(OWNER_ROUTES.map((r) => [r.id, r.needs])) as Record<OwnerRouteId, string>
    expect(needs).toEqual({
      file: 'this-browser',
      assess: 'this-browser',
      build: 'this-browser',
      sync: 'the-server',
      owner: 'the-server',
      recover: 'the-server',
    })
  })
})

describe('rankOwnerRoutes', () => {
  it('returns the groups in order, with every route exactly once', () => {
    const ranked = rankOwnerRoutes()
    expect(ranked.map((g) => g.group)).toEqual([...GROUP_ORDER])

    const flattened = ranked.flatMap((g) => g.routes.map((r) => r.id))
    expect(flattened.length).toBe(OWNER_ROUTES.length)
    expect(new Set(flattened).size).toBe(OWNER_ROUTES.length)

    for (const g of ranked) {
      expect(g.heading).toBe(GROUP_HEADING[g.group])
      expect(g.note).toBe(GROUP_NOTE[g.group])
    }
  })

  it('sorts within a group by order, and breaks ties by id so the result is total', () => {
    for (const g of rankOwnerRoutes()) {
      const orders = g.routes.map((r) => r.order)
      expect([...orders].sort((a, b) => a - b), g.group).toEqual(orders)
    }

    // Two routes at the same order must not depend on array position. Proven with a planted pair
    // written in the wrong sequence, so a missing tiebreak would surface here rather than as an
    // occasional reshuffle in the UI.
    const tied: OwnerRoute[] = [
      { ...OWNER_ROUTES[0]!, id: 'sync', order: 1 },
      { ...OWNER_ROUTES[0]!, id: 'file', order: 1 },
    ]
    expect(rankOwnerRoutes(tied)[0]!.routes.map((r) => r.id)).toEqual(['file', 'sync'])
  })

  it('drops a group with nothing in it rather than rendering an empty heading', () => {
    const onlyArrive = OWNER_ROUTES.filter((r) => r.group === 'arrive')
    // Non-vacuity first: the full catalogue really does produce both groups, so the single-group
    // result below is the filter working and not the function only ever returning one.
    expect(rankOwnerRoutes().length).toBe(2)
    const ranked = rankOwnerRoutes(onlyArrive)
    expect(ranked.length).toBe(1)
    expect(ranked[0]!.group).toBe('arrive')
  })

  it('does not reorder the exported catalogue by rendering it', () => {
    const before = OWNER_ROUTES.map((r) => r.id)
    rankOwnerRoutes()
    rankOwnerRoutes(OWNER_ROUTES)
    expect(OWNER_ROUTES.map((r) => r.id)).toEqual(before)
  })
})

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   3. THE DISCLOSURE RULE. The load-bearing block.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('the screen only asks what the server already tells anyone', () => {
  it('every permitted probe is a real, unauthenticated endpoint', () => {
    const byId = new Map(ENDPOINTS.map((e) => [e.id, e]))
    for (const id of ORIENTATION_PROBES) {
      const ep = byId.get(id)
      expect(ep, `${id} is not an endpoint admin/health.ts knows about`).toBeDefined()
      // The whole rule in one assertion: nothing here may require a credential, because this page
      // has none and the person reading it may be a stranger.
      expect(ep!.authenticated, `${id} is authenticated`).toBe(false)
    }
    expect(orientationEndpoints().map((e) => e.path)).toEqual(['/healthz', '/readyz'])
  })

  it('the capability probe is excluded, and the exclusion is a real exclusion', () => {
    // Non-vacuity: /v1/config exists, is unauthenticated, and would therefore have passed the test
    // above. It is left out on a separate judgement — it publishes a mail-configuration flag, and
    // an unauthenticated page is not where a stranger learns which delivery paths a deployment
    // has. If it ever gets added here, this fails.
    const capability = ENDPOINTS.find((e) => e.id === 'capability')!
    expect(capability.path).toBe('/v1/config')
    expect(capability.authenticated).toBe(false)
    expect(ORIENTATION_PROBES).not.toContain('capability')
  })

  it('nothing under /v1 is probed, because those paths run the auth guard', () => {
    // With sync configured, every /v1 sync path goes through AuthGuard.authorize(); a request with
    // no bearer token lands on BAD_TOKEN, which calls recordFailure() against the caller's source
    // identity. A probe on page load would burn a failed-auth attempt per visit — and behind a
    // proxy with no trusted-proxy configuration every visitor shares one source, so a first screen
    // that probed sync could walk the owner into their own lockout.
    //
    // Non-vacuity: ENDPOINTS really does contain a /v1 path, so this is a filter with something to
    // catch rather than a test of an empty set.
    expect(ENDPOINTS.some((e) => e.path.startsWith('/v1'))).toBe(true)
    expect(orientationEndpoints().some((e) => e.path.startsWith('/v1'))).toBe(false)
  })
})

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   4. Folding the readings into one statement.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('readServerReach', () => {
  it('says nothing at all before anything has been asked', () => {
    const reach = readServerReach([])
    expect(reach.state).toBe('not-checked')
    expect(reach.routeNote).toBeNull()
  })

  it('two answers give the least interesting state, and it still says what it is silent on', () => {
    const reach = readServerReach(bothAnswering())
    expect(reach.state).toBe('answering')
    // No reassurance badge on the routes. A route expected to work gets nothing, because a mark
    // saying "this one is fine" is a claim this page cannot make.
    expect(reach.routeNote).toBeNull()
    // ...and the "and that is the whole of it" clause is the point of the state.
    expect(reach.silentOn).toContain('whether sync is set up')
    expect(reach.silentOn).toContain('access token')
  })

  it('a refused write is reported, without a reason it does not have', () => {
    const reach = readServerReach([
      reading('liveness', response(200, OK)),
      reading('writability', response(503, NOT_OK)),
    ])
    expect(reach.state).toBe('refusing')
    expect(reach.routeNote).not.toBeNull()
    expect(reach.silentOn).toContain('carries no reason')
  })

  it('no answer reads as a fact about this browser, and names the offline case', () => {
    const reach = readServerReach([
      reading('liveness', transport('TypeError: Failed to fetch')),
      reading('writability', transport('TypeError: Failed to fetch')),
    ])
    expect(reach.state).toBe('no-answer')
    // Opening index.html from disk is a supported Phase-0 use. Reporting it as a fault would
    // make the most private way to use this product look broken.
    expect(reach.statement).toContain('from a file on your own disk')
    expect(reach.silentOn).toContain('not about the server')
  })

  it('liveness leads: a missing or odd liveness answer settles it before readiness is read', () => {
    // Non-vacuity: with liveness responding, readiness is what decides the outcome.
    expect(readServerReach(bothAnswering()).state).toBe('answering')

    // Liveness unreachable wins even when readiness somehow answered.
    expect(
      readServerReach([
        reading('liveness', transport('down')),
        reading('writability', response(200, OK)),
      ]).state,
    ).toBe('no-answer')

    // An undocumented liveness answer is its own outcome, not folded into a failure.
    expect(
      readServerReach([
        reading('liveness', response(502, 'gateway')),
        reading('writability', response(200, OK)),
      ]).state,
    ).toBe('undocumented')

    // Liveness answered, readiness did not: not a shape this build produces, so it is not claimed
    // to be one.
    expect(readServerReach([reading('liveness', response(200, OK))]).state).toBe('undocumented')
  })

  it('is total: every id crossed with every response shape produces a statement', () => {
    const shapes: ProbeResponse[] = [
      response(200, OK),
      response(503, NOT_OK),
      response(502, 'gateway'),
      response(200, 'not json'),
      transport('TypeError: Failed to fetch'),
    ]
    let cases = 0
    for (const live of shapes) {
      for (const write of shapes) {
        const reach = readServerReach([
          reading('liveness', live),
          reading('writability', write),
        ])
        cases++
        expect(REACH_WORD[reach.state], 'state has no chip word').toBeTruthy()
        expect(reach.statement.length).toBeGreaterThan(20)
        expect(reach.silentOn.length).toBeGreaterThan(20)
      }
    }
    expect(cases).toBe(25) // the matrix really was walked
  })

  it('every state has a word, a statement and a limitation', () => {
    const states: ReachState[] = ['not-checked', 'answering', 'refusing', 'no-answer', 'undocumented']
    for (const s of states) {
      expect(REACH_WORD[s], s).toBeTruthy()
      expect(REACH_SILENT_ON[s]?.length, s).toBeGreaterThan(20)
    }
  })
})

describe('routeNoteFor', () => {
  it('marks the server routes when there is something to say', () => {
    const reach = readServerReach([
      reading('liveness', transport('down')),
      reading('writability', transport('down')),
    ])
    const sync = OWNER_ROUTES.find((r) => r.id === 'sync')!
    // Presence first: the detector is live, so the absence below is a decision rather than a bug.
    expect(routeNoteFor(sync, reach)).toBe(reach.routeNote)
    expect(routeNoteFor(sync, reach)).not.toBeNull()
  })

  it('never marks a route that runs in this browser, however bad the reach is', () => {
    const file = OWNER_ROUTES.find((r) => r.id === 'file')!
    const assess = OWNER_ROUTES.find((r) => r.id === 'assess')!
    for (const readings of [
      [reading('liveness', transport('down')), reading('writability', transport('down'))],
      [reading('liveness', response(200, OK)), reading('writability', response(503, NOT_OK))],
      [reading('liveness', response(502, 'gateway')), reading('writability', response(502, 'x'))],
    ]) {
      const reach = readServerReach(readings)
      expect(reach.routeNote, 'the fixture is meant to produce a note').not.toBeNull()
      // Opening a backup file works on a train. Marking it impaired because a fetch failed would
      // be the screen lying about its most reliable feature.
      expect(routeNoteFor(file, reach), file.id).toBeNull()
      expect(routeNoteFor(assess, reach), assess.id).toBeNull()
    }
  })

  it('marks nothing at all when both endpoints answered', () => {
    const reach = readServerReach(bothAnswering())
    for (const r of OWNER_ROUTES) expect(routeNoteFor(r, reach), r.id).toBeNull()
  })
})

describe('showsReachWhenCompact', () => {
  it('keeps bad news after a dismissal and drops the uninteresting states', () => {
    const bad: ProbeReading[][] = [
      [reading('liveness', transport('down'))],
      [reading('liveness', response(200, OK)), reading('writability', response(503, NOT_OK))],
      [reading('liveness', response(502, 'x')), reading('writability', response(502, 'x'))],
    ]
    for (const readings of bad) {
      expect(showsReachWhenCompact(readServerReach(readings))).toBe(true)
    }
    // What the person dismissed was the explanation of who the three surfaces are for, not the
    // state of their server — so only these two collapse with it.
    expect(showsReachWhenCompact(readServerReach(bothAnswering()))).toBe(false)
    expect(showsReachWhenCompact(readServerReach([]))).toBe(false)
  })
})

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   5. The return visit, and exactly what is left on the machine.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('the remembered dismissal', () => {
  it('round-trips, and writes ONE entry holding ONE integer', () => {
    const storage = memoryStorage()
    expect(readDismissedVersion(storage)).toBeNull()
    expect(orientationView(readDismissedVersion(storage))).toBe('full')

    expect(rememberDismissal(storage)).toBe(true)
    expect(orientationView(readDismissedVersion(storage))).toBe('compact')

    // The privacy claim, asserted rather than described. One key, one value, and the value is the
    // version and nothing else: no audience, no route, and deliberately no timestamp — when
    // someone last opened Daymark is information about their care on a shared machine.
    expect(Object.keys(storage.entries)).toEqual([ORIENTATION_STORAGE_KEY])
    expect(storage.entries[ORIENTATION_STORAGE_KEY]).toBe(String(ORIENTATION_VERSION))
    expect(JSON.stringify(storage.entries)).not.toMatch(/owner|clinician|operator|therapist|admin/i)
  })

  it('forgetting removes the entry rather than blanking it', () => {
    const storage = memoryStorage()
    rememberDismissal(storage)
    expect(Object.keys(storage.entries).length).toBe(1) // presence, before the absence
    expect(forgetDismissal(storage)).toBe(true)
    // An entry holding 0 would still announce that this profile has opened Daymark. The existence
    // of the record is the half being cleared.
    expect(Object.keys(storage.entries)).toEqual([])
    expect(orientationView(readDismissedVersion(storage))).toBe('full')
  })

  it('fails OPEN on every kind of unusable storage', () => {
    // Non-vacuity: a working storage really does yield 'compact', so each 'full' below is the
    // fallback firing and not the function being incapable of hiding the panel.
    expect(orientationView(readDismissedVersion(memoryStorage({ [ORIENTATION_STORAGE_KEY]: '1' })))).toBe('compact')

    const throwsOnRead: OrientationStorage = {
      getItem: () => {
        throw new Error('blocked')
      },
      setItem: () => {},
    }
    const throwsOnWrite: OrientationStorage = {
      getItem: () => null,
      setItem: () => {
        throw new Error('QuotaExceededError')
      },
    }

    // Unlike pinStore.ts, which throws because falling back to "nothing pinned" would restore the
    // hole it exists to close, nothing here is a security control. Unreadable storage means the
    // person sees the orientation again — mildly annoying, never wrong — and never an exception
    // reaching the page.
    expect(readDismissedVersion(null)).toBeNull()
    expect(readDismissedVersion(throwsOnRead)).toBeNull()
    expect(orientationView(readDismissedVersion(null))).toBe('full')

    // A refused write is reported to the caller as false, so the screen can say so, and never as
    // a thrown error.
    expect(rememberDismissal(null)).toBe(false)
    expect(rememberDismissal(throwsOnWrite)).toBe(false)
    expect(() => rememberDismissal(throwsOnWrite)).not.toThrow()

    // Storage that cannot remove must not report a deletion that did not happen.
    expect(forgetDismissal(null)).toBe(false)
    expect(forgetDismissal({ getItem: () => null, setItem: () => {} })).toBe(false)
    expect(
      forgetDismissal({
        getItem: () => null,
        setItem: () => {},
        removeItem: () => {
          throw new Error('blocked')
        },
      }),
    ).toBe(false)
  })

  it('refuses to act on a value this build did not write', () => {
    // Presence: a value it DID write is read back.
    expect(readDismissedVersion(memoryStorage({ [ORIENTATION_STORAGE_KEY]: '2' }))).toBe(2)
    expect(readDismissedVersion(memoryStorage({ [ORIENTATION_STORAGE_KEY]: ' 1 ' }))).toBe(1)

    // Everything else is null — hiding the orientation on the strength of "true", "" or a float
    // would be acting on something whose meaning is unknown.
    for (const raw of ['', ' ', 'true', 'null', '1.5', '-1', '0x1', '1e3', 'v1', '{"v":1}', '99999999999999999999']) {
      expect(readDismissedVersion(memoryStorage({ [ORIENTATION_STORAGE_KEY]: raw })), raw).toBeNull()
    }
    // A different key is not this key.
    expect(readDismissedVersion(memoryStorage({ 'daymark.orientation.dismissed.v0': '1' }))).toBeNull()
  })

  it('a newer stored version does not force the panel back open', () => {
    expect(orientationView(0, 1)).toBe('full')
    expect(orientationView(1, 1)).toBe('compact')
    // A downgraded deployment, or a profile that met a newer build first: the person has already
    // read a superset, so reopening the panel would be a regression dressed as caution.
    expect(orientationView(2, 1)).toBe('compact')
    // A bumped orientation shows once more, which is the reason this is a number.
    expect(orientationView(1, 2)).toBe('full')
    expect(orientationView(null, 1)).toBe('full')
  })

  it('the sentence shown to the person names the real key and the real value', () => {
    // The copy quotes the storage key and the version. Left unpinned, the sentence and the code
    // drift and the page ends up describing a key it does not write.
    expect(WHAT_IS_STORED).toContain(ORIENTATION_STORAGE_KEY)
    expect(WHAT_IS_STORED).toContain(`the number ${ORIENTATION_VERSION}`)
    expect(WHAT_IS_STORED).toContain('nothing else')
    // And it admits the residual cost rather than claiming there is none.
    expect(WHAT_IS_STORED).toContain('machine you share')
  })
})

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   6. The copy. Every detector is proven on a planted sample first.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

/**
 * Every string this module authors: exported constants, deep, plus the view models it builds
 * across every input shape. There is no channel here for text the module did not write — unlike
 * admin/health.ts, this module never echoes a server's bytes — so the corpus is total.
 */
function authoredStrings(): { path: string; text: string }[] {
  const out: { path: string; text: string }[] = []

  const walk = (value: unknown, path: string) => {
    if (typeof value === 'string') out.push({ path, text: value })
    else if (Array.isArray(value)) value.forEach((v, i) => walk(v, `${path}[${i}]`))
    else if (value && typeof value === 'object') {
      for (const [k, v] of Object.entries(value)) walk(v, `${path}.${k}`)
    }
  }

  for (const [name, value] of Object.entries(onboarding)) {
    if (typeof value === 'function') continue
    walk(value, name)
  }

  const shapes: ProbeResponse[] = [
    response(200, OK),
    response(503, NOT_OK),
    response(502, 'gateway'),
    transport('TypeError: Failed to fetch'),
  ]
  for (const live of shapes) {
    for (const write of shapes) {
      walk(readServerReach([reading('liveness', live), reading('writability', write)]), 'reach')
    }
  }
  walk(readServerReach([]), 'reach.empty')
  walk(rankOwnerRoutes(), 'ranked')

  /*
   * AND THE COMPONENTS' OWN MARKUP.
   *
   * This corpus used to be `Object.entries(onboarding)` — module exports only — while both this
   * module and Orientation.svelte carried comments asserting that the whole screen's register was
   * checked. It was not. Review planted
   *
   *     <p class="lede">All good — your server is healthy and you are all set. Setup 100% complete.</p>
   *
   * directly into the markup and all 820 tests passed: every prohibition below applied to strings
   * the component might never render, and to none of the ones it authored itself. The arrangement
   * was right and the guard was missing, which is the worse of the two ways to be wrong, because
   * the comment said it was done.
   *
   * So the rendered text of each component is read off disk and appended. Extraction is
   * deliberately crude — strip <script> and <style>, strip tags, drop mustaches — because the
   * point is not to parse Svelte but to catch prose a person typed into the template. A tag
   * attribute is not prose and is excluded; `aria-label` and `title` are, and are pulled out
   * separately.
   */
  for (const rel of COMPONENTS) {
    const src = readFileSync(fileURLToPath(new URL(rel, import.meta.url)), 'utf8')
    const body = src
      .replace(/<script[\s\S]*?<\/script>/g, ' ')
      .replace(/<style[\s\S]*?<\/style>/g, ' ')
      .replace(/<!--[\s\S]*?-->/g, ' ')
    for (const m of body.matchAll(/\b(?:aria-label|title|alt|placeholder)="([^"{}]+)"/g)) {
      out.push({ path: `${rel}@attr`, text: m[1]! })
    }
    const prose = body
      .replace(/\{[^{}]*\}/g, ' ')
      .replace(/<[^>]*>/g, ' ')
      .replace(/&[a-z]+;/g, ' ')
      .replace(/\s+/g, ' ')
      .trim()
    if (prose) out.push({ path: `${rel}@text`, text: prose })
  }

  return out
}

/**
 * Every component that renders this module's screen. Listed rather than globbed: a new component
 * in this directory should have to be added here consciously, and a glob that silently matched
 * nothing would restore exactly the hole this closes.
 */
const COMPONENTS = [
  '../components/onboarding/Orientation.svelte',
  '../components/onboarding/ServerState.svelte',
]

const CORPUS = authoredStrings()

describe('the copy', () => {
  it('the corpus is real and covers the whole module', () => {
    // Non-vacuity for every absence below. If this collapsed, the six tests after it would all
    // pass over nothing.
    expect(CORPUS.length).toBeGreaterThan(60)
    const joined = CORPUS.map((c) => c.text).join(' ')
    expect(joined.length).toBeGreaterThan(6000)
    // Sentences from every section really are in it.
    expect(joined).toContain('I am a clinician someone invited')
    expect(joined).toContain('Open a backup file')
    expect(joined).toContain('configuration checklist')
    expect(joined).toContain('Nothing answered at this address')
  })

  it('claims no health, no pass and no green', () => {
    // NO GREEN, NO SUCCESS (app.css invariant 2). A first screen is exactly where someone reaches
    // for a tick and "All good"; a configured server gets a neutral statement of fact, because the
    // page cannot know that things are fine.
    const CLAIMS_HEALTH =
      /(?<![\w-])(green|success|successful|healthy|health check|all good|all clear|everything is fine|verified|secure|trusted|passed|passing|ok|okay|good to go|you'?re set|all set)(?![\w-])/i

    // The detector detects, on the exact phrases a well-meaning edit would introduce.
    expect(CLAIMS_HEALTH.test('All good!')).toBe(true)
    expect(CLAIMS_HEALTH.test('Server healthy')).toBe(true)
    expect(CLAIMS_HEALTH.test('Everything is fine here')).toBe(true)
    expect(CLAIMS_HEALTH.test('Configuration verified')).toBe(true)
    expect(CLAIMS_HEALTH.test('you are all set')).toBe(true)
    // ...and does not fire on words that merely contain them.
    expect(CLAIMS_HEALTH.test('looking at your own data')).toBe(false)
    expect(CLAIMS_HEALTH.test('a validated instrument')).toBe(false)

    const offenders = CORPUS.filter((c) => CLAIMS_HEALTH.test(c.text)).map((c) => `${c.path}: ${c.text}`)
    expect(offenders).toEqual([])
  })

  it('renders no figure, grade or streak', () => {
    const A_FIGURE = /%|(?<![\w-])(percent|percentage|grade|graded|streak|rating|out of \d)/i
    expect(A_FIGURE.test('87% configured')).toBe(true)
    expect(A_FIGURE.test('Setup grade: B')).toBe(true)
    expect(A_FIGURE.test('4 day streak')).toBe(true)
    expect(A_FIGURE.test('3 out of 5 checks')).toBe(true)
    expect(A_FIGURE.test('scores and bands are descriptive')).toBe(false)

    const offenders = CORPUS.filter((c) => A_FIGURE.test(c.text)).map((c) => `${c.path}: ${c.text}`)
    expect(offenders).toEqual([])
  })

  it('never names a configuration subject the server keeps to itself', () => {
    // THE DISCLOSURE RULE, at the level of words. A diagnostic may only restate what the server
    // already tells an unauthenticated caller; these subjects are told to nobody, so naming one —
    // even to say it is unset — hands a stranger a checklist of which protections are off.
    const WITHHELD =
      /(smtp|trusted[ _-]?prox|\bprox(y|ies)\b|DAYMARK_[A-Z_]+|data director|datadir|free space|disk space|mail (host|server)|outbound mail|environment variable|\bkeyparams\b)/i

    // Every one of these is a sentence that would be tempting and wrong on this screen.
    expect(WITHHELD.test('SMTP is not configured on this server')).toBe(true)
    expect(WITHHELD.test('DAYMARK_TRUSTED_PROXIES is not set')).toBe(true)
    expect(WITHHELD.test('you are behind a proxy with no trusted-proxy list')).toBe(true)
    expect(WITHHELD.test('the data directory has 2 GB free space')).toBe(true)
    expect(WITHHELD.test('set the environment variable to enable this')).toBe(true)
    // ...and the words the screen legitimately does use are not caught.
    expect(WITHHELD.test('Connect to your sync server')).toBe(false)
    expect(WITHHELD.test('its storage would not take a write')).toBe(false)

    const offenders = CORPUS.filter((c) => WITHHELD.test(c.text)).map((c) => `${c.path}: ${c.text}`)
    expect(offenders).toEqual([])
  })

  it('never claims to know who the owner is or that relationships exist', () => {
    const IDENTITY = /(your (therapist|clinician|doctor) is|you have \d+|\d+ (relationship|clinician|therapist)s?\b|logged in as|signed in as)/i
    expect(IDENTITY.test('You have 3 clinicians')).toBe(true)
    expect(IDENTITY.test('Signed in as someone')).toBe(true)
    expect(IDENTITY.test('A clinician you invited has a separate portal')).toBe(false)

    const offenders = CORPUS.filter((c) => IDENTITY.test(c.text)).map((c) => `${c.path}: ${c.text}`)
    expect(offenders).toEqual([])
  })

  it('keeps the register flat: no cheer, no exclamation, no welcome', () => {
    const CHEER = /!|(?<![\w-])(welcome|congrat|awesome|great job|let'?s get started|getting started|hooray|nice work)/i
    expect(CHEER.test('Welcome to Daymark')).toBe(true)
    expect(CHEER.test('Let’s get started'.replace('’', "'"))).toBe(true)
    expect(CHEER.test('Set up in 3 steps!')).toBe(true)
    expect(CHEER.test('This page is a viewer.')).toBe(false)

    const offenders = CORPUS.filter((c) => CHEER.test(c.text)).map((c) => `${c.path}: ${c.text}`)
    expect(offenders).toEqual([])
  })

  it('never addresses the reader as a patient, a case or a user of a medical device', () => {
    const PATIENT = /(?<![\w-])(patient|your case|case file|clinical assessment|diagnos(is|e|ed|tic tool)|treatment plan|medical device|prescription)(?![\w-])/i
    expect(PATIENT.test('your patient record')).toBe(true)
    expect(PATIENT.test('open your case file')).toBe(true)
    expect(PATIENT.test('a diagnosis of anything')).toBe(true)
    expect(PATIENT.test('Self-tracking tools rather than medical assessments')).toBe(false)

    const offenders = CORPUS.filter((c) => PATIENT.test(c.text)).map((c) => `${c.path}: ${c.text}`)
    expect(offenders).toEqual([])
  })

  it('the standing statements are present and say what they are for', () => {
    // These four are the screen's own promises, so their absence is a regression rather than a
    // style change.
    expect(ORIENTATION_TITLE.length).toBeGreaterThan(4)
    expect(ORIENTATION_LEDE).toContain('three different pages')
    // "State what runs where" — and it must stay true of a build that writes a dismissal note.
    expect(WHAT_THIS_PAGE_IS).toContain('runs in your browser')
    expect(WHAT_THIS_PAGE_IS).toContain('never uploaded')
    expect(WHAT_THIS_PAGE_IS).toContain('orientation')
    // The refusal, on the page rather than only in a comment.
    expect(WHY_SO_FEW_CHECKS).toContain('no configuration checklist')
    expect(WHY_SO_FEW_CHECKS).toContain('there is no sign-in')
    expect(WHY_SO_FEW_CHECKS).toContain('does not have one yet')
    expect(STORAGE_REFUSED).toContain('again next time')
    expect(COMPACT_SUMMARY).toContain('separate portal')
    // The disclosure rule, as a checkable fact rather than an assurance: the reader is told which
    // two endpoints were read and that both answer anyone.
    expect(PROBES_ARE_PUBLIC).toContain('/healthz')
    expect(PROBES_ARE_PUBLIC).toContain('/readyz')
    expect(PROBES_ARE_PUBLIC).toContain('no sign-in')
    expect(ADMIN_NOT_LINKED).toContain('separate page')
  })

  it('every word the screen renders lives in this module, so these tests reach all of it', () => {
    // The headings and control labels are here rather than in Orientation.svelte for exactly one
    // reason: the corpus above walks this module. A heading authored in the component would be the
    // one phrase on the page outside every check in this file — which is how admin/health.ts's
    // chip words escaped its own vocabulary rules until they were moved.
    const labels = Object.values(LABELS)
    expect(labels.length).toBeGreaterThanOrEqual(12)
    for (const [name, text] of Object.entries(LABELS)) {
      expect(text.length, name).toBeGreaterThan(2)
      expect(text.trim(), name).toBe(text)
    }
    // And they really are in the corpus the register tests filter.
    const joined = CORPUS.map((c) => c.text)
    for (const text of labels) expect(joined, text).toContain(text)
  })

  it('every group and needs label is filled in', () => {
    const groups: RouteGroup[] = ['arrive', 'occasional']
    for (const g of groups) {
      expect(GROUP_HEADING[g], g).toBeTruthy()
      expect(GROUP_NOTE[g]!.length, g).toBeGreaterThan(40)
    }
    expect(Object.values(NEEDS_LABEL).every((v) => v.length > 5)).toBe(true)
  })
})

describe('regressions: a note about this origin is not a verdict on someone else’s server', () => {
  const allRoutes = rankOwnerRoutes().flatMap((g) => g.routes)
  const serverRoute = allRoutes.find((r) => r.needs === 'the-server')!
  const unreachable = readServerReach([
    reading('liveness', transport('TypeError: Failed to fetch')),
    reading('writability', transport('TypeError: Failed to fetch')),
  ])

  it('says nothing at all when the page was opened from a file', () => {
    // The documented Phase-0 use. A relative fetch from a file: page fails instantly and
    // establishes nothing about any server; reporting it as "nothing answered" sent someone to
    // debug a machine that was fine, or that did not exist.
    expect(routeNoteFor(serverRoute, unreachable, 'local-file')).toBeNull()
    // The control: served, the same reach DOES produce a note, so the null above is the origin
    // rule and not this route never carrying one.
    expect(routeNoteFor(serverRoute, unreachable, 'served')).not.toBeNull()
  })

  it('names the address it is about, so a route pointed elsewhere is not condemned', () => {
    // A bundle on a static host with the sync API elsewhere gets that host's 404 for /healthz.
    // The note is true of the page's origin and must not read as a verdict on the sync server,
    // which is healthy and was never asked.
    const odd = readServerReach([
      reading('liveness', response(200, '<!doctype html>')),
      reading('writability', response(200, '<!doctype html>')),
    ])
    const note = routeNoteFor(serverRoute, odd, 'served')!
    expect(note).toContain('this page’s own address')
    expect(note.toLowerCase()).toContain('somewhere else')
    // And it must not make a bare claim about "the server".
    expect(note).not.toMatch(/^The server (is|was) /)
  })

  it('never attaches a server note to a route that stays in the browser', () => {
    const localRoute = allRoutes.find((r) => r.needs !== 'the-server')!
    expect(routeNoteFor(localRoute, unreachable, 'served')).toBeNull()
  })
})
