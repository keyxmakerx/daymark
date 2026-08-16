import { describe, it, expect } from 'vitest'
import { createHash } from 'node:crypto'
import * as admin from './health'
import {
  ENDPOINTS,
  AUTH_PRESSURE,
  AUTH_PRESSURE_HEADLINE,
  CHAIN_CAVEAT,
  CHAIN_HEADLINE,
  CHAIN_NOT_ADMIN_READABLE,
  CONSOLE_COVERS,
  CONSOLE_WITHHELD,
  CREDENTIAL_POSTURE,
  NO_SINGLE_FIGURE,
  STANDING_FACTS,
  STANDING_FACTS_HEADLINE,
  chainUnavailable,
  describeLastChecked,
  parseChainExport,
  readProbe,
  verifyChainRun,
  type ChainEntry,
  type ChainProblem,
  type ChainReport,
  type ProbeId,
  type ProbeResponse,
} from './health'

/*
 * ═══════════════════════════════════════════════════════════════════════════════════════════
 * WHAT THIS SUITE IS FOR, AND WHY IT IS SHAPED THE WAY IT IS
 * ═══════════════════════════════════════════════════════════════════════════════════════════
 *
 * Three of the admin console's requirements are claims about what the software does NOT say — no
 * percentage, no score, no completeness claim, no relationship content. A test for an absence is
 * the easiest test in the world to write vacuously: `expect(offenders).toEqual([])` over a corpus
 * that turned out to be empty, or a regex that never matched anything because it was wrong.
 *
 * So every absence assertion below is preceded by its own presence assertion, in the same test,
 * with a comment saying so: the detector is shown catching a planted example, or the corpus is
 * shown to contain the real sentences before it is filtered. The convention is not decoration —
 * this repository has shipped a suite of thirty-eight passing tests whose fixture built an
 * epoch-day field in milliseconds, a value the app cannot produce, which hid every sleep log
 * landing on 1 January 1970.
 *
 * THE CHAIN FIXTURE IS A REAL CHAIN. It is not built by calling the module and asserting the
 * module agrees with itself. `buildChain` below is an independent transcription of
 * AuditStore.kt's `chainHash` and `canonicalMeta` — the '|' join, the field order, the empty
 * string for absent optionals, the sorted-then-percent-escaped meta encoding, the 64-hex genesis
 * — hashed with node's SHA-256, which is what the server's `sha256Hex` is. If the module's copy
 * of that encoding drifts from the server's, the parity test fails rather than both halves
 * agreeing on the same mistake.
 */

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   The chain fixture: an independent transcription of the server's append path.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

const sha256Hex = (input: string) => createHash('sha256').update(Buffer.from(input, 'utf8')).digest('hex')

const GENESIS = '0'.repeat(64)

/** AuditStore.escapeMeta: '%' first, so the escape character is itself escapable. */
const escape = (s: string) => s.replace(/%/g, '%25').replace(/,/g, '%2C').replace(/=/g, '%3D')

/** AuditStore.canonicalMeta: sort the RAW keys, then escape both halves, then join on ','. */
const encodeMeta = (meta: Record<string, string>) =>
  Object.keys(meta)
    .sort()
    .map((k) => `${escape(k)}=${escape(meta[k]!)}`)
    .join(',')

interface Event {
  ts: number
  actor: string
  action: string
  objectRef?: string
  meta?: Record<string, string>
}

/**
 * Append events the way the server does, starting from seq 1 and the genesis hash.
 *
 * `ts` is SECONDS. AuditStore's clock is `System.currentTimeMillis() / 1000`, so a fixture in
 * milliseconds would be a value the server cannot produce — the exact defect class this file's
 * header names. The unit is asserted, not assumed, in "the fixture is a chain this server could
 * actually have written" below.
 */
function buildChain(relRef: string, events: Event[]): ChainEntry[] {
  let prev = GENESIS
  return events.map((ev, i) => {
    const seq = i + 1
    const metaEncoded = ev.meta ? encodeMeta(ev.meta) : ''
    const canonical = [
      prev,
      String(seq),
      String(ev.ts),
      relRef,
      ev.actor,
      ev.action,
      ev.objectRef ?? '',
      metaEncoded,
    ].join('|')
    const entryHash = sha256Hex(canonical)
    prev = entryHash
    return {
      seq,
      ts: ev.ts,
      actor: ev.actor,
      action: ev.action,
      objectRef: ev.objectRef ?? null,
      meta: ev.meta ?? null,
      entryHash,
    }
  })
}

const REL = 'rel_7f3a9c2b'

/** Epoch SECONDS, five minutes apart, in August 2026 — the same shape the server writes. */
const T0 = 1_786_000_000

const EVENTS: Event[] = [
  { ts: T0, actor: 'therapist', action: 'auth.success', meta: { credentialId: 'cred_a1' } },
  { ts: T0 + 300, actor: 'therapist', action: 'share.open', objectRef: 'shares/lineage_9d/4' },
  { ts: T0 + 600, actor: 'owner', action: 'share.revoke', objectRef: 'shares/lineage_9d' },
  { ts: T0 + 900, actor: 'therapist', action: 'share.denied', objectRef: 'shares/lineage_9d/4' },
  { ts: T0 + 1200, actor: 'therapist', action: 'assignment.publish', objectRef: 'assignments/lineage_2e/1' },
  { ts: T0 + 1500, actor: 'therapist', action: 'session.expired' },
]

