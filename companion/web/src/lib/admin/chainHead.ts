/*
 * The server-side chain check: GET /v1/relations/{relRef}/audit-chain, read and rendered.
 *
 * WHAT THE ROUTE IS. AuditStore.verifyChain recomputes one relationship's stored audit chain from
 * its oldest surviving entry to its head, exactly as append computed it, and this route returns
 * the summary: an entry count, the sequence extent, the head hash as stored, and the sequence
 * number of the first internal break if there is one. No entries travel — no actors, actions,
 * timestamps or object references — so nothing this module handles quotes what the log records.
 *
 * WHO CAN CALL IT, AND WHY. The route is gated on the OWNER bearer token, exactly as the
 * therapist-keys read is. Not because a head hash opens anything — it opens nothing — but because
 * a head plus an entry count, served per relRef to anyone who asked, answers "does this
 * relationship exist on this server, and how active has it been" for any reference an anonymous
 * caller cares to probe. That is exactly the relationship metadata the plan documents a
 * compromised server as able to take (PLAN_2026-08-COMPANION-NEXT.md 1.2: how many relationships
 * exist, how often someone syncs), and this server does not volunteer it to callers who present
 * nothing. On a self-hosted box the operator and the owner are usually the same person, which is
 * why this panel lives on the admin console at all; an administrator who is NOT the owner does
 * not hold this token and this panel is not an invitation to obtain it.
 *
 * WHAT THE VERDICT IS WORTH, stated before anything else because it is less than it looks. The
 * check runs ON the server, over rows the server holds, and this module renders what the server
 * said about itself. Against an honest server with damaged storage — a bad disk, a botched
 * restore, a hand-edited row — a reported break is real evidence. Against a server that lies it
 * is nothing: whoever can rewrite the entries can recompute the chain over them, and whoever
 * writes the response can write it with no break in it. The pasted-run examiner beside this
 * panel, now that it has a real digest (lib/admin/sha256.ts), is the version of the same
 * arithmetic that does not take the server's word for it.
 *
 * WHAT SURVIVES EVEN A LYING SERVER: the head hash, once it is anchored somewhere the server
 * cannot reach. A server that rewrites or truncates history it has already served must change
 * its head, so a head that still matches a note taken earlier is one point of history the server
 * remains committed to. The plan's eventual answer is the phone recording the head on an
 * interval (PLAN_2026-08-COMPANION-NEXT.md 3.9.7 — the chain is only evidence if its head is
 * anchored beyond the server's reach); until that exists, the anchor is a person writing the
 * digest down, which is why this module renders it in reading groups a person can copy by hand
 * and compare group by group later, the same four-character chunks the key-fingerprint ceremonies
 * use.
 *
 * SHAPE OF THE MODULE, matching lib/admin/health.ts: the words live here as tested constants, the
 * view is (input) → view model, and the ONE function that performs I/O is fetchChainHead with an
 * injectable fetch. Every string a person reads is authored here except machineDetail, which is
 * the single declared channel for bytes the module did not write. NOTHING HERE LOGS — not the
 * token, not the reference, not the head.
 */

/** Where to send the read, and what to authorize it with. Mirrors owner/therapistKeys.ts. */
export interface OwnerEndpoint {
  /** Base URL of the companion server. Empty string means the origin that served this page. */
  baseUrl: string
  /** The owner's bearer access token, presented once per read and held by the caller, not here. */
  token: string
}

type FetchLike = typeof fetch

/** What the caller observed when it asked. Transport failure is a distinct case from any status. */
export type ChainHeadResponse =
  | { kind: 'response'; status: number; body: string }
  | { kind: 'transport'; error: string }

/**
 * Perform the one read. Never throws: a transport failure is a value, because the component's job
 * on any outcome is to render it, and an exception path is a rendering path someone forgets.
 */
export async function fetchChainHead(
  endpoint: OwnerEndpoint,
  relRef: string,
  doFetch: FetchLike = fetch.bind(globalThis),
): Promise<ChainHeadResponse> {
  const base = endpoint.baseUrl.replace(/\/+$/, '')
  try {
    const res = await doFetch(`${base}/v1/relations/${encodeURIComponent(relRef)}/audit-chain`, {
      headers: { Authorization: `Bearer ${endpoint.token}`, accept: 'application/json' },
      cache: 'no-store',
    })
    return { kind: 'response', status: res.status, body: await res.text() }
  } catch (e) {
    return { kind: 'transport', error: e instanceof Error ? `${e.name}: ${e.message}` : String(e) }
  }
}

