/*
 * SIGN-IN — the contract a person reads before they are trusted with someone else's record,
 * and the digest of the image serving them, as plain data.
 *
 * WHAT THIS SCREEN IS FOR. Signing in here opens another person's mental-health record. The
 * plan (COMPANION_WEB_REDESIGN_PLAN.md, Phase 3 item 4) calls it a "two-column contract": on
 * one side the credential entry, on the other an explicit statement of what the person signing
 * in is about to be trusted with and what the server does and does not learn. This module owns
 * the second column and the digest control. It owns no credential, no key, no request.
 *
 * WHY THE CONTRACT IS DATA AND NOT MARKUP. Every sentence below is a premade constant written
 * by a person. Holding them here rather than in the component buys three things a <p> cannot:
 * the set can be checked for COMPLETENESS (a section that quietly lost its only clause is a
 * contract with a hole in it, and a hole in this contract is a person consenting to less than
 * they were told); the wording can be checked for the claims this product may not make; and
 * both checks run in vitest, where a failure is loud, instead of in a review someone skipped.
 * Nothing here is generated, inferred, or phrased by a model.
 *
 * WHY THE MODULE REFUSES TO STATE THE ASSURANCE CAVEAT ITSELF. The fixed LowerAssuranceBanner
 * copy is quoted verbatim in COMPANION_SECURITY.md and asserted character-for-character by
 * components/invariants.tree.test.ts. It is rendered by the component that owns it and is
 * retyped nowhere — not here, not in the screen. `checkContract` actively rejects a clause that
 * reaches for the banner's subjects, because the way fixed copy actually gets softened is not
 * an edit to the fixed copy: it is a friendlier paraphrase added beside it.
 *
 * WHY THE DIGEST IS SHOWN WHOLE. A truncated hash is a hash you cannot compare. `sha256:e7c40c…`
 * and a tampered image whose digest shares those eight characters read identically, and the
 * reader has no way to know. So the value a person sees is the entire digest, grouped for
 * legibility but never elided; `shortenDigest` exists for the one place a whole hash does not
 * fit, and `digestReference` will not hand you a short form without the set it has to stay
 * distinct from. See signIn.test.ts, "display never collapses two digests into one".
 *
 * WHY NOTHING HERE COMPARES THE DIGEST TO ANYTHING. It would be one line and it would be a lie:
 * the comparison would be run by the same page whose integrity is in question, and a tampered
 * page would simply report that it matched. Worse, it would put a verdict on screen — the
 * software telling an operator that things checked out — which is the shape this product does
 * not draw anywhere, on any surface. The digest is displayed and copyable so it can be checked
 * somewhere that is not this page. That is the whole of the design.
 *
 * NO SVELTE, NO DOM, NO CLOCK. Nothing here imports either, and nothing here reads the time —
 * `$derived` cannot see a `Date.now()` inside a callee, which is the bug that shipped in
 * TherapistPortal.svelte and left the idle guard permanently true. There is no time in this
 * module for that mistake to be made with.
 */

/* ── The pinned image digest ─────────────────────────────────────────────────────────────── */

/** Hash algorithms a digest may name, and the exact hex length each one produces. */
const HEX_CHARS = { sha256: 64, sha512: 128 } as const

export type DigestAlgorithm = keyof typeof HEX_CHARS

/**
 * The one marker this module uses to say "something was left out". Every shortened string
 * carries it, so a short form can never be mistaken for a whole value by eye or by test.
 */
export const ELISION = '…'

/** Hex characters kept at each end of a shortened digest. Only used where a whole one cannot fit. */
export const SHORT_HEAD = 12
export const SHORT_TAIL = 8

/** How the hex is chunked for reading. Lossless — the chunks concatenate back to the digest. */
export const GROUP_SIZE = 8

/** Longest unreadable value echoed back to the operator before it is cut (and marked). */
export const REPORTED_MAX = 120

export type DigestFault = 'absent' | 'noAlgorithm' | 'unknownAlgorithm' | 'notHex' | 'wrongLength'