const CHAIN = buildChain(REL, EVENTS)

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   The endpoint catalogue matches the server this console talks to.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('the probe catalogue describes the server that exists', () => {
  it('lists exactly the three endpoints this build serves, and marks all three unauthenticated', () => {
    // Presence before absence: the catalogue is really populated, so the "no /metrics" assertion
    // below is filtering a non-empty list rather than reporting on nothing.
    expect(ENDPOINTS.length).toBe(3)
    expect(ENDPOINTS.map((e) => e.path)).toEqual(['/healthz', '/readyz', '/v1/config'])
    expect(ENDPOINTS.every((e) => e.method === 'GET')).toBe(true)

    // Every one of them answers anybody who can reach the app. If a future build adds a
    // credential this flips, and it should flip here first.
    expect(ENDPOINTS.every((e) => e.authenticated === false)).toBe(true)

    // The endpoints the console must NOT pretend to have. Nothing in companion/server registers
    // any of these; inventing one would be the whole failure this screen is meant to avoid.
    const invented = ['/metrics', '/v1/metrics', '/v1/admin', '/v1/stats', '/v1/audit/verify']
    expect(ENDPOINTS.filter((e) => invented.includes(e.path))).toEqual([])
  })

  it('every endpoint carries what it does not cover, beside what it does', () => {
    for (const e of ENDPOINTS) {
      expect(e.answers.length, `${e.path} answers`).toBeGreaterThan(20)
      expect(e.silentOn.length, `${e.path} silentOn`).toBeGreaterThan(40)
    }
    // The two limitations the server documents about itself, which are the ones an operator is
    // most likely to be wrong about.
    const liveness = ENDPOINTS.find((e) => e.id === 'liveness')!
    expect(liveness.silentOn).toMatch(/full disk|read-only/i)
    const ready = ENDPOINTS.find((e) => e.id === 'writability')!
    expect(ready.silentOn).toMatch(/lock contention/i)
    expect(ready.silentOn).toMatch(/log/i) // the 503 body carries no reason; the log does
  })
})

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   Readings.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

const response = (status: number, body: string): ProbeResponse => ({ kind: 'response', status, body })

describe('readings are computed from the bodies the server actually sends', () => {
  it('reads the two bodies /healthz and /readyz can send', () => {
    expect(readProbe('liveness', response(200, '{"ok":true}')).state).toBe('responding')
    expect(readProbe('writability', response(200, '{"ok":true}')).state).toBe('responding')

    const refusing = readProbe('writability', response(503, '{"ok":false}'))
    expect(refusing.state).toBe('refusing')
    // The operator is sent to the log, because the body genuinely does not carry the reason.
    expect(refusing.observed).toMatch(/log/i)
  })

  it('a liveness 200 is not allowed to imply the server can still write', () => {
    // The misreading this screen exists to prevent: /healthz stays 200 through a full disk.
    const reading = readProbe('liveness', response(200, '{"ok":true}'))
    expect(reading.observed).toBe('Answered 200. The process is up and routing.')
    expect(reading.observed).not.toMatch(/writ|disk|ready/i)
    // And the limitation travels with the reading rather than living on another screen.
    expect(reading.silentOn).toMatch(/full disk/i)
  })

  it('an undocumented answer is reported as undocumented, not swallowed', () => {
    // Non-vacuity: the same endpoint with the documented body is NOT flagged, so 'unexpected'
    // means something. A proxy answering 502 in front of the app is the realistic case.
    expect(readProbe('liveness', response(200, '{"ok":true}')).state).toBe('responding')
    const odd = readProbe('liveness', response(502, '<html>Bad Gateway</html>'))
    expect(odd.state).toBe('unexpected')
    expect(odd.observed).toContain('502')
    expect(odd.machineDetail).toBe('<html>Bad Gateway</html>')

    // A 200 with a body this build never sends is equally undocumented.
    expect(readProbe('writability', response(200, 'OK')).state).toBe('unexpected')
    // …and a 503 on liveness is too: only readiness has a documented refusal.
    expect(readProbe('liveness', response(503, '{"ok":false}')).state).toBe('unexpected')
  })

  it('a transport failure says nothing about the server', () => {
    const reading = readProbe('writability', { kind: 'transport', error: 'TypeError: Failed to fetch' })
    expect(reading.state).toBe('unreachable')
    expect(reading.observed).toMatch(/says nothing about the server/i)
    expect(reading.machineDetail).toBe('TypeError: Failed to fetch')
  })

  it('the capability probe reports the flag, and refuses to guess when the body is wrong', () => {
    const on = readProbe('capability', response(200, '{"smtpEnabled":true}'))
    expect(on.state).toBe('responding')
    expect(on.smtpEnabled).toBe(true)

    const off = readProbe('capability', response(200, '{"smtpEnabled":false}'))
    expect(off.state).toBe('responding')
    expect(off.smtpEnabled).toBe(false)

    // Presence proven above; now the absence. A missing or non-boolean flag must not default to
    // false, which would read on screen as "the operator switched mail off".
    const bad = readProbe('capability', response(200, '{"smtp":"yes"}'))
    expect(bad.state).toBe('unexpected')
    expect(bad.smtpEnabled).toBeUndefined()
  })
})

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   The chain. The fixture first, then the detectors, then the claims.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('the fixture is a chain this server could actually have written', () => {
  it('uses epoch SECONDS, not milliseconds', () => {
    // The defect class this repository has already shipped once: a fixture in a unit the app
    // cannot produce, which made a suite green over a bug. Read as seconds these land in 2026;
    // read as milliseconds they would land in 1970, and a fixture that only makes sense under
    // the wrong reading is not a fixture.
    for (const e of CHAIN) {
      expect(new Date(e.ts * 1000).getUTCFullYear()).toBe(2026)
      expect(new Date(e.ts).getUTCFullYear()).toBe(1970) // what the wrong unit would have meant
    }
  })

  it('is chained off the genesis hash, in 64 lowercase hex, with no repeats', () => {
    expect(CHAIN.length).toBe(6)
    expect(CHAIN.map((e) => e.seq)).toEqual([1, 2, 3, 4, 5, 6])
    for (const e of CHAIN) expect(e.entryHash).toMatch(/^[0-9a-f]{64}$/)
    expect(new Set(CHAIN.map((e) => e.entryHash)).size).toBe(6)

    // The first entry really does chain off the genesis constant — recomputed here from the
    // transcription rather than taken from the module.
    const first = EVENTS[0]!
    const canonical = [
      GENESIS, '1', String(first.ts), REL, first.actor, first.action, '', encodeMeta(first.meta!),
    ].join('|')
    expect(CHAIN[0]!.entryHash).toBe(sha256Hex(canonical))
  })

  it('exercises the meta encoding that the escaping exists for', () => {
    // The forgery the percent-escaping closes: a value containing the separators. If the module's
    // encoding were not injective in the same way the server's is, this entry would verify under
    // a forged reading of its own meta.
    const hostile = buildChain(REL, [
      { ts: T0, actor: 'therapist', action: 'auth.success', meta: { credentialId: 'a,sourceIp=10.0.0.1', z: '100%' } },
    ])
    expect(encodeMeta(hostile[0]!.meta!)).toBe('credentialId=a%2CsourceIp%3D10.0.0.1,z=100%25')
    const report = verifyChainRun({ relRef: REL, entries: hostile, digest: sha256Hex })
    expect(report.verdict).toBe('internally-consistent')
  })

  it('sorts the keys, because the server does and a divergence would fabricate alarm', () => {
    // AuditStore.kt encodes meta through toSortedMap(), so this must sort too. Every other fixture
    // here is single-key or already alphabetical, which left the .sort() untested: deleting it
    // kept the whole suite green. The consequence of losing it is not a subtle one — every honest
    // multi-key entry would report hash-mismatch, and the operator would be reading a screen full
    // of fabricated alarm about a server that had done nothing wrong.
    //
    // Reachable in practice through the path this console actually advertises: a run the operator
    // reconstructs by hand from audit.db, where key order is whatever they typed.
    const outOfOrder = { sourceIp: '10.0.0.1', credentialId: 'cred_a1' }
    const sorted = { credentialId: 'cred_a1', sourceIp: '10.0.0.1' }
    expect(Object.keys(outOfOrder)).not.toEqual(Object.keys(sorted)) // the fixture really is unsorted
    expect(encodeMeta(outOfOrder)).toBe(encodeMeta(sorted))
    expect(encodeMeta(outOfOrder)).toBe('credentialId=cred_a1,sourceIp=10.0.0.1')

    // And end to end: a chain whose meta was written out of order still verifies.
    const chain = buildChain(REL, [
      { ts: T0, actor: 'therapist', action: 'auth.success', meta: outOfOrder },
    ])
    expect(verifyChainRun({ relRef: REL, entries: chain, digest: sha256Hex }).verdict).toBe(
      'internally-consistent',
    )
  })
})

