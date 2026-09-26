import { describe, it, expect } from 'vitest'
import { readFileSync, readdirSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { parse } from 'svelte/compiler'
import ts from 'typescript'

/**
 * THE WORD A PERSON READS FOR THE PROFESSIONAL IS "CLINICIAN" (#158).
 *
 * A therapist, a doctor and a psychiatrist are one role to Daymark, and someone who shares with a
 * psychiatrist was being told, on half the screens, that they were sharing with a therapist. The
 * pages are named the same way (#310): the owner console, the clinician console, the admin console
 * and the practice console, each for who uses it.
 *
 * WHAT MAY STILL SAY "therapist", AND WHY. Identifiers, routes, file names and values that are
 * stored or sent: `therapist.html`, `/therapist-keys`, the audit actor `'therapist'`, storage keys
 * such as `daymark.therapist.keys.v1`. Renaming any of them breaks links, saved data or the wire.
 * They are listed below by exact value, each with its reason, and each entry has to be live in the
 * tree — an allowance for a value nothing uses any more is how a rule stops applying unnoticed.
 *
 * WHY THIS PARSES RATHER THAN GREPS. Almost every file under src/ names `therapist` as an
 * identifier (`therapist.displayName`, `{therapist}`, `TherapistPortal`), and comments quote the
 * old wording on purpose. A grep cannot tell a word a person reads from a variable, so the Svelte
 * compiler's own parser and TypeScript's are used to pull out exactly what can reach a screen:
 * markup text, attribute values, and string literals in scripts and modules. Comments are not
 * text, and an expression such as `{therapist.displayName}` is not text either — its value is.
 */

/** This file is at src/lib/components/, so two levels up is src/ and three is the package. */
const SRC = fileURLToPath(new URL('../../', import.meta.url))
const PKG = fileURLToPath(new URL('../../../', import.meta.url))
const PAGES = ['index.html', 'therapist.html', 'admin.html', 'practice.html']

function walk(dir: string): string[] {
  const out: string[] = []
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const path = dir + entry.name
    if (entry.isDirectory()) out.push(...walk(path + '/'))
    else if (/\.(svelte|ts)$/.test(entry.name) && !/\.test\.ts$/.test(entry.name)) out.push(path)
  }
  return out.sort()
}

/** Where an expression's value would go, so text either side of it never runs together. */
const HOLE = '\u0000'
const squash = (s: string) => s.replace(/\s+/g, ' ').trim()

/**
 * Every string a TypeScript module can put in front of a person: string literals and template
 * text, with `'a ' + 'b'` folded into one string the way it renders. Module specifiers are not
 * words, so imports and re-exports are skipped.
 */
function tsStrings(code: string): string[] {
  const sf = ts.createSourceFile('module.ts', code, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)
  const out: string[] = []
  const fold = (n: ts.Node): string | null => {
    if (ts.isStringLiteral(n) || ts.isNoSubstitutionTemplateLiteral(n)) return n.text
    if (ts.isTemplateExpression(n)) return n.head.text + n.templateSpans.map((s) => HOLE + s.literal.text).join('')
    if (ts.isParenthesizedExpression(n)) return fold(n.expression)
    if (ts.isBinaryExpression(n) && n.operatorToken.kind === ts.SyntaxKind.PlusToken) {
      const left = fold(n.left)
      const right = fold(n.right)
      return left !== null && right !== null ? left + right : null
    }
    return null
  }
  const visit = (n: ts.Node): void => {
    if (ts.isImportDeclaration(n) || ts.isExportDeclaration(n)) return
    const folded = fold(n)
    if (folded !== null) {
      out.push(squash(folded))
      return
    }
    ts.forEachChild(n, visit)
  }
  visit(sf)
  return out
}

/**
 * Every string a component can put in front of a person: text in the markup, every static
 * attribute value, string literals inside markup expressions, and the strings in its scripts.
 * Comments are skipped by construction — the parser gives them a node type of their own.
 */
