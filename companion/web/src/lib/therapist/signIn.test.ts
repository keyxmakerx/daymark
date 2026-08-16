import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

import {
  ELISION,
  GROUP_SIZE,
  REPORTED_MAX,
  SCREEN_COPY,
  SECTION_HEADING,
  SECTION_ORDER,
  SIGN_IN_CONTRACT,
  SHORT_HEAD,
  SHORT_TAIL,
  capReported,
  checkContract,
  collidingShortForms,
  contractSections,
  describeDigest,
  digestReference,
  echoesBannerSubject,
  groupHex,
  makesAssuranceClaim,
  parseDigest,
  shortenDigest,
  type ContractClause,
  type ParsedDigest,
} from './signIn'

/**
 * SIGN-IN — the tests.
 *
 * Two of the properties this file asserts are absences: "the display never collapses two
 * digests", and "this screen holds no authentication path". An absence test is the easiest kind
 * to write vacuously — a detector that matches nothing reports a clean bill of health on an
 * empty grep — so every one of them below first proves its detector CAN see the thing when the
 * thing is there, on a real subject from this codebase, and only then asserts it is not there.
 * Where that proof is a planted example, the comment says so.
 *
 * The component is asserted by reading its source. There is no rendering harness in this repo
 * (vitest runs in `node`), and every property claimed here is a property of the source text
 * anyway: which module it imports, whether a call sits inside a conditional, whether a sentence
 * appears. Reading the file is the direct test of those, not a proxy for one. Comments are
 * stripped first, for the reason components/invariants.tree.test.ts gives: an honest file
 * explains what it does NOT do, and a guard run over raw text would be satisfied by the
 * explanation instead of by the code.
 */

/* ── Reading the two source files this suite makes claims about ─────────────────────────── */

const read = (rel: string) => readFileSync(fileURLToPath(new URL(rel, import.meta.url)), 'utf8')

const MODULE_SRC = read('./signIn.ts')
const SCREEN_SRC = read('../components/therapist/SignInScreen.svelte')
const LOGIN_GATE_SRC = read('../components/therapist/LoginGate.svelte')
const BANNER_SRC = read('../components/therapist/LowerAssuranceBanner.svelte')

/** Source with commentary removed: what actually ships. Same shape as the tree-wide suite's. */
const codeOnly = (src: string) =>
  src
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/(?<!:)\/\/[^\n]*/g, '')

/** The markup a person actually gets: script and style gone, comments gone. */
const markupOf = (src: string) =>
  src
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/<script[\s\S]*?<\/script>/g, '')
    .replace(/<style[\s\S]*?<\/style>/g, '')

/** The prose a person reads: markup with the tags taken out and whitespace collapsed. */
const proseOf = (src: string) =>
  markupOf(src)
    .replace(/<[^>]*>/g, '')
    .replace(/\s+/g, ' ')
    .trim()

const SCREEN_CODE = codeOnly(SCREEN_SRC)
const SCREEN_MARKUP = markupOf(SCREEN_SRC)

/* ── Digests, as fixtures ───────────────────────────────────────────────────────────────── */

const digestOf = (hex: string): ParsedDigest => {
  const parse = parseDigest(`sha256:${hex}`)
  if (!parse.ok) throw new Error(`fixture is not a digest: ${hex}`)
  return parse.digest
}

/**
 * Six sha256 digests chosen to attack a display, not to look realistic. Two of them differ only
 * in the middle, which is exactly what a head-and-tail abbreviation throws away; two more differ
 * only in the first character and only in the last.
 */
const HEXES = {
  base: 'a'.repeat(64),
  firstChar: 'b' + 'a'.repeat(63),
  lastChar: 'a'.repeat(63) + 'b',
  middle: 'a'.repeat(32) + 'b' + 'a'.repeat(31),
  sharedEnds1: 'a'.repeat(12) + '0'.repeat(44) + 'b'.repeat(8),
  sharedEnds2: 'a'.repeat(12) + '1'.repeat(44) + 'b'.repeat(8),
} as const