describe('the chain detectors detect', () => {
  /*
   * Every "this chain is fine" assertion in the next block is worthless unless these pass first.
   * Each case below plants exactly one defect in the real chain and shows the verifier naming it.
   */

  it('catches a flipped character in an entry hash', () => {
    const tampered = CHAIN.map((e, i) =>
      i === 3 ? { ...e, entryHash: e.entryHash.slice(0, 63) + (e.entryHash.endsWith('a') ? 'b' : 'a') } : e,
    )
    const report = verifyChainRun({ relRef: REL, entries: tampered, digest: sha256Hex })
    expect(report.verdict).toBe('contradicted')
    expect(report.findings.map((f) => f.problem)).toContain('hash-mismatch')
  })

  it('catches an entry whose CONTENT was edited after it was written', () => {
    // The realistic tamper: rewrite what happened, leave the stored hash alone.
    const tampered = CHAIN.map((e) => (e.seq === 3 ? { ...e, action: 'share.open' } : e))
    const report = verifyChainRun({ relRef: REL, entries: tampered, digest: sha256Hex })
    expect(report.verdict).toBe('contradicted')
    expect(report.findings.some((f) => f.seq === 3 && f.problem === 'hash-mismatch')).toBe(true)
  })

  it('catches a hash repeated across two entries', () => {
    const tampered = CHAIN.map((e) => (e.seq === 5 ? { ...e, entryHash: CHAIN[1]!.entryHash } : e))
    const report = verifyChainRun({ relRef: REL, entries: tampered })
    expect(report.verdict).toBe('contradicted')
    expect(report.findings.map((f) => f.problem)).toContain('repeated-hash')
  })

  it('catches a malformed hash', () => {
    const tampered = CHAIN.map((e) => (e.seq === 2 ? { ...e, entryHash: 'DEADBEEF' } : e))
    const report = verifyChainRun({ relRef: REL, entries: tampered })
    expect(report.verdict).toBe('contradicted')
    expect(report.findings.map((f) => f.problem)).toContain('malformed-hash')
  })

  it('catches a timestamp that runs backwards as the sequence advances', () => {
    const tampered = CHAIN.map((e) => (e.seq === 4 ? { ...e, ts: e.ts - 5000 } : e))
    const report = verifyChainRun({ relRef: REL, entries: tampered })
    expect(report.verdict).toBe('contradicted')
    expect(report.findings.map((f) => f.problem)).toContain('timestamp-regression')
  })

  it('catches a sequence number missing from the middle of a run', () => {
    const withHole = CHAIN.filter((e) => e.seq !== 4)
    // Structurally — no digest available, which is the console's default position.
    const structural = verifyChainRun({ entries: withHole })
    expect(structural.verdict).toBe('incomplete-run')
    expect(structural.findings.map((f) => f.problem)).toContain('sequence-skip')
    // And with the digest, where the broken link is evidence too and outranks the gap.
    const hashed = verifyChainRun({ relRef: REL, entries: withHole, digest: sha256Hex })
    expect(hashed.verdict).toBe('contradicted')
    expect(hashed.findings.map((f) => f.problem)).toEqual(
      expect.arrayContaining(['sequence-skip', 'hash-mismatch']),
    )
  })

  it('catches two entries sharing a sequence number', () => {
    const doubled = [...CHAIN, { ...CHAIN[2]!, entryHash: CHAIN[5]!.entryHash.replace(/^./, '1') }]
    const report = verifyChainRun({ entries: doubled })
    expect(report.verdict).toBe('contradicted')
    expect(report.findings.map((f) => f.problem)).toContain('repeated-sequence')
  })
})

