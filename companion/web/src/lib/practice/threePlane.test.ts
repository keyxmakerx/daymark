import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

import {
  ALL_PRACTICE_CAPABILITIES,
  CAPABILITIES,
  PATIENT_GRANT_ONLY_CAPABILITIES,
  READ_CLINICAL_CAPABILITIES,
  READ_MINTING_CAPABILITIES,
  frictionRank,
  readsAnyClinical,
  readsOthersClinical,
  type PracticeCapability,
} from './capabilities'
import {
  ROLES,
  anyClinicalReadCapabilitiesIn,
  capabilitiesOutsideDeclaredPlanes,
  isAdminPlaneOnly,
  readClinicalCapabilitiesIn,
  readMintingCapabilitiesIn,
  roleById,
  type PracticeRole,
  type ReadPosture,
} from './roles'

/**
 * THE THREE-PLANE INVARIANT.
 *
 * docs/COMPANION_ACCESS_CONTROL.md § The three planes makes one claim that the rest of the
 * clinical layer is built to keep:
 *
 *     "admins live in the control and monitoring planes, never the data plane. That is how
 *      'an admin can revoke anyone and see who-accessed-what, yet cannot read a single clinical
 *      note' is TRUE, NOT MARKETING."
 *
 * The document says "not marketing" about itself. That is a promise a paragraph cannot keep. This
 * suite is the difference: after it, the sentence is a property of the code that a build fails on,
 * and the day somebody adds a read scope to the practice-admin preset because a clinic asked for
 * it, they find out from CI rather than from a breach notification.
 *
 * ─── HOW IT IS SHAPED, AND WHY IT IS SHAPED THAT WAY ────────────────────────────────────────────
 *
 * BY ITERATION, NOT BY ROLE. Every structural assertion below quantifies over the whole catalog —
 * `ROLES.filter(...)`, `ALL_PRACTICE_CAPABILITIES.filter(...)` — and never over a hand-written
 * list of the five roles that exist today. A suite of five assertions is a suite that silently
 * stops covering the sixth role, and the sixth role is exactly the one nobody reviewed as
 * carefully as the first five. The specific roles the specification calls out by name get their
 * own named assertions in block (3), but as an ADDITION to the quantified rule, never instead of
 * it.
 *
 * OVERLAPPING, ON PURPOSE. Blocks (1), (2) and (3) all catch a read capability landing in the
 * practice-admin preset, and that redundancy is deliberate. Each one can be defeated on its own —
 * (1) by also declaring the data plane on the role, (2) by mislabelling the capability's
 * `authorization`, (3) by renaming the role — and defeating all three at once is no longer a slip
 * in a hurry, it is a decision somebody had to make in the open, in a diff, three times.
 *
 * AGAINST THE DOCUMENT, AT RUN TIME. Block (4) reads the specification off disk and compares it
 * cell by cell with the catalog. This is the assertion most likely to be left out of a suite like
 * this, and the one that decays fastest without: code and specification do not drift because
 * anybody decided to diverge, they drift because a table was edited on a Tuesday and nothing
 * connected the edit to the model. If the document grows a ninth role, this suite fails until
 * somebody models it.
 *
 * NON-VACUOUS BY CONSTRUCTION. A guard shaped like `expect(offenders).toEqual([])` has one failure
 * mode worse than every other: it goes green because it matched nothing. So every list here is
 * asserted non-empty before it is filtered, the document parser is proven to reject a section that
 * does not exist, and the read-capability detector is fired at a deliberately malformed role in
 * block (0) to show it can see the violation it exists to find.
 *
 * WHETHER THIS SUITE ACTUALLY CATCHES ANYTHING WAS TESTED, NOT ASSUMED. Adding 'read.share' to the
 * org-admin preset in roles.ts turns eight assertions red here — in blocks (1), (2) and (3) — plus
 * one more in catalog.test.ts. Adding 'consent.orgTeam' to it turns four red — in blocks (0), (2)
 * and (3), plus catalog.test.ts — where before this suite grew the minting rule it turned none red
 * at all. Rewording one cell of the document's role table turns block (4) red and names the cell. If you
 * change the model here, do all three again: an invariant test that has never once been shown to
 * fail is an invariant test that has never been shown to assert anything.
 *
 * THE HOLE THAT WAS IN IT, RECORDED SO THE SHAPE OF IT IS NOT RE-LEARNED THE HARD WAY. Until block
 * (2) grew "nobody but the patient may authorize somebody else's reading", every rule here was
 * about a capability that READS. `consent.orgTeam` reads nothing, is control-plane, and is
 * conferred by a role — so all five blocks skipped it, and an org admin could be handed the
 * capability the specification itself calls "a whole team's worth of future read capability in one
 * signature" with all 51 assertions still green. The lesson generalises past this one id: the
 * dangerous act in an access-control model is not always a read, and a suite that only knows how to
 * look for reads is blind in exactly the direction an author who wants a shortcut will walk.
 */

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   Reading the specification.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

