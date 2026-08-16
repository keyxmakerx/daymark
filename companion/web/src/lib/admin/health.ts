/*
 * The admin console's view models: everything that screen says, computed from what this server
 * actually serves.
 *
 * ─────────────────────────────────────────────────────────────────────────────────────────────
 * WHAT THIS BUILD ACTUALLY EXPOSES, read out of companion/server rather than assumed
 * ─────────────────────────────────────────────────────────────────────────────────────────────
 *
 * Reachable by anyone who can reach the app, with no credential at all:
 *
 *   GET /healthz     Application.kt — 200 {"ok":true}, always. Liveness only.
 *   GET /readyz      Application.kt — 200 {"ok":true} / 503 {"ok":false}. Readiness.kt probes the
 *                    data directory with a 4 KiB write + fsync + delete, cached 5 s. The failure
 *                    body is content-free ON PURPOSE; the reason goes to the server log.
 *   GET /v1/config   Application.kt — {"smtpEnabled":bool}. The one capability flag published.
 *
 * Reachable only with the owner bearer token AND the relationship inbox token, together:
 *
 *   GET /v1/rel/{relRef}/audit   AuditRoutes.kt — the relationship audit log.
 *
 * That is the entire surface. There is no /metrics. There is no counters route. There is no
 * chain-verification route: AuditStore appends a hash chain and never checks one. There is no
 * administrator identity anywhere in Config.kt — no DAYMARK_ADMIN_* setting exists, so this
 * console has no credential of its own to present and nothing to present one to.
 *
 * ─────────────────────────────────────────────────────────────────────────────────────────────
 * WHY THE GAPS ARE MODELLED AS FIRST-CLASS OUTPUT
 * ─────────────────────────────────────────────────────────────────────────────────────────────
 *
 * The tempting move, when a console is specified to show auth pressure and the server publishes
 * no counters, is to show something adjacent and let the operator infer. A dial fed by whatever
 * happened to be reachable. A figure derived from two things that were not measuring the same
 * quantity. Every one of those reads, on the screen, exactly like a measurement.
 *
 * So the gaps are [StatedGap] values with the same weight as the readings: they render, they say
 * "this build exposes no counter for X", and they say where the number really does live inside
 * the running process so an operator can go and get it another way. An operator who leaves this
 * screen knowing four numbers do not exist is better informed than one who leaves with four
 * numbers that were invented.
 *
 * ─────────────────────────────────────────────────────────────────────────────────────────────
 * WHY THERE IS NO SINGLE FIGURE, AND WHY THAT IS ENFORCED HERE RATHER THAN IN THE COMPONENT
 * ─────────────────────────────────────────────────────────────────────────────────────────────
 *
 * No percentage, no score, no overall grade. A rolled-up number would have to average a probe
 * that answered, counters that do not exist, and a chain run whose completeness is not knowable —
 * and the averaging is precisely where the dishonesty enters, because it converts three
 * different kinds of not-knowing into one confident figure. The component renders strings; if
 * the vocabulary lives here then the ban is testable, and it is (see health.test.ts).
 *
 * PURE. No fetch, no DOM, no timers, no Svelte. Every function is (input) → view model. The
 * caller does the I/O and hands the raw response in; the component renders what comes out.
 */

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   1. Operational health — the three endpoints this build really serves.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

export type ProbeId = 'liveness' | 'writability' | 'capability'

export interface Endpoint {
  id: ProbeId
  method: 'GET'
  path: string
  label: string
  /** False for all three. Stated per endpoint so the screen never implies a credential it lacks. */
  authenticated: boolean
  /** What a normal answer here does establish. */
  answers: string
  /** What it does NOT establish. Kept beside `answers` so the two are never read apart. */
  silentOn: string
}

/**
 * The probe catalogue, in the order the console renders it.
 *
 * `silentOn` is not editorial hedging — each sentence is a limitation the server's own source
 * states about itself. Readiness.kt documents the SQLite-contention gap in its class comment;
 * Application.kt documents why /healthz stays 200 through a full disk and why the /readyz
 * failure body carries no reason.
 */