describe('a chain that verifies still says incompleteness is possible', () => {
  /*
   * THE non-negotiable. Everything above exists so that this block cannot be read as "the
   * verifier is lenient": it names a defect in six different ways before it says a clean run is
   * clean.
   */

  it('reports the intact chain as internally consistent — and never as complete', () => {
    const report = verifyChainRun({ relRef: REL, entries: CHAIN, digest: sha256Hex })
    expect(report.verdict).toBe('internally-consistent')
    expect(report.findings).toEqual([])
    expect(report.entriesExamined).toBe(6)

    // The verdict sentence itself does not claim completeness.
    expect(report.headline).toBe(CHAIN_HEADLINE['internally-consistent'])
    expect(report.headline).toMatch(/internally consistent/i)
    expect(report.headline).not.toMatch(/\bcomplete\b|\bnothing is missing\b|\bverified\b|\bintact\b/i)

    // The caveat is attached to the CLEAN report, which is the only report where it matters.
    expect(report.caveat).toBe(CHAIN_CAVEAT)
    expect(report.caveat).toMatch(/not completeness/i)
    expect(report.caveat).toMatch(/declines to append/i)
    expect(report.caveat).toMatch(/truncat/i)
    expect(report.caveat).toMatch(/do not read it as/i)

    // And it is body text: long enough to be a paragraph a person reads, not a footnote.
    expect(report.caveat.length).toBeGreaterThan(400)

    // Completeness is listed under what was NOT checked, on the clean report.
    expect(report.notChecked.some((s) => /complete/i.test(s))).toBe(true)
  })

  it('every report this module can produce carries the same caveat', () => {
    // Including the ones where a lazy implementation would omit it.
    const reports: ChainReport[] = [
      verifyChainRun({ relRef: REL, entries: CHAIN, digest: sha256Hex }),
      verifyChainRun({ entries: CHAIN }),
      verifyChainRun({ entries: [] }),
      verifyChainRun({ entries: CHAIN.filter((e) => e.seq !== 4) }),
      verifyChainRun({ relRef: REL, entries: CHAIN.map((e) => ({ ...e, entryHash: 'x' })) }),
      chainUnavailable(CHAIN_NOT_ADMIN_READABLE),
    ]
    expect(reports.length).toBe(6)
    for (const r of reports) {
      expect(r.caveat, r.verdict).toBe(CHAIN_CAVEAT)
      expect(r.notChecked.length, r.verdict).toBeGreaterThan(0)
    }
    // The six really are different reports, not the same one six times.
    expect(new Set(reports.map((r) => r.verdict)).size).toBeGreaterThanOrEqual(4)
  })

  it('a TRUNCATED history verifies exactly as cleanly as the full one — which is the point', () => {
    // The concrete demonstration behind the caveat's wording. A hostile server that serves only
    // the first three entries of a six-entry log produces a run this check cannot fault.
    const full = verifyChainRun({ relRef: REL, entries: CHAIN, digest: sha256Hex })
    const truncated = verifyChainRun({ relRef: REL, entries: CHAIN.slice(0, 3), digest: sha256Hex })

    expect(full.verdict).toBe('internally-consistent')
    expect(truncated.verdict).toBe('internally-consistent')
    expect(truncated.findings).toEqual([])
    // Nothing in the verdict distinguishes them. The only difference is a count, which is not
    // evidence of anything on its own — the log may simply have had three entries.
    expect(truncated.headline).toBe(full.headline)
    expect(truncated.entriesExamined).toBe(3)
  })

  it('a history where an event was NEVER appended is indistinguishable from a whole one', () => {
    // Withholding, as opposed to truncation: the server declines to record the third event at
    // all, and chains the fourth off the second. That is a different chain, and it is a perfectly
    // valid one.
    const suppressed = buildChain(REL, EVENTS.filter((_, i) => i !== 2))
    const report = verifyChainRun({ relRef: REL, entries: suppressed, digest: sha256Hex })
    expect(report.verdict).toBe('internally-consistent')
    expect(report.findings).toEqual([])
    // No sequence gap either: the server renumbers, because seq is assigned at append time.
    expect(report.newestSeq).toBe(5)
  })

  it('the oldest entry of a WINDOW cannot be hash-checked, and the report says so', () => {
    // A page fetched with a cursor has no predecessor for its first row, so that row's hash has
    // nothing to chain to. This is not a shortcut in the implementation; it is what examining a
    // window of a log means, and an operator who does not know it will over-read the result.
    const window = CHAIN.slice(2) // seq 3..6
    const clean = verifyChainRun({ relRef: REL, entries: window, digest: sha256Hex })
    expect(clean.verdict).toBe('internally-consistent')
    expect(clean.notChecked.some((s) => s.includes('entry 3'))).toBe(true)

    // Non-vacuity, and the sharp end of it: edit the CONTENT of that unverifiable oldest row and
    // the run still comes back clean, because nothing in the window can contradict it. The same
    // edit one row later is caught (proven in "catches an entry whose CONTENT was edited").
    const editedOldest = window.map((e) => (e.seq === 3 ? { ...e, actor: 'owner', action: 'auth.success' } : e))
    const stillClean = verifyChainRun({ relRef: REL, entries: editedOldest, digest: sha256Hex })
    expect(stillClean.verdict).toBe('internally-consistent')
    expect(stillClean.findings).toEqual([])
  })
})