/** src/lib/practice -> src/lib -> src -> web -> companion -> repo root. */
const SPEC_PATH = fileURLToPath(new URL('../../../../../docs/COMPANION_ACCESS_CONTROL.md', import.meta.url))
const SPEC = readFileSync(SPEC_PATH, 'utf8')

/**
 * Markdown noise removed so a comparison is about the words rather than about the typography.
 *
 * The document is written with typographic punctuation — it uses the NON-BREAKING HYPHEN U+2011 in
 * "server-enforced", "zero-knowledge", "hash-chained" and a dozen other places — so a naive
 * `includes()` against an ASCII string in this file would fail for a reason that has nothing to do
 * with what either text says. Both sides go through this, so the comparison is symmetrical.
 */
function normalizeText(s: string): string {
  return s
    .replace(/‑/g, '-')
    .replace(/[‘’]/g, "'")
    .replace(/[“”]/g, '"')
    .replace(/[*`]/g, '')
    .replace(/\s+/g, ' ')
    .trim()
}

/** The prose, with blockquote markers dropped, for the verbatim-claim assertions. */
const SPEC_PROSE = normalizeText(SPEC.replace(/^\s*>\s?/gm, ''))

/** Every `##` / `###` heading, normalized — the set a capability's `sections` must name from. */
const SPEC_HEADINGS = new Set(
  [...SPEC.matchAll(/^#{2,3} +(.+?)\s*$/gm)].map((m) => normalizeText(m[1]!)),
)

/** Top-level (`##`) sections, keyed by heading. */
function topLevelSections(): Map<string, string> {
  const out = new Map<string, string>()
  for (const part of SPEC.split(/^## /m).slice(1)) {
    const nl = part.indexOf('\n')
    out.set(normalizeText(nl < 0 ? part : part.slice(0, nl)), nl < 0 ? '' : part.slice(nl + 1))
  }
  return out
}
const SECTIONS = topLevelSections()

/** Pipe-table rows in a chunk of markdown, as normalized cells. Separator rows are dropped. */
function tableRows(body: string): string[][] {
  return body
    .split('\n')
    .map((l) => l.trim())
    .filter((l) => l.startsWith('|') && l.endsWith('|'))
    .map((l) => l.slice(1, -1).split('|').map(normalizeText))
    .filter((cells) => !cells.every((c) => /^:?-{3,}:?$/.test(c)))
}

const ROLE_SECTION = SECTIONS.get('Role catalog') ?? ''
const ROLE_TABLE = tableRows(ROLE_SECTION)
const ROLE_HEADER = ROLE_TABLE[0] ?? []

interface SpecRole {
  label: string
  manages: string
  reads: string
}

const SPEC_ROLES: SpecRole[] = ROLE_TABLE.slice(1).map((cells) => ({
  label: cells[0] ?? '',
  manages: cells[1] ?? '',
  reads: cells[2] ?? '',
}))

/**
 * The document's "Normally reads clinical content?" answer, as the machine value roles.ts uses.
 *
 * Returns null when the cell says something this classifier has never seen, and block (4) treats a
 * null as a failure rather than skipping the row. That matters: the tempting way to write this is
 * to fall through to 'never' on an unrecognised cell, which would mean an edit rewording the
 * clinician row into something unparseable would be read as "reads nothing" and pass.
 */
function postureFromSpecCell(cell: string): ReadPosture | null {
  const t = cell.toLowerCase()
  // Checked FIRST, because the supervisor's answer is the one that says both things at once: a
  // consented grant may exist, and the title never produces it.
  if (t.includes('never by title') || t.includes('only via explicit, consented grant')) {
    return 'only-by-separate-grant'
  }
  if (/^no\b/.test(t)) return 'never'
  if (/^their own data/.test(t)) return 'own-data'
  if (/^narrowed\b/.test(t)) return 'granted-narrowed'
  if (/^yes\b/.test(t)) return 'granted'
  return null
}

/** `role: capability` lines, so a failure names something you can open and fix. */
const offendersIn = (role: PracticeRole, caps: readonly PracticeCapability[]) =>
  caps.map((c) => `${role.id}: ${c}`)

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   (0) The suite has a subject, and the detectors detect.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('(0) this suite can fail', () => {
  it('read the specification off disk', () => {
    expect(SPEC.length).toBeGreaterThan(5_000)
    expect(SPEC).toContain('## Role catalog')
  })

  it('the section parser found the role catalog, and rejects a section that does not exist', () => {
    // Non-vacuity for every assertion in block (4). A parser that returned the whole document, or
    // an empty string, for any heading would make the completeness checks meaningless.
    expect(ROLE_SECTION.length).toBeGreaterThan(200)
    expect(SECTIONS.get('The three planes')).toBeDefined()
    expect(SECTIONS.get('No Such Section')).toBeUndefined()
  })

  it('the role table parsed, with the columns it is supposed to have', () => {
    // THE GUARD AGAINST A VACUOUS PASS. If the table is ever reformatted out of pipe syntax, or
    // moved to another section, this parser yields zero rows — and every "the document and the
    // code agree" assertion below would then be comparing two empty sets and reporting success.
    // So the count is asserted here, loudly, before anything is compared.
    expect(SPEC_ROLES.length, 'parsed no roles out of the specification — the parser is broken, not the catalog').toBeGreaterThan(0)
    expect(SPEC_ROLES.length).toBeGreaterThanOrEqual(8)
    expect(ROLE_HEADER).toEqual(['Role', 'Manages', 'Normally reads clinical content?'])
    expect(SPEC_ROLES.map((r) => r.label)).toContain('Org admin')
    expect(SPEC_ROLES.map((r) => r.label)).not.toContain('God admin')
  })

  it('the catalog is populated', () => {
    expect(ALL_PRACTICE_CAPABILITIES.length).toBeGreaterThan(20)
    expect(ROLES.length).toBeGreaterThanOrEqual(8)
  })

  it('there are read-clinical capabilities to find', () => {
    // If this set were empty, every "no role carries a read capability" assertion below would pass
    // on the empty intersection and prove nothing whatsoever.
    expect(READ_CLINICAL_CAPABILITIES.length).toBeGreaterThan(0)
    expect(READ_CLINICAL_CAPABILITIES).toContain('read.share')
    expect(READ_CLINICAL_CAPABILITIES).toContain('read.supervision')
    expect(PATIENT_GRANT_ONLY_CAPABILITIES.length).toBeGreaterThan(0)
  })

  it('there are read-MINTING capabilities to find, and they are not the read scopes', () => {
    // The set the patient-root-of-consent rule is quantified over, asserted populated before it is
    // used to filter anything. It is also asserted to be DISJOINT from the read scopes, because
    // that disjointness is the reason the rule needs its own detector: every minting capability
    // reads nothing and lives in the control plane, so every filter written against reading walks
    // straight past it. A suite that had only the read-scope detector would be blind here.
    expect(READ_MINTING_CAPABILITIES.length).toBeGreaterThan(1)
    expect(READ_MINTING_CAPABILITIES).toContain('grant.issue')
    expect(READ_MINTING_CAPABILITIES).toContain('consent.orgTeam')
    for (const cap of READ_MINTING_CAPABILITIES) {
      expect(CAPABILITIES[cap].reads, `${cap} both mints read and reads — say which`).toBe('none')
      expect(READ_CLINICAL_CAPABILITIES).not.toContain(cap)
    }
  })

  it('the minting detector sees a preset that can authorize somebody else’s reading', () => {
    // Same shape as the read-scope detector below, on the capability the specification calls a
    // whole team's worth of future read capability in one signature. Written out rather than
    // spread from the live preset, so this stays a test of the DETECTOR whatever the catalog holds.
    const admin = roleById('org-admin')
    const tampered: PracticeRole = { ...admin, manages: ['audit.review', 'consent.orgTeam'] }
    expect(readMintingCapabilitiesIn(tampered)).toEqual(['consent.orgTeam'])
    expect(readMintingCapabilitiesIn({ ...admin, manages: ['audit.review', 'grant.issue'] })).toEqual(['grant.issue'])
    expect(readMintingCapabilitiesIn(admin)).toEqual([])
    // And it is not the read-scope detector wearing a different name: that one sees nothing here.
    expect(readClinicalCapabilitiesIn(tampered)).toEqual([])
  })

  it('the detector sees a read capability in a preset that has one', () => {
    // The exact violation this suite exists to catch, performed on a throwaway copy of the role
    // most likely to attract it. This is the assertion that makes the green in block (1) mean
    // something: the detector is not returning an empty list because it cannot see.
    const admin = roleById('org-admin')
    // The preset is written out rather than spread from the live one, so that this stays a test of
    // the DETECTOR whatever the catalog happens to contain. Deriving it from admin.manages would
    // make the expectation depend on the very thing the next line is checking.
    const tampered: PracticeRole = { ...admin, manages: ['audit.review', 'read.share'] }
    expect(readClinicalCapabilitiesIn(tampered)).toEqual(['read.share'])
    expect(readClinicalCapabilitiesIn(admin)).toEqual([])
    expect(isAdminPlaneOnly(admin)).toBe(true)
    expect(isAdminPlaneOnly(roleById('patient'))).toBe(false)
  })
})

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   (1) "Admins live in the control and monitoring planes, never the data plane."
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('(1) admins live in the control and monitoring planes, never the data plane', () => {
  const adminPlaneOnly = ROLES.filter(isAdminPlaneOnly)

  it('there are admin-plane-only roles, and the predicate is a filter rather than a pass', () => {
    expect(adminPlaneOnly.length).toBeGreaterThanOrEqual(4)
    expect(adminPlaneOnly.map((r) => r.id)).toEqual(
      expect.arrayContaining(['front-desk', 'supervisor', 'org-admin', 'platform-sysadmin']),
    )
    // Some role must be OUTSIDE the filter, or "admin-plane-only" would just mean "a role".
    expect(ROLES.length).toBeGreaterThan(adminPlaneOnly.length)
  })

  it('no control- or monitoring-plane role carries a read-clinical capability', () => {
    // THE CLAIM, quantified. Every role, forever, not the four that exist today.
    const offenders = adminPlaneOnly.flatMap((r) => offendersIn(r, readClinicalCapabilitiesIn(r)))
    expect(offenders, 'a role outside the data plane holds a capability that reads clinical content').toEqual([])
  })

  it('nor any capability that opens clinical content at all, their own included', () => {
    // Stricter than the line above and worth stating separately: an admin has no clinical record
    // of their own in this system, so 'own' is not a loophole they can be handed either.
    const offenders = adminPlaneOnly.flatMap((r) => offendersIn(r, anyClinicalReadCapabilitiesIn(r)))
    expect(offenders).toEqual([])
  })

  it('nor any data-plane capability whatsoever', () => {
    const offenders = adminPlaneOnly.flatMap((r) =>
      offendersIn(r, r.manages.filter((c) => CAPABILITIES[c].plane === 'data')),
    )
    expect(offenders).toEqual([])
  })

  it('no role reaches into a plane it does not declare', () => {
    // The containment half. Declaring the planes and then listing the capabilities is two chances
    // to say the same thing, and this is what makes disagreeing between them a failure — a role
    // cannot quietly acquire data-plane reach while still describing itself as an admin.
    const offenders = ROLES.flatMap((r) => offendersIn(r, capabilitiesOutsideDeclaredPlanes(r)))
    expect(offenders).toEqual([])
  })

  it('every role declares at least one plane and exercises every plane it declares', () => {
    // An idle plane declaration is either a mistake or a hedge, and both are worth catching: a
    // role that declares the data plane and holds nothing in it is a role that has left the door
    // open for a capability nobody will re-review.
    const idle = ROLES.flatMap((r) => {
      expect(r.planes.length, `${r.id} declares no plane`).toBeGreaterThan(0)
      const used = new Set(r.manages.map((c) => CAPABILITIES[c].plane))
      return r.planes.filter((p) => !used.has(p)).map((p) => `${r.id}: declares ${p}, uses nothing in it`)
    })
    expect(idle).toEqual([])
  })
})

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   (2) "Read capability is separate and comes only from a patient grant."
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('(2) a role never carries a key', () => {
  it('no role preset — admin or clinical — reads another person’s clinical content', () => {
    // The global form, which covers the clinicians too. A clinician reading a granted client's
    // record is the normal case and is NOT this: it comes from the owner-signed Grant that
    // assignments/grant.ts already implements, never from the fact of being a clinician.
    const offenders = ROLES.flatMap((r) => offendersIn(r, readClinicalCapabilitiesIn(r)))
    expect(offenders, 'membership in an org must never grant read').toEqual([])
  })

  it('no role preset contains a capability that only a patient grant can mint', () => {
    const offenders = ROLES.flatMap((r) =>
      offendersIn(r, r.manages.filter((c) => PATIENT_GRANT_ONLY_CAPABILITIES.includes(c))),
    )
    expect(offenders).toEqual([])
  })

  it('every read-clinical capability in the vocabulary is grant-only or break-glass', () => {
    // The converse, and the reason the rule above cannot be walked around by inventing a read
    // scope with a role-shaped authorization. There is no third way to acquire read.
    const wrong = READ_CLINICAL_CAPABILITIES.filter(
      (c) => CAPABILITIES[c].authorization !== 'grant-only' && CAPABILITIES[c].authorization !== 'break-glass',
    )
    expect(wrong, 'a read scope that something other than a patient grant could mint').toEqual([])
  })

  it('no role-conferred capability reads anyone but the actor themselves', () => {
    // The patient's own key is the single role-conferred data-plane capability in the catalog, and
    // it reads exactly one record: theirs. Written as a property rather than as "except the
    // patient", because a rule with a named exception stops covering anything the day the name
    // changes.
    const roleConferred = ALL_PRACTICE_CAPABILITIES.filter((c) => CAPABILITIES[c].authorization === 'role')
    expect(roleConferred.length).toBeGreaterThan(5)
    expect(roleConferred.filter(readsOthersClinical)).toEqual([])
    // And the 'own' case really is populated, so the assertion above is not passing by emptiness.
    expect(roleConferred.filter(readsAnyClinical)).toEqual(['keys.manage'])
  })

  it('nobody but the patient may authorize somebody else’s reading', () => {
    /*
     * THE RULE THIS SUITE PREVIOUSLY MADE ONLY ABOUT `grant.issue`, quantified over the vocabulary.
     *
     * § Consent model: "Patient is always the root of consent. Roles decide who may REQUEST access;
     * the patient's grant is what AUTHORIZES it." Pinning that to one capability by name was pinning
     * one of two doors: `consent.orgTeam` is the other, and by the specification's own description
     * it is the wider one — a standing authorisation for a team whose members are chosen afterwards,
     * by the practice. Handing it to an org admin would let an administrator sign a patient's care-
     * team consent and then pick the team, which is the god admin the design exists to rule out,
     * arrived at without a single read scope changing hands.
     *
     * So the assertion is over the SET, and the set is a declared fact per capability rather than a
     * list in this file — see CapabilityFacts.mintsRead. A capability added next year that mints
     * read has to say so in its own diff, and the moment it does, it is bound by this line.
     */
    expect(READ_MINTING_CAPABILITIES.length).toBeGreaterThan(1)
    for (const cap of READ_MINTING_CAPABILITIES) {
      const holders = ROLES.filter((r) => r.manages.includes(cap)).map((r) => r.id)
      expect(holders, `${cap} authorizes somebody else’s reading and is held by more than the patient`).toEqual([
        'patient',
      ])
    }
    // The same statement from the role's side, so a role acquiring one is named as the offender.
    const offenders = ROLES.filter((r) => r.id !== 'patient').flatMap((r) => offendersIn(r, readMintingCapabilitiesIn(r)))
    expect(offenders, 'a role other than the patient can authorize somebody else’s reading').toEqual([])
    // And the patient really does hold them, or the rule above is satisfied by a catalog in which
    // nobody can consent to anything at all.
    expect(readMintingCapabilitiesIn(roleById('patient')).sort()).toEqual([...READ_MINTING_CAPABILITIES].sort())
  })

  it('minting read is priced, wherever it is reached from', () => {
    // § The annoyance budget: "Grant, extend, or widen a share | Step-up (MFA) | Creates new read
    // capability — the actual risk". The price follows the act rather than the name of the act, so
    // it is asserted over the set for the same reason the rule above is.
    const free = READ_MINTING_CAPABILITIES.filter((c) => frictionRank(CAPABILITIES[c].friction) === 0)
    expect(free, 'creating read capability for somebody else must cost something').toEqual([])
  })

  it('break-glass is emergency-only and belongs to no role at all', () => {
    // § Clinician turnover: "A transfer must never route through break-glass. A planned departure
    // is not an emergency, and that is the one door this design must not let it open." A door no
    // title opens is the only way to keep that true.
    const breakGlass = ALL_PRACTICE_CAPABILITIES.filter((c) => CAPABILITIES[c].authorization === 'break-glass')
    expect(breakGlass.length).toBeGreaterThan(0)
    const offenders = ROLES.flatMap((r) => offendersIn(r, r.manages.filter((c) => breakGlass.includes(c))))
    expect(offenders).toEqual([])
    for (const c of breakGlass) expect(CAPABILITIES[c].friction).toBe('maximum')
  })
})

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   (3) The roles the specification names, named back.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('(3) the roles the specification calls out by name', () => {
  it('the org admin can revoke anyone and review the log, and can read no clinical content', () => {
    // BOTH HALVES. A test that only checked the prohibition would pass just as happily on an org
    // admin who could do nothing at all, and an admin who cannot revoke is not a safer system —
    // it is one where the safe direction became impossible, which § The annoyance budget names as
    // its own failure.
    const admin = roleById('org-admin')
    expect(admin.manages).toContain('access.revoke')
    expect(admin.manages).toContain('access.killSwitch')
    expect(admin.manages).toContain('audit.review')
    expect(admin.manages).toContain('member.manage')
    expect(admin.manages).toContain('role.assign')
    expect(readClinicalCapabilitiesIn(admin)).toEqual([])
    expect(admin.planes).not.toContain('data')
    expect(admin.reads).toBe('never')
  })

  it('the front desk schedules and invites, and reads no notes', () => {
    const desk = roleById('front-desk')
    expect(desk.manages).toContain('schedule.manage')
    expect(desk.manages).toContain('member.invite')
    expect(readClinicalCapabilitiesIn(desk)).toEqual([])
    expect(desk.reads).toBe('never')
    // "membership logistics" is paperwork, not authority: the front desk must not be able to admit
    // a member, because § Orgs makes membership the thing that "drives access".
    expect(desk.manages).not.toContain('member.manage')
    expect(desk.manages).not.toContain('role.assign')
    // Even the mildest read scope in the system is a grant, not a perk of the desk.
    expect(desk.manages).not.toContain('read.scheduleMetadata')
  })

  it('the platform sysadmin runs the box and holds no key', () => {
    const ops = roleById('platform-sysadmin')
    expect(ops.manages).toContain('server.operate')
    expect(readClinicalCapabilitiesIn(ops)).toEqual([])
    expect(ops.planes).not.toContain('data')
    expect(ops.reads).toBe('never')
  })

  it('the supervisor does not read by title', () => {
    // § Role catalog, supervisor row: "Only via explicit, consented grant (clinical supervision),
    // NEVER BY TITLE". The supervision read scope exists in the vocabulary precisely so that its
    // absence from this preset is a fact and not an omission — and no other role holds it either.
    const supervisor = roleById('supervisor')
    expect(READ_CLINICAL_CAPABILITIES).toContain('read.supervision')
    expect(supervisor.manages).not.toContain('read.supervision')
    expect(readClinicalCapabilitiesIn(supervisor)).toEqual([])
    expect(supervisor.planes).not.toContain('data')
    expect(supervisor.reads).toBe('only-by-separate-grant')
    const holders = ROLES.filter((r) => r.manages.includes('read.supervision')).map((r) => r.id)
    expect(holders, 'supervision read is a grant the client signs, not a role').toEqual([])
  })

  it('the patient holds their own key and every consent decision', () => {
    // The root of consent, and the counterweight to all of the above: the one participant who
    // reads clinical content by right rather than by grant reads only their own.
    const patient = roleById('patient')
    expect(patient.manages).toContain('keys.manage')
    expect(patient.manages).toContain('grant.issue')
    expect(patient.manages).toContain('grant.revoke')
    expect(patient.manages).toContain('audit.viewOwn')
    expect(patient.reads).toBe('own-data')
    expect(readClinicalCapabilitiesIn(patient)).toEqual([])
    expect(anyClinicalReadCapabilitiesIn(patient)).toEqual(['keys.manage'])
    // Nobody else may issue a grant on somebody's behalf. This is what "the patient is always the
    // root of consent" means once it is a list of ids.
    const issuers = ROLES.filter((r) => r.manages.includes('grant.issue')).map((r) => r.id)
    expect(issuers).toEqual(['patient'])
    // And the same for the OTHER way a reader gets authorized, which is the one a by-name rule
    // misses: org-consent is a patient signing for a team the practice then chooses, so it is the
    // patient's signature or it is nobody's. Block (2) states this over the whole set; it is
    // repeated here by name because this is the row somebody will argue about with a clinic on the
    // phone, and an argument is easier to have against a named assertion.
    const consenters = ROLES.filter((r) => r.manages.includes('consent.orgTeam')).map((r) => r.id)
    expect(consenters, 'an org admin signing a patient’s care-team consent is not a shortcut, it is the god admin').toEqual(['patient'])
    expect(patient.manages).toContain('consent.orgTeam')
  })
})

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   (4) The catalog and the specification describe the same eight roles.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('(4) the catalog is complete against the specification', () => {
  const specLabels = SPEC_ROLES.map((r) => r.label)
  const codeLabels = ROLES.map((r) => r.docLabel)
  const specByLabel = new Map(SPEC_ROLES.map((r) => [r.label, r]))

  it('models every role the document lists', () => {
    const unmodelled = specLabels.filter((l) => !codeLabels.includes(l))
    expect(unmodelled, 'the specification lists roles this catalog does not model').toEqual([])
  })

  it('invents no role the document does not list', () => {
    const invented = codeLabels.filter((l) => !specLabels.includes(l))
    expect(invented, 'this catalog models roles the specification does not list').toEqual([])
  })

  it('models exactly as many roles as the document lists', () => {
    // Catches a duplicated row, which the two set comparisons above would both wave through.
    expect(ROLES.length).toBe(SPEC_ROLES.length)
  })

  it('transcribes the "Manages" cell of every row verbatim', () => {
    // Transcription, not paraphrase, so an edit to the document's own words fails here and names
    // the row. The preset itself is a reading of this phrase and cannot be diffed mechanically —
    // this is the half that can be, so it is.
    const drifted = ROLES.filter((r) => specByLabel.get(r.docLabel)?.manages !== r.managesSummary).map(
      (r) => `${r.docLabel}: code "${r.managesSummary}" vs spec "${specByLabel.get(r.docLabel)?.manages}"`,
    )
    expect(drifted).toEqual([])
  })

  it('transcribes the "Normally reads clinical content?" cell of every row verbatim', () => {
    const drifted = ROLES.filter((r) => specByLabel.get(r.docLabel)?.reads !== r.readsSummary).map(
      (r) => `${r.docLabel}: code "${r.readsSummary}" vs spec "${specByLabel.get(r.docLabel)?.reads}"`,
    )
    expect(drifted).toEqual([])
  })

  it('reads the same answer out of that column as the document gives', () => {
    // The load-bearing column, classified from the document's own prose and compared with the
    // machine value. An unclassifiable cell fails rather than defaulting, so a reworded row cannot
    // be silently read as "reads nothing".
    const wrong: string[] = []
    for (const r of ROLES) {
      const cell = specByLabel.get(r.docLabel)!.reads
      const posture = postureFromSpecCell(cell)
      if (posture === null) wrong.push(`${r.docLabel}: cannot classify "${cell}"`)
      else if (posture !== r.reads) wrong.push(`${r.docLabel}: code ${r.reads} vs spec ${posture}`)
    }
    expect(wrong).toEqual([])
    // And the classifier is not answering the same thing to everything.
    expect(new Set(ROLES.map((r) => r.reads)).size).toBeGreaterThanOrEqual(4)
  })

  it('every section a capability cites is a section the document has', () => {
    // Citations are how a later reader diffs the two documents; a citation pointing at a heading
    // that was renamed away is worse than none, because it looks checked.
    const cited = new Set(ALL_PRACTICE_CAPABILITIES.flatMap((c) => CAPABILITIES[c].sections))
    expect(cited.size).toBeGreaterThan(5)
    expect([...cited].filter((s) => !SPEC_HEADINGS.has(s))).toEqual([])
    // The heading set is real, not "everything".
    expect(SPEC_HEADINGS.has('Role catalog')).toBe(true)
    expect(SPEC_HEADINGS.has('Section That Does Not Exist')).toBe(false)
  })

  it('every capability cites at least one section', () => {
    const uncited = ALL_PRACTICE_CAPABILITIES.filter((c) => CAPABILITIES[c].sections.length === 0)
    expect(uncited).toEqual([])
  })

  it('the sentences this model was built from are still the ones the document says', () => {
    // If the specification's central claims are edited away, this model is orphaned and its author
    // should hear about it from a failing build rather than from a reader.
    expect(SPEC_PROSE).toContain('admins live in the control and monitoring planes, never the data plane')
    expect(SPEC_PROSE).toContain(
      'revoke anyone and see who-accessed-what, yet cannot read a single clinical note',
    )
    expect(SPEC_PROSE).toContain(
      'Roles gate actions (server-enforced). Read capability is separate and comes only from a patient grant.',
    )
    expect(SPEC_PROSE).toContain('A patient/client is not owned by the org.')
    expect(SPEC_PROSE).toContain('never by title')
    expect(SPEC_PROSE).toContain('There is no single "god" admin')
  })
})

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   (5) The annoyance budget's asymmetry.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('(5) the safe direction is never the expensive one', () => {
  it('revoking costs no more than granting', () => {
    // § The annoyance budget: "Never make the safe direction expensive. Revoking, narrowing a
    // share, and turning something off must always be easier than granting, widening, and turning
    // on. Asymmetry is the point." An asymmetry is only checkable if both prices are on one scale,
    // which is what `friction` is for.
    const grant = frictionRank(CAPABILITIES['grant.issue'].friction)
    expect(grant).toBeGreaterThan(0) // granting really is priced, or the comparison is empty
    expect(frictionRank(CAPABILITIES['grant.revoke'].friction)).toBeLessThanOrEqual(grant)
    expect(frictionRank(CAPABILITIES['access.revoke'].friction)).toBeLessThanOrEqual(grant)
    expect(frictionRank(CAPABILITIES['access.killSwitch'].friction)).toBeLessThanOrEqual(
      frictionRank(CAPABILITIES['member.manage'].friction),
    )
  })

  it('reading what you already hold a grant for costs nothing beyond the session', () => {
    // "The grant was the decision; charging again teaches people to hate the system." Break-glass
    // is the deliberate exception and is checked separately in block (2).
    const priced = READ_CLINICAL_CAPABILITIES.filter(
      (c) => CAPABILITIES[c].authorization === 'grant-only' && CAPABILITIES[c].friction !== 'none',
    )
    expect(priced, 'a grant already held is being charged for a second time').toEqual([])
  })

  it('creating new read capability is the thing that is priced', () => {
    expect(CAPABILITIES['grant.issue'].friction).toBe('step-up')
    expect(CAPABILITIES['consent.orgTeam'].friction).toBe('step-up')
    expect(CAPABILITIES['member.manage'].friction).toBe('step-up')
    expect(CAPABILITIES['role.assign'].friction).toBe('step-up')
  })

  it('the patient is never charged the maximum for anything they hold', () => {
    // "The patient's own friction is capped hardest. A person in a bad moment must never be locked
    // out of their own data by a security measure meant to constrain someone else."
    const patient = roleById('patient')
    const maxed = patient.manages.filter((c) => CAPABILITIES[c].friction === 'maximum')
    expect(maxed).toEqual([])
    expect(CAPABILITIES['keys.manage'].friction).toBe('none')
    expect(CAPABILITIES['audit.viewOwn'].friction).toBe('none')
  })
})