const CORPUS = Object.values(HEXES).map(digestOf)

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   The digest
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('reading the digest a deployment reports', () => {
  it('parses a canonical sha256 digest', () => {
    const parse = parseDigest(
      '  sha256:e7c40c2378f8c4462aea63041f6b29b7f2b9ab8e80b9217d6d5faf70dfff1779  ',
    )
    expect(parse.ok).toBe(true)
    if (!parse.ok) return
    expect(parse.digest.algorithm).toBe('sha256')
    expect(parse.digest.hex).toHaveLength(64)
    expect(parse.digest.canonical).toBe(
      'sha256:e7c40c2378f8c4462aea63041f6b29b7f2b9ab8e80b9217d6d5faf70dfff1779',
    )
  })

  it('normalises hex case, which is not a difference in value — and only that', () => {
    // Hex is case-insensitive by definition: AB… and ab… are the same digest and always were,
    // so folding case merges nothing. A one-character difference in the VALUE must survive, and
    // that is the half of this that could actually go wrong.
    const upper = parseDigest(`SHA256:${'A'.repeat(64)}`)
    const lower = parseDigest(`sha256:${'a'.repeat(64)}`)
    const other = parseDigest(`sha256:${'a'.repeat(63)}b`)
    expect(upper.ok && lower.ok && other.ok).toBe(true)
    if (!upper.ok || !lower.ok || !other.ok) return
    expect(upper.digest.canonical).toBe(lower.digest.canonical)
    expect(other.digest.canonical).not.toBe(lower.digest.canonical)
  })

  it('names each way a digest can fail to be one, distinctly', () => {
    // Distinct faults because the screen says something different for each; collapsing them to
    // one "bad digest" would tell an operator with a truncated value the same thing as an
    // operator whose deployment reported nothing at all.
    const fault = (raw: string | null) => {
      const parse = parseDigest(raw)
      return parse.ok ? 'ok' : parse.fault
    }
    expect(fault(null)).toBe('absent')
    expect(fault('   ')).toBe('absent')
    expect(fault('e7c40c2378f8c446')).toBe('noAlgorithm')
    expect(fault(`md5:${'a'.repeat(32)}`)).toBe('unknownAlgorithm')
    expect(fault(`sha256:${'z'.repeat(64)}`)).toBe('notHex')
    expect(fault('sha256:abc123')).toBe('wrongLength')
    expect(fault(`sha512:${'a'.repeat(128)}`)).toBe('ok')
    expect(fault(`sha512:${'a'.repeat(64)}`)).toBe('wrongLength')
  })

  it('groups the hex for reading without losing a character of it', () => {
    // The grouping is the form a person compares against a registry, so it has to be exactly as
    // distinguishing as the digest. Lossless by construction: the chunks concatenate back.
    const hex = HEXES.lastChar
    const groups = groupHex(hex)
    expect(groups.join('')).toBe(hex)
    expect(groups).toHaveLength(64 / GROUP_SIZE)
    // A size that does not divide the length keeps the remainder rather than padding or dropping.
    const ragged = groupHex(hex, 7)
    expect(ragged.join('')).toBe(hex)
    expect(ragged.at(-1)).toHaveLength(64 % 7)
    expect(() => groupHex(hex, 0)).toThrow(RangeError)
  })
})

