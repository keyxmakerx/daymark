import { describe, it, expect } from 'vitest'
import { readFileSync, readdirSync } from 'node:fs'
import { join, dirname, sep } from 'node:path'
import { fileURLToPath } from 'node:url'

/*
 * DOES THE DOCUMENTATION DESCRIBE THIS REPOSITORY?
 *
 * Written after the same failure happened four times in one working session:
 *
 *   - docs claimed the design system "reserves" night-sky tokens `--c-sky-bg` / `--c-sky-ink` /
 *     `--c-sky-faint`. Those names existed nowhere in the tree and never had.
 *   - a plan pointed at `design/web-05-report-projects.png`, which does not exist.
 *   - two security findings were written in the present tense as OPEN defects for a day after both
 *     were fixed, with a "Fix:" paragraph proposing what the code already did.
 *   - counts of components, tests and mood references drifted as the tree moved under them.
 *
 * Every one is the same shape: prose asserting something about the code, with nothing checking it.
 * In a project whose central claim is that it does not overstate what it has built, documentation
 * that overstates what it has built is not a cosmetic problem — it is the product's own failure
 * mode, aimed at the people most likely to trust it (a reviewer, a clinician, the maintainer in
 * three weeks).
 *
 * This guards the mechanically checkable half: references that must resolve. It cannot check whether
 * a paragraph is honest, and it does not try. Prose still needs a reader.
 */

const HERE = dirname(fileURLToPath(import.meta.url))
const REPO = join(HERE, '..', '..', '..', '..') // src/lib -> src -> web -> companion -> repo root
const DOCS = join(REPO, 'docs')

function markdownFiles(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true })
    .filter((e) => e.isFile() && e.name.endsWith('.md'))
    .map((e) => join(dir, e.name))
}

const DOC_FILES = markdownFiles(DOCS)
const DOC_TEXT = new Map(DOC_FILES.map((f) => [f, readFileSync(f, 'utf8')]))
const APP_CSS = readFileSync(join(REPO, 'companion', 'web', 'src', 'app.css'), 'utf8')

/** `path/like/this.ts` inside backticks, filtered to things that look like real repo paths. */
const PATH_IN_BACKTICKS = /`([A-Za-z0-9._/-]+\.(?:ts|tsx|kt|kts|svelte|css|json|md|yml|yaml|png|html))`/g
/**
 * A CSS custom property, recognised only where it is written AS CSS — `var(--x)` or `--x:`.
 *
 * A bare `--x` in backticks is as often a CLI flag as a token (`--frozen-lockfile` was the one that
 * exposed this), and a guard that reports a shell flag as a missing design token teaches its reader
 * to distrust it.
 */
const TOKEN_AS_CSS = /var\(\s*(--[a-z][a-z0-9-]*)\s*\)|(--[a-z][a-z0-9-]*)\s*:/g

/** Paths that are illustrative rather than real, or belong to another repo/toolchain. */
const NOT_OURS =
  /^(node_modules|dist|build|out|\.|https?:|example|path|your|some|<|\$)|(^|\/)(TABLE_NAME|foo|bar)\b/i

function candidatePaths(text: string): string[] {
  return [...text.matchAll(PATH_IN_BACKTICKS)]
    .map((m) => m[1])
    .filter((p) => !NOT_OURS.test(p))
    // A bare filename with no directory is usually prose ("see build.gradle.kts"), not a path claim.
    .filter((p) => p.includes('/'))
}

/**
 * Every tracked file in the repo, as repo-relative paths, for suffix matching.
 *
 * Documentation legitimately writes shorthand — `stats/SupportOffer.kt`, `instruments/predicate.ts`,
 * `src/app.css` — because a full path from the root is noise in a sentence. The question a reader
 * has is "does this file exist", not "did the author type the whole prefix". A resolver that
 * demanded the prefix would flag correct prose, and a guard that cries wolf is one that gets
 * switched off, which is worse than not having it.
 */