export interface ParsedDigest {
  algorithm: DigestAlgorithm
  /** Lowercase hex, exactly `HEX_CHARS[algorithm]` characters. */
  hex: string
  /** `algorithm:hex`, lowercased. The only form this module ever puts on a clipboard. */
  canonical: string
}

export type DigestParse = { ok: true; digest: ParsedDigest } | { ok: false; fault: DigestFault }

function isAlgorithm(name: string): name is DigestAlgorithm {
  return Object.prototype.hasOwnProperty.call(HEX_CHARS, name)
}

/**
 * Read a digest of the form `sha256:<hex>`.
 *
 * Case is normalised to lower. That is not a collapse of two values: hex is case-insensitive by
 * definition, so `AB…` and `ab…` are the same digest and always were. A one-character
 * difference in the value survives, which is the property signIn.test.ts pins.
 */
export function parseDigest(raw: string | null | undefined): DigestParse {
  const text = (raw ?? '').trim()
  if (text === '') return { ok: false, fault: 'absent' }

  const cut = text.indexOf(':')
  if (cut < 1) return { ok: false, fault: 'noAlgorithm' }

  const algorithm = text.slice(0, cut).trim().toLowerCase()
  const hex = text.slice(cut + 1).trim()

  if (!isAlgorithm(algorithm)) return { ok: false, fault: 'unknownAlgorithm' }
  if (!/^[0-9a-fA-F]+$/.test(hex)) return { ok: false, fault: 'notHex' }
  if (hex.length !== HEX_CHARS[algorithm]) return { ok: false, fault: 'wrongLength' }

  const lower = hex.toLowerCase()
  return { ok: true, digest: { algorithm, hex: lower, canonical: `${algorithm}:${lower}` } }
}

/**
 * Chunk hex for reading. LOSSLESS by construction: `groupHex(h).join('') === h` for every input,
 * which is what makes the rendered value as distinguishing as the digest itself. A remainder
 * shorter than `size` is kept, never padded and never dropped.
 */
export function groupHex(hex: string, size: number = GROUP_SIZE): string[] {
  if (!Number.isInteger(size) || size < 1) {
    throw new RangeError('group size must be a positive integer')
  }
  const out: string[] = []
  for (let i = 0; i < hex.length; i += size) out.push(hex.slice(i, i + size))
  return out
}

/**
 * A shortened reference, for the one context too small for 64 hex characters (a header chip, an
 * accessible name). NOT a value to compare against anything: two different images can produce
 * the same short form, which is exactly why `digestReference` guards it and why nothing in this
 * module ever presents a short form as the digest.
 */
export function shortenDigest(digest: ParsedDigest): string {
  if (digest.hex.length <= SHORT_HEAD + SHORT_TAIL) return digest.canonical
  const head = digest.hex.slice(0, SHORT_HEAD)
  const tail = digest.hex.slice(-SHORT_TAIL)
  return `${digest.algorithm}:${head}${ELISION}${tail}`
}

/**
 * The short forms that two or more DIFFERENT digests in `digests` share. Empty when every short
 * form still identifies exactly one digest. Deduplicated by canonical first, so the same digest
 * listed twice is not mistaken for a collision.
 */
export function collidingShortForms(digests: readonly ParsedDigest[]): string[] {
  const byShort = new Map<string, Set<string>>()
  for (const d of digests) {
    const short = shortenDigest(d)
    const seen = byShort.get(short) ?? new Set<string>()
    seen.add(d.canonical)
    byShort.set(short, seen)
  }
  return [...byShort.entries()].filter(([, seen]) => seen.size > 1).map(([short]) => short)
}

/**
 * The shortest form of `digest` that still tells it apart from every digest in `alongside`.
 *
 * The comparison set is a required argument rather than an option with a default, so a caller
 * cannot get a short form without having stated what it has to stay distinct from. When the
 * short forms collide, every colliding digest falls back to its whole value — the display gets
 * longer rather than ambiguous, which is the only acceptable direction for that trade.
 */
export function digestReference(digest: ParsedDigest, alongside: readonly ParsedDigest[]): string {
  const short = shortenDigest(digest)
  const pool = alongside.some((d) => d.canonical === digest.canonical)
    ? alongside
    : [digest, ...alongside]
  return collidingShortForms(pool).includes(short) ? digest.canonical : short
}