describe('the display never collapses two digests into one', () => {
  it('the corpus really is six different digests', () => {
    // Non-vacuity for everything below: if the fixtures were accidentally equal, every
    // "distinct" assertion in this block would pass by comparing a value to itself.
    expect(CORPUS).toHaveLength(6)
    expect(new Set(CORPUS.map((d) => d.canonical)).size).toBe(6)
  })

  it('abbreviation on its own IS dangerous — the collision is real, not hypothetical', () => {
    // The detector must be shown to see the thing before its silence means anything. Two
    // different images whose digests share twelve leading and eight trailing characters
    // abbreviate to the same string, and a reader comparing that string learns nothing.
    const a = digestOf(HEXES.sharedEnds1)
    const b = digestOf(HEXES.sharedEnds2)
    expect(a.canonical).not.toBe(b.canonical)
    expect(shortenDigest(a)).toBe(shortenDigest(b))
    expect(collidingShortForms([a, b])).toEqual([shortenDigest(a)])

    // And it reports nothing when there is nothing: two digests that differ at the ends are
    // told apart by the short form, so they are not in the collision list.
    expect(collidingShortForms([digestOf(HEXES.base), digestOf(HEXES.firstChar)])).toEqual([])

    // The same digest listed twice is one digest, not a collision.
    expect(collidingShortForms([a, a])).toEqual([])
  })

  it('so the guarded reference falls back to the whole value rather than stay ambiguous', () => {
    const shorts = CORPUS.map(shortenDigest)
    // The guard is doing work: unguarded, the six digests do NOT have six distinct short forms.
    expect(new Set(shorts).size).toBeLessThan(CORPUS.length)

    const referenced = CORPUS.map((d) => digestReference(d, CORPUS))
    expect(new Set(referenced).size).toBe(CORPUS.length)

    // Each colliding digest fell back to its whole value; the ones that were already
    // unambiguous stayed short, so the fallback is targeted rather than a blanket give-up.
    const ambiguous = digestOf(HEXES.sharedEnds1)
    const unambiguous = digestOf(HEXES.firstChar)
    expect(digestReference(ambiguous, CORPUS)).toBe(ambiguous.canonical)
    expect(digestReference(unambiguous, CORPUS)).toBe(shortenDigest(unambiguous))
    expect(digestReference(unambiguous, CORPUS)).toContain(ELISION)

    // With nothing to be confused with, a short form is fine — and still marked as short, so it
    // can never be mistaken for a whole digest by eye or by a later test.
    expect(digestReference(ambiguous, [])).toBe(shortenDigest(ambiguous))
    expect(shortenDigest(ambiguous)).toContain(ELISION)
    expect(shortenDigest(ambiguous)).toHaveLength(
      'sha256:'.length + SHORT_HEAD + ELISION.length + SHORT_TAIL,
    )
  })

  it('and what the screen actually renders is one string per digest', () => {
    // The end-to-end property, over the value the component draws: the algorithm prefix plus the
    // grouped hex. Six different images must produce six different readings.
    const rendered = CORPUS.map((d) => {
      const shown = describeDigest(d.canonical)
      expect(shown.kind).toBe('digest')
      if (shown.kind !== 'digest') return ''
      return `${shown.algorithm}:${shown.groups.join('')}`
    })
    expect(new Set(rendered).size).toBe(CORPUS.length)
    for (const value of rendered) expect(value).not.toContain(ELISION)
  })

  it('and what the copy control copies is the whole digest, not what was drawn', () => {
    const shown = describeDigest(`sha256:${HEXES.middle}`)
    expect(shown.kind).toBe('digest')
    if (shown.kind !== 'digest') return
    expect(shown.copyText).toBe(`sha256:${HEXES.middle}`)
    expect(shown.copyText).toHaveLength('sha256:'.length + 64)
    expect(shown.copyText).not.toContain(ELISION)
    expect(shown.copyText).not.toContain(' ')
    expect(shown.algorithmLabel).toBe('SHA256')
  })
})

describe('a digest that cannot be read is stated, not hidden', () => {
  it('says which way it failed, and quotes back what was reported', () => {
    const shown = describeDigest('sha256:abc123')
    expect(shown.kind).toBe('fault')
    if (shown.kind !== 'fault') return
    expect(shown.fault).toBe('wrongLength')
    expect(shown.message.length).toBeGreaterThan(20)
    expect(shown.reported).toBe('sha256:abc123')
    expect(shown.reportedShortened).toBe(false)
  })

  it('has nothing to quote back when nothing was reported', () => {
    const shown = describeDigest(null)
    expect(shown.kind).toBe('fault')
    if (shown.kind !== 'fault') return
    expect(shown.fault).toBe('absent')
    expect(shown.reported).toBeNull()
  })

  it('marks the echo when it had to be cut', () => {
    // An unreadable value is echoed so the operator sees the actual string. If it is too long
    // for a layout it is cut — and the cut is MARKED, so a shortened echo can never be read as
    // the whole of what the deployment said.
    const long = 'sha256:' + 'a'.repeat(REPORTED_MAX * 2)
    const capped = capReported(long)
    expect(capped.shortened).toBe(true)
    expect(capped.text).toHaveLength(REPORTED_MAX + ELISION.length)
    expect(capped.text.endsWith(ELISION)).toBe(true)
    // The other direction, so the flag is not simply always true.
    expect(capReported('sha256:abc123')).toEqual({ text: 'sha256:abc123', shortened: false })
  })
})

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   The contract
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

/** A clause that is fine, used as the base for each planted violation below. */
const OK_CLAUSE: ContractClause = {
  id: 'test.ok',
  section: 'trusted',
  text: 'A clause long enough to be a statement about what this account is able to open.',
}

