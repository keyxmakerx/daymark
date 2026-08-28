import { describe, it, expect } from 'vitest'
import * as chainHead from './chainHead'
import {
  CHAIN_HEAD_CAVEAT,
  CHAIN_HEAD_GATE,
  CHAIN_HEAD_WORD,
  HEAD_PURPOSE,
  fetchChainHead,
  groupDigest,
  parseChainHeadRecord,
  readChainHead,
  type ChainHeadResponse,
} from './chainHead'

/*
 * The same discipline as health.test.ts, for the same reasons: half of what this module promises
 * is what it does NOT say — no completeness claim, no warmer word than the arithmetic supports,
 * no banned vocabulary anywhere in its authored strings. Absence tests go vacuous silently, so
 * every one below is preceded by a presence assertion in the same test: the detector is shown
 * catching a planted example, or the corpus is shown non-empty, before anything is filtered.
 */

/** A stored head the way the server writes one: 64 lowercase hex. */
const HEAD = 'ab12cd34'.repeat(8)

const record = (over: Partial<Record<string, unknown>> = {}): Record<string, unknown> => ({
  entryCount: 6,
  oldestSeq: 1,
  headSeq: 6,
  headHash: HEAD,
  ...over,
})

const ok = (body: unknown): ChainHeadResponse => ({ kind: 'response', status: 200, body: JSON.stringify(body) })

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   Reading groups.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('the head digest renders in reading groups', () => {
  it('groups by four and is lossless, so what is written down is the whole value', () => {
    const groups = groupDigest(HEAD)
    expect(groups.length).toBe(16)
    expect(groups.every((g) => g.length === 4)).toBe(true)
    // The contract that makes hand-copying safe: joining the groups reproduces the digest
    // exactly. A grouping that summarised would have a person comparing against less than the
    // server is committed to.
    expect(groups.join('')).toBe(HEAD)
    // Same at awkward lengths: the tail group carries the remainder rather than dropping it.
    expect(groupDigest('abcde').join('')).toBe('abcde')
    expect(groupDigest('abcde').pop()).toBe('e')
    expect(groupDigest('')).toEqual([])
  })
})

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   Parsing the wire record: understood or refused, never coerced.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('parsing the chain report', () => {
  it('accepts the shapes the server actually sends', () => {
    // Presence first, so every refusal below means something.
    expect(parseChainHeadRecord(record())).toEqual({
      entryCount: 6, oldestSeq: 1, headSeq: 6, headHash: HEAD, firstBreakSeq: null,
    })
    // A break is a well-formed answer, not a parse failure.
    expect(parseChainHeadRecord(record({ firstBreakSeq: 3 }))!.firstBreakSeq).toBe(3)
    // The empty chain omits its optionals on the wire (explicitNulls=false server-side).
    expect(parseChainHeadRecord({ entryCount: 0 })).toEqual({
      entryCount: 0, oldestSeq: null, headSeq: null, headHash: null, firstBreakSeq: null,
    })
    // A pruned chain starts above 1 and that is ordinary.
    expect(parseChainHeadRecord(record({ oldestSeq: 4 }))!.oldestSeq).toBe(4)
    // Unknown extra fields are ignored: additive server changes are not lies.
    expect(parseChainHeadRecord(record({ someFutureField: 'x' }))).not.toBeNull()
  })

  it('refuses everything else rather than rendering part of it', () => {
    const refusals: unknown[] = [
      null,
      [],
      'a string',
      {},
      record({ entryCount: -1 }),
      record({ entryCount: 2.5 }),
      record({ entryCount: '6' }),
      // A head that is not the one shape this server writes: wrong length, wrong case, not hex.
      record({ headHash: HEAD.slice(0, 63) }),
      record({ headHash: HEAD.toUpperCase() }),
      record({ headHash: 'z'.repeat(64) }),
      // Contradictory shapes the server cannot produce: entries with no head, a head with no
      // entries, a break in an empty chain. Partial rendering of these would be this module
      // inventing a chain the server never described.
      record({ headHash: null }),
      record({ headSeq: null }),
      { entryCount: 0, headHash: HEAD },
      { entryCount: 0, firstBreakSeq: 1 },
      record({ firstBreakSeq: 0 }),
      record({ firstBreakSeq: 'soon' }),
    ]
    for (const body of refusals) {
      expect(parseChainHeadRecord(body), JSON.stringify(body)).toBeNull()
    }
  })
})

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   The views: total over every answer, honest in every one.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('reading the response', () => {
  it('a no-break report renders the head in groups with its purpose stated', () => {
    const v = readChainHead(ok(record()))
    expect(v.verdict).toBe('reported-consistent')
    expect(v.word).toBe('Reported consistent')
    expect(v.headline).toMatch(/6 entries, sequence 1 to 6/)
    expect(v.headGroups.join('')).toBe(HEAD)
    // The digest is rendered WITH its use, or nobody writes it down: the purpose names the
    // note-taking, the comparison, and the plan's phone anchor it stands in for.
    expect(v.headNote).toBe(HEAD_PURPOSE)
    expect(HEAD_PURPOSE).toMatch(/write it down/i)
    expect(HEAD_PURPOSE).toMatch(/cannot reach/i)
    expect(HEAD_PURPOSE).toMatch(/3\.9\.7/)
    expect(HEAD_PURPOSE).toMatch(/phone anchor/i)
    // Provenance travels on the view: the server reported this about itself.
    expect(v.notes.some((n) => /said about itself/i.test(n))).toBe(true)
  })

  it('a single-entry chain is not described in the plural', () => {
    const v = readChainHead(ok({ entryCount: 1, oldestSeq: 3, headSeq: 3, headHash: HEAD }))
    expect(v.headline).toMatch(/1 entry, sequence 3 to 3/)
    expect(v.headline).not.toMatch(/1 entries/)
  })

  it('a break is reported at its seq, and the head demoted to evidence', () => {
    const v = readChainHead(ok(record({ firstBreakSeq: 4 })))
    expect(v.verdict).toBe('break-reported')
    expect(v.word).toBe('Break reported')
    expect(v.headline).toContain('entry 4')
    expect(v.firstBreakSeq).toBe(4)
    // The head still renders — what the server serves today is worth recording — but its note
    // must NOT be the write-down-and-compare purpose, which only a no-break run earns.
    expect(v.headGroups.join('')).toBe(HEAD)
    expect(v.headNote).not.toBe(HEAD_PURPOSE)
    expect(v.headNote).toMatch(/contradicts itself/i)
    expect(v.headNote).toMatch(/not as a value to compare/i)
  })

  it('a pruned chain carries the unanchored-oldest note; an unpruned one does not', () => {
    const pruned = readChainHead(ok(record({ oldestSeq: 4 })))
    expect(pruned.notes.some((n) => n.includes('entry 4, not entry 1'))).toBe(true)
    // The note may explain the ordinary cause (retention pruning) but must never CLOSE the
    // question: an adversarial prefix deletion produces the identical signal, and the copy
    // has to say the check cannot distinguish them. An earlier draft said "not a finding",
    // which normalized the one silent-edit case the caveat exists to keep open.
    expect(pruned.notes.some((n) => /cannot tell the two apart/i.test(n))).toBe(true)
    expect(pruned.notes.some((n) => /not a finding/i.test(n))).toBe(false)
    // Non-vacuity the other way: the note appears only when it is true.
    const whole = readChainHead(ok(record()))
    expect(whole.notes.some((n) => /retention pruning/i.test(n))).toBe(false)
  })

  it('an empty chain says quiet and never-existed are the same answer', () => {
    const v = readChainHead(ok({ entryCount: 0 }))
    expect(v.verdict).toBe('nothing-recorded')
    expect(v.headGroups).toEqual([])
    expect(v.headNote).toBeNull()
    expect(v.headline).toMatch(/deliberately the same answer/i)
  })

  it('401 and 403 produce one identical sentence, because the surface is non-enumerating', () => {
    const unauthorized = readChainHead({ kind: 'response', status: 401, body: '{"error":"unauthorized"}' })
    const forbidden = readChainHead({ kind: 'response', status: 403, body: '{"error":"unauthorized"}' })
    expect(unauthorized.verdict).toBe('refused')
    expect(unauthorized.headline).toMatch(/nothing was read/i)
    // The server refuses without saying which way the token was wrong; inventing the
    // distinction here would hand a probe what the server withheld.
    expect(forbidden.headline).toBe(unauthorized.headline)
  })

  it('429 is read as the lockout working, not as a verdict about the chain', () => {
    const v = readChainHead({ kind: 'response', status: 429, body: '{"error":"rate limited"}' })
    expect(v.verdict).toBe('refused')
    expect(v.headline).toMatch(/not a statement about the chain/i)
  })

  it('503 is the documented fail-closed answer, not an anomaly', () => {
    // Application.kt answers 503 on every portal path when the therapist portal is off. Reading
    // that as "undocumented" would report the server's own documented behaviour as suspicious.
    const v = readChainHead({ kind: 'response', status: 503, body: '{"error":"therapist portal not configured"}' })
    expect(v.verdict).toBe('not-configured')
    expect(v.headline).toMatch(/no chain to check/i)
  })

  it('a transport failure says nothing about the chain, and echoes only through machineDetail', () => {
    const v = readChainHead({ kind: 'transport', error: 'TypeError: Failed to fetch' })
    expect(v.verdict).toBe('unreachable')
    expect(v.headline).toMatch(/says nothing about the chain/i)
    expect(v.machineDetail).toBe('TypeError: Failed to fetch')
  })

  it('an undocumented answer is reported as undocumented, with the bytes quoted apart', () => {
    // Presence: the documented body is NOT flagged, so 'unexpected' means something.
    expect(readChainHead(ok(record())).verdict).toBe('reported-consistent')
    const odd = readChainHead({ kind: 'response', status: 502, body: '<html>Bad Gateway</html>' })
    expect(odd.verdict).toBe('unexpected')
    expect(odd.headline).toContain('502')
    expect(odd.machineDetail).toBe('<html>Bad Gateway</html>')
    // A 200 whose body is not a chain report is equally undocumented — including the
    // contradictory shapes parse refuses.
    expect(readChainHead({ kind: 'response', status: 200, body: 'OK' }).verdict).toBe('unexpected')
    expect(readChainHead(ok({ entryCount: 0, headHash: HEAD })).verdict).toBe('unexpected')
  })

  it('every view carries the caveat, the reassuring one most of all', () => {
    const responses: ChainHeadResponse[] = [
      ok(record()),
      ok(record({ firstBreakSeq: 2 })),
      ok({ entryCount: 0 }),
      { kind: 'response', status: 401, body: '' },
      { kind: 'response', status: 429, body: '' },
      { kind: 'response', status: 503, body: '' },
      { kind: 'response', status: 502, body: 'gateway' },
      { kind: 'transport', error: 'x' },
    ]
    const views = responses.map(readChainHead)
    // The battery really covers the vocabulary before the loop asserts anything.
    expect(new Set(views.map((v) => v.verdict)).size).toBe(7)
    for (const v of views) {
      expect(v.caveat, v.verdict).toBe(CHAIN_HEAD_CAVEAT)
      expect(v.word, v.verdict).toBe(CHAIN_HEAD_WORD[v.verdict])
    }
    // And the caveat says the two things it exists to say: not completeness, and self-reported.
    expect(CHAIN_HEAD_CAVEAT).toMatch(/not completeness/i)
    expect(CHAIN_HEAD_CAVEAT).toMatch(/declines to append/i)
    expect(CHAIN_HEAD_CAVEAT).toMatch(/truncates/i)
    expect(CHAIN_HEAD_CAVEAT).toMatch(/statement about itself/i)
    expect(CHAIN_HEAD_CAVEAT.length).toBeGreaterThan(400)
  })
})

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   The fetch: one request, the token in the header, never anywhere else.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('fetchChainHead', () => {
  it('asks the one route, bearer token in the header, reference encoded into the path', async () => {
    let seenUrl = ''
    let seenAuth: string | null = null
    const stub = (async (url: unknown, init?: RequestInit) => {
      seenUrl = String(url)
      seenAuth = new Headers(init?.headers).get('authorization')
      return new Response('{"entryCount":0}', { status: 200 })
    }) as typeof fetch
    const res = await fetchChainHead({ baseUrl: 'https://server.example/', token: 'tok-123' }, 'rel_abc', stub)
    expect(seenUrl).toBe('https://server.example/v1/relations/rel_abc/audit-chain')
    expect(seenAuth).toBe('Bearer tok-123')
    expect(res).toEqual({ kind: 'response', status: 200, body: '{"entryCount":0}' })
    // And the token appears nowhere in what the reader renders from it.
    expect(JSON.stringify(readChainHead(res))).not.toContain('tok-123')
  })

  it('turns a thrown fetch into a transport value instead of an exception path', async () => {
    const stub = (async () => {
      throw new TypeError('Failed to fetch')
    }) as typeof fetch
    const res = await fetchChainHead({ baseUrl: '', token: 't' }, 'rel_abc', stub)
    expect(res).toEqual({ kind: 'transport', error: 'TypeError: Failed to fetch' })
  })
})

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   No banned vocabulary, no warmer word than the arithmetic supports.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

