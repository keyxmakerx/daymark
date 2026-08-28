import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import * as setup from './shape'
import { existsSync } from 'node:fs'
import {
  ASK_REASON_NOTE,
  CHOICE_HEADING,
  CHOICE_IS_REVERSIBLE,
  CONFIGURATION_IS_NOT_RE_READ,
  CONFIG_FIELD,
  CONFIG_NOT_PUBLISHED_YET,
  CONFIG_PATH,
  CONFIG_SETTING,
  LABELS,
  PRACTICE_CONSOLE_ELSEWHERE,
  PRACTICE_MISSING,
  PRACTICE_OPEN_QUESTION,
  PRACTICE_ROLE_NOTE,
  PRACTICE_SERVER_HAS,
  SETUP_CHOICE_VERSION,
  SETUP_LEDE,
  SETUP_LIMITS,
  SETUP_STORAGE_KEY,
  SETUP_TITLE,
  SHAPES,
  SHAPE_IDS,
  configuredHowToChange,
  configuredStatement,
  configuredUnrecognised,
  decidedShape,
  forgetShape,
  isChangeable,
  isShapeId,
  opensOnStatement,
  primaryLabel,
  readSetupMode,
  readStoredChoice,
  rememberShape,
  resolveSetup,
  shapeById,
  type SetupStorage,
  type ShapeId,
} from './shape'
import { OWNER_ROUTES } from '../onboarding/audience'

/*
 * ═══════════════════════════════════════════════════════════════════════════════════════════
 * WHAT THIS SUITE IS FOR
 * ═══════════════════════════════════════════════════════════════════════════════════════════
 *
 * The first-run entry makes four claims that are all easy to believe and hard to notice breaking:
 *
 *   1. it asks the question exactly once, and a returning person never meets it again;
 *   2. configuration answering it beats every local answer, and the screen says so;
 *   3. a browser that refuses to store anything costs a repeated question and never access;
 *   4. the one shape that is not built says so, in the interface, at the point of choosing it.
 *
 * Three of those four are ABSENCES — no question, no storage, no working console — and an absence
 * is the easiest thing to test vacuously. So the convention from lib/onboarding/audience.test.ts
 * is kept: every absence assertion is preceded, in the same test, by a presence assertion. The
 * detector is shown catching a planted example before it is turned loose, and every list is shown
 * non-empty before it is filtered.
 *
 * The register tests run over MODULE STRINGS AND COMPONENT MARKUP TOGETHER, for the reason
 * audience.test.ts records at length: when its corpus was module exports only, a planted "All
 * good — your server is healthy and you are all set" in the markup passed all 820 tests. The
 * components in this directory are supposed to author no prose at all, and there is a test below
 * that checks exactly that rather than trusting the header comments which say it.
 */

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   Fixtures.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