function indexFiles(dir: string, acc: string[] = []): string[] {
  for (const e of readdirSync(dir, { withFileTypes: true })) {
    if (/^(node_modules|\.git|build|dist|\.gradle|\.idea)$/.test(e.name)) continue
    const p = join(dir, e.name)
    if (e.isDirectory()) indexFiles(p, acc)
    else acc.push(p.slice(REPO.length + 1).split(sep).join('/'))
  }
  return acc
}
const FILE_INDEX = indexFiles(REPO)

/**
 * True when some real file matches this documented path.
 *
 * Two shorthands are honoured because both are ordinary, correct English:
 *
 *  - a SUFFIX: `stats/SupportOffer.kt`, `src/app.css`. The reader's question is "does this file
 *    exist", not "did the author type the whole prefix from the repo root".
 *  - an ELLIPSIS SEGMENT: `app/src/androidTest/.../MigrationTest.kt`. Kotlin source sits eight
 *    directories deep under a reversed-domain package; spelling that out in a sentence is noise.
 *    `...` stands for any run of intervening directories, so the claim is still fully checked —
 *    the file must exist AND be under that prefix.
 *
 * A resolver that rejected either would flag correct prose, and a guard that cries wolf is one that
 * gets switched off, which is worse than not having it.
 */
function resolves(p: string): boolean {
  const cleaned = p.replace(/^\.\//, '').replace(/^\/+/, '')
  if (cleaned.includes('/.../')) {
    const parts = cleaned.split('/.../').map((s) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'))
    const re = new RegExp('(^|/)' + parts.join('/(.+/)?') + '$')
    return FILE_INDEX.some((f) => re.test(f))
  }
  return FILE_INDEX.some((f) => f === cleaned || f.endsWith('/' + cleaned))
}

/**
 * Paths the documentation names that deliberately do NOT exist in the tree.
 *
 * A doc may legitimately print a file the *operator* is expected to create — the hardening guide is
 * mostly that. The word "optional" or "new file" next to it is a prose signal this test cannot
 * read, so the rule is: absence has to be declared here, with a reason, where a reviewer sees it in
 * the diff. That keeps the useful half of the check (a path nobody vouched for is a bug) without
 * flagging honest prose.
 *
 * Every entry is asserted to be genuinely absent, so an excuse cannot outlive the thing it excuses:
 * if one of these ever lands for real, this table is what tells you to delete the entry.
 */
const ABSENT_PATHS: Record<string, string> = {
  'companion/docker-compose.smtp.yml':
    'HARDENING §2.1 — an opt-in override the operator writes if they enable SMTP. Shipping it would ' +
    'put a mail network in the default topology, which is the thing that section argues against.',
  'renovate.json':
    'HARDENING §4.7 — proposed config, not adopted. The repo uses .github/dependabot.yml instead; ' +
    'the section is kept because its point (a pinned-and-rotting digest is worse than an unpinned ' +
    'one) applies to whichever updater is running.',
}

describe('the corpus is actually being read', () => {
  it('found documentation to check', () => {
    // Guards every assertion below: an empty corpus would make them all pass.
    expect(DOC_FILES.length).toBeGreaterThan(10)
    expect([...DOC_TEXT.values()].join('').length).toBeGreaterThan(50_000)
  })

  it('found path references to check', () => {
    const total = [...DOC_TEXT.values()].reduce((n, t) => n + candidatePaths(t).length, 0)
    expect(total).toBeGreaterThan(40)
  })

  it('the resolver is not simply saying yes to everything', () => {
    expect(resolves('docs/THIS_FILE_DOES_NOT_EXIST.md')).toBe(false)
    expect(resolves('companion/web/src/app.css')).toBe(true)
  })

  it('the ellipsis segment matches a real path and still rejects a false one', () => {
    expect(resolves('app/.../stats/Signals.kt')).toBe(true)
    // Right filename, wrong prefix: `...` must not degrade into "any file anywhere".
    expect(resolves('companion/.../stats/Signals.kt')).toBe(false)
    expect(resolves('app/.../stats/NoSuchThing.kt')).toBe(false)
  })
})

describe('every file the documentation points at exists', () => {
  for (const [file, text] of DOC_TEXT) {
    const name = file.slice(REPO.length + 1)
    const paths = [...new Set(candidatePaths(text))]
    if (!paths.length) continue
    it(`${name} — ${paths.length} referenced paths`, () => {
      const missing = paths.filter((p) => !resolves(p) && !(p in ABSENT_PATHS))
      expect(missing, `${name} references files that do not exist`).toEqual([])
    })
  }

  it('the declared-absent paths are still absent', () => {
    // Otherwise the excuse outlives the thing it excuses.
    const nowPresent = Object.keys(ABSENT_PATHS).filter(resolves)
    expect(nowPresent, 'these exist now — delete their ABSENT_PATHS entries').toEqual([])
  })

  it('every declared-absent path is one the documentation actually names', () => {
    // A stale entry silently widens the hole for a path that was renamed or removed.
    const allText = [...DOC_TEXT.values()].join('\n')
    const unreferenced = Object.keys(ABSENT_PATHS).filter((p) => !allText.includes(p))
    expect(unreferenced, 'no doc mentions these — delete their ABSENT_PATHS entries').toEqual([])
  })
})

/**
 * Tokens the documentation names that app.css does not define — each with the reason.
 *
 * The first version of this test asserted the simple rule "if a doc names a token, app.css defines
 * it", and it was wrong in the most instructive way: it flagged `--success`, a token the design
 * system names *specifically to forbid*. A rule that cannot tell "we built this" from "we decided
 * never to build this" is not measuring honesty, it is measuring vocabulary.
 *
 * So absence is declared, not inferred, and it splits three ways:
 *
 *  FORBIDDEN   the name must never appear in app.css. Enforced for real, against values as well as
 *              names, in components/ui/invariants.test.ts group (d).
 *  SUPERSEDED  a pre-implementation sketch's name for something that shipped under another name.
 *  UNBUILT     named in a proposal the docs mark as not built.
 */
const ABSENT_TOKENS: Record<string, string> = {
  // FORBIDDEN — a green "you are fine" is a clinical claim this product does not get to make.
  '--success': 'DESIGN_SYSTEM §2.3.2 — no health-coloured status token; ui/invariants.test.ts (d)',
  '--warning': 'DESIGN_SYSTEM §2.3.2 — same rule; the mood ramp is data, not a severity palette',
  '--trust-locked': 'UX §10.1/§496 — a served page is never painted green, whatever it claims',
  '--trust-caution': 'UX §10 — the trust strip carries no colour vocabulary of its own',
  '--trust-open': 'UX §10 — same; assurance is worded, not hued',

  // SUPERSEDED — UX.md §3 and FEATURES.md §7.2 predate app.css. Both now carry a status banner
  // naming the replacements; these entries are the mechanical half of the same correction.
  '--paper': 'sketch name; shipped as --paper-bg',
  '--sheet': 'sketch name; shipped as --paper-sheet',
  '--surface': 'sketch name; shipped as --paper-sheet',
  '--ink': 'sketch name; shipped as --ink-text',
  '--soft': 'sketch name; shipped as --ink-soft',
  '--faint': 'sketch name; shipped as --ink-faint',
  '--accent': 'sketch name; shipped as --ink-accent',
  '--hair': 'sketch name; shipped as --hairline',
  '--radius-card': 'sketch name; shipped as --radius',
  '--radius-chip': 'sketch name; shipped as --radius-sm',
  '--font-serif': 'sketch name; shipped as --font-display',
  '--font-sans': 'sketch name; shipped as --font-text',

  // UNBUILT — DESIGN_SYSTEM §3.2 says so in its own banner. The reduced-motion guarantee does not
  // depend on them: the global block neutralises durations rather than each site opting in.
  '--ease-standard': 'DESIGN_SYSTEM §3.2 "motion tokens do not exist"; transitions are per-component',
  '--ease-entrance': 'DESIGN_SYSTEM §3.2 — same',
  '--dur-fast': 'DESIGN_SYSTEM §3.2 — same',
  '--dur-base': 'DESIGN_SYSTEM §3.2 — same',
  '--dur-slow': 'DESIGN_SYSTEM §3.2 — same',
}

describe('every design token the documentation names is defined', () => {
  /*
   * The `--c-sky-*` case: prose described a token family as "reserved" by the design system, which
   * made a web Sky sound like it would consume something already agreed rather than introduce it.
   * A reader had no way to tell, because nothing connected the sentence to app.css.
   */
  const documented = new Set<string>()
  for (const text of DOC_TEXT.values()) {
    for (const m of text.matchAll(TOKEN_AS_CSS)) documented.add(m[1] ?? m[2])
  }

  it('found tokens to check, and the check can fail', () => {
    expect(documented.size).toBeGreaterThan(10)
    expect(APP_CSS.includes('--paper-bg')).toBe(true)
    expect(APP_CSS.includes('--token-that-does-not-exist')).toBe(false)
  })

  it('names no token that is neither defined nor declared absent', () => {
    const undeclared = [...documented].filter((t) => !APP_CSS.includes(`${t}:`) && !(t in ABSENT_TOKENS))
    expect(undeclared, 'documented tokens neither in app.css nor in ABSENT_TOKENS').toEqual([])
  })

  it('the declared-absent tokens are still absent', () => {
    // The load-bearing half. `--success` and the `--trust-*` family are not merely unbuilt: a green
    // "all clear" is a claim this product must never make, and ui/invariants.test.ts group (d)
    // enforces that against app.css directly. This assertion is the documentation-side echo — if
    // one of these lands, the doc saying it does not exist has become the lie.
    const nowDefined = Object.keys(ABSENT_TOKENS).filter((t) => APP_CSS.includes(`${t}:`))
    expect(nowDefined, 'these are defined now — the docs saying otherwise are wrong').toEqual([])
  })

  it('every declared-absent token is one the documentation actually names', () => {
    const nowUnmentioned = Object.keys(ABSENT_TOKENS).filter((t) => !documented.has(t))
    expect(nowUnmentioned, 'no doc names these — delete their ABSENT_TOKENS entries').toEqual([])
  })
})

describe('claims of the form "not built" stay true', () => {
  /*
   * PLAN §5 exists to stop green CI being read as "the feature landed". If something on that list
   * quietly acquires a production caller, the list becomes the lie instead of the safeguard.
   */
  const webSrc = join(REPO, 'companion', 'web', 'src')

  function sourceFiles(dir: string, acc: string[] = []): string[] {
    for (const e of readdirSync(dir, { withFileTypes: true })) {
      const p = join(dir, e.name)
      if (e.isDirectory()) sourceFiles(p, acc)
      else if (/\.(ts|svelte)$/.test(e.name) && !/\.test\.ts$/.test(e.name)) acc.push(p)
    }
    return acc
  }

  const PRODUCTION = sourceFiles(webSrc).map((f) => ({ f, text: readFileSync(f, 'utf8') }))

  it('collected production sources to search', () => {
    expect(PRODUCTION.length).toBeGreaterThan(30)
  })

  it('the companion dialogue is still unmounted, as the docs say', () => {
    // Three of its six destinations (check-in, journal, safety-plan) do not exist in this app, so
    // mounting it here would give endings nothing to open. When that changes, this test should be
    // updated in the same commit as the mount — and PLAN §5 with it.
    const importers = PRODUCTION.filter(
      (p) => /from '.*companion\/(content|walk)'/.test(p.text) && !p.f.endsWith('Companion.svelte'),
    )
    expect(importers.map((p) => p.f.slice(REPO.length + 1))).toEqual([])
  })
})