describe('the report never claims a check it did not run', () => {
  it('without a digest, the run says the hashes were not recomputed — and really did not', () => {
    // Presence: with a digest, this exact input is caught.
    const tampered = CHAIN.map((e) => (e.seq === 3 ? { ...e, action: 'share.open' } : e))
    expect(verifyChainRun({ relRef: REL, entries: tampered, digest: sha256Hex }).verdict).toBe('contradicted')

    // Absence: without one, it is NOT caught — and the report is required to admit that rather
    // than let a clean structural verdict pass for a tamper check.
    const structural = verifyChainRun({ relRef: REL, entries: tampered })
    expect(structural.verdict).toBe('internally-consistent')
    expect(structural.findings).toEqual([])
    expect(structural.notChecked.some((s) => /says nothing about tampering/i.test(s))).toBe(true)
    expect(structural.checked.some((s) => /recomputed/i.test(s))).toBe(false)
  })

  it('a digest with no relationship reference is skipped, not guessed', () => {
    // Guessing the relRef would turn every honest entry into a mismatch — a screen full of
    // fabricated alarm, which is worse than an admitted gap.
    const report = verifyChainRun({ entries: CHAIN, digest: sha256Hex })
    expect(report.verdict).toBe('internally-consistent')
    expect(report.findings).toEqual([])
    expect(report.notChecked.some((s) => /relationship reference/i.test(s))).toBe(true)
  })

  it('an empty run establishes nothing, including that there was nothing to return', () => {
    const report = verifyChainRun({ entries: [] })
    expect(report.verdict).toBe('nothing-returned')
    expect(report.checked).toEqual([])
    expect(report.entriesExamined).toBe(0)
    expect(report.oldestSeq).toBeNull()
    expect(report.notChecked.some((s) => /nothing to return/i.test(s))).toBe(true)
  })

  it('withdraws only the sentence whose check failed, not all of them', () => {
    // A run with one gap has still established that its timestamps do not run backwards. Showing
    // that is more informative than a blanket refusal, and it is what the operator needs to know.
    const gapped = verifyChainRun({ entries: CHAIN.filter((e) => e.seq !== 4) })
    expect(gapped.verdict).toBe('incomplete-run')
    expect(gapped.checked.some((s) => /unbroken/i.test(s))).toBe(false)
    expect(gapped.checked.some((s) => /Timestamps do not move backwards/i.test(s))).toBe(true)
  })

  it('walks the run in sequence order, so arrival order is never a finding', () => {
    // The API returns newest-first; a dump off the box is oldest-first. Treating arrival order as
    // evidence would manufacture a finding out of a SELECT clause.
    const newestFirst = [...CHAIN].reverse()
    const shuffled = [CHAIN[2]!, CHAIN[0]!, CHAIN[5]!, CHAIN[1]!, CHAIN[4]!, CHAIN[3]!]
    for (const order of [CHAIN, newestFirst, shuffled]) {
      const report = verifyChainRun({ relRef: REL, entries: order, digest: sha256Hex })
      expect(report.verdict).toBe('internally-consistent')
      expect(report.oldestSeq).toBe(1)
      expect(report.newestSeq).toBe(6)
    }
  })
})

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   The relationship audit log is owner-readable. This console must not leak it.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('the chain report is about the chain, never about what the log records', () => {
  it('leaks no actor, action, object reference or meta value into its output', () => {
    const SENTINELS = [
      'therapist_hilda_moreau',
      'share.open.sentinel',
      'shares/lineage_SENTINEL/4',
      'cred_SENTINEL_9',
    ]
    const row = (over: Partial<Event> = {}): Event => ({
      ts: T0,
      actor: SENTINELS[0]!,
      action: SENTINELS[1]!,
      objectRef: SENTINELS[2]!,
      meta: { credentialId: SENTINELS[3]! },
      ...over,
    })
    const marked = buildChain(REL, [row(), row({ ts: T0 + 60 })])

    // NON-VACUITY, both halves. The sentinels really are in the input…
    const asInput = JSON.stringify(marked)
    for (const s of SENTINELS) expect(asInput, `sentinel ${s} missing from fixture`).toContain(s)
    // …and a naive report that serialised its entries WOULD carry them, so the detector below is
    // capable of failing. (This is the shape of the bug, written out.)
    const leaky = JSON.stringify({ verdict: 'internally-consistent', entries: marked })
    for (const s of SENTINELS) expect(leaky).toContain(s)

    /*
     * EVERY channel a sentence can come out of, not the two that were easiest to build.
     *
     * This test used to run over a clean report and one timestamp-regression, which reached three
     * of the six problem kinds. The other three detail strings could each have interpolated the
     * offending row — the exact "lazy implementation quotes the row it is complaining about" this
     * test names — and shipped green. Worse, the `relRef` assertion below sat on a branch the
     * fixture could not reach at all: the only prose `relRef` appears in is the windowed-run
     * sentence, guarded by `seq !== 1`, and every fixture here started at seq 1. That assertion
     * was incapable of failing.
     *
     * So: one report per problem kind, plus a windowed run, and the union of kinds actually
     * observed is asserted to be the whole vocabulary before any of it is scanned.
     */
    const clean = verifyChainRun({ relRef: REL, entries: marked, digest: sha256Hex })
    const longer = buildChain(REL, [row(), row({ ts: T0 + 60 }), row({ ts: T0 + 120 }), row({ ts: T0 + 180 })])

    const reports = [
      clean,
      // timestamp-regression (+ hash-mismatch, since the hash covers ts)
      verifyChainRun({ relRef: REL, entries: marked.map((e) => (e.seq === 2 ? { ...e, ts: e.ts - 9_999 } : e)), digest: sha256Hex }),
      // malformed-hash
      verifyChainRun({ relRef: REL, entries: marked.map((e) => (e.seq === 2 ? { ...e, entryHash: 'not-a-hash' } : e)), digest: sha256Hex }),
      // repeated-hash
      verifyChainRun({ relRef: REL, entries: marked.map((e) => (e.seq === 2 ? { ...e, entryHash: marked[0]!.entryHash } : e)), digest: sha256Hex }),
      // repeated-sequence
      verifyChainRun({ relRef: REL, entries: marked.map((e) => (e.seq === 2 ? { ...e, seq: 1 } : e)), digest: sha256Hex }),
      // sequence-skip
      verifyChainRun({ relRef: REL, entries: [longer[0]!, longer[3]!], digest: sha256Hex }),
      // a WINDOWED run — the only path where relRef is in scope and rendered into prose
      verifyChainRun({ relRef: REL, entries: longer.slice(2), digest: sha256Hex }),
      // and the same window with no digest, which takes the other unavailable branch
      verifyChainRun({ relRef: REL, entries: longer.slice(2) }),
    ]

    // The union really is the whole vocabulary: without this, dropping a case above would quietly
    // shrink what the scan covers while every assertion below still passed.
    const seen = new Set(reports.flatMap((r) => r.findings.map((f) => f.problem)))
    const ALL: ChainProblem[] = [
      'hash-mismatch',
      'repeated-hash',
      'repeated-sequence',
      'malformed-hash',
      'sequence-skip',
      'timestamp-regression',
    ]
    for (const kind of ALL) expect(seen, `no report exercised ${kind}`).toContain(kind)
    expect(clean.findings).toEqual([])

    for (const report of reports) {
      const rendered = JSON.stringify(report)
      for (const s of SENTINELS) {
        expect(rendered, `report leaked ${s}`).not.toContain(s)
      }
      // Nor the relationship reference itself, which is the identifier the whole log hangs off.
      expect(rendered, 'report leaked the relationship reference').not.toContain(REL)
    }
  })

  it('states that the audit log is owner-readable and not served here', () => {
    expect(CHAIN_NOT_ADMIN_READABLE).toMatch(/owner bearer token/i)
    expect(CHAIN_NOT_ADMIN_READABLE).toMatch(/inbox token/i)
    expect(CHAIN_NOT_ADMIN_READABLE).toMatch(/owner-readable by design/i)
    // And that no verification route exists to be called instead.
    expect(CHAIN_NOT_ADMIN_READABLE).toMatch(/no route .* verifies a chain/i)

    const withheld = CONSOLE_WITHHELD.find((w) => /audit log/i.test(w.subject))
    expect(withheld, 'the audit log must be named as withheld').toBeDefined()
    expect(withheld!.reason).toMatch(/owner-readable/i)
    // Non-vacuity for the lookup: a subject that is not on the list is not found.
    expect(CONSOLE_WITHHELD.find((w) => /nothing at all/i.test(w.subject))).toBeUndefined()
  })

  it('names what this console covers and what it declines to show', () => {
    expect(CONSOLE_COVERS.length).toBe(3)
    expect(CONSOLE_COVERS.join(' ')).toMatch(/health/i)
    expect(CONSOLE_COVERS.join(' ')).toMatch(/pressure/i)
    expect(CONSOLE_COVERS.join(' ')).toMatch(/internal consistency/i)
    expect(CONSOLE_WITHHELD.length).toBeGreaterThanOrEqual(3)
  })
})

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   No percentage. No score. No overall figure. Anywhere in the output.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