export const ENDPOINTS: readonly Endpoint[] = [
  {
    id: 'liveness',
    method: 'GET',
    path: '/healthz',
    label: 'Process liveness',
    authenticated: false,
    answers: 'The process is up and routing requests.',
    silentOn:
      'Nothing else. A full disk, a data volume mounted read-only, a volume owned by another ' +
      'UID and a wedged database all leave this endpoint answering 200. Point a load balancer ' +
      'here and monitoring at readiness, not the other way round.',
  },
  {
    id: 'writability',
    method: 'GET',
    path: '/readyz',
    label: 'Data directory writability',
    authenticated: false,
    answers:
      'The data directory took a 4 KiB write, an fsync and a delete within the last five ' +
      'seconds. The result is cached for that window, so this is a recent answer rather than a ' +
      'live one.',
    silentOn:
      'SQLite lock contention is deliberately not probed — probing it would either take the ' +
      'store lock, turning a long write into a hanging health endpoint, or touch the connection ' +
      'from another thread, which the driver forbids. A wedged writer therefore still answers ' +
      '200, and the server documents that as a known gap. When this does answer 503 the body ' +
      'carries no reason: the reason is written to the server log only, so read the log.',
  },
  {
    id: 'capability',
    method: 'GET',
    path: '/v1/config',
    label: 'Outbound mail capability',
    authenticated: false,
    answers:
      'Whether the operator enabled outbound SMTP — the single non-secret capability flag this ' +
      'build publishes.',
    silentOn:
      'Configuration, not behaviour. It says the feature is switched on; it never says a message ' +
      'was accepted, queued or delivered.',
  },
]

/** What the caller observed when it asked. Transport failure is a distinct case from any status. */
export type ProbeResponse =
  | { kind: 'response'; status: number; body: string }
  | { kind: 'transport'; error: string }

/**
 * Four states, and none of them is a grade.
 *
 * `responding` is the documented normal answer; `refusing` is the documented failure answer that
 * only readiness has; `unreachable` is no answer at all; `unexpected` is an answer this build is
 * not written to give, which is a real and separate thing to know — it usually means something
 * between the browser and the app is answering instead of the app.
 */
export type ProbeState = 'responding' | 'refusing' | 'unreachable' | 'unexpected'

export interface ProbeReading {
  id: ProbeId
  label: string
  endpoint: string
  state: ProbeState
  /** One authored sentence stating what was observed. Never a verdict about the deployment. */
  observed: string
  answers: string
  silentOn: string
  /**
   * The ONE channel that echoes text this module did not author — a transport error string, a
   * body that did not parse. Everything else on a reading is the product's own vocabulary, which
   * is what makes "no percentage anywhere in the output" a checkable claim (health.test.ts).
   */
  machineDetail?: string
  /** Present only for the capability probe, and only when the body parsed. */
  smtpEnabled?: boolean
}

const endpointFor = (id: ProbeId): Endpoint => ENDPOINTS.find((e) => e.id === id)!

/** `{"ok":true}` / `{"ok":false}` — the exact two bodies /healthz and /readyz can send. */
function readOkBody(body: string): boolean | null {
  try {
    const parsed: unknown = JSON.parse(body)
    if (parsed && typeof parsed === 'object' && typeof (parsed as { ok?: unknown }).ok === 'boolean') {
      return (parsed as { ok: boolean }).ok
    }
    return null
  } catch {
    return null
  }
}

/**
 * Turn one raw response into one reading.
 *
 * Deliberately total: every id × every response shape produces a reading. A console that renders
 * nothing when it does not recognise an answer has hidden the most interesting case it will ever
 * see.
 */
export function readProbe(id: ProbeId, res: ProbeResponse): ProbeReading {
  const ep = endpointFor(id)
  const base = {
    id,
    label: ep.label,
    endpoint: `${ep.method} ${ep.path}`,
    answers: ep.answers,
    silentOn: ep.silentOn,
  }

  if (res.kind === 'transport') {
    return {
      ...base,
      state: 'unreachable',
      observed:
        'No answer. The request did not complete, so this says nothing about the server — only ' +
        'that this browser could not reach it.',
      machineDetail: res.error,
    }
  }

  if (id === 'capability') {
    if (res.status === 200) {
      const flag = readSmtpFlag(res.body)
      if (flag !== null) {
        return {
          ...base,
          state: 'responding',
          observed: flag
            ? 'Answered 200. Outbound SMTP is switched on in this deployment.'
            : 'Answered 200. Outbound SMTP is switched off in this deployment.',
          smtpEnabled: flag,
        }
      }
    }
    return {
      ...base,
      state: 'unexpected',
      observed: `Answered ${res.status}, which is not a shape this build is written to send here.`,
      machineDetail: res.body,
    }
  }

  const ok = readOkBody(res.body)

  if (res.status === 200 && ok === true) {
    return {
      ...base,
      state: 'responding',
      observed:
        id === 'liveness'
          ? 'Answered 200. The process is up and routing.'
          : 'Answered 200. The data directory accepted a write when it was last probed.',
    }
  }

  if (id === 'writability' && res.status === 503 && ok === false) {
    return {
      ...base,
      state: 'refusing',
      observed:
        'Answered 503. The data directory could not be written when it was last probed. This ' +
        'endpoint does not say which failure it is — read the server log for the reason.',
    }
  }

  return {
    ...base,
    state: 'unexpected',
    observed: `Answered ${res.status}, which is not a shape this build is written to send here.`,
    machineDetail: res.body,
  }
}