/** A Storage stand-in with the entries visible, so a test can assert on what was written. */
function memoryStorage(initial: Record<string, string> = {}) {
  const entries: Record<string, string> = { ...initial }
  const store: SetupStorage & { entries: Record<string, string> } = {
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

/** The browser that will not co-operate: every operation throws, as private modes really do. */
const throwingStorage: SetupStorage = {
  getItem() {
    throw new DOMException('The operation is insecure.', 'SecurityError')
  },
  setItem() {
    throw new DOMException('QuotaExceededError', 'QuotaExceededError')
  },
  removeItem() {
    throw new DOMException('The operation is insecure.', 'SecurityError')
  },
}

/** A storage with no removeItem at all — the shape a hand-rolled stand-in usually has. */
const noRemoveStorage: SetupStorage = {
  getItem: () => null,
  setItem: () => undefined,
}

const config = {
  reading: { kind: 'reading' } as const,
  unreachable: { kind: 'unreachable' } as const,
  absent: { kind: 'absent' } as const,
  set: (shape: ShapeId) => ({ kind: 'set', shape }) as const,
  unrecognised: (value: string) => ({ kind: 'unrecognised', value }) as const,
}

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   1. The three shapes.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('the three deployment shapes', () => {
  it('are exactly three, and the catalog agrees with the id list', () => {
    // Non-vacuity for everything below: a catalog that emptied out would satisfy most of the
    // filters in this file without anyone noticing.
    expect(SHAPES.length).toBe(3)
    expect(SHAPES.map((s) => s.id)).toEqual([...SHAPE_IDS])
    expect([...SHAPE_IDS]).toEqual(['solo', 'paired', 'practice'])
    // Declaration order is presentation order, and Solo leading is a decision, not an accident.
    expect(SHAPES[0]!.id).toBe('solo')
  })

  it('each one says who owns the machine, what it gets you, and how it ranks', () => {
    for (const shape of SHAPES) {
      // A phrase, not a bare noun: "Solo" on its own answers nothing, which is the failure the
      // six flat tabs had before lib/onboarding/audience.ts.
      expect(shape.label.split(/\s+/).length, shape.id).toBeGreaterThan(2)
      expect(shape.arrangement.split(/\s+/).length, shape.id).toBeGreaterThan(6)
      expect(shape.summary.split(/\s+/).length, shape.id).toBeGreaterThan(6)
      expect(shape.ranking.split(/\s+/).length, shape.id).toBeGreaterThan(4)
      expect(shape.buildNote.split(/\s+/).length, shape.id).toBeGreaterThan(6)
      // And none of them is a restatement of the label.
      expect(shape.summary, shape.id).not.toBe(shape.label)
    }
  })

  it('ranks Solo as what most people want and Practice as more work for no better backup', () => {
    // The brief for this screen is explicit about both, and an unranked list of three is how
    // somebody ends up standing a clinic server up for themselves.
    expect(shapeById('solo').ranking).toContain('most people')
    expect(shapeById('practice').ranking).toMatch(/more work/i)
    expect(shapeById('practice').ranking).toMatch(/no better/i)
    // Paired is ranked by CASE rather than by popularity — it is the right answer for exactly one
    // situation, and saying "some people want this" would rank nothing.
    expect(shapeById('paired').ranking).toMatch(/one clinician/i)
  })

  it('states which shapes are built and which is a placeholder, as data rather than as prose', () => {
    // `buildState` is the field the interface reads to mark the gap. If it stopped distinguishing
    // them, the practice choice would look as finished as the other two.
    const byState = Object.fromEntries(SHAPES.map((s) => [s.id, s.buildState]))
    expect(byState).toEqual({ solo: 'built', paired: 'built', practice: 'separate-page' })
    // And the one whose surface is elsewhere says so IN WORDS on the choice itself, not only in a
    // flag: that this page is not where a practice is administered, and that what it opens holds
    // nothing of its own.
    expect(shapeById('practice').buildNote).toMatch(/its own page/i)
    expect(shapeById('practice').buildNote).toMatch(/not on this one/i)
    expect(shapeById('practice').buildNote).toMatch(/no data of its own/i)
    // The two that are built describe what works rather than claiming completion.
    expect(shapeById('solo').buildNote).toMatch(/backup file/i)
    expect(shapeById('paired').buildNote).toMatch(/fingerprint/i)
  })

  it('opens each shape on a surface that exists', () => {
    const routeIds = OWNER_ROUTES.map((r) => r.id)
    // Non-vacuity: the owner's routes really were imported, rather than an empty array being
    // trivially satisfied by every membership test below.
    expect(routeIds.length).toBe(6)
    expect(routeIds).toContain('file')

    for (const shape of SHAPES) {
      if (shape.primary === 'practice') continue
      expect(routeIds, `${shape.id} opens on a route that does not exist`).toContain(shape.primary)
    }
    // Solo lands on the thing that works with no server at all; Paired lands where invitations
    // are minted; Practice lands on the marked placeholder.
    expect(shapeById('solo').primary).toBe('file')
    expect(shapeById('paired').primary).toBe('owner')
    expect(shapeById('practice').primary).toBe('practice')
  })

  it('names the surface using audience.ts’s own route labels rather than a second spelling', () => {
    const fileRoute = OWNER_ROUTES.find((r) => r.id === 'file')!
    expect(primaryLabel('solo')).toBe(fileRoute.label)
    expect(primaryLabel('practice')).toBe(LABELS.practiceSurface)
    expect(opensOnStatement('solo')).toContain(fileRoute.label)
  })

  it('recognises its own ids and nothing else', () => {
    for (const id of SHAPE_IDS) expect(isShapeId(id)).toBe(true)
    expect(isShapeId('Solo')).toBe(false)
    expect(isShapeId('single')).toBe(false)
    expect(isShapeId('')).toBe(false)
    expect(isShapeId(null)).toBe(false)
    expect(isShapeId(1)).toBe(false)
    expect(() => shapeById('nope' as ShapeId)).toThrow()
  })
})

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   2. Which shape was chosen — what the browser keeps.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('the answer this browser remembers', () => {
  it('round-trips a choice through one entry holding one line', () => {
    const store = memoryStorage()
    expect(rememberShape(store, 'paired')).toBe(true)
    // The whole of what is written: a version and a word. No name, no date, nothing about a
    // journal — assert the exact value, because "contains the shape" would pass a record that had
    // quietly grown a timestamp.
    expect(store.entries[SETUP_STORAGE_KEY]).toBe(`${SETUP_CHOICE_VERSION}:paired`)
    expect(Object.keys(store.entries)).toEqual([SETUP_STORAGE_KEY])
    expect(readStoredChoice(store)).toEqual({ version: SETUP_CHOICE_VERSION, shape: 'paired' })
  })

  it('reads back every shape it can write', () => {
    for (const id of SHAPE_IDS) {
      const store = memoryStorage()
      rememberShape(store, id)
      expect(readStoredChoice(store)?.shape, id).toBe(id)
    }
  })

  it('refuses to act on an entry this build did not write', () => {
    // A value this cannot read is a value it must not act on: routing somebody's machine on the
    // strength of a string nothing here produced is worse than asking again.
    const good = memoryStorage({ [SETUP_STORAGE_KEY]: '1:solo' })
    expect(readStoredChoice(good)).not.toBeNull() // the reader really does read

    for (const raw of [
      'solo', // no version
      '1:SOLO', // case
      '1: solo', // padded
      '1:single', // not a shape
      'true',
      '',
      '{"shape":"solo"}',
      '1:solo:extra',
      '-1:solo',
      '99999999999999999999:solo', // more digits than the pattern admits
    ]) {
      expect(readStoredChoice(memoryStorage({ [SETUP_STORAGE_KEY]: raw })), raw).toBeNull()
    }
  })

  it('erases the record rather than writing a sentinel over it', () => {
    const store = memoryStorage()
    rememberShape(store, 'solo')
    expect(Object.keys(store.entries)).toHaveLength(1) // there is something to erase
    expect(forgetShape(store)).toBe(true)
    // The existence of the entry is the part being cleared: an entry holding "none" is still a
    // key in storage announcing that this profile has opened Daymark.
    expect(Object.keys(store.entries)).toEqual([])
    expect(readStoredChoice(store)).toBeNull()
  })

  it('says so, rather than throwing, when the browser will not co-operate', () => {
    // FAIL OPEN. Nothing here is a security control, so unreadable storage means a repeated
    // question — never an exception, and never a locked page.
    expect(() => readStoredChoice(throwingStorage)).not.toThrow()
    expect(readStoredChoice(throwingStorage)).toBeNull()
    expect(rememberShape(throwingStorage, 'solo')).toBe(false)
    expect(forgetShape(throwingStorage)).toBe(false)

    // No storage object at all — the case where localStorage itself is unreachable.
    expect(readStoredChoice(null)).toBeNull()
    expect(rememberShape(null, 'solo')).toBe(false)
    expect(forgetShape(null)).toBe(false)

    // And a storage that simply cannot remove reports the failure rather than claiming a
    // deletion that did not happen.
    expect(forgetShape(noRemoveStorage)).toBe(false)
  })
})

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   3. First run versus returning.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('first run versus a returning person', () => {
  it('asks when nothing has been answered', () => {
    const decision = resolveSetup({ config: config.absent, session: null, stored: null })
    expect(decision).toEqual({ state: 'ask', reason: 'not-yet-set-up' })
    expect(decidedShape(decision)).toBeNull()
  })

  it('does not ask a returning person', () => {
    const store = memoryStorage()
    rememberShape(store, 'paired')
    const decision = resolveSetup({
      config: config.absent,
      session: null,
      stored: readStoredChoice(store),
    })
    expect(decision).toEqual({ state: 'chosen', shape: 'paired', origin: 'this-browser' })
    expect(decidedShape(decision)).toBe('paired')
  })

  it('prefers a choice made this visit over the one this browser had', () => {
    // The fail-open half: somebody who just changed the answer sees the new one now, whether or
    // not the browser agreed to keep it.
    const decision = resolveSetup({
      config: config.absent,
      session: 'practice',
      stored: { version: SETUP_CHOICE_VERSION, shape: 'solo' },
    })
    expect(decision).toEqual({ state: 'chosen', shape: 'practice', origin: 'this-visit' })
  })

  it('asks again, with a reason, when the stored answer predates this build’s choices', () => {
    const decision = resolveSetup({
      config: config.absent,
      session: null,
      stored: { version: 0, shape: 'solo' },
      version: 1,
    })
    expect(decision).toEqual({ state: 'ask', reason: 'choices-changed' })
    // And the reason is a sentence the screen can print, not just an enum.
    expect(ASK_REASON_NOTE['choices-changed']).toMatch(/asked once more/i)
    // The ordinary first run deliberately has nothing extra to say — the lede is the explanation.
    expect(ASK_REASON_NOTE['not-yet-set-up']).toBeNull()
  })

  it('honours an answer from a NEWER build rather than re-asking', () => {
    // A profile that met a newer build first has answered a superset of this build's question;
    // reopening it would be a regression dressed as caution (audience.ts makes the same call).
    const decision = resolveSetup({
      config: config.absent,
      session: null,
      stored: { version: SETUP_CHOICE_VERSION + 5, shape: 'solo' },
    })
    expect(decision).toEqual({ state: 'chosen', shape: 'solo', origin: 'this-browser' })
  })

  it('lets the answer be changed from the page, but only when the page owns it', () => {
    expect(isChangeable(resolveSetup({ config: config.absent, session: 'solo', stored: null }))).toBe(true)
    expect(isChangeable(resolveSetup({ config: config.set('solo'), session: null, stored: null }))).toBe(false)
    expect(isChangeable(resolveSetup({ config: config.reading, session: null, stored: null }))).toBe(false)
    expect(isChangeable(resolveSetup({ config: config.absent, session: null, stored: null }))).toBe(false)
  })

  it('survives a storage that throws, end to end, without ever failing closed', () => {
    // The whole path with the worst browser: read throws, write throws, and the person still gets
    // the shape they asked for this visit.
    expect(readStoredChoice(throwingStorage)).toBeNull()
    const firstVisit = resolveSetup({ config: config.absent, session: null, stored: null })
    expect(firstVisit.state).toBe('ask')

    const kept = rememberShape(throwingStorage, 'solo')
    expect(kept).toBe(false)
    const afterChoosing = resolveSetup({ config: config.absent, session: 'solo', stored: null })
    expect(afterChoosing).toEqual({ state: 'chosen', shape: 'solo', origin: 'this-visit' })
    // ...and next time they are asked again, which is the cost and the whole of the cost.
    expect(resolveSetup({ config: config.absent, session: null, stored: null }).state).toBe('ask')
  })
})

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   4. Configuration answers the question.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('a configuration-provided mode', () => {
  it('wins over asking', () => {
    const decision = resolveSetup({ config: config.set('practice'), session: null, stored: null })
    expect(decision).toEqual({ state: 'configured', shape: 'practice' })
    // The point of the whole branch: the question is not put to anybody.
    expect(decision.state).not.toBe('ask')
  })

  it('wins over this browser and over this visit', () => {
    // An operator who pinned the shape has said what this machine is. A per-browser answer must
    // not quietly override them, and neither must a click made ten seconds ago.
    const decision = resolveSetup({
      config: config.set('solo'),
      session: 'practice',
      stored: { version: SETUP_CHOICE_VERSION, shape: 'paired' },
    })
    expect(decision).toEqual({ state: 'configured', shape: 'solo' })
    expect(isChangeable(decision)).toBe(false)
  })

  it('suppresses the QUESTION while the probe is in flight, and nothing else', () => {
    // Half one: with nothing answered locally, waiting beats asking. Putting a question on screen
    // that configuration is about to answer, and then rearranging the page under the reader, is
    // worse than a moment of stated waiting.
    expect(resolveSetup({ config: config.reading, session: null, stored: null })).toEqual({
      state: 'reading',
    })

    // Half two, and it is the one that was wrong first: a RETURNING person is not held up by it.
    // With `reading` ranked above the local answers, every returning person met a "reading
    // configuration" panel where their own surface should have been, on every single load, for as
    // long as the probe took — which is precisely the cost this screen's second state exists to
    // avoid. An answered question must never take a screen away from anybody.
    expect(
      resolveSetup({
        config: config.reading,
        session: null,
        stored: { version: SETUP_CHOICE_VERSION, shape: 'solo' },
      }),
    ).toEqual({ state: 'chosen', shape: 'solo', origin: 'this-browser' })
    expect(
      resolveSetup({ config: config.reading, session: 'paired', stored: null }),
    ).toEqual({ state: 'chosen', shape: 'paired', origin: 'this-visit' })
  })

  it('falls through to asking when the endpoint is silent or unreachable', () => {
    for (const c of [config.absent, config.unreachable]) {
      expect(resolveSetup({ config: c, session: null, stored: null }).state, c.kind).toBe('ask')
    }
  })

  it('asks — and says why — when configuration named something that is not a shape', () => {
    // Not the same as no answer: somebody stated an intention. Collapsing it into silence would
    // have the page ask a question the operator believes they already answered.
    const decision = resolveSetup({
      config: config.unrecognised('SOLO'),
      session: null,
      stored: null,
    })
    expect(decision).toEqual({ state: 'ask', reason: 'configuration-not-understood' })
    expect(configuredUnrecognised('SOLO')).toContain('SOLO')
    expect(configuredUnrecognised('SOLO')).toContain(CONFIG_FIELD)
  })

  it('reads the field out of the endpoint body, strictly', () => {
    // The endpoint answers anyone, so restating a value published there discloses nothing new —
    // which is the reason this screen is allowed to name the value at all.
    expect(readSetupMode('{"setupMode":"solo"}')).toEqual({ kind: 'set', shape: 'solo' })
    expect(readSetupMode('{"smtpEnabled":true,"setupMode":"practice"}')).toEqual({
      kind: 'set',
      shape: 'practice',
    })
    // Absent is the expected state on this build: the field is not published yet.
    expect(readSetupMode('{"smtpEnabled":false}')).toEqual({ kind: 'absent' })
    expect(readSetupMode('{"setupMode":null}')).toEqual({ kind: 'absent' })
    // No trimming, no case folding, no aliases: a configuration value is a contract, and quietly
    // accepting "Solo " teaches an operator a spelling the server may not accept tomorrow.
    expect(readSetupMode('{"setupMode":"Solo"}')).toEqual({ kind: 'unrecognised', value: 'Solo' })
    expect(readSetupMode('{"setupMode":" solo"}')).toEqual({ kind: 'unrecognised', value: ' solo' })
    expect(readSetupMode('{"setupMode":3}')).toEqual({ kind: 'unrecognised', value: '3' })
    // Nothing readable at all.
    for (const body of [null, '', 'not json', '"solo"', '[]', 'null']) {
      expect(readSetupMode(body), String(body)).toEqual({ kind: 'unreachable' })
    }
  })

  it('names the endpoint, the field and the setting to remove, in the copy itself', () => {
    // A screen that silently skips a question leaves the operator with nowhere to look. Every one
    // of these four facts has to survive an edit to the wording.
    const statement = configuredStatement('paired')
    expect(statement).toContain(CONFIG_PATH)
    expect(statement).toContain(CONFIG_FIELD)
    expect(statement).toContain('paired')
    expect(statement).toMatch(/did not ask/i)

    const change = configuredHowToChange()
    expect(change).toContain(CONFIG_SETTING)
    expect(change).toMatch(/remove/i)
    expect(CONFIG_SETTING).toMatch(/^DAYMARK_[A-Z_]+$/)
    expect(CONFIG_PATH).toBe('/v1/config')
  })

  it('marks the not-yet-published field as a placeholder, in plain words', () => {
    // The read path is live and the server field is not there yet. That is a placeholder, and a
    // placeholder that does not say it is one is indistinguishable from a bug.
    expect(CONFIG_NOT_PUBLISHED_YET).toMatch(/^Placeholder/)
    expect(CONFIG_NOT_PUBLISHED_YET).toContain(CONFIG_FIELD)
    expect(CONFIG_NOT_PUBLISHED_YET).toContain(CONFIG_PATH)
    expect(CONFIG_NOT_PUBLISHED_YET).toMatch(/nothing is being guessed/i)
  })
})

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   5. The practice placeholder.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('the practice panel, which is not the practice console', () => {
  it('says it is a placeholder and that nothing on it is data', () => {
    expect(PRACTICE_MISSING).toMatch(/^Placeholder/)
    expect(PRACTICE_MISSING).toMatch(/nothing on it is data/i)
    expect(PRACTICE_MISSING).toMatch(/not the practice console/i)
    // The specific denials, because "no data" is the sentence somebody skims past. A fake name, a
    // fake count or a fake date is indistinguishable from a bug to the person trying to work out
    // whether this deployment works.
    expect(PRACTICE_MISSING).toMatch(/no roster/i)
    expect(PRACTICE_MISSING).toMatch(/no member/i)
    expect(PRACTICE_MISSING).toMatch(/no number/i)
  })

  it('points at the page where a practice is actually administered, and claims nothing more', () => {
    // Where that console is, is a fact about this build. What it can do is a claim about somebody
    // else's screen — so the copy says where, and explicitly defers the rest to that page.
    expect(PRACTICE_CONSOLE_ELSEWHERE).toContain('practice.html')
    expect(PRACTICE_CONSOLE_ELSEWHERE).toMatch(/stated there rather than guessed at here/i)
    expect(LABELS.practiceConsoleHref).toBe('./practice.html')
  })

  it('links only to a page this build actually bundles', () => {
    // A pointer to a document that is not in the bundle is a 404 dressed as a route. If the
    // practice console ever leaves this bundle, this fails here — where the fix is to drop the
    // pointer — rather than in somebody's browser.
    const webRoot = new URL('../../../', import.meta.url)
    const file = LABELS.practiceConsoleHref.replace(/^\.\//, '')
    expect(file).toBe('practice.html')
    expect(existsSync(fileURLToPath(new URL(file, webRoot))), `${file} is not in the bundle`).toBe(true)
    // ...and is a real rollup entry rather than an orphan document that never gets built.
    const viteConfig = readFileSync(fileURLToPath(new URL('vite.config.ts', webRoot)), 'utf8')
    expect(viteConfig, `${file} is not a build entry`).toContain(file)
  })

  it('lists operations the server really has, with no count beside any of them', () => {
    expect(PRACTICE_SERVER_HAS.length).toBeGreaterThanOrEqual(4)
    // The four the server routes actually implement (/v1/orgs: roster, add, accept, role change,
    // remove, audit). If this list grew a row the server does not have, it would be a claim.
    const joined = PRACTICE_SERVER_HAS.join(' ')
    expect(joined).toMatch(/roster/i)
    expect(joined).toMatch(/accept/i)
    expect(joined).toMatch(/role/i)
    expect(joined).toMatch(/audit/i)
    // A digit here would be a measurement of a deployment nothing on this page has looked at.
    expect(/\d/.test('3 members')).toBe(true) // the detector detects
    expect(PRACTICE_SERVER_HAS.filter((line) => /\d/.test(line))).toEqual([])
  })

  it('carries the two facts a person choosing it is least likely to already know', () => {
    // A role never carries a key — the rule that makes "an admin can revoke anyone yet cannot
    // read a single note" true rather than marketing.
    expect(PRACTICE_ROLE_NOTE).toMatch(/never carries a key/i)
    expect(PRACTICE_ROLE_NOTE).toContain('COMPANION_ACCESS_CONTROL.md')
    // And the open question that gates the whole shape (§3.11.3).
    expect(PRACTICE_OPEN_QUESTION).toMatch(/forgotten passphrase/i)
    expect(PRACTICE_OPEN_QUESTION).toMatch(/read the\s+journals/is)
  })
})

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   6. The copy.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

/** Every component that renders this module. Listed rather than globbed, as audience.test.ts is. */
const COMPONENTS = [
  '../components/setup/SetupEntry.svelte',
  '../components/setup/ShapeStrip.svelte',
  '../components/setup/PracticePlaceholder.svelte',
]

const componentSource = new Map<string, string>(
  COMPONENTS.map((rel) => [rel, readFileSync(fileURLToPath(new URL(rel, import.meta.url)), 'utf8')]),
)

/** Script and style gone, comments gone — what is left is markup and whatever prose is in it. */
function markupOf(rel: string): string {
  return componentSource
    .get(rel)!
    .replace(/<script[\s\S]*?<\/script>/g, ' ')
    .replace(/<style[\s\S]*?<\/style>/g, ' ')
    .replace(/<!--[\s\S]*?-->/g, ' ')
}

/** The prose a person reads: mustaches dropped, tags removed, whitespace collapsed. */
function proseOf(rel: string): string {
  return markupOf(rel)
    .replace(/\{[^{}]*\}/g, ' ')
    .replace(/<[^>]*>/g, ' ')
    .replace(/&[a-z]+;/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
}

/**
 * Every string this module authors, flattened, plus anything the components author themselves.
 * Functions that produce a sentence are called with a real argument so their output is policed
 * too — a rule that applied only to constants would miss half the configured branch.
 */
function authoredStrings(): { path: string; text: string }[] {
  const out: { path: string; text: string }[] = []

  const push = (path: string, value: unknown) => {
    if (typeof value === 'string') out.push({ path, text: value })
    else if (Array.isArray(value)) value.forEach((v, i) => push(`${path}[${i}]`, v))
    else if (value && typeof value === 'object') {
      for (const [k, v] of Object.entries(value)) push(`${path}.${k}`, v)
    }
  }

  for (const [name, value] of Object.entries(setup)) {
    if (typeof value === 'function') continue
    push(`shape.ts@${name}`, value)
  }

  // The sentence-producing functions, each with an argument the screen really passes.
  for (const id of SHAPE_IDS) {
    push('shape.ts@configuredStatement', configuredStatement(id))
    push('shape.ts@opensOnStatement', opensOnStatement(id))
  }
  push('shape.ts@configuredHowToChange', configuredHowToChange())
  push('shape.ts@configuredUnrecognised', configuredUnrecognised('something'))

  for (const rel of COMPONENTS) {
    const body = markupOf(rel)
    for (const m of body.matchAll(/\b(?:aria-label|title|alt|placeholder)="([^"{}]+)"/g)) {
      out.push({ path: `${rel}@attr`, text: m[1]! })
    }
    const prose = proseOf(rel)
    if (prose) out.push({ path: `${rel}@text`, text: prose })
  }

  return out
}

const CORPUS = authoredStrings()

describe('the copy', () => {
  it('the corpus is real and covers the whole module', () => {
    // Non-vacuity for every absence below. If this collapsed, the register tests would all pass
    // over nothing.
    expect(CORPUS.length).toBeGreaterThan(40)
    const joined = CORPUS.map((c) => c.text).join(' ')
    expect(joined.length).toBeGreaterThan(4000)
    // Sentences from every section really are in it.
    expect(joined).toContain('locks a copy of your journal')
    expect(joined).toContain('A clinic runs this machine')
    expect(joined).toContain('Placeholder')
    expect(joined).toContain(CONFIG_SETTING)
  })

  it('claims no health, no pass and no green', () => {
    // app.css invariant 2. A setup screen is exactly where someone reaches for a tick and "you're
    // all set"; answering one question routes a page and verifies nothing.
    const CLAIMS_HEALTH =
      /(?<![\w-])(green|success|successful|healthy|health check|all good|all clear|everything is fine|verified|secure|trusted|passed|passing|okay|good to go|you'?re set|all set|complete|ready to go)(?![\w-])/i

    expect(CLAIMS_HEALTH.test('All good')).toBe(true)
    expect(CLAIMS_HEALTH.test('Setup complete')).toBe(true)
    expect(CLAIMS_HEALTH.test('Your server is healthy')).toBe(true)
    expect(CLAIMS_HEALTH.test('you are all set')).toBe(true)
    expect(CLAIMS_HEALTH.test('one clinician you invite')).toBe(false)

    const offenders = CORPUS.filter((c) => CLAIMS_HEALTH.test(c.text)).map((c) => `${c.path}: ${c.text}`)
    expect(offenders).toEqual([])
  })

  it('renders no figure, grade, streak or step count', () => {
    const A_FIGURE = /%|(?<![\w-])(percent|percentage|grade|graded|streak|rating|score|step \d|out of \d)(?![\w-])/i
    expect(A_FIGURE.test('87% set up')).toBe(true)
    expect(A_FIGURE.test('step 2 of 3')).toBe(true)
    expect(A_FIGURE.test('a 4 day streak')).toBe(true)
    expect(A_FIGURE.test('One copy on one disk')).toBe(false)

    const offenders = CORPUS.filter((c) => A_FIGURE.test(c.text)).map((c) => `${c.path}: ${c.text}`)
    expect(offenders).toEqual([])
  })

  it('keeps the register flat: no cheer, no exclamation, no welcome', () => {
    // Someone may be reading this at 2am on a bad night.
    const CHEER =
      /!|(?<![\w-])(welcome|congrat|awesome|great job|let'?s get started|getting started|hooray|nice work|you'?ve got this)(?![\w-])/i
    expect(CHEER.test('Welcome to Daymark')).toBe(true)
    expect(CHEER.test('Set up in 3 steps!')).toBe(true)
    expect(CHEER.test('What is this machine for?')).toBe(false)

    const offenders = CORPUS.filter((c) => CHEER.test(c.text)).map((c) => `${c.path}: ${c.text}`)
    expect(offenders).toEqual([])
  })

  it('never addresses the reader as a patient, a case or a user of a medical device', () => {
    const PATIENT =
      /(?<![\w-])(patient|your case|case file|clinical assessment|diagnos(is|e|ed|tic tool)|treatment plan|medical device|prescription)(?![\w-])/i
    expect(PATIENT.test('your patient record')).toBe(true)
    expect(PATIENT.test('a diagnosis of anything')).toBe(true)
    expect(PATIENT.test('one clinician you invite')).toBe(false)

    const offenders = CORPUS.filter((c) => PATIENT.test(c.text)).map((c) => `${c.path}: ${c.text}`)
    expect(offenders).toEqual([])
  })

  it('never promises the copy is safe, or that it survives a lost phone', () => {
    // THE TWO CLAIMS THIS SCREEN IS FORBIDDEN TO MAKE. There is one disk here and nothing backing
    // it up (§3.11.1), so "your journal is safe" is the sentence somebody would remember on the
    // day the disk died — and the recovery property, which is real, holds only while this machine
    // still has the copy. Both are promises, and neither is this screen's to make.
    const OVERPROMISE =
      /(?<![\w-])(safe|safely|kept safe|backed up|never lose|can'?t lose|cannot lose|always be able|guarantee[ds]?|peace of mind)(?![\w-])/i
    expect(OVERPROMISE.test('your journal is safe here')).toBe(true)
    expect(OVERPROMISE.test('it is backed up')).toBe(true)
    expect(OVERPROMISE.test('you will never lose your history')).toBe(true)
    expect(OVERPROMISE.test('One copy on one disk here')).toBe(false)

    const offenders = CORPUS.filter((c) => OVERPROMISE.test(c.text)).map((c) => `${c.path}: ${c.text}`)
    expect(offenders).toEqual([])
  })

  it('spends under seventy words before the choices', () => {
    // The screen is seen once and is allowed to explain itself; it is not allowed to be a page of
    // reading. The budget covers everything above the three options — heading, lede, limits, and
    // the question itself.
    const words = (s: string) => s.split(/\s+/).filter((w) => /[a-z]/i.test(w)).length
    const before = words(SETUP_TITLE) + words(SETUP_LEDE) + words(SETUP_LIMITS) + words(CHOICE_HEADING)
    expect(before).toBeLessThan(70)
    // ...and is not a stub either: the explanation has to actually explain.
    expect(before).toBeGreaterThan(40)
  })

  it('opens with cause and effect, and states the two facts it must not soften', () => {
    // The wording that read clearest: the phone does a thing, this machine receives it, here is
    // what you type. Not a noun-phrase definition of a product.
    expect(SETUP_LEDE).toBe(
      'Daymark on your phone locks a copy of your journal and sends it to this machine. You type ' +
        'the passphrase you chose on the phone to read it here.',
    )
    expect(SETUP_LIMITS).toMatch(/one disk/i)
    expect(SETUP_LIMITS).toMatch(/gone/i)
    expect(SETUP_LIMITS).toMatch(/nobody can open it/i)
    // The choice is reversible and says so where the choice is made, not afterwards.
    expect(CHOICE_IS_REVERSIBLE).toMatch(/changed later/i)
  })

  it('every word the screen renders lives in this module', () => {
    // The strongest version of the arrangement audience.test.ts arrived at the hard way. Its
    // corpus was module exports only, and a planted "All good — your server is healthy and you
    // are all set" in the markup passed the whole suite. These three components are supposed to
    // author NOTHING: every visible string arrives through a mustache from shape.ts, which is
    // what puts all of it inside the register tests above.
    //
    // The detector first, on a planted line, so a broken extractor cannot pass this quietly.
    const planted = '<p class="lede">All good — you are all set.</p>'
    expect(/[A-Za-z]/.test(planted.replace(/\{[^{}]*\}/g, ' ').replace(/<[^>]*>/g, ' '))).toBe(true)

    for (const rel of COMPONENTS) {
      // Non-vacuity: the markup really was read and really does contain mustaches to strip.
      expect(markupOf(rel).length, rel).toBeGreaterThan(200)
      expect(markupOf(rel), rel).toContain('{')
      expect(proseOf(rel), `${rel} authors prose of its own`).not.toMatch(/[A-Za-z]/)
    }
  })

  it('every label is filled in and is used', () => {
    const labels = Object.values(LABELS)
    expect(labels.length).toBeGreaterThanOrEqual(12)
    for (const [name, text] of Object.entries(LABELS)) {
      expect(text.length, name).toBeGreaterThan(2)
      expect(text.trim(), name).toBe(text)
    }
    // Each one really reaches a screen, so dead copy is caught here instead of accumulating.
    // Read over CODE with commentary removed — a label named only in a comment explaining why it
    // was removed would otherwise keep passing this — and over shape.ts too, because one label
    // reaches the page through a sentence this module builds rather than through markup.
    const strip = (src: string) =>
      src
        .replace(/<!--[\s\S]*?-->/g, ' ')
        .replace(/\/\*[\s\S]*?\*\//g, ' ')
        .replace(/(?<!:)\/\/[^\n]*/g, ' ')
    const moduleSource = readFileSync(fileURLToPath(new URL('./shape.ts', import.meta.url)), 'utf8')
    const code = [...COMPONENTS.map((rel) => componentSource.get(rel)!), moduleSource]
      .map(strip)
      .join('\n')
    // Non-vacuity: stripping really did leave the code, and the LABELS block itself is excluded
    // by looking for the qualified reference rather than the bare key.
    expect(code).toContain('LABELS.whatThisMeans')
    expect(code).not.toContain('This file fetches two URLs') // a comment, and it is gone
    for (const name of Object.keys(LABELS)) {
      expect(code, `LABELS.${name} is never used`).toContain(`LABELS.${name}`)
    }
  })
})

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   7. The screen itself, read as source.

   There is no component-rendering harness here — the suite runs in node — and every property
   below is a property of the source text: "the each-loop is over the catalog", "the configured
   branch prints the setting". Reading the file is the direct test, not a proxy for one, and the
   same technique is used in src/lib/components/invariants.tree.test.ts.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('the first-run screen renders all three choices', () => {
  const entry = componentSource.get('../components/setup/SetupEntry.svelte')!

  it('walks the catalog rather than listing three shapes of its own', () => {
    // Non-vacuity: the file was really read.
    expect(entry.length).toBeGreaterThan(1000)
    expect(entry).toContain("from '../../setup/shape'")
    // ONE each-loop over the module's catalog is what makes "all three render" follow from
    // SHAPES.length === 3, which the first test in this file pins.
    expect(entry).toMatch(/\{#each SHAPES as shape \(shape\.id\)\}/)
    expect(SHAPES.length).toBe(3)
    // A hand-written list would show up as the ids appearing literally in the markup.
    for (const id of SHAPE_IDS) {
      expect(entry, `${id} is hand-written into the markup`).not.toContain(`>${id}<`)
    }
  })

  it('prints every part of a choice, including the one that says what is built', () => {
    for (const field of ['label', 'summary', 'arrangement', 'ranking', 'buildNote']) {
      expect(entry, `shape.${field} is not rendered`).toContain(`{shape.${field}}`)
    }
    // The build state is a chip on the choice itself, so the gap is visible before the click.
    expect(entry).toContain('BUILD_TONE[shape.buildState]')
    expect(entry).toContain('BUILD_WORD[shape.buildState]')
    // And every choice is a control that reports which one it is.
    expect(entry).toContain('onchoose(shape.id)')
  })

  it('asks nothing while configuration might still answer', () => {
    // The `reading` branch renders a statement and no choices; the choices live inside the `ask`
    // branch. If those collapsed into one branch, a question would flash on screen and then be
    // replaced by a configured deployment's answer.
    expect(entry).toContain("{#if decision.state === 'reading'}")
    expect(entry).toContain("{:else if decision.state === 'ask'}")
    const askBranch = entry.slice(entry.indexOf("{:else if decision.state === 'ask'}"))
    expect(askBranch).toContain('{#each SHAPES as shape')
    const readingBranch = entry.slice(
      entry.indexOf("{#if decision.state === 'reading'}"),
      entry.indexOf("{:else if decision.state === 'ask'}"),
    )
    expect(readingBranch).not.toContain('{#each SHAPES as shape')
  })

  it('marks the not-yet-published configuration field as a placeholder on the screen', () => {
    expect(entry).toContain('CONFIG_NOT_PUBLISHED_YET')
    expect(entry).toContain('configuredUnrecognised(config.value)')
  })
})

describe('the returning-person strip', () => {
  const strip = componentSource.get('../components/setup/ShapeStrip.svelte')!

  it('says plainly when configuration answered, and names what to remove', () => {
    expect(strip.length).toBeGreaterThan(1000)
    expect(strip).toContain("{#if decision.state === 'configured'}")
    expect(strip).toContain('configuredStatement(decision.shape)')
    expect(strip).toContain('configuredHowToChange()')
  })

  it('offers no control that would appear to change a configured deployment', () => {
    // Rendered absent rather than disabled: a button that looked like it changed the shape and
    // then lost to the same flag on the next load is worse than no button.
    expect(strip).toContain('isChangeable(decision)')
    expect(strip).toContain('{#if changeable}')
    expect(isChangeable({ state: 'configured', shape: 'solo' })).toBe(false)
  })

  it('says that a settled browser stops reading configuration, and only for a chosen shape', () => {
    /*
     * The trade this slice made. Configuration still outranks the browser every time it is read —
     * but the page reads it only while the question is open, so that a settled machine's offline
     * surface makes no request at all. That is invisible to exactly one person, the operator who
     * pins the shape AFTER somebody answered here, so it is written next to the answer.
     *
     * Guarded on `chosen`: a CONFIGURED deployment is the re-read, and telling its operator that
     * their setting is not consulted would be the one false sentence on the strip.
     */
    expect(strip).toContain('CONFIGURATION_IS_NOT_RE_READ')
    const after = strip.slice(strip.indexOf('CONFIGURATION_IS_NOT_RE_READ', strip.indexOf('<details')))
    expect(strip.slice(0, strip.indexOf('{CONFIGURATION_IS_NOT_RE_READ}'))).toContain(
      "{#if decision.state === 'chosen'}",
    )
    expect(after).toContain('{/if}')
    expect(CONFIGURATION_IS_NOT_RE_READ).toContain(CONFIG_PATH)
    expect(CONFIGURATION_IS_NOT_RE_READ).toContain(CONFIG_FIELD)
    expect(CONFIGURATION_IS_NOT_RE_READ).toMatch(/does not read/i)
    expect(CONFIGURATION_IS_NOT_RE_READ).toMatch(/reopened/i)
  })

  it('states the recovery-link bypass instead of leaving the machine looking set up', () => {
    expect(strip).toContain('RECOVERY_LINK_BYPASS')
    expect(strip).toContain('{:else if bypassed}')
  })
})

describe('the practice placeholder panel', () => {
  const panel = componentSource.get('../components/setup/PracticePlaceholder.svelte')!

  it('leads with the placeholder statement and shows nothing that looks like data', () => {
    expect(panel.length).toBeGreaterThan(800)
    expect(panel).toContain('PRACTICE_MISSING')
    expect(panel).toContain('LABELS.placeholder')
    // The way out, rendered as an anchor to the sibling page rather than described in prose.
    expect(panel).toContain('href={LABELS.practiceConsoleHref}')
    // No fetch, no store, no client: an empty roster is indistinguishable from a broken one, so
    // this panel does not go looking for anything to render.
    expect(panel).not.toMatch(/fetch\(|PortalClient|onMount/)
    expect(panel).not.toMatch(/\$state|\$derived/)
  })

  it('is the only place App.svelte routes the practice surface', () => {
    // So the swap, when the practice console lands, is one import and one element.
    const app = readFileSync(fileURLToPath(new URL('../../App.svelte', import.meta.url)), 'utf8')
    expect(app).toContain('<PracticePlaceholder />')
    expect(app).toContain("{:else if source === 'practice'}")
    // And the practice surface is deliberately NOT one of the owner's six routes.
    expect(OWNER_ROUTES.map((r) => r.id)).not.toContain('practice')
  })
})

describe('App.svelte gates on the decision rather than on a flag of its own', () => {
  const app = readFileSync(fileURLToPath(new URL('../../App.svelte', import.meta.url)), 'utf8')
  const strip = componentSource.get('../components/setup/ShapeStrip.svelte')!

  it('shows the first-run screen only while there is no answer', () => {
    expect(app).toContain('resolveSetup(')
    expect(app).toContain('{#if setupGateOpen}')
    expect(app).toContain('<SetupEntry')
    expect(app).toContain('<ShapeStrip')
  })

  it('never lets a recovery link meet the setup question', () => {
    // An emailed access-token link is followed by someone who has already lost something. The
    // gate is closed for that visit, and the strip says the question was deferred rather than
    // answered.
    expect(app).toContain('startedOnRecoveryLink')
    expect(app).toMatch(/!startedOnRecoveryLink && \(decision\.state === 'reading' \|\| decision\.state === 'ask'\)/)
  })

  it('lands on a shape’s surface once per shape, not once per page', () => {
    // A boolean latch would leave somebody looking at the surface for a shape the strip above
    // them says this machine is not: the stored answer resolves first, and a configured
    // deployment's answer can outrank it a moment later.
    expect(app).toContain('let landedShape: ShapeId | null = null')
    expect(app).toContain('shape === landedShape')
  })

  it('reads configuration only while the question is open', () => {
    /*
     * THE DEFECT THIS REPLACES. The probe was an `$effect` here that read no reactive state, so it
     * ran on every load of index.html — including the loads where somebody went straight to "Open
     * a backup file", the tab whose strip promises that it sends nothing. The bound and the abort
     * moved to lib/setup/configProbe.ts with the request; what App.svelte still owns is the
     * decision to make one, and it has to be the shared rule rather than a condition of its own.
     */
    expect(app).toContain('startConfigurationRead({ session: sessionShape, stored }')
    expect(app, 'App.svelte fetches directly again').not.toMatch(/(?<![\w.])fetch\s*\(/)
    // The cost of that scope is stated on the page rather than only in a comment.
    expect(strip).toContain('CONFIGURATION_IS_NOT_RE_READ')
  })
})