/**
 * Every string the module authors: its exported constants, deep, plus the view models it builds
 * from a battery of inputs.
 *
 * `machineDetail` is skipped, and that is deliberate rather than convenient — it is the single
 * declared channel for text the module did NOT author (a fetch error, a body that did not
 * parse). The ban is on the product's own vocabulary; refusing to echo a server's bytes back to
 * the operator would be a different and worse kind of dishonesty. That the channel really is the
 * only one is asserted separately, below.
 */
function authoredStrings(): { path: string; text: string }[] {
  const out: { path: string; text: string }[] = []

  const walk = (value: unknown, path: string) => {
    if (typeof value === 'string') {
      out.push({ path, text: value })
    } else if (Array.isArray(value)) {
      value.forEach((v, i) => walk(v, `${path}[${i}]`))
    } else if (value && typeof value === 'object') {
      for (const [k, v] of Object.entries(value)) {
        if (k === 'machineDetail') continue
        walk(v, `${path}.${k}`)
      }
    }
  }

  // 1. Every exported constant.
  for (const [name, value] of Object.entries(admin)) {
    if (typeof value === 'function') continue
    walk(value, name)
  }

  // 2. Every reading, across every id and every response shape.
  const responses: ProbeResponse[] = [
    response(200, '{"ok":true}'),
    response(503, '{"ok":false}'),
    response(200, '{"smtpEnabled":true}'),
    response(200, '{"smtpEnabled":false}'),
    response(502, 'gateway'),
    { kind: 'transport', error: 'TypeError: Failed to fetch' },
  ]
  for (const id of ['liveness', 'writability', 'capability'] as ProbeId[]) {
    for (const [i, res] of responses.entries()) walk(readProbe(id, res), `readProbe(${id},${i})`)
  }

  // 3. Every chain report shape.
  walk(verifyChainRun({ relRef: REL, entries: CHAIN, digest: sha256Hex }), 'chain.clean')
  walk(verifyChainRun({ entries: CHAIN }), 'chain.structural')
  walk(verifyChainRun({ entries: [] }), 'chain.empty')
  walk(verifyChainRun({ entries: CHAIN.filter((e) => e.seq !== 4) }), 'chain.gap')
  walk(
    verifyChainRun({ relRef: REL, entries: CHAIN.map((e) => (e.seq === 2 ? { ...e, ts: 1 } : e)), digest: sha256Hex }),
    'chain.tampered',
  )
  walk(verifyChainRun({ relRef: REL, entries: CHAIN.slice(2), digest: sha256Hex }), 'chain.window')
  walk(chainUnavailable(CHAIN_NOT_ADMIN_READABLE), 'chain.unavailable')

  // 4. Parse failures and the staleness line.
  for (const text of ['', 'not json', '{"nope":1}', '[{"seq":"x"}]', '[{"seq":1,"ts":2}]']) {
    walk(parseChainExport(text), `parse(${text})`)
  }
  for (const [at, now] of [[null, 0], [1000, 1000], [0, 30_000], [0, 90_000], [0, 3_600_000], [0, 90_000_000]] as const) {
    out.push({ path: `describeLastChecked(${at},${now})`, text: describeLastChecked(at, now) })
  }

  return out
}