function readSmtpFlag(body: string): boolean | null {
  try {
    const parsed: unknown = JSON.parse(body)
    if (
      parsed &&
      typeof parsed === 'object' &&
      typeof (parsed as { smtpEnabled?: unknown }).smtpEnabled === 'boolean'
    ) {
      return (parsed as { smtpEnabled: boolean }).smtpEnabled
    }
    return null
  } catch {
    return null
  }
}

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   2. Authentication pressure — which this build does not publish at all.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

/**
 * A number an operator wants, which this build does not serve.
 *
 * `heldAt` is the point of the whole structure. "We don't have that" is a shrug; "the count
 * exists in AuthGuard's per-source failure map, which is marked visible-for-tests and read by no
 * route" is a lead. It tells the operator the number is real, tells them it is not a feature
 * someone forgot to switch on, and tells whoever picks up the work where to start.
 */
export interface StatedGap {
  id: string
  /** What the operator wanted to know. */
  subject: string
  /** Always of the form "This build exposes no …". The console states the gap; it never fills it. */
  statement: string
  /** Where the quantity really does live inside the running process, unexposed. */
  heldAt: string
}

export const AUTH_PRESSURE_HEADLINE =
  'This build publishes no counters. There is no metrics endpoint and no statistics route of any ' +
  'kind, so none of the figures below can be shown here. They are listed rather than omitted ' +
  'because an operator who knows a number is unavailable is better placed than one who assumes ' +
  'it is zero. Nothing on this screen is a stand-in, an estimate or a sample of any of them.'

export const AUTH_PRESSURE: readonly StatedGap[] = [
  {
    id: 'bearer-failures',
    subject: 'Failed owner bearer-token authentications',
    statement: 'This build exposes no counter for failed bearer-token authentications.',
    heldAt:
      'AuthGuard keeps a per-source failure count in memory and decays it. The accessor is ' +
      'marked visible-for-tests and no route reads it.',
  },
  {
    id: 'active-lockouts',
    subject: 'Sources currently locked out',
    statement: 'This build exposes no counter for active lockouts.',
    heldAt:
      'The same AuthGuard failure map carries a lockout expiry per source. Nothing lists it, and ' +
      'a lockout arriving or clearing is not logged.',
  },
  {
    id: 'tracked-sources',
    subject: 'Distinct source addresses under rate limiting',
    statement: 'This build exposes no counter for the number of sources being tracked.',
    heldAt:
      'AuthGuard holds one token bucket per source. The only externalisation is a single WARN ' +
      'log line, emitted at most once a second, when a sweep frees nothing — which is the shape ' +
      'of a wide distributed flood. That log line is the whole signal.',
  },
  {
    id: 'rate-limited',
    subject: 'Requests rejected by the rate limiter',
    statement: 'This build exposes no counter for rate-limited requests.',
    heldAt:
      'Nowhere. The limiter returns a decision per request and keeps no tally, so this number ' +
      'does not exist in the process either — it would have to be added.',
  },
  {
    id: 'totp-lockouts',
    subject: 'Therapist credentials locked out on TOTP failures',
    statement: 'This build exposes no counter for TOTP lockouts.',
    heldAt:
      'auth.db, table totp, columns fail_count and locked_until — one row per enrolled ' +
      'credential. Durable and queryable on the box, served by no route.',
  },
  {
    id: 'live-sessions',
    subject: 'Live therapist sessions',
    statement: 'This build exposes no counter for live sessions.',
    heldAt:
      'auth.db, table sessions — one row per session with its idle and absolute expiry and a ' +
      'revoked flag. No route lists or counts them.',
  },
  {
    id: 'invite-failures',
    subject: 'Failed invite redemptions',
    statement: 'This build exposes no counter for failed invite redemptions.',
    heldAt:
      'auth.db, table invites, column fail_count, which drives the redemption backoff. Served by ' +
      'no route.',
  },
]

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   3. Chain integrity — internal consistency, and nothing whatsoever about completeness.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

/**
 * THE CAVEAT. Body text under the verdict, not small print beside it, and attached to every
 * report this module can produce — including the clean one, which is the only one where it
 * matters.
 *
 * The failure this exists to prevent is an operator reading a clean verdict as an assurance that
 * the log is whole. It is not, and cannot be: the chain is server-computed, so a server that
 * declines to append an event leaves a chain that verifies perfectly, and a server that truncates
 * to an earlier point leaves a shorter chain that also verifies perfectly. Tampering with a
 * STORED entry is detectable. Never having stored one is not.
 */
export const CHAIN_CAVEAT =
  'Internal consistency is not completeness, and this check cannot become a completeness check. ' +
  'It establishes that the entries handed to it agree with one another. It cannot establish that ' +
  'the server handed over every entry it holds, or that it recorded the event at all: the chain ' +
  'is computed by the server, so a server that quietly declines to append leaves a chain that ' +
  'passes this check exactly as cleanly, and one that truncates the history to an earlier point ' +
  'leaves a shorter chain that also passes it exactly as cleanly. Read a clean result as "nothing ' +
  'in what was returned contradicts anything else in what was returned". Do not read it as ' +
  '"nothing is missing", because that is not a question this or any check on server-computed ' +
  'entries can answer.'