/** A contract with one clause per section — the minimum that passes, before planting a fault. */
const minimalContract = (): ContractClause[] =>
  SECTION_ORDER.map((section) => ({ ...OK_CLAUSE, id: `test.${section}`, section }))

describe('the contract this screen shows', () => {
  it('is complete, and is a contract rather than a heading list', () => {
    const check = checkContract()
    expect(check.problems).toEqual([])
    expect(check.complete).toBe(true)

    expect(SIGN_IN_CONTRACT.length).toBeGreaterThanOrEqual(8)
    for (const section of SECTION_ORDER) {
      const clauses = SIGN_IN_CONTRACT.filter((c) => c.section === section)
      expect(clauses.length, `${section} has no clauses`).toBeGreaterThan(0)
      expect(SECTION_HEADING[section].length).toBeGreaterThan(10)
    }
    // Each clause is a sentence, not a label. Asserted on the shipped copy so a future edit that
    // reduces a clause to three words fails here rather than shipping a contract of stubs.
    for (const clause of SIGN_IN_CONTRACT) {
      expect(clause.text.length, clause.id).toBeGreaterThan(60)
      expect(clause.text.trim()).toBe(clause.text)
    }
  })

  it('names what the server sees and what it cannot, and says which claim is about what', () => {
    // The two halves that make this a contract rather than a reassurance. Read from the shipped
    // clauses, so deleting either side fails here.
    const sees = SIGN_IN_CONTRACT.filter((c) => c.section === 'serverSees')
    const cannot = SIGN_IN_CONTRACT.filter((c) => c.section === 'serverCannotSee')
    expect(sees.length).toBeGreaterThanOrEqual(2)
    expect(cannot.length).toBeGreaterThanOrEqual(2)
    // The "cannot see" side is scoped: it describes the released software, not this page. A
    // contract that claims otherwise would be contradicting the fixed notice beside it.
    expect(cannot.some((c) => /software as released|not the page in front of you/i.test(c.text))).toBe(
      true,
    )
  })

  it('groups into reading order without losing or duplicating a clause', () => {
    const groups = contractSections()
    expect(groups.map((g) => g.section)).toEqual([...SECTION_ORDER])
    expect(groups.map((g) => g.heading)).toEqual(SECTION_ORDER.map((s) => SECTION_HEADING[s]))
    const ids = groups.flatMap((g) => g.clauses.map((c) => c.id))
    expect(ids.length).toBe(SIGN_IN_CONTRACT.length)
    expect(new Set(ids).size).toBe(SIGN_IN_CONTRACT.length)
    // Order within a section is source order — the clauses are written to be read in sequence.
    expect(groups[0]!.clauses.map((c) => c.id)).toEqual(
      SIGN_IN_CONTRACT.filter((c) => c.section === 'trusted').map((c) => c.id),
    )
  })

  it('the completeness check fails on every way a contract can stop being one', () => {
    // Non-vacuity for "the shipped contract is complete": the check must be able to fail. Each
    // planted fault is a real failure mode — a heading with nothing under it, a clause that
    // cannot render because another one took its key, a stub, a blank.
    expect(checkContract(minimalContract()).complete).toBe(true)

    const missingSection = minimalContract().filter((c) => c.section !== 'serverCannotSee')
    const missing = checkContract(missingSection)
    expect(missing.complete).toBe(false)
    expect(missing.problems.join(' ')).toContain(SECTION_HEADING.serverCannotSee)

    const duplicated = [...minimalContract(), { ...OK_CLAUSE, id: 'test.trusted' }]
    expect(checkContract(duplicated).problems.join(' ')).toContain('share the id')

    const blank = minimalContract()
    blank[0] = { ...blank[0]!, text: '   ' }
    expect(checkContract(blank).problems.join(' ')).toContain('has no text')

    const stub = minimalContract()
    stub[0] = { ...stub[0]!, text: 'Your data.' }
    expect(checkContract(stub).problems.join(' ')).toContain('too short')

    const nowhere = minimalContract()
    // A section value from outside the union — the runtime shape a bad merge produces.
    nowhere[0] = { ...nowhere[0]!, section: 'legal' as unknown as ContractClause['section'] }
    expect(checkContract(nowhere).problems.join(' ')).toContain('no section this screen renders')
  })
})