/** The wire record, parsed and shape-checked. Absent optionals come back as null. */
export interface ChainHeadRecord {
  entryCount: number
  oldestSeq: number | null
  headSeq: number | null
  headHash: string | null
  firstBreakSeq: number | null
}

const HEX64 = /^[0-9a-f]{64}$/

/**
 * Turn a parsed 200 body into a record, or refuse it with null.
 *
 * Shape, never trust: a 64-lowercase-hex head is the only head this server writes, and a body
 * that decorates a positive count with no head, or a zero count with one, is not something this
 * server sends and is refused whole rather than partially rendered — the one habit worth keeping
 * when reading from a machine that may be lying is that a value is either understood or refused.
 * Unknown extra fields are ignored: a later server that adds one is not lying, and a parser that
 * rejects what it was not told about turns every additive change into a coordinated release.
 */
export function parseChainHeadRecord(body: unknown): ChainHeadRecord | null {
  if (typeof body !== 'object' || body === null || Array.isArray(body)) return null
  const fields = body as Record<string, unknown>

  const count = fields.entryCount
  if (typeof count !== 'number' || !Number.isSafeInteger(count) || count < 0) return null

  const seqOrNull = (value: unknown): number | null | undefined => {
    if (value === undefined || value === null) return null
    if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 1) return undefined
    return value
  }
  const oldestSeq = seqOrNull(fields.oldestSeq)
  const headSeq = seqOrNull(fields.headSeq)
  const firstBreakSeq = seqOrNull(fields.firstBreakSeq)
  if (oldestSeq === undefined || headSeq === undefined || firstBreakSeq === undefined) return null

  let headHash: string | null = null
  if (fields.headHash !== undefined && fields.headHash !== null) {
    if (typeof fields.headHash !== 'string' || !HEX64.test(fields.headHash)) return null
    headHash = fields.headHash
  }

  // The shapes this server can actually produce: a populated chain carries all three extent
  // fields; an empty one carries none of them and no break.
  if (count > 0 && (oldestSeq === null || headSeq === null || headHash === null)) return null
  if (count === 0 && (oldestSeq !== null || headSeq !== null || headHash !== null || firstBreakSeq !== null)) {
    return null
  }

  return { entryCount: count, oldestSeq, headSeq, headHash, firstBreakSeq }
}

/**
 * A digest in reading groups, for a person copying it down one chunk at a time.
 *
 * LOSSLESS by construction — `groupDigest(v).join('') === v` — so what is written down is the
 * whole value and never a summary of it. Four to a group, matching the reading groups the key
 * ceremonies use (owner/therapistKeys.ts groupFingerprint, and the therapist acceptance page's
 * own), so a person who has compared fingerprints before is chunking the same way here. Restated
 * rather than imported for the same reason therapistKeys.ts restates it: that module pulls
 * libsodium in behind it, and none of that belongs in the admin console's bundle for the sake of
 * a slice() loop. The contract that matters is the group size, and it is stated in both places.
 */
export function groupDigest(value: string, size = 4): string[] {
  const out: string[] = []
  for (let i = 0; i < value.length; i += size) out.push(value.slice(i, i + size))
  return out
}

export type ChainHeadVerdict =
  | 'reported-consistent'
  | 'break-reported'
  | 'nothing-recorded'
  | 'not-configured'
  | 'refused'
  | 'unreachable'
  | 'unexpected'

/**
 * The chip words, fixed here so no call site can substitute a warmer one. Note what is absent,
 * as in health.ts: nothing that claims the chain is whole, right, or finished. "Reported
 * consistent" carries its own provenance — the server reported it about itself — where a bare
 * "Consistent" would read as this console's finding.
 */
export const CHAIN_HEAD_WORD: Record<ChainHeadVerdict, string> = {
  'reported-consistent': 'Reported consistent',
  'break-reported': 'Break reported',
  'nothing-recorded': 'Nothing recorded',
  'not-configured': 'Not configured',
  refused: 'Refused',
  unreachable: 'No answer',
  unexpected: 'Undocumented',
}