export type ChainVerdict =
  | 'internally-consistent'
  | 'contradicted'
  | 'incomplete-run'
  | 'nothing-returned'
  | 'unavailable'

/**
 * The verdict vocabulary, fixed here so no call site can invent a warmer word for it.
 *
 * Note what is absent: no "verified", no "valid", no "intact", no "passed". Each of those is
 * heard as a statement about the log rather than about the arithmetic, which is the exact
 * misreading [CHAIN_CAVEAT] exists to head off. "Internally consistent" is narrow enough that
 * it invites the follow-up question instead of closing it.
 */
export const CHAIN_HEADLINE: Record<ChainVerdict, string> = {
  'internally-consistent':
    'The entries examined are internally consistent with one another.',
  contradicted:
    'The entries examined contradict one another. At least one entry disagrees with the run it sits in.',
  'incomplete-run':
    'The run examined skips sequence numbers. Entries that were appended are absent from what was returned.',
  'nothing-returned':
    'No entries were examined. Nothing here is a statement about the log.',
  unavailable:
    'No chain was available to examine.',
}

/**
 * The word shown on a probe's chip, and the word shown on the chain verdict's.
 *
 * These lived in AdminConsole.svelte, which put the single loudest phrase on each panel outside
 * every check this module makes. The rule stated a few lines up — no "verified", no "valid", no
 * "intact", no "passed" — was enforced over the headlines and not over the chips a reader's eye
 * lands on first, and `authoredStrings()` never walked them because the corpus is this file. So
 * shortening a chip to "Verified" would have contradicted the module's own promise with the whole
 * suite green.
 *
 * A chain that verifies is "Internally consistent" and never "Verified", because a hostile server
 * can decline to append or truncate to a shorter run that verifies perfectly. A probe that answers
 * is "Responding" and never "Healthy": this system reports what it observed, and the absence of
 * something to say is the only reassurance it offers.
 */
export const PROBE_STATE_WORD: Record<ProbeState, string> = {
  responding: 'Responding',
  refusing: 'Refusing',
  unreachable: 'No answer',
  unexpected: 'Undocumented',
}

export const CHAIN_VERDICT_WORD: Record<ChainVerdict, string> = {
  'internally-consistent': 'Internally consistent',
  'incomplete-run': 'Run incomplete',
  contradicted: 'Contradicted',
  'nothing-returned': 'Nothing examined',
  unavailable: 'Not available',
}

export type ChainProblem =
  | 'hash-mismatch'
  | 'repeated-hash'
  | 'repeated-sequence'
  | 'malformed-hash'
  | 'sequence-skip'
  | 'timestamp-regression'

export interface ChainFinding {
  seq: number
  problem: ChainProblem
  detail: string
}

export interface ChainReport {
  verdict: ChainVerdict
  headline: string
  /** Always [CHAIN_CAVEAT]. Present on every report, clean ones included. */
  caveat: string
  /** Authored sentences: the properties this run actually established. */
  checked: string[]
  /** Authored sentences: the properties it did not. Never empty — completeness always sits here. */
  notChecked: string[]
  findings: ChainFinding[]
  entriesExamined: number
  oldestSeq: number | null
  newestSeq: number | null
}

export interface ChainEntry {
  seq: number
  ts: number
  actor: string
  action: string
  objectRef?: string | null
  meta?: Record<string, string> | null
  entryHash: string
}

export interface ChainInput {
  entries: ChainEntry[]
  /**
   * The relationship reference the run belongs to. It is part of the hashed content but is not a
   * field on the wire entries, so hash recomputation needs it supplied. Omit it and the hash
   * check is skipped and said to be skipped, rather than run against a guess.
   */
  relRef?: string
  /**
   * Lowercase-hex SHA-256. Injected because a pure module may not reach for WebCrypto or
   * node:crypto, and because a caller that has no digest available should get an honest partial
   * check rather than a check that silently became structural.
   */
  digest?: (input: string) => string
}

/** 64 hex zeros, chained ahead of the first entry for a relationship (AuditStore.GENESIS_HASH). */
const GENESIS_HASH = '0'.repeat(64)

const HEX64 = /^[0-9a-f]{64}$/