function svelteStrings(code: string): string[] {
  const ast = parse(code, { modern: true })
  const out: string[] = []
  const visit = (n: unknown): void => {
    if (!n || typeof n !== 'object') return
    if (Array.isArray(n)) return n.forEach(visit)
    const node = n as Record<string, unknown>
    if (node.type === 'Comment') return
    if (node.type === 'Text' && typeof node.data === 'string') out.push(squash(node.data))
    if (node.type === 'Literal' && typeof node.value === 'string') out.push(squash(node.value))
    if (node.type === 'TemplateLiteral') {
      const quasis = node.quasis as { value: { cooked?: string; raw: string } }[]
      out.push(squash(quasis.map((q) => q.value.cooked ?? q.value.raw).join(HOLE)))
      return
    }
    for (const [key, value] of Object.entries(node)) if (key !== 'metadata' && key !== 'parent') visit(value)
  }
  visit(ast.fragment)
  for (const block of [ast.instance, ast.module]) {
    // The parser positions every node; the ESTree Program type just does not declare it.
    const at = block?.content as unknown as { start: number; end: number } | undefined
    if (at) out.push(...tsStrings(code.slice(at.start, at.end)))
  }
  return out.filter((s) => s.length > 0)
}

/** The title, the description and the fallback text of an HTML entry page, comments removed. */
function pageStrings(html: string): string[] {
  const bare = html.replace(/<!--[\s\S]*?-->/g, '').replace(/<script[\s\S]*?<\/script>/g, '')
  const out: string[] = []
  for (const m of bare.matchAll(/\bcontent="([^"]*)"/g)) out.push(squash(m[1]!))
  for (const m of bare.matchAll(/>([^<]+)</g)) out.push(squash(m[1]!))
  return out.filter((s) => s.length > 0)
}

function stringsOf(path: string, text: string): string[] {
  if (path.endsWith('.svelte')) return svelteStrings(text)
  if (path.endsWith('.html')) return pageStrings(text)
  return tsStrings(text)
}

const rel = (abs: string) => abs.slice(PKG.length)
const FILES = new Map<string, string>([
  ...walk(SRC).map((abs) => [rel(abs), readFileSync(abs, 'utf8')] as [string, string]),
  ...PAGES.map((p) => [p, readFileSync(PKG + p, 'utf8')] as [string, string]),
])
const VISIBLE = new Map<string, string[]>([...FILES].map(([path, text]) => [path, stringsOf(path, text)]))

const WORD = /\btherapists?\b/i

/**
 * Values that contain the word and are not words anyone reads. Exact values only: a new visible
 * sentence cannot slip through by resembling one of these.
 */
const IDENTIFIERS: { value: string; because: string }[] = [
  { value: 'therapist', because: 'a stored and sent value: the audit actor, the signal author, the pairing domain label' },
  { value: 'owner-to-therapist', because: 'the pairing envelope direction, bound into the key derivation' },
  { value: 'therapist-to-owner', because: 'the pairing envelope direction, bound into the key derivation' },
  { value: `/v1/relations/${HOLE}/therapist-keys`, because: 'a server route, with the relationship in the middle' },
  { value: `${HOLE}/v1/relations/${HOLE}/therapist-keys`, because: 'the same server route, after the server address' },
  { value: '/therapist', because: 'a route the invitation link and the server share' },
  { value: '/therapist.html', because: 'a file name the server serves' },
  { value: './therapist.html', because: 'a file name, as a sibling-relative link' },
  { value: 'daymark.therapist.keys.v1', because: 'a storage key; renaming it strands the saved key record' },
  { value: 'daymark.therapist.inbox-token.v1', because: 'a storage key' },
  { value: 'daymark.therapist.share-seen.v1', because: 'a storage key' },
  { value: 'daymark.pairing.therapist-run.v1', because: 'a storage key' },
  { value: 'therapist-app', because: 'the element id therapist.html mounts on' },
  { value: '#therapist-app mount point missing', because: 'a developer error naming that element id; it fires only when the HTML shell is broken' },
]
const ALLOWED = new Set(IDENTIFIERS.map((i) => i.value))

/** Everything in `strings` that says the word and is not one of the identifiers above. */
function wordsSaid(strings: string[]): string[] {
  return strings.filter((s) => WORD.test(s) && !ALLOWED.has(s))
}