/**
 * THE CAVEAT, carried on every view this module can produce, the reassuring one most of all. It
 * restates the limitation health.ts's CHAIN_CAVEAT states — internal consistency is not
 * completeness, and cannot become it — and adds the one this panel introduces: the arithmetic ran
 * on the machine being checked.
 */
export const CHAIN_HEAD_CAVEAT =
  'Internal consistency is not completeness. A server that quietly declines to append an event ' +
  'leaves a chain that reports no break, and one that truncates the tail leaves a shorter chain ' +
  'that also reports no break — this check cannot see what was never stored or what is gone. ' +
  'And here the arithmetic ran on the server itself, over its own rows: against an honest server ' +
  'with damaged storage a reported break is real evidence, but a server willing to rewrite ' +
  'entries is equally able to recompute the chain over them and report itself consistent. Treat ' +
  'the verdict as the server’s statement about itself. What outlives a lying server is the ' +
  'head digest compared against a note the server cannot reach, and the recomputation you can ' +
  'run yourself in the examiner above, over entries taken from the database directly.'

/**
 * What the head digest is FOR — rendered under the digest whenever the chain reports no break,
 * because a value with no stated use is a value nobody writes down.
 */
export const HEAD_PURPOSE =
  'The head digest is the value to take away from this panel. Write it down, with the entry ' +
  'count and today’s date, somewhere this server cannot reach — paper does fine. A ' +
  'server that rewrites or truncates history it has already served must change its head, so on ' +
  'a later visit: if new entries were expected, expect a new head over a higher count; if the ' +
  'log should have been quiet, the head should not have moved at all. A head that contradicts ' +
  'your note is worth asking hard questions about, and your note is the only party in that ' +
  'comparison the server cannot edit. This is also the exact value a future phone anchor would ' +
  'record on an interval and hold against the server (the plan’s 3.9.7); until that ' +
  'exists, the note is the anchor.'

/**
 * Why the panel asks for a credential the rest of the console refuses to have. Rendered above
 * the inputs, so the person typing the token knows what the gate is protecting and whether the
 * token is theirs to type.
 */
export const CHAIN_HEAD_GATE =
  'This check is gated on the owner bearer token, exactly as reading the therapist’s ' +
  'published keys is — not because a chain head opens anything, but because a head plus an ' +
  'entry count, served per relationship to anyone who asked, would tell an anonymous caller ' +
  'which relationships exist on this server and how active each one is. That is relationship ' +
  'metadata, and this server does not volunteer it. On a self-hosted deployment the operator ' +
  'and the owner are often the same person, which is why the panel is here; if this token is ' +
  'not yours, this panel is not an invitation to obtain it. The token is sent once, to this ' +
  'server’s own route, and kept only in the field below while this page is open.'

export interface ChainHeadView {
  verdict: ChainHeadVerdict
  /** The chip word — always CHAIN_HEAD_WORD[verdict]. */
  word: string
  /** One authored sentence stating what was observed. */
  headline: string
  /** Always [CHAIN_HEAD_CAVEAT]. Present on every view, the reassuring ones included. */
  caveat: string
  /** The head digest in reading groups; empty when there is no head to show. */
  headGroups: string[]
  /** What to do with the digest — HEAD_PURPOSE on a no-break run, an evidence note on a break. */
  headNote: string | null
  /** Authored sentences qualifying the run: pruning, provenance, what was not established. */
  notes: string[]
  entryCount: number | null
  oldestSeq: number | null
  headSeq: number | null
  firstBreakSeq: number | null
  /** The ONE channel for text this module did not author: an error string, an unparseable body. */
  machineDetail?: string
}

/** The provenance note, on every view that carries a server-computed result. */
const SELF_REPORT_NOTE =
  'This check ran on the server, over the rows the server holds, and this panel reports what ' +
  'the server said about itself. The examiner above, over a run you paste from the database, is ' +
  'the version of this arithmetic that does not take the server’s word for it.'

const view = (
  verdict: ChainHeadVerdict,
  headline: string,
  rest: Partial<Omit<ChainHeadView, 'verdict' | 'word' | 'headline' | 'caveat'>> = {},
): ChainHeadView => ({
  verdict,
  word: CHAIN_HEAD_WORD[verdict],
  headline,
  caveat: CHAIN_HEAD_CAVEAT,
  headGroups: [],
  headNote: null,
  notes: [],
  entryCount: null,
  oldestSeq: null,
  headSeq: null,
  firstBreakSeq: null,
  ...rest,
})