/**
 * The server's meta encoding, reimplemented.
 *
 * Order-stable (keys sorted before escaping) and injective (the three meaningful characters are
 * percent-escaped), matching AuditStore.canonicalMeta. The injectivity is load-bearing rather
 * than tidy: with raw separators a therapist-supplied value containing a comma encodes to
 * something that reads back as two fields, and a verifier that re-canonicalises the parsed map
 * computes the identical hash — so the tamper-evidence machinery certifies the forgery instead
 * of catching it. A verifier that got this wrong would either miss that or reject every honest
 * entry, so health.test.ts checks it against an independently transcribed copy.
 */
function canonicalMeta(meta: Record<string, string>): string {
  return Object.keys(meta)
    .sort()
    .map((k) => `${escapeMeta(k)}=${escapeMeta(meta[k]!)}`)
    .join(',')
}

// '%' first, or the escape character is not itself escapable.
const escapeMeta = (s: string): string => s.split('%').join('%25').split(',').join('%2C').split('=').join('%3D')

/** AuditStore.chainHash's input: the fields joined by '|', absent optionals as empty strings. */
function chainHashInput(prevHash: string, e: ChainEntry, relRef: string): string {
  const meta = e.meta ? canonicalMeta(e.meta) : ''
  return [
    prevHash,
    String(e.seq),
    String(e.ts),
    relRef,
    e.actor,
    e.action,
    e.objectRef ?? '',
    meta,
  ].join('|')
}

/**
 * Examine a run of audit entries.
 *
 * Order-independent by design: the run is walked in sequence order regardless of the order it
 * arrived in. The API returns newest-first and a dump taken off the box may be either way round,
 * and treating arrival order as evidence would manufacture a finding out of a `SELECT` clause.
 *
 * Every report carries [CHAIN_CAVEAT] and a non-empty `notChecked`. There is no path through this
 * function that produces a bare clean verdict.
 */