/**
 * Every string this module authors: exported constants, deep, plus views from the full response
 * battery. machineDetail is skipped — it is the single declared channel for text the module did
 * not write, same contract as health.ts.
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
  for (const [name, value] of Object.entries(chainHead)) {
    if (typeof value === 'function') continue
    walk(value, name)
  }
  const battery: ChainHeadResponse[] = [
    ok(record()),
    ok(record({ firstBreakSeq: 3 })),
    ok(record({ oldestSeq: 5 })),
    ok({ entryCount: 1, oldestSeq: 9, headSeq: 9, headHash: HEAD }),
    ok({ entryCount: 0 }),
    { kind: 'response', status: 401, body: 'x' },
    { kind: 'response', status: 429, body: 'x' },
    { kind: 'response', status: 503, body: 'x' },
    { kind: 'response', status: 500, body: 'upstream at 97 pct memory' },
    { kind: 'transport', error: 'boom' },
  ]
  battery.forEach((res, i) => walk(readChainHead(res), `read(${i})`))
  return out
}

describe('nothing this module says is a score, a tick, or a success state', () => {
  // The identical ban list health.test.ts enforces over its own module, because the two panels
  // sit on one screen and a word forbidden on one must not arrive through the other.
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
    expect(corpus.length).toBeGreaterThan(60)
    const joined = corpus.map((c) => c.text).join('\n')
    expect(joined.length).toBeGreaterThan(4000)
    // Landmarks from the constants and from a computed view, so a walk that lost a branch fails
    // here rather than by an empty offender list.
    expect(joined).toContain('Internal consistency is not completeness.')
    expect(joined).toContain('The head digest is the value to take away from this panel.')
    expect(joined).toContain('Reported consistent')
    expect(joined).toMatch(/sequence 1 to 6/)

    const planted = [
      'Chain integrity: 100% verified',
      'ninety percent of entries recomputed',
      'compliance score 4/5, all clear',
      'the shield is green and the log is healthy',
      'graded A, rating stable, 3 out of 4 checks',
    ]
    for (const { name, re } of BANNED) {
      expect(planted.some((p) => re.test(p)), `the detector for "${name}" matches nothing`).toBe(true)
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

  it('no verdict word claims completeness, correctness, or a pass', () => {
    // The same claim vocabulary health.test.ts bans from its chips. "Reported consistent" and
    // never "Verified": a break-free report from the machine being checked is that machine's
    // statement about itself, and the chip must not upgrade it.
    const CLAIMS = /\b(verified|valid|intact|passed|pass|healthy|ok|good|secure|safe|clean)\b/i
    for (const planted of ['Verified', 'Valid', 'Intact', 'Passed', 'All good']) {
      expect(CLAIMS.test(planted), `the detector missed "${planted}"`).toBe(true)
    }
    expect(CLAIMS.test('Reported consistent')).toBe(false)

    const words = Object.entries(CHAIN_HEAD_WORD)
    expect(words.length).toBe(7)
    for (const [key, word] of words) {
      expect(CLAIMS.test(word), `verdict word for "${key}" claims more than observed: "${word}"`).toBe(false)
    }
  })

  it('the gate copy says whose token this is and what the gate protects', () => {
    expect(CHAIN_HEAD_GATE).toMatch(/owner bearer token/i)
    expect(CHAIN_HEAD_GATE).toMatch(/which relationships exist/i)
    expect(CHAIN_HEAD_GATE).toMatch(/how active/i)
    expect(CHAIN_HEAD_GATE).toMatch(/not an invitation to obtain it/i)
    // And where the token goes, said before a person types it.
    expect(CHAIN_HEAD_GATE).toMatch(/sent once/i)
  })
})