/** An over-long unreadable value, cut to something a layout can hold — and marked as cut. */
export function capReported(raw: string): { text: string; shortened: boolean } {
  const text = raw.trim()
  if (text.length <= REPORTED_MAX) return { text, shortened: false }
  return { text: text.slice(0, REPORTED_MAX) + ELISION, shortened: true }
}

/**
 * Why a digest could not be read, in words. Premade, one per fault; the screen picks, it never
 * composes. None of them accuse the operator of anything — an absent digest is a fact about a
 * deployment, and the screen says so and stops.
 */
export const DIGEST_FAULT_TEXT: Record<DigestFault, string> = {
  absent: 'This deployment did not report an image digest.',
  noAlgorithm: 'The reported digest does not name a hash algorithm, so there is nothing to read it as.',
  unknownAlgorithm: 'The reported digest names a hash algorithm this page does not know how to display.',
  notHex: 'The reported digest is not hexadecimal, so it is not a digest this page can display.',
  wrongLength: 'The reported digest is the wrong length for the algorithm it names.',
}

export type DigestPresentation =
  | {
      kind: 'digest'
      algorithm: DigestAlgorithm
      /** `SHA256` — the algorithm as a chrome label, so the hex below it needs no preamble. */
      algorithmLabel: string
      canonical: string
      /** Exactly what a copy control puts on the clipboard: the whole digest, nothing added. */
      copyText: string
      /** The hex in reading chunks. Concatenates back to the whole value. */
      groups: string[]
    }
  | {
      kind: 'fault'
      fault: DigestFault
      message: string
      /** What was reported, when there was anything to report. Null for an absent digest. */
      reported: string | null
      reportedShortened: boolean
    }

/** Everything the digest control needs, from the raw string the deployment reported. */
export function describeDigest(raw: string | null | undefined): DigestPresentation {
  const parse = parseDigest(raw)
  if (parse.ok) {
    const { algorithm, canonical, hex } = parse.digest
    return {
      kind: 'digest',
      algorithm,
      algorithmLabel: algorithm.toUpperCase(),
      canonical,
      copyText: canonical,
      groups: groupHex(hex),
    }
  }
  const trimmed = (raw ?? '').trim()
  const capped = capReported(trimmed)
  return {
    kind: 'fault',
    fault: parse.fault,
    message: DIGEST_FAULT_TEXT[parse.fault],
    reported: parse.fault === 'absent' ? null : capped.text,
    reportedShortened: parse.fault === 'absent' ? false : capped.shortened,
  }
}

/* ── The contract ────────────────────────────────────────────────────────────────────────── */

export type ContractSection = 'trusted' | 'serverSees' | 'serverCannotSee' | 'session'

/** Reading order. A section missing from the contract is a failure, not an empty heading. */
export const SECTION_ORDER: readonly ContractSection[] = [
  'trusted',
  'serverSees',
  'serverCannotSee',
  'session',
]

export const SECTION_HEADING: Record<ContractSection, string> = {
  trusted: 'What you are being trusted with',
  serverSees: 'What the server can see',
  serverCannotSee: 'What the server cannot see',
  session: 'While you are signed in',
}

export interface ContractClause {
  /** Stable id. Used by the completeness check and as the list key; never shown. */
  id: string
  section: ContractSection
  text: string
}

/**
 * The contract itself. Written by a person, fixed at build time, and rendered whole — there is
 * no "show more", no collapsed section and no summary, because a contract you have to expand is
 * one the reader is entitled to assume was not important.
 */