describe('the extractor reads what a person reads, and only that', () => {
  it('walked the whole tree and the four entry pages', () => {
    // Non-vacuity: a walk that found nothing would pass every assertion below.
    expect(FILES.size).toBeGreaterThan(150)
    expect([...FILES.keys()].filter((p) => p.endsWith('.svelte')).length).toBeGreaterThan(60)
    for (const page of PAGES) expect(VISIBLE.get(page)!.length, page).toBeGreaterThan(1)
  })

  it('sees the word in markup text, in an attribute, in an expression and in a script', () => {
    const planted = [
      '<script lang="ts">',
      "  const label: string = 'Ask your therapist'",
      '</script>',
      '<p>Refresh to fetch this therapist’s log.</p>',
      '<input placeholder="therapist@example.com" />',
      "<span>{busy ? 'Waiting for the therapist' : 'Ready'}</span>",
    ].join('\n')
    expect(wordsSaid(svelteStrings(planted))).toEqual([
      'Refresh to fetch this therapist’s log.',
      'therapist@example.com',
      'Waiting for the therapist',
      'Ask your therapist',
    ])
  })

  it('sees the word in a module string, folded across a concatenation', () => {
    const planted = "export const X = 'refusing to seal a share to an unpinned ' +\n  'therapist'\nconst y = `for ${name}, a therapist`"
    expect(wordsSaid(tsStrings(planted))).toEqual([
      'refusing to seal a share to an unpinned therapist',
      `for ${HOLE}, a therapist`,
    ])
  })

  it('sees the word in a page title, a description and a noscript line', () => {
    const planted =
      '<!-- the therapist portal, in a comment --><title>Daymark — Therapist portal</title>' +
      '<meta name="description" content="A therapist portal." /><noscript><p>The therapist portal needs JavaScript.</p></noscript>'
    expect(wordsSaid(pageStrings(planted))).toEqual([
      'A therapist portal.',
      'Daymark — Therapist portal',
      'The therapist portal needs JavaScript.',
    ])
  })

  it('does not read comments, or an identifier inside an expression, as words', () => {
    const planted = [
      '<script lang="ts">',
      '  // the therapist portal, quoted in a comment',
      '  /* Therapist portal */',
      '  let { therapist } = $props()',
      '</script>',
      '<!-- a therapist, in a comment -->',
      '<h3>What {therapist.displayName} can do</h3>',
    ].join('\n')
    expect(wordsSaid(svelteStrings(planted))).toEqual([])
    // The same text as a sentence is seen, so the empty answer above is the comments' doing.
    expect(wordsSaid(svelteStrings('<p>the therapist portal</p>'))).toEqual(['the therapist portal'])
  })
})

describe('no word a person reads says "therapist"', () => {
  it('in any component, module or entry page', () => {
    const offenders: string[] = []
    for (const [path, strings] of VISIBLE) for (const s of wordsSaid(strings)) offenders.push(`${path}: ${s}`)
    expect(offenders).toEqual([])
  })

  it('every identifier allowed to keep the word is still in use, and says why it may', () => {
    const everything = new Set([...VISIBLE.values()].flat())
    for (const { value, because } of IDENTIFIERS) {
      expect(everything.has(value), `${value} is allowed but no longer used`).toBe(true)
      expect(because.length, value).toBeGreaterThan(10)
    }
  })
})

/**
 * The new wording, pinned where it renders. Each line is a substring of one string a person
 * reads in that file, so rewording it back — or anywhere near back — fails here and names it.
 */