describe('the copy on this screen makes no claim this product cannot keep', () => {
  it('the reassurance detector detects reassurance', () => {
    // Calibrated on the sentences a well-meaning editor actually writes. Without this, "none of
    // our copy reassures" would be satisfied by a regex that matches nothing.
    expect(makesAssuranceClaim('Your data is completely secure.')).not.toBeNull()
    expect(makesAssuranceClaim('Everything you read here stays private.')).not.toBeNull()
    expect(makesAssuranceClaim('We guarantee that nobody else can read it.')).not.toBeNull()
    expect(makesAssuranceClaim('Rest assured, this connection is fine.')).not.toBeNull()
    expect(makesAssuranceClaim('It is impossible to read without your passphrase.')).not.toBeNull()
    // And it does not fire on an honest statement that happens to use the same nouns.
    expect(makesAssuranceClaim('The server never receives the key that decrypts this.')).toBeNull()
    expect(makesAssuranceClaim('Keys are held in memory only.')).toBeNull()
  })

  it('and none of the shipped copy trips it', () => {
    for (const clause of SIGN_IN_CONTRACT) {
      expect(makesAssuranceClaim(clause.text), clause.id).toBeNull()
    }
    for (const [key, text] of Object.entries(SCREEN_COPY)) {
      expect(makesAssuranceClaim(text), key).toBeNull()
      expect(text.trim(), key).toBe(text)
      expect(text.length, key).toBeGreaterThan(0)
    }
  })
})

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   The fixed assurance banner
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('the fixed assurance banner is not this screen’s to alter', () => {
  const bannerProse = proseOf(BANNER_SRC)

  it('the banner has fixed prose, and the containment check can see it', () => {
    // Subject + detector, before any absence is claimed. The sentence is NOT retyped here: it is
    // read from the component that owns it, so this suite cannot drift from the copy and cannot
    // become a second place the wording lives.
    expect(bannerProse.length).toBeGreaterThan(200)
    expect(bannerProse.startsWith('Lower-assurance path (TOTP).')).toBe(true)
    expect(`before ${bannerProse} after`.includes(bannerProse)).toBe(true)
  })

  it('the screen renders it, unconditionally, and never retypes a word of it', () => {
    expect(SCREEN_CODE).toContain("import LowerAssuranceBanner from './LowerAssuranceBanner.svelte'")
    expect(SCREEN_MARKUP).toContain('<LowerAssuranceBanner />')

    // Neither the screen nor the module contains the banner's sentences — not whole, and not the
    // opening fragment a paraphrase would start from.
    const screenProse = proseOf(SCREEN_SRC)
    expect(screenProse).not.toContain(bannerProse)
    expect(screenProse).not.toContain(bannerProse.slice(0, 60))
    expect(codeOnly(MODULE_SRC)).not.toContain(bannerProse.slice(0, 60))
  })

  it('and the screen has no knob that could hide, reorder or qualify it', () => {
    const props = /let\s*\{[\s\S]*?\}\s*=\s*\$props\(\)/.exec(codeOnly(SCREEN_SRC))
    expect(props, 'the screen declares no props at all').not.toBeNull()
    // The subject exists: these are the props it does declare.
    expect(props![0]).toContain('credentials')
    expect(props![0]).toContain('imageDigest')
    // And none of them is about the banner.
    expect(props![0]).not.toMatch(/banner|assurance|notice/i)

    // The render site is at the top level of the markup, not inside any block. Depth counted
    // rather than eyeballed — see the paired assertion below, which proves the counter can
    // report a non-zero depth.
    expect(blockDepthAt(SCREEN_MARKUP, '<LowerAssuranceBanner />')).toBe(0)
  })

  it('the contract cannot grow a paraphrase of it either', () => {
    // The way fixed copy is actually softened is not an edit to the fixed copy — it is a
    // friendlier restatement placed next to it. So a clause that reaches for the banner's
    // subjects is a completeness failure. Detector proved on the banner's own words first.
    expect(echoesBannerSubject(bannerProse)).toBe(true)
    expect(echoesBannerSubject('Keys are held in memory only.')).toBe(false)

    for (const clause of SIGN_IN_CONTRACT) {
      expect(echoesBannerSubject(clause.text), clause.id).toBe(false)
    }

    const trespassing = minimalContract()
    trespassing[0] = {
      ...trespassing[0]!,
      text: 'This is the lower-assurance path, and it is not a zero-knowledge guarantee at all.',
    }
    expect(checkContract(trespassing).problems.join(' ')).toContain('restates the fixed assurance')
  })
})

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   No second authentication path
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