export function verifyChainRun(input: ChainInput): ChainReport {
  const { entries, relRef, digest } = input

  const notChecked: string[] = [
    'Whether the run is complete. See the caveat below — it is not a limitation of this check ' +
      'but of what a server-computed chain can prove.',
    'The order the entries arrived in. The run is walked in sequence order, because arrival ' +
      'order is a property of the query that fetched it rather than of the log.',
  ]

  if (entries.length === 0) {
    return {
      verdict: 'nothing-returned',
      headline: CHAIN_HEADLINE['nothing-returned'],
      caveat: CHAIN_CAVEAT,
      checked: [],
      notChecked: [
        'Everything. An empty run establishes nothing at all — least of all that there was ' +
          'nothing to return.',
        ...notChecked,
      ],
      findings: [],
      entriesExamined: 0,
      oldestSeq: null,
      newestSeq: null,
    }
  }

  const ordered = [...entries].sort((a, b) => a.seq - b.seq)
  const findings: ChainFinding[] = []

  // Shape first: a hash that is not 64 lowercase hex was not produced by this server.
  for (const e of ordered) {
    if (!HEX64.test(e.entryHash)) {
      findings.push({
        seq: e.seq,
        problem: 'malformed-hash',
        detail:
          'The entry hash is not 64 lowercase hex characters, which is the only shape this ' +
          'server writes.',
      })
    }
  }

  // Each hash covers its own sequence number, so two entries cannot honestly share one.
  const seenHash = new Map<string, number>()
  for (const e of ordered) {
    const first = seenHash.get(e.entryHash)
    if (first !== undefined) {
      findings.push({
        seq: e.seq,
        problem: 'repeated-hash',
        detail: `This entry carries the same hash as entry ${first}. An entry hash covers its own sequence number, so two entries cannot honestly share one.`,
      })
    } else {
      seenHash.set(e.entryHash, e.seq)
    }
  }

  for (let i = 1; i < ordered.length; i++) {
    const prev = ordered[i - 1]!
    const cur = ordered[i]!

    if (cur.seq === prev.seq) {
      findings.push({
        seq: cur.seq,
        problem: 'repeated-sequence',
        detail: 'Two entries share this sequence number. The store makes it a primary key, so one of them did not come from it.',
      })
    } else if (cur.seq !== prev.seq + 1) {
      findings.push({
        seq: cur.seq,
        problem: 'sequence-skip',
        detail: `Sequence jumps from ${prev.seq} to ${cur.seq}. Retention pruning removes the OLDEST entries, so a run that simply starts above 1 is expected — a gap in the middle of a run is not.`,
      })
    }

    if (cur.ts < prev.ts) {
      findings.push({
        seq: cur.seq,
        problem: 'timestamp-regression',
        detail: `This entry is stamped earlier than entry ${prev.seq}, which was appended before it. Entries are stamped in append order, so this is either a server clock that moved backwards or a run that was assembled rather than read.`,
      })
    }
  }

  /*
   * `checked` is assembled from the checks that actually held, one sentence each.
   *
   * NOT all-or-nothing. A run with one sequence skip has still established that its timestamps
   * do not run backwards and that no hash repeats, and withdrawing those alongside the one that
   * failed would tell the operator less than the run supports. Each sentence therefore appears
   * only if its own check found nothing, and the failures are in `findings` where they belong.
   */
  const failed = (...problems: ChainProblem[]) => findings.some((f) => problems.includes(f.problem))
  const checked: string[] = []
  if (!failed('sequence-skip', 'repeated-sequence')) {
    checked.push(
      `Sequence numbers run unbroken from ${ordered[0]!.seq} to ${ordered[ordered.length - 1]!.seq}.`,
    )
  }
  if (!failed('repeated-hash', 'repeated-sequence')) {
    checked.push('No two entries share a sequence number or an entry hash.')
  }
  if (!failed('timestamp-regression')) {
    checked.push('Timestamps do not move backwards as the sequence advances.')
  }
  if (!failed('malformed-hash')) {
    checked.push('Every entry hash has the shape this server writes.')
  }

  // The hash recomputation — the only check that is about tamper-evidence rather than shape.
  if (digest && relRef !== undefined) {
    for (let i = 0; i < ordered.length; i++) {
      const e = ordered[i]!
      const prevHash =
        i > 0 ? ordered[i - 1]!.entryHash : e.seq === 1 ? GENESIS_HASH : null
      if (prevHash === null) continue // predecessor is outside this run; see notChecked below
      if (digest(chainHashInput(prevHash, e, relRef)) !== e.entryHash) {
        findings.push({
          seq: e.seq,
          problem: 'hash-mismatch',
          detail:
            'Recomputing this entry hash from its own contents and its predecessor gives a ' +
            'different value. Its contents, its predecessor, or the hash itself has changed since ' +
            'it was written.',
        })
      }
    }
    if (!failed('hash-mismatch')) {
      checked.push(
        'Each entry hash was recomputed from that entry and its predecessor in the run, and matched.',
      )
    }
    if (ordered[0]!.seq !== 1) {
      notChecked.push(
        `The hash of entry ${ordered[0]!.seq}, the oldest in this run. Its predecessor is not in the run, so there is nothing to chain it to. That is inherent to examining a window of a log rather than all of it.`,
      )
    }
  } else {
    notChecked.push(
      digest === undefined
        ? 'Whether each entry hash matches its own contents. No digest function was supplied, so ' +
          'the entry hashes were treated as opaque strings and only their shape and uniqueness ' +
          'were examined. This run therefore says nothing about tampering with a stored entry.'
        : 'Whether each entry hash matches its own contents. The relationship reference is part ' +
          'of the hashed content and was not supplied, and guessing it would turn every honest ' +
          'entry into a mismatch. Only shape and uniqueness were examined.',
    )
  }

  const contradictions: ChainProblem[] = [
    'hash-mismatch',
    'repeated-hash',
    'repeated-sequence',
    'malformed-hash',
    'timestamp-regression',
  ]
  const verdict: ChainVerdict = failed(...contradictions)
    ? 'contradicted'
    : failed('sequence-skip')
      ? 'incomplete-run'
      : 'internally-consistent'

  return {
    verdict,
    headline: CHAIN_HEADLINE[verdict],
    caveat: CHAIN_CAVEAT,
    checked,
    notChecked,
    findings,
    entriesExamined: ordered.length,
    oldestSeq: ordered[0]!.seq,
    newestSeq: ordered[ordered.length - 1]!.seq,
  }
}

/**
 * The report this console shows by default, because on this build the admin cannot fetch a chain.
 *
 * The only route that serves audit entries requires the relationship inbox token and the owner
 * bearer token together, and the server operator holds neither. That is a design decision worth
 * keeping, not a bug to route around — so the console states it and offers the check over a run
 * the operator supplies from the box they administer.
 */
export function chainUnavailable(reason: string): ChainReport {
  return {
    verdict: 'unavailable',
    headline: CHAIN_HEADLINE.unavailable,
    caveat: CHAIN_CAVEAT,
    checked: [],
    notChecked: [reason],
    findings: [],
    entriesExamined: 0,
    oldestSeq: null,
    newestSeq: null,
  }
}

export const CHAIN_NOT_ADMIN_READABLE =
  'This build serves audit entries from one route, and that route requires the relationship ' +
  'inbox token and the owner bearer token together. A server administrator holds neither, and ' +
  'should not: the log records a private relationship, and it is owner-readable by design. ' +
  'There is also no route anywhere in this build that verifies a chain — the server appends ' +
  'entries and never checks them. To examine a run here, take it from the audit database on the ' +
  'host you administer and paste it below; it stays in this browser tab.'

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   3b. Reading a run the operator supplies.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

export type ChainParse =
  | { kind: 'entries'; entries: ChainEntry[] }
  | { kind: 'unreadable'; problem: string }