const NOW: Record<string, string[]> = {
  'therapist.html': [
    'Daymark Companion — clinician console',
    'Clinician console for a self-hosted Daymark Companion. Access is what each person granted; nothing in it is a diagnosis.',
    'The Daymark Companion clinician console needs JavaScript. It runs in your browser and talks only to the server you signed in to.',
  ],
  'index.html': ['Daymark Companion — owner console'],
  'admin.html': ['Daymark Companion — admin console'],
  'practice.html': ['Daymark Companion — practice console'],
  'src/lib/components/therapist/SignInScreen.svelte': ['Clinician console'],
  'src/lib/components/therapist/LoginGate.svelte': ['Clinician console — sign in'],
  'src/lib/components/therapist/TherapistPortal.svelte': ['Clinician console section'],
  'src/lib/onboarding/audience.ts': ['the clinician console'],
  'src/lib/components/owner/AssignmentInbox.svelte': ['Refresh to fetch assignments from your clinicians.'],
  'src/lib/components/owner/AuditList.svelte': [
    'Pin a clinician to see their access log.',
    "Refresh to fetch this clinician's access log.",
  ],
  'src/lib/components/owner/GrantManager.svelte': ['you turn on exactly what this clinician may do.'],
  'src/lib/components/owner/NotificationSettings.svelte': [
    'A clinician finishes enrolling',
    'A clinician assigns something new',
    'A clinician publishes a new game plan',
  ],
  'src/lib/components/owner/OwnerUnlock.svelte': ['Share your public keys with clinicians out-of-band to pin:'],
  'src/lib/components/owner/PinRecord.svelte': [
    'This browser no longer holds a record of any clinician keys.',
    'The first time you seal a share to a clinician, this browser writes down the fingerprints',
    'Nothing recorded. The first share you seal to a clinician writes their fingerprints here.',
    'A clinician not entered in this session',
    'A clinician who changed their keys looks exactly like someone substituting their own',
    'it does not reach your clinicians.',
    'the next share you seal to a clinician trusts whatever key it is handed',
  ],
  'src/lib/components/owner/PinnedTherapistPicker.svelte': [
    'No pinned clinicians. Pin a clinician — check their fingerprint words with them out of band — before you grant capabilities or share data. The console refuses to seal to an unpinned key.',
    'Select a pinned clinician',
  ],
  'src/lib/components/owner/TherapistKeyIntake.svelte': [
    'A clinician who changed their keys and someone substituting their own look identical from here.',
    "Your clinician's clinician console publishes their two public keys to the server",
    "it cannot tell your clinician's real key from one substituted for it",
  ],
  'src/lib/components/owner/NonDiagnosticBanner.svelte': [
    'Anything a clinician assigns or shares here is guidance from them — never a diagnosis.',
  ],
  'src/lib/components/owner/auditLabels.ts': ['Your clinician'],
  'src/lib/components/owner/InvitePanel.svelte': ['clinician@example.com'],
  'src/lib/components/owner/PairingPanel.svelte': ['clinician@example.com'],
  'src/lib/pairing/copy.ts': ['If that was not your clinician, you can stop this invitation.'],
  'src/lib/companion/content.ts': [
    "There's the hard-moment exercise your clinician set up.",
    "There's the writing exercise your clinician set up, about what matters to you.",
  ],
  'src/lib/admin/chainHead.ts': [
    'exactly as reading the clinician’s published keys is',
    'The server answered that the clinician console is not configured on this deployment, so there are no relationships here and no chain to check.',
  ],
  'src/lib/admin/health.ts': [
    'Clinician credentials locked out on TOTP failures',
    'Live clinician sessions',
    'each enrolled clinician’s TOTP seed',
    'second-factor codes for every enrolled clinician.',
    'TOTP is the only second factor a clinician can enrol here',
    'Client and clinician identities',
  ],
  'src/lib/share/sharecrypto.ts': [
    'refusing to seal a share to an unpinned clinician',
    'meta.recipientFp does not match the clinician X25519 key',
    'the clinician X25519 key is not the pinned one for this relationship',
    'share id, version, creation time or expiry is not in the form a clinician accepts',
    'share is addressed to a different clinician key',
    'sealed CEK could not be opened (not addressed to this clinician)',
  ],
  'src/lib/therapist/pinStore.ts': [
    'the stored clinician pins are unreadable',
    "it could not remember this clinician's keys — nothing was sealed or sent",
    'the key on file for this clinician is already the one you are holding — nothing to rotate',
    'nothing is pinned for this clinician, so there is nothing to rotate',
  ],
  'src/lib/assignments/inbox.ts': ['Could not verify authorship against the pinned clinician key — refused.'],
  'src/lib/assignments/crypto.ts': ['assignment signature does not match the pinned clinician key'],
  'src/lib/assignments/validate.ts': [
    '" is not granted to this clinician',
    'assignment author does not match the granted clinician',
  ],
  'src/lib/therapist/gamePlan.ts': ['game plan signature does not match the pinned clinician key'],
  'src/lib/companion/signals.ts': ['Dialogue is authored either by the app or by a clinician.'],
  'src/lib/therapist/inviteAccept.ts': [
    'an encryption key without its signing key is not a clinician’s record, it is half of one.',
  ],
  'src/lib/setup/shape.ts': ['they accept it on the clinician console,'],
  'src/lib/practice/roles.ts': ['Clinical assistant'],
}

describe('the new wording is where a person reads it', () => {
  it('names only files that exist', () => {
    for (const path of Object.keys(NOW)) expect(FILES.has(path), path).toBe(true)
  })

  it('the check fails on a file that does not say it', () => {
    // Control: the same lookup over the pre-#158 wording of one line does not find the new one.
    const before = svelteStrings('<EmptyState title="Refresh to fetch assignments from your therapists." />')
    expect(before.some((s) => s.includes('Refresh to fetch assignments from your clinicians.'))).toBe(false)
    expect(before.some((s) => s.includes('Refresh to fetch assignments from your therapists.'))).toBe(true)
  })

  for (const [path, lines] of Object.entries(NOW)) {
    it(`${path}`, () => {
      const strings = VISIBLE.get(path) ?? []
      for (const line of lines) expect(strings.some((s) => s.includes(line)), `${path}: ${line}`).toBe(true)
    })
  }
})