export const SIGN_IN_CONTRACT: readonly ContractClause[] = [
  {
    id: 'trusted.record',
    section: 'trusted',
    text:
      "This account opens another person's record: mood check-ins, journal entries, sleep logs, " +
      'goals, and self-check results reduced to scores and bands — never their raw answers.',
  },
  {
    id: 'trusted.granted',
    section: 'trusted',
    text:
      'You can read it because that person granted it, item by item, and they can withdraw any ' +
      'of it at any time without telling you why.',
  },
  {
    id: 'trusted.notARecord',
    section: 'trusted',
    text:
      'What you open is a copy of what they chose to export. It is not a clinical record, it is ' +
      'not complete, and nothing in it is a diagnosis.',
  },
  {
    id: 'sees.signIn',
    section: 'serverSees',
    text:
      'That this credential signed in, and when. The server authenticates you, so it necessarily ' +
      'learns that you were here.',
  },
  {
    id: 'sees.shape',
    section: 'serverSees',
    text:
      'The size, timing and order of the encrypted blobs that move between you and the person ' +
      'who shared them.',
  },
  {
    id: 'sees.audit',
    section: 'serverSees',
    text:
      "Event types and timestamps in the relationship's audit log — which the person you are " +
      'working with can read, so your visit is visible to them and not only to the operator.',
  },
  {
    id: 'cannot.contents',
    section: 'serverCannotSee',
    text:
      'The contents of anything you open. Bundles, assignments and game plans are decrypted in ' +
      'this browser with keys the server never receives.',
  },
  {
    id: 'cannot.passphrase',
    section: 'serverCannotSee',
    text:
      'Your reading passphrase, which is used in this page to unwrap those keys and is not sent ' +
      'anywhere.',
  },
  {
    id: 'cannot.scope',
    section: 'serverCannotSee',
    text:
      'Both of those describe the software as released, not the page in front of you — which is ' +
      'what the notice at the top of this screen is about, and why the image digest is a control ' +
      'here rather than a footnote.',
  },
  {
    id: 'session.memory',
    section: 'session',
    text:
      'Keys are held in memory only. Logging out, going idle, or closing the tab drops them, and ' +
      'the next visit starts from this screen again.',
  },
  {
    id: 'session.noVerdicts',
    section: 'session',
    text:
      'This portal states what a bundle contains. It does not score it, does not rank one period ' +
      "of a person's life against another, and does not tell you what to do about any of it.",
  },
  {
    id: 'session.provenance',
    section: 'session',
    text:
      'Each measure you read is shown with where it came from — a self-report, or a named ' +
      'instrument carrying its provenance tier.',
  },
]

/**
 * The fixed copy this screen renders outside the contract. Held here with the clauses so the
 * same checks run over all of it, and so the component stays a renderer.
 */
export const SCREEN_COPY = {
  pageTitle: 'Sign in',
  contractTitle: 'Before you sign in',
  contractLede:
    'This is the whole of what signing in here means. It is the same on every visit, and it is ' +
    'not a summary of a longer document held somewhere else.',
  credentialTitle: 'Your credentials',
  digestTitle: 'Pinned image digest',
  digestNote:
    'This is what the deployment says it is running. It is a claim made by the same page you are ' +
    'reading, so it counts for something only when you compare it against a source that is not ' +
    'this page — the release you pulled, or the registry. Nothing on this screen checks it for you.',
  digestCopy: 'Copy digest',
  digestCopied: 'Copied the whole digest.',
  digestCopyUnavailable: 'This browser did not allow the copy. The value above can be selected.',
  refusalCardTitle: 'Sign-in is not being offered',
  refusalTitle: 'This screen cannot state what you would be trusted with',
  refusalBody:
    'The contract above is incomplete, so the sign-in form is not being offered. Reading someone ' +
    "else's record starts with a complete statement of what that means; without one there is " +
    'nothing here to agree to.',
} as const

/* ── Checking the contract ───────────────────────────────────────────────────────────────── */

/**
 * Claim shapes this product does not make about itself, anywhere. A contract that reassures is
 * worse than no contract: it spends the reader's attention buying them a promise the software
 * is not in a position to keep. Kept as named patterns so a failure says which one fired.
 *
 * These are matched against the copy, so signIn.test.ts proves each one fires on a planted
 * sentence before it is trusted to report that the real copy is clean.
 */