describe('nothing this module says is a score, a percentage or an overall figure', () => {
  const BANNED: { name: string; re: RegExp }[] = [
    { name: 'percent sign', re: /%/ },
    { name: 'the word percent', re: /\bpercent(age)?s?\b/i },
    { name: 'a score', re: /\bscor(e|es|ed|ing)\b/i },
    { name: 'a grade', re: /\bgrad(e|es|ed|ing)\b/i },
    { name: 'a rating', re: /\brat(ing|ings|ed)\b/i },
    { name: 'compliance', re: /\bcompliance\b/i },
    { name: 'a ratio like 3/5', re: /\b\d+\s*\/\s*\d+\b/ },
    { name: 'out of N', re: /\bout of \d+\b/i },
    { name: 'green', re: /\bgreen\b/i },
    { name: 'a shield', re: /\bshield\b/i },
    { name: 'all clear', re: /\ball[- ]clear\b/i },
    { name: 'healthy', re: /\bhealthy\b/i },
  ]

  const corpus = authoredStrings()

  it('the corpus is real and the detectors detect', () => {
    // Non-vacuity, first half: the walk actually collected the module's prose. Without this the
    // filter below would report success over an empty list.
    expect(corpus.length).toBeGreaterThan(150)
    const joined = corpus.map((c) => c.text).join('\n')
    expect(joined.length).toBeGreaterThan(9000)
    // Landmark sentences from three different sections, so a walk that lost a branch is caught
    // here rather than by an empty offender list.
    expect(joined).toContain('Internal consistency is not completeness')
    expect(joined).toContain('This build exposes no counter for active lockouts.')
    expect(joined).toContain('The TOTP seed is stored in the clear')
    expect(joined).toContain('Answered 200. The process is up and routing.')

    // Non-vacuity, second half: each detector catches a planted example of the thing it bans.
    const planted = [
      'Deployment health: 87% — all clear',
      'compliance score 4/5 this week',
      'overall grade: B, rating stable',
      'the shield is green and the system is healthy',
      'ninety percent of probes answered',
      'passed 3 out of 4 checks',
    ]
    for (const { name, re } of BANNED) {
      expect(planted.some((p) => re.test(p)), `the detector for "${name}" matches nothing`).toBe(true)
    }
  })

  it('no verdict word claims completeness, correctness, or a pass', () => {
    /*
     * The chips, specifically — not the prose around them.
     *
     * These two maps put the single loudest phrase on each panel, and they used to live in
     * AdminConsole.svelte where nothing checked them at all. Moving them into this module puts
     * them in the corpus above, but that corpus bans scores and green shields; it does not ban
     * the words that matter here, because the caveat prose legitimately uses them ("a server that
     * declines to append leaves a chain that verifies perfectly"). So the ban is scoped to the
     * verdict vocabulary, where any of these words would be a claim the module cannot support.
     *
     * "Internally consistent" and never "Verified": a hostile server can decline to append, or
     * truncate to a shorter run that verifies perfectly. "Responding" and never "Healthy": this
     * console reports what it observed.
     */
    const CLAIMS = /\b(verified|valid|intact|passed|pass|healthy|ok|good|secure|safe|clean)\b/i
    // The detector fires on exactly the substitutions this is here to prevent, so the assertion
    // below is a fact about the vocabulary and not about a regex that matches nothing.
    for (const planted of ['Verified', 'Valid', 'Intact', 'Passed', 'Healthy', 'All good']) {
      expect(CLAIMS.test(planted), `the detector missed "${planted}"`).toBe(true)
    }
    expect(CLAIMS.test('Internally consistent')).toBe(false)

    const words = [
      ...Object.entries(admin.CHAIN_VERDICT_WORD),
      ...Object.entries(admin.PROBE_STATE_WORD),
    ]
    // The maps really were read: an empty list would satisfy every assertion in the loop.
    expect(words.length).toBe(9)
    for (const [key, word] of words) {
      expect(CLAIMS.test(word), `verdict word for "${key}" claims more than observed: "${word}"`).toBe(false)
    }
  })

  it('no authored string contains any of them', () => {
    const offenders: string[] = []
    for (const { path, text } of corpus) {
      for (const { name, re } of BANNED) {
        const hit = re.exec(text)
        if (hit) offenders.push(`${path}: ${name} — "${hit[0]}" in ${JSON.stringify(text.slice(0, 90))}`)
      }
    }
    expect(offenders).toEqual([])
  })

  it('and says out loud why there is no single figure', () => {
    expect(NO_SINGLE_FIGURE).toMatch(/no overall figure/i)
    expect(NO_SINGLE_FIGURE).toMatch(/will not be one/i)
    expect(NO_SINGLE_FIGURE).toMatch(/counters that do not exist/i)
  })

  it('machineDetail is the only channel that echoes text the module did not author', () => {
    // The exemption above has to be earned. A body containing a percentage must survive verbatim
    // in machineDetail — and must appear nowhere else on the reading.
    const reading = readProbe('liveness', response(500, 'upstream at 97% memory, refusing'))
    expect(reading.machineDetail).toBe('upstream at 97% memory, refusing')
    const withoutDetail = { ...reading, machineDetail: undefined }
    expect(JSON.stringify(withoutDetail)).not.toContain('97')
    expect(JSON.stringify(withoutDetail)).not.toContain('%')
    // And a reading that has nothing to echo does not invent the field.
    expect(readProbe('liveness', response(200, '{"ok":true}')).machineDetail).toBeUndefined()
  })
})

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   The known-bad news, on the screen.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('the standing facts about this deployment are present and unflattering', () => {
  it('carries both, and no reassuring third', () => {
    // Presence before any claim about content.
    expect(STANDING_FACTS.length).toBe(2)
    const ids = STANDING_FACTS.map((f) => f.id)
    expect(ids).toEqual(expect.arrayContaining(['webauthn-501', 'totp-seed-cleartext']))
    // Non-vacuity for the lookup: a fact that is not there is not found.
    expect(STANDING_FACTS.find((f) => f.id === 'everything-is-fine')).toBeUndefined()
    // Two tones exist and neither of them reassures. There is no 'info' and no 'ok' here.
    expect(STANDING_FACTS.every((f) => f.tone === 'warn' || f.tone === 'critical')).toBe(true)
    expect(STANDING_FACTS.some((f) => f.tone === 'critical')).toBe(true)
  })

  it('says WebAuthn is a 501 stub, and what that costs', () => {
    const fact = STANDING_FACTS.find((f) => f.id === 'webauthn-501')!
    expect(fact.title).toMatch(/501/)
    expect(fact.body).toMatch(/501 Not Implemented/)
    expect(fact.body).toMatch(/never (was )?written|were never written/i)
    // The consequence, not just the fact: TOTP is the only second factor, and it is phishable.
    expect(fact.consequence).toMatch(/TOTP/)
    expect(fact.consequence).toMatch(/phishable/i)
    expect(fact.consequence).toMatch(/step-up/i)
    // Checkable rather than assertable.
    expect(fact.evidence).toMatch(/TherapistAuthRoutes\.kt/)
  })

  it('says the TOTP seed is stored in the clear, and what that costs', () => {
    const fact = STANDING_FACTS.find((f) => f.id === 'totp-seed-cleartext')!
    expect(fact.title).toMatch(/in the clear/i)
    expect(fact.body).toMatch(/secret_b64/)
    expect(fact.body).toMatch(/not as a hash/i)
    // It must be framed as structural, or a reader will file it as a bug someone will fix.
    expect(fact.body).toMatch(/structural/i)
    // The consequence: backups of the data directory are as good as the credential.
    expect(fact.consequence).toMatch(/backup/i)
    expect(fact.consequence).toMatch(/mint valid/i)
    expect(fact.evidence).toMatch(/AuthStore\.kt/)
    expect(fact.tone).toBe('critical')
  })

  it('is framed as a standing condition, not as a changelog entry', () => {
    // The whole point of the panel: these do not scroll away and do not clear on recovery.
    expect(STANDING_FACTS_HEADLINE).toMatch(/not .*changelog entries/i)
    expect(STANDING_FACTS_HEADLINE).toMatch(/do not clear/i)
    expect(STANDING_FACTS_HEADLINE).toMatch(/until the build changes/i)
  })
})

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   Auth pressure: seven gaps, stated as gaps.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('auth pressure is reported as absent rather than approximated', () => {
  it('states each gap in the same words and says where the number really lives', () => {
    expect(AUTH_PRESSURE.length).toBeGreaterThanOrEqual(5)
    expect(new Set(AUTH_PRESSURE.map((g) => g.id)).size).toBe(AUTH_PRESSURE.length)
    for (const gap of AUTH_PRESSURE) {
      expect(gap.statement, gap.id).toMatch(/^This build exposes no /)
      expect(gap.subject.length, gap.id).toBeGreaterThan(10)
      // `heldAt` is what turns a shrug into a lead. A gap without one is a gap nobody can act on.
      expect(gap.heldAt.length, gap.id).toBeGreaterThan(40)
    }
    // The subjects an operator under attack actually asks about.
    const subjects = AUTH_PRESSURE.map((g) => g.subject).join(' | ')
    expect(subjects).toMatch(/bearer/i)
    expect(subjects).toMatch(/lock(ed )?out/i)
    expect(subjects).toMatch(/rate limit/i)
    expect(subjects).toMatch(/TOTP/)
    expect(subjects).toMatch(/session/i)
    // Non-vacuity for the lookup: something not tracked is not found.
    expect(AUTH_PRESSURE.find((g) => g.id === 'requests-per-second')).toBeUndefined()
  })

  it('the headline refuses the substitute rather than apologising for the gap', () => {
    expect(AUTH_PRESSURE_HEADLINE).toMatch(/no metrics endpoint/i)
    expect(AUTH_PRESSURE_HEADLINE).toMatch(/stand-in/i)
    expect(AUTH_PRESSURE_HEADLINE).toMatch(/assumes it is zero/i)
  })

  it('the one signal that does exist is named as the whole signal', () => {
    // AuthGuard's WARN line on a sweep that frees nothing is the only externalisation of source
    // tracking anywhere in the build. Saying so is more useful than listing the gap alone.
    const tracked = AUTH_PRESSURE.find((g) => g.id === 'tracked-sources')!
    expect(tracked.heldAt).toMatch(/WARN log line/i)
    expect(tracked.heldAt).toMatch(/whole signal/i)
  })
})

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   Credential posture.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('the console is honest about having no credential of its own', () => {
  it('says there is no administrator identity on this build', () => {
    expect(CREDENTIAL_POSTURE).toMatch(/no administrator identity/i)
    expect(CREDENTIAL_POSTURE).toMatch(/unauthenticated/i)
    // It must not be mistaken for a control surface: it grants nothing.
    expect(CREDENTIAL_POSTURE).toMatch(/holds no authority/i)
    // And the operational instruction that follows from that.
    expect(CREDENTIAL_POSTURE).toMatch(/where you would serve a shell/i)
    // Pasted runs stay local — the operator is told before they paste, not after.
    expect(CREDENTIAL_POSTURE).toMatch(/browser tab/i)
  })
})

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   Reading a run the operator supplies.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('parsing a pasted run', () => {
  it('accepts the wire page shape and a bare array alike', () => {
    const page = JSON.stringify({ events: [...CHAIN].reverse(), nextCursor: null })
    const fromPage = parseChainExport(page)
    expect(fromPage.kind).toBe('entries')
    if (fromPage.kind !== 'entries') throw new Error('unreachable')
    expect(fromPage.entries.length).toBe(6)
    // …and the parsed entries still verify, so the parse is lossless where it matters.
    expect(verifyChainRun({ relRef: REL, entries: fromPage.entries, digest: sha256Hex }).verdict).toBe(
      'internally-consistent',
    )

    const fromArray = parseChainExport(JSON.stringify(CHAIN))
    expect(fromArray.kind).toBe('entries')
  })

  it('refuses rather than coercing, so no finding is ever manufactured', () => {
    // Presence first: a well-formed row IS accepted, so the rejections below mean something.
    expect(parseChainExport(JSON.stringify([CHAIN[0]])).kind).toBe('entries')

    const cases: [string, RegExp][] = [
      ['', /Nothing was pasted/],
      ['   ', /Nothing was pasted/],
      ['not json', /not JSON/],
      ['{"rows":[]}', /events/],
      ['"a string"', /events/],
      ['[{"ts":1,"actor":"a","action":"b","entryHash":"c"}]', /numeric "seq"/],
      ['[{"seq":1,"actor":"a","action":"b","entryHash":"c"}]', /numeric "ts"/],
      ['[{"seq":1,"ts":1,"action":"b","entryHash":"c"}]', /"actor"/],
      ['[{"seq":1,"ts":1,"actor":"a","action":"b"}]', /"entryHash"/],
      ['[1,2,3]', /not an object/],
      ['[{"seq":1,"ts":1,"actor":"a","action":"b","entryHash":"c","meta":[]}]', /not an object/],
      ['[{"seq":1,"ts":1,"actor":"a","action":"b","entryHash":"c","meta":{"k":7}}]', /non-string value/],
    ]
    for (const [text, expected] of cases) {
      const parsed = parseChainExport(text)
      expect(parsed.kind, `should have refused: ${text}`).toBe('unreadable')
      if (parsed.kind !== 'unreadable') throw new Error('unreachable')
      expect(parsed.problem, text).toMatch(expected)
    }
  })

  it('keeps meta and objectRef through the round trip, because the hash covers them', () => {
    const parsed = parseChainExport(JSON.stringify(CHAIN))
    if (parsed.kind !== 'entries') throw new Error('unreachable')
    expect(parsed.entries[0]!.meta).toEqual({ credentialId: 'cred_a1' })
    expect(parsed.entries[1]!.objectRef).toBe('shares/lineage_9d/4')
    // Absent optionals must come back as null, not as the string "null" or an empty object —
    // either would change the hash input and fail every honest entry.
    expect(parsed.entries[5]!.objectRef).toBeNull()
    expect(parsed.entries[5]!.meta).toBeNull()
  })
})

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   Staleness.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('how stale the readings are', () => {
  it('reads a clock it is given rather than one it fetches', () => {
    expect(describeLastChecked(null, 1_700_000_000_000)).toBe('not checked yet')
    expect(describeLastChecked(1000, 1000)).toBe('checked just now')
    expect(describeLastChecked(0, 4_999)).toBe('checked just now')
    expect(describeLastChecked(0, 30_000)).toBe('checked 30 seconds ago')
    expect(describeLastChecked(0, 60_000)).toBe('checked a minute ago')
    expect(describeLastChecked(0, 300_000)).toBe('checked 5 minutes ago')
    expect(describeLastChecked(0, 3_600_000)).toBe('checked an hour ago')
    expect(describeLastChecked(0, 7_200_000)).toBe('checked 2 hours ago')
    expect(describeLastChecked(0, 90_000_000)).toMatch(/stale/)
  })

  it('does not render a negative age when the browser clock moves backwards', () => {
    // Presence: the same pair the right way round produces a real age, so the clamp below is not
    // hiding a broken calculation.
    expect(describeLastChecked(0, 30_000)).toBe('checked 30 seconds ago')
    expect(describeLastChecked(30_000, 0)).toBe('checked just now')
    expect(describeLastChecked(30_000, 0)).not.toMatch(/-/)
  })
})