/**
 * Accept either the wire page shape (`{"events":[…]}`) or a bare array, because the two sources
 * an operator can actually get a run from produce those two shapes.
 *
 * Strict about fields on purpose. A row missing `seq` could be coerced to 0 and walked, and the
 * run would then report a sequence skip that the log does not have — a manufactured finding is
 * worse than a refusal, because the operator would go looking for it.
 */
export function parseChainExport(text: string): ChainParse {
  const trimmed = text.trim()
  if (trimmed === '') return { kind: 'unreadable', problem: 'Nothing was pasted.' }

  let parsed: unknown
  try {
    parsed = JSON.parse(trimmed)
  } catch {
    return { kind: 'unreadable', problem: 'This is not JSON.' }
  }

  let rows: unknown
  if (Array.isArray(parsed)) {
    rows = parsed
  } else if (parsed && typeof parsed === 'object' && Array.isArray((parsed as { events?: unknown }).events)) {
    rows = (parsed as { events: unknown[] }).events
  } else {
    return {
      kind: 'unreadable',
      problem:
        'Expected either an array of entries or an object with an "events" array — the two ' +
        'shapes a run can arrive in.',
    }
  }

  const entries: ChainEntry[] = []
  for (const [i, row] of (rows as unknown[]).entries()) {
    if (!row || typeof row !== 'object') {
      return { kind: 'unreadable', problem: `Entry at position ${i} is not an object.` }
    }
    const r = row as Record<string, unknown>
    for (const field of ['seq', 'ts'] as const) {
      if (typeof r[field] !== 'number' || !Number.isFinite(r[field])) {
        return { kind: 'unreadable', problem: `Entry at position ${i} has no numeric "${field}".` }
      }
    }
    for (const field of ['actor', 'action', 'entryHash'] as const) {
      if (typeof r[field] !== 'string' || r[field] === '') {
        return { kind: 'unreadable', problem: `Entry at position ${i} has no "${field}".` }
      }
    }
    let meta: Record<string, string> | null = null
    if (r.meta !== undefined && r.meta !== null) {
      if (typeof r.meta !== 'object' || Array.isArray(r.meta)) {
        return { kind: 'unreadable', problem: `Entry at position ${i} has a "meta" that is not an object.` }
      }
      const flat: Record<string, string> = {}
      for (const [k, v] of Object.entries(r.meta as Record<string, unknown>)) {
        if (typeof v !== 'string') {
          return {
            kind: 'unreadable',
            problem: `Entry at position ${i} has a non-string value under meta."${k}".`,
          }
        }
        flat[k] = v
      }
      meta = flat
    }
    entries.push({
      seq: r.seq as number,
      ts: r.ts as number,
      actor: r.actor as string,
      action: r.action as string,
      objectRef: typeof r.objectRef === 'string' ? r.objectRef : null,
      meta,
      entryHash: r.entryHash as string,
    })
  }

  return { kind: 'entries', entries }
}

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   4. Standing facts — the known-bad news, on the screen rather than in a changelog.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

/**
 * A true, current, unflattering statement about how this deployment works.
 *
 * Modelled separately from findings and readings because it behaves differently: it does not
 * arrive from a probe, it does not clear when something recovers, and it does not scroll away.
 * Both entries below are conditions of the build, and the failure mode they are here to prevent
 * is the operator learning about them from an incident rather than from the console.
 */
export interface StandingFact {
  id: string
  title: string
  /** What is true. */
  body: string
  /** What it means for the person running this server. */
  consequence: string
  /** Where to read it in the source, so the fact is checkable rather than assertable. */
  evidence: string
  /** Maps to the callout tones. There is no reassuring tone, here or anywhere. */
  tone: 'warn' | 'critical'
}

export const STANDING_FACTS_HEADLINE =
  'Standing facts about this deployment. These are not incidents and not changelog entries: they ' +
  'describe how this build works right now, they do not clear, and they will keep being shown ' +
  'until the build changes.'

export const STANDING_FACTS: readonly StandingFact[] = [
  {
    id: 'totp-seed-cleartext',
    title: 'The TOTP seed is stored in the clear',
    body:
      'The auth database holds each enrolled therapist’s TOTP seed as base64 in the totp ' +
      'table, column secret_b64 — not as a hash. This is structural rather than an oversight: a ' +
      'TOTP verifier has to recompute the code, so it has to hold the shared secret. Invite ' +
      'codes, session identifiers and inbox tokens on this server are hashed; this one cannot be.',
    consequence:
      'Anyone who can read the data directory, or any backup or snapshot of it, can mint valid ' +
      'second-factor codes for every enrolled therapist. Treat that directory as holding an ' +
      'authenticating secret, not only ciphertext: its backups need the protection you would give ' +
      'a password file, and a restored copy is as good as the original.',
    evidence: 'companion/server auth/AuthStore.kt (CREATE TABLE totp); docs/COMPANION_SECURITY.md 5.2',
    tone: 'critical',
  },
  {
    id: 'webauthn-501',
    title: 'Passkey sign-in is a stub that answers 501',
    body:
      'Every WebAuthn route on this build answers 501 Not Implemented. Registration and assertion ' +
      'verification were never written. The relying-party identifier and origins are pinned in ' +
      'configuration so an eventual implementation cannot regress to deriving them from the Host ' +
      'header, and that pinning is the entire extent of what exists.',
    consequence:
      'TOTP is the only second factor a therapist can enrol here, and TOTP is phishable in a way a ' +
      'hardware passkey is not. The fresh, action-scoped step-up assertion that sensitive actions ' +
      'are specified to require cannot be obtained on this build, so those actions rest on the ' +
      'session alone.',
    evidence: 'companion/server routes/TherapistAuthRoutes.kt (webauthnStub)',
    tone: 'warn',
  },
]

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   5. Scope, credential posture, and the figure that is deliberately not here.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