/**
 * Turn one raw response into one view. Deliberately total, like readProbe: every response shape
 * produces something to render, because the most interesting answer this panel will ever see is
 * the one nobody wrote a branch for.
 */
export function readChainHead(res: ChainHeadResponse): ChainHeadView {
  if (res.kind === 'transport') {
    return view(
      'unreachable',
      'No answer. The request did not complete, so this says nothing about the chain — ' +
        'only that this browser could not reach the server.',
      { machineDetail: res.error },
    )
  }

  // The refusals this surface actually sends, each with its own honest reading. 401 and 403 are
  // ONE sentence on purpose: the owner surface answers non-enumeratingly, and this module will
  // not invent a distinction it was not given.
  if (res.status === 401 || res.status === 403) {
    return view(
      'refused',
      'The server did not accept the owner token, and nothing was read. It does not say which ' +
        'way the token was wrong, and neither can this panel.',
    )
  }
  if (res.status === 429) {
    return view(
      'refused',
      'The server is holding this address off for now — the lockout and rate machinery ' +
        'working, not a statement about the chain. Wait before trying again.',
    )
  }
  if (res.status === 503) {
    return view(
      'not-configured',
      'The server answered that the therapist portal is not configured on this deployment, so ' +
        'there are no relationships here and no chain to check.',
    )
  }

  if (res.status === 200) {
    let parsed: unknown
    try {
      parsed = JSON.parse(res.body)
    } catch {
      parsed = undefined
    }
    const record = parsed === undefined ? null : parseChainHeadRecord(parsed)
    if (record !== null) {
      return readRecord(record)
    }
  }

  return view(
    'unexpected',
    `Answered ${res.status}, which is not a shape this build is written to send here. Nothing ` +
      'on this panel is a statement about the chain.',
    { machineDetail: res.body },
  )
}

/** The three states a well-formed record can be in. */
function readRecord(record: ChainHeadRecord): ChainHeadView {
  const base = {
    entryCount: record.entryCount,
    oldestSeq: record.oldestSeq,
    headSeq: record.headSeq,
    firstBreakSeq: record.firstBreakSeq,
  }

  if (record.entryCount === 0) {
    return view(
      'nothing-recorded',
      'The server holds no entries under this reference. That is the answer for a quiet ' +
        'relationship and for one that never existed, and the two are deliberately the same ' +
        'answer.',
      { ...base, notes: [SELF_REPORT_NOTE] },
    )
  }

  const notes: string[] = []
  if (record.oldestSeq !== null && record.oldestSeq > 1) {
    notes.push(
      `The oldest entry still held is entry ${record.oldestSeq}, not entry 1. The ordinary ` +
        'cause is retention pruning, which removes the oldest entries by design and takes ' +
        'their hashes with it — but a hand that deleted the earliest entries would leave ' +
        'exactly this signal, and this check cannot tell the two apart. Either way the ' +
        'surviving oldest entry had nothing left to be recomputed against, so it was taken ' +
        'as the starting point rather than checked.',
    )
  }
  notes.push(SELF_REPORT_NOTE)

  if (record.firstBreakSeq !== null) {
    return view(
      'break-reported',
      `The server reports that its stored chain stops agreeing with itself at entry ` +
        `${record.firstBreakSeq}: recomputing that entry from what is stored beneath it does ` +
        'not give the hash stored on it. Something under that entry changed after it was ' +
        'written — a failing disk, a partial restore, or a hand that edited the file.',
      {
        ...base,
        headGroups: record.headHash === null ? [] : groupDigest(record.headHash),
        headNote:
          'This head sits on top of a chain that contradicts itself. Note it down with the ' +
          'break report and the date — as a record of what the server served today, not as ' +
          'a value to compare a later head against.',
        notes,
      },
    )
  }

  return view(
    'reported-consistent',
    `The server recomputed its stored chain and reports no internal break: ` +
      `${record.entryCount} ${record.entryCount === 1 ? 'entry' : 'entries'}, sequence ` +
      `${record.oldestSeq} to ${record.headSeq}, each agreeing with the one beneath it as stored.`,
    {
      ...base,
      headGroups: record.headHash === null ? [] : groupDigest(record.headHash),
      headNote: HEAD_PURPOSE,
      notes,
    },
  )
}