const ASSURANCE_CLAIMS: { name: string; pattern: RegExp }[] = [
  {
    name: 'asserts a state of safety',
    pattern:
      /\b(?:is|are|stays?|remains?)\s+(?:always\s+|completely\s+|fully\s+|entirely\s+|totally\s+)?(?:secure|safe|private|protected|anonymous|confidential|encrypted end-to-end)\b/i,
  },
  { name: 'promises', pattern: /\bwe\s+(?:guarantee|ensure|promise|assure)\b/i },
  {
    name: 'grades itself',
    pattern: /\b(?:guaranteed|bank-grade|military-grade|unbreakable|impossible to (?:read|break|access))\b/i,
  },
  { name: 'tells the reader to relax', pattern: /\b(?:rest assured|don't worry|do not worry|you can be confident)\b/i },
]

/**
 * Subjects the fixed assurance banner owns. A contract clause that reaches for one of them is
 * restating copy this screen does not hold — and a paraphrase beside fixed copy is how fixed
 * copy gets softened without anyone editing it. The banner renders itself, verbatim, from its
 * own component; the words are not repeated in this module or in the screen.
 */
const BANNER_SUBJECTS = /\b(?:lower-assurance|zero-knowledge|passkey|webauthn|tampered)\b/i

/** True when `text` makes one of the claims this product may not make. */
export function makesAssuranceClaim(text: string): string | null {
  for (const { name, pattern } of ASSURANCE_CLAIMS) if (pattern.test(text)) return name
  return null
}

/** True when `text` restates the fixed assurance banner's subject rather than leaving it to it. */
export function echoesBannerSubject(text: string): boolean {
  return BANNER_SUBJECTS.test(text)
}

export interface ContractCheck {
  /** Whether the screen may offer sign-in at all. */
  complete: boolean
  /** One line per fault, naming the clause or section at fault. Empty when complete. */
  problems: string[]
}

/** The shortest a clause may be and still be a statement rather than a label. */
const MIN_CLAUSE_CHARS = 40

/**
 * Is this contract fit to be shown to someone about to open another person's record?
 *
 * Not a style check. Each rule below is a way the contract could stop being one: a section with
 * nothing under it (the reader is told a heading and consents to a blank), a clause that
 * reassures instead of stating, a clause that paraphrases the fixed banner beside it, two
 * clauses sharing an id (one of them will not render, and which one is a detail of the list
 * key). The screen refuses to offer sign-in when this fails — see SignInScreen.svelte.
 */
export function checkContract(clauses: readonly ContractClause[] = SIGN_IN_CONTRACT): ContractCheck {
  const problems: string[] = []
  const seen = new Set<string>()

  for (const clause of clauses) {
    if (seen.has(clause.id)) problems.push(`Two clauses share the id "${clause.id}".`)
    seen.add(clause.id)

    if (!SECTION_ORDER.includes(clause.section)) {
      problems.push(`Clause "${clause.id}" is in no section this screen renders.`)
    }

    const text = clause.text.trim()
    if (text === '') {
      problems.push(`Clause "${clause.id}" has no text.`)
    } else if (text.length < MIN_CLAUSE_CHARS) {
      problems.push(`Clause "${clause.id}" is too short to state anything.`)
    }

    const claim = makesAssuranceClaim(text)
    if (claim !== null) problems.push(`Clause "${clause.id}" ${claim}.`)

    if (echoesBannerSubject(text)) {
      problems.push(`Clause "${clause.id}" restates the fixed assurance notice instead of leaving it to it.`)
    }
  }

  for (const section of SECTION_ORDER) {
    if (!clauses.some((c) => c.section === section)) {
      problems.push(`Nothing is stated under "${SECTION_HEADING[section]}".`)
    }
  }

  return { complete: problems.length === 0, problems }
}

export interface ContractGroup {
  section: ContractSection
  heading: string
  clauses: ContractClause[]
}

/** The contract in reading order, grouped under its headings. Clause order is preserved. */
export function contractSections(
  clauses: readonly ContractClause[] = SIGN_IN_CONTRACT,
): ContractGroup[] {
  return SECTION_ORDER.map((section) => ({
    section,
    heading: SECTION_HEADING[section],
    clauses: clauses.filter((c) => c.section === section),
  }))
}