export interface WithheldSubject {
  subject: string
  reason: string
}

export const CONSOLE_COVERS: readonly string[] = [
  'Operational health — what the server answers on its own probe endpoints, and what those answers do not cover.',
  'Authentication pressure — and, on this build, the fact that none of it is published.',
  'Audit-chain integrity — internal consistency of a run of entries, never its completeness.',
]

export const CONSOLE_WITHHELD: readonly WithheldSubject[] = [
  {
    subject: 'The relationship audit log',
    reason:
      'It is owner-readable. Reading it requires the relationship inbox token and the owner bearer ' +
      'token together, and a server administrator holds neither. Who shared what with whom is not ' +
      'an operations question, and this console neither fetches it nor reports it. The chain check ' +
      'below is the one place a run can appear here, and only because an operator pasted it in ' +
      'from a database they already hold; nothing it reports back quotes what the log records.',
  },
  {
    subject: 'Anything a person wrote',
    reason:
      'Entries, journals, answers and shares are opaque blobs to this server and stay opaque here. ' +
      'The console never decrypts and holds no key.',
  },
  {
    subject: 'Client and therapist identities',
    reason:
      'Relationships are addressed by opaque references. Nothing on this screen names a person, ' +
      'and nothing on it should be used to work out who a reference belongs to.',
  },
]

/**
 * Why there is no single figure at the top of this screen.
 *
 * Rendered, not just intended. An operator who expects a dashboard number and does not find one
 * will look for where it went; this is where it went, and why it is not coming back.
 */
export const NO_SINGLE_FIGURE =
  'There is no overall figure on this screen, and there will not be one. Any single number would ' +
  'have to combine a probe that answered, counters that do not exist on this build, and a chain ' +
  'run whose completeness cannot be established — and the combining is where the dishonesty would ' +
  'enter, because it turns three different kinds of not-knowing into one confident figure. Read ' +
  'the panels.'

export const CREDENTIAL_POSTURE =
  'This build defines no administrator identity. There is no setting that configures one and no ' +
  'route that authenticates one, so this console presents no credential and holds no authority ' +
  'that whoever opened it did not already have. The three endpoints it reads are unauthenticated ' +
  'and answer anyone who can reach the app. Serve this route where you would serve a shell, not ' +
  'where you would serve the portal — and note that a chain run pasted in below sits in this ' +
  'browser tab for as long as the tab is open.'

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   6. When the readings were taken.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

/**
 * How stale the readings are, in words.
 *
 * Takes `now` rather than reading a clock, for two reasons. It keeps the module pure, and it
 * keeps the Svelte side honest: `$derived` re-evaluates on tracked dependency change only, and a
 * clock read inside a callee is invisible to it — a bug that shipped in this codebase's therapist
 * portal, where an idle guard silently stopped ticking. A component must pass a `$state` clock in.
 */
export function describeLastChecked(atMs: number | null, nowMs: number): string {
  if (atMs === null) return 'not checked yet'
  // No clamp. A negative elapsed — a clock that moved backwards, or a timestamp from a machine
  // slightly ahead — yields negative seconds, which the "just now" branch below already absorbs.
  // The Math.max(0, …) that was here read as though it were guarding a broken calculation, and the
  // test claiming to prove it could not tell its presence from its absence: branch order was doing
  // the work either way. Removing it makes the one mechanism visible instead of two.
  const seconds = Math.floor((nowMs - atMs) / 1000)
  if (seconds < 5) return 'checked just now'
  if (seconds < 60) return `checked ${seconds} seconds ago`
  const minutes = Math.floor(seconds / 60)
  if (minutes === 1) return 'checked a minute ago'
  if (minutes < 60) return `checked ${minutes} minutes ago`
  const hours = Math.floor(minutes / 60)
  if (hours === 1) return 'checked an hour ago'
  if (hours < 24) return `checked ${hours} hours ago`
  return 'checked more than a day ago — these readings are stale'
}