/** Block nesting depth at the first occurrence of `needle` in a piece of Svelte markup. */
function blockDepthAt(markup: string, needle: string): number {
  const at = markup.indexOf(needle)
  expect(at, `${needle} is not in the markup`).toBeGreaterThan(-1)
  const before = markup.slice(0, at)
  const opens = (before.match(/\{#(?:if|each|await|key|snippet)\b/g) ?? []).length
  const closes = (before.match(/\{\/(?:if|each|await|key|snippet)\}/g) ?? []).length
  return opens - closes
}

describe('the sign-in screen is not a second authentication path', () => {
  /** What an authentication path looks like in this codebase. */
  const AUTH_MARKERS: { name: string; pattern: RegExp }[] = [
    { name: 'the portal client', pattern: /PortalClient/ },
    { name: 'a local key unwrap', pattern: /\bunwrap\s*\(/ },
    { name: 'the TOTP verify call', pattern: /loginTotp/ },
    { name: 'a secret field', pattern: /type="password"/ },
    { name: 'the crypto init', pattern: /initAssignmentCrypto/ },
  ]

  it('the markers describe LoginGate, which is the path that exists', () => {
    // Non-vacuity, on the real subject: LoginGate owns the whole credential path, so every
    // marker must fire on it. If one stops matching, the corresponding absence below has
    // silently stopped meaning anything.
    const gate = codeOnly(LOGIN_GATE_SRC)
    for (const { name, pattern } of AUTH_MARKERS) {
      expect(pattern.test(gate), `LoginGate no longer shows ${name}`).toBe(true)
    }
  })

  it('and none of them appears in the sign-in screen', () => {
    for (const { name, pattern } of AUTH_MARKERS) {
      expect(pattern.test(SCREEN_CODE), `the screen has grown ${name}`).toBe(false)
    }
    // Nor any of the modules an auth path needs, nor a request of its own.
    expect(SCREEN_CODE).not.toMatch(/from\s+'[^']*therapist\/(session|keyStore)'/)
    expect(SCREEN_CODE).not.toMatch(/from\s+'[^']*assignments\//)
    expect(SCREEN_CODE).not.toMatch(/\bfetch\s*\(/)
    // The credential entry is the caller's, passed in and rendered — never built here.
    expect(SCREEN_MARKUP).toContain('{@render credentials()}')
  })

  it('offers the credential entry unconditionally — a copy fault must never be an outage', () => {
    // This screen previously gated sign-in on `contract.complete`, so an edit that shortened one
    // clause below the minimum length would have taken the portal offline and shown a clinician
    // internal strings like `Clause "cannot.passphrase" is too short to state anything.` A
    // copy-editing mistake is not a reason to lock people out of a mental-health product, and the
    // vitest suite already fails on that condition long before it could ship.
    //
    // The completeness finding is still rendered — above the entry, where whoever can fix it will
    // see it — but it never stands between anyone and the door.
    const before = SCREEN_MARKUP.slice(0, SCREEN_MARKUP.indexOf('{@render credentials()}'))
    expect(before).not.toContain('{#if contract.complete}')

    // The detector, proved in both directions: this same search DOES find a conditional that is
    // genuinely present, so the absence above is a fact about the markup rather than a search that
    // cannot see `{#if` at all.
    expect(before).toContain('{#if !contract.complete}')
    expect(SCREEN_MARKUP).toContain('{@render credentials()}')
  })
})

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   No clock
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('the module reads no clock', () => {
  it('because a clock inside a callee is invisible to $derived', () => {
    // The bug that shipped in TherapistPortal.svelte: `$derived` tracked only the argument, the
    // time was read inside the function it called, and the idle guard never fired again. There
    // is no time in this module for that to be made with — asserted over the code, since the
    // header comment necessarily names the thing it is banning.
    const detector = /\b(?:Date\.now|new Date)\s*\(/
    expect(detector.test('const t = Date.now()')).toBe(true)
    expect(detector.test('const d = new Date(ms)')).toBe(true)
    expect(detector.test('const groups = groupHex(hex)')).toBe(false)

    // The comment stripper is load-bearing here, so prove it removed the prose that names it.
    expect(MODULE_SRC).toContain('Date.now()')
    expect(detector.test(codeOnly(MODULE_SRC))).toBe(false)
  })
})
