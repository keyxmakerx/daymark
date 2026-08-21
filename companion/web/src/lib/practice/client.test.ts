import { describe, it, expect } from 'vitest'
import {
  FAILURE_SENTENCE,
  PracticeClient,
  failureSentence,
  formatInstant,
  isMemberId,
  isPracticeName,
  memberIdProblem,
  nothingChanged,
  practiceNameProblem,
  readFailure,
  type PracticeFailure,
} from './client'

/*
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * WHAT THIS SUITE IS FOR
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 *
 * Two halves, and the second is the one that matters.
 *
 * REQUEST SHAPING. Every call is recorded and inspected: the path, the method, the headers, the
 * body. Three of these are security-bearing rather than cosmetic — the anti-forgery token on every
 * write (without it any page a clinician visited could re-role their colleagues, since the session
 * cookie rides cross-site on its own), the step-up code on exactly the two acts that widen who may
 * be offered access and on neither of the others, and `credentials: 'include'` on everything, since
 * the cookie is HttpOnly and cannot be attached by hand.
 *
 * ERROR HANDLING. The surface has three refusals that look alike and are not, and the assertions
 * below are written against the consequence of getting each one wrong: a 404 read as "no such
 * practice" teaches a user something false about a server that was deliberately being careful; a
 * 403 sorted into the wrong bucket sends somebody hunting for an administrator they do not need; a
 * transport failure read as "nothing happened" is a guess about a row that may well exist.
 *
 * Every absence assertion is preceded by a presence assertion in the same test — the convention
 * this repository already uses in lib/admin/health.test.ts and lib/onboarding/audience.test.ts —
 * so a check cannot go green by matching nothing.
 */

interface Recorded {
  url: string
  init: RequestInit
}

/** A fetch that records what it was asked and answers from a table. Mirrors session.test.ts. */
function fakeFetch(
  routes: { match: string; method?: string; respond: () => Response }[],
  log: Recorded[] = [],
): typeof fetch {
  return (async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    const method = (init?.method ?? 'GET').toUpperCase()
    log.push({ url, init: init ?? {} })
    const hit = routes.find(
      (r) => url.includes(r.match) && (r.method === undefined || r.method === method),
    )
    if (!hit) return new Response(JSON.stringify({ error: 'no route in this test' }), { status: 599 })
    return hit.respond()
  }) as unknown as typeof fetch
}

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

const headersOf = (rec: Recorded): Record<string, string> =>
  (rec.init.headers ?? {}) as Record<string, string>

const MEMBER = { memberId: 'ada', role: 'clinician', addedAt: 1_700_000_000_000, accepted: false }

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   Request shaping.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('request shaping', () => {
  it('reads a roster from the practice in the path, with the cookie attached', async () => {
    const log: Recorded[] = []
    const client = new PracticeClient(
      'https://clinic.example/',
      fakeFetch([{ match: '/members', respond: () => json({ orgId: 'p1', members: [MEMBER] }) }], log),
    )

    const result = await client.roster('p1')

    expect(result.ok).toBe(true)
    expect(result.ok && result.value).toEqual([MEMBER])
    expect(log).toHaveLength(1)
    // The trailing slash on the base is stripped exactly once — a doubled slash is a different
    // path to a strict router, and the failure would be a 404 that reads like a missing practice.
    expect(log[0].url).toBe('https://clinic.example/v1/orgs/p1/members')
    expect(log[0].init.credentials).toBe('include')
  })

  it('percent-encodes the practice and member ids it is given', async () => {
    // The server's charset excludes slashes, so a caller cannot legitimately produce one — which is
    // exactly why the encoding must not be left to the caller. An id that escaped the path segment
    // would address a different route entirely.
    const log: Recorded[] = []
    const client = new PracticeClient(
      '',
      fakeFetch([{ match: '/v1/orgs', respond: () => json({ memberId: 'x', sessionsCut: 0 }) }], log),
    )

    await client.removeMember('a/b', 'c d', { csrf: 'CSRF' })

    expect(log[0].url).toContain('a%2Fb')
    expect(log[0].url).toContain('c%20d')
    expect(log[0].url).not.toContain('a/b')
  })

  it('sends the anti-forgery token on every write, and never on a read', async () => {
    const log: Recorded[] = []
    const client = new PracticeClient(
      '',
      fakeFetch(
        [
          { match: '/audit', respond: () => json({ events: [] }) },
          { match: '/members/me/accept', respond: () => json(MEMBER) },
          { match: '/role', respond: () => json(MEMBER) },
          { match: '/members', method: 'POST', respond: () => json(MEMBER, 201) },
          { match: '/members', method: 'DELETE', respond: () => json({ memberId: 'ada', sessionsCut: 1 }) },
          { match: '/members', method: 'GET', respond: () => json({ orgId: 'p1', members: [] }) },
        ],
        log,
      ),
    )

    await client.roster('p1')
    await client.audit('p1')
    await client.addMember('p1', 'ada', 'clinician', { csrf: 'C1', stepUpCode: '111111' })
    await client.changeRole('p1', 'ada', 'front_desk', { csrf: 'C2', stepUpCode: '222222' })
    await client.acceptOwnSeat('p1', { csrf: 'C3' })
    await client.removeMember('p1', 'ada', { csrf: 'C4' })

    const [roster, audit, add, role, accept, remove] = log
    // Presence first: the writes really do carry it, so the two absences below mean something.
    expect(headersOf(add)['X-CSRF-Token']).toBe('C1')
    expect(headersOf(role)['X-CSRF-Token']).toBe('C2')
    expect(headersOf(accept)['X-CSRF-Token']).toBe('C3')
    expect(headersOf(remove)['X-CSRF-Token']).toBe('C4')
    expect(headersOf(roster)['X-CSRF-Token']).toBeUndefined()
    expect(headersOf(audit)['X-CSRF-Token']).toBeUndefined()
  })

  it('spends a step-up code on the two widening acts and on nothing else', async () => {
    // The annoyance budget in one assertion. Adding a member and changing a role change who may be
    // offered access and cost a code; accepting your own seat confers nothing on you, and removing
    // somebody is the safe direction, which must never be made expensive. A code attached to a
    // removal would also burn a single-use step on the one act that must never be blocked by a
    // missing authenticator.
    const log: Recorded[] = []
    const client = new PracticeClient(
      '',
      fakeFetch(
        [
          { match: '/members/me/accept', respond: () => json(MEMBER) },
          { match: '/role', respond: () => json(MEMBER) },
          { match: '/members', method: 'POST', respond: () => json(MEMBER, 201) },
          { match: '/members', method: 'DELETE', respond: () => json({ memberId: 'ada', sessionsCut: 0 }) },
        ],
        log,
      ),
    )

    const auth = { csrf: 'C', stepUpCode: '123456' }
    await client.addMember('p1', 'ada', 'clinician', auth)
    await client.changeRole('p1', 'ada', 'org_admin', auth)
    await client.acceptOwnSeat('p1', auth)
    await client.removeMember('p1', 'ada', auth)

    const [add, role, accept, remove] = log
    expect(headersOf(add)['X-Stepup-Code']).toBe('123456')
    expect(headersOf(role)['X-Stepup-Code']).toBe('123456')
    expect(headersOf(accept)['X-Stepup-Code']).toBeUndefined()
    expect(headersOf(remove)['X-Stepup-Code']).toBeUndefined()
  })

  it('omits a blank step-up header rather than sending an empty one', async () => {
    const log: Recorded[] = []
    const client = new PracticeClient(
      '',
      fakeFetch([{ match: '/members', respond: () => json(MEMBER, 201) }], log),
    )

    await client.addMember('p1', 'ada', 'clinician', { csrf: 'C', stepUpCode: '   ' })

    expect(headersOf(log[0])['X-CSRF-Token']).toBe('C')
    expect(headersOf(log[0])['X-Stepup-Code']).toBeUndefined()
  })

  it('sends the anti-forgery header even when the token is empty', async () => {
    // A missing header must never validate against a missing stored token. Sending it blank earns a
    // 401, which is the correct answer for a caller with no token; omitting it would be indistinct
    // from a cross-site post that never had one.
    const log: Recorded[] = []
    const client = new PracticeClient(
      '',
      fakeFetch([{ match: '/members', respond: () => json({ error: 'unauthorized' }, 401) }], log),
    )

    await client.removeMember('p1', 'ada', { csrf: '' })

    expect(Object.keys(headersOf(log[0]))).toContain('X-CSRF-Token')
    expect(headersOf(log[0])['X-CSRF-Token']).toBe('')
  })

  it('puts the role in the body of an add and a role change, and the ids in the path', async () => {
    const log: Recorded[] = []
    const client = new PracticeClient(
      '',
      fakeFetch(
        [
          { match: '/role', respond: () => json(MEMBER) },
          { match: '/members', method: 'POST', respond: () => json(MEMBER, 201) },
        ],
        log,
      ),
    )

    await client.addMember('p1', 'ada', 'front_desk', { csrf: 'C', stepUpCode: '1' })
    await client.changeRole('p1', 'ada', 'org_admin', { csrf: 'C', stepUpCode: '2' })

    expect(JSON.parse(String(log[0].init.body))).toEqual({ memberId: 'ada', role: 'front_desk' })
    expect(log[0].url).toBe('/v1/orgs/p1/members')
    expect(JSON.parse(String(log[1].init.body))).toEqual({ role: 'org_admin' })
    expect(log[1].url).toBe('/v1/orgs/p1/members/ada/role')
  })

  it('creates a practice with the bearer token and no session headers', async () => {
    const log: Recorded[] = []
    const client = new PracticeClient(
      '',
      fakeFetch(
        [{ match: '/v1/orgs', respond: () => json({ orgId: 'p9', name: 'Clinic', createdAt: 5 }, 201) }],
        log,
      ),
    )

    const result = await client.createPractice('Clinic', 'ada', 'TOKEN')

    expect(result.ok && result.value.orgId).toBe('p9')
    expect(headersOf(log[0])['Authorization']).toBe('Bearer TOKEN')
    // Not a member's request: no anti-forgery token, because there is no session to forge against.
    expect(headersOf(log[0])['X-CSRF-Token']).toBeUndefined()
    expect(JSON.parse(String(log[0].init.body))).toEqual({ name: 'Clinic', adminMemberId: 'ada' })
  })

  it('sends audit paging parameters only when it was asked for them', async () => {
    const log: Recorded[] = []
    const client = new PracticeClient(
      '',
      fakeFetch([{ match: '/audit', respond: () => json({ events: [], nextCursor: null }) }], log),
    )

    await client.audit('p1')
    await client.audit('p1', { before: 42 })
    await client.audit('p1', { before: 42, limit: 10 })

    // The bare read carries no query at all, so the route's own default page size is the one in
    // force rather than a number this client picked.
    expect(log[0].url).toBe('/v1/orgs/p1/audit')
    expect(log[1].url).toBe('/v1/orgs/p1/audit?before=42')
    expect(log[2].url).toBe('/v1/orgs/p1/audit?before=42&limit=10')
  })

  it('reports having no way to make a request rather than throwing', async () => {
    // A console mounted in a context with no fetch must not take the page down, and must not
    // report a server refusal it never asked for.
    const client = new PracticeClient('', null)
    const result = await client.roster('p1')
    expect(result.ok).toBe(false)
    expect(!result.ok && result.failure.kind).toBe('no-answer')
  })
})

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   Error handling.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('reading a refusal', () => {
  it('separates the two different 403s on the server’s own words', () => {
    // The whole reason readFailure exists. One is a wall, the other is a missing six-digit code.
    expect(readFailure(403, JSON.stringify({ error: 'step-up required' })).kind).toBe('step-up-required')
    expect(readFailure(403, JSON.stringify({ error: 'insufficient role' })).kind).toBe('role-insufficient')
  })

  it('quotes an unfamiliar 403 instead of sorting it into whichever is more common', () => {
    const failure = readFailure(403, JSON.stringify({ error: 'something this build has not seen' }))
    expect(failure.kind).toBe('refused')
    expect(failure).toHaveProperty('serverSaid', 'something this build has not seen')
  })

  it('maps every status this surface produces', () => {
    expect(readFailure(400, JSON.stringify({ error: 'unknown role' })).kind).toBe('invalid')
    expect(readFailure(401, JSON.stringify({ error: 'unauthorized' })).kind).toBe('unauthorized')
    expect(readFailure(404, JSON.stringify({ error: 'not found' })).kind).toBe('not-found')
    expect(readFailure(409, JSON.stringify({ error: 'already a member' })).kind).toBe('conflict')
    expect(readFailure(429, JSON.stringify({ error: 'rate limited' })).kind).toBe('rate-limited')
    expect(readFailure(502, '<html>bad gateway</html>').kind).toBe('server-error')
  })

  it('carries the server’s conflict message through, because it is more specific than ours', () => {
    const failure = readFailure(409, JSON.stringify({ error: 'the practice must keep an admin' }))
    expect(failureSentence(failure)).toContain('the practice must keep an admin')
  })

  it('quotes a non-JSON body, capped, rather than inventing a message', () => {
    const long = 'x'.repeat(500)
    const failure = readFailure(400, long)
    expect(failure).toHaveProperty('serverSaid')
    const said = (failure as { serverSaid: string }).serverSaid
    expect(said.length).toBeLessThan(250)
    expect(said.startsWith('xxx')).toBe(true)
  })

  it('says nothing extra when the body was empty', () => {
    const failure = readFailure(400, '')
    expect(failure).toEqual({ kind: 'invalid', serverSaid: '' })
    expect(failureSentence(failure)).toBe(FAILURE_SENTENCE.invalid)
  })

  it('never resolves a 404 into one of its two meanings, and says both', () => {
    // The non-enumeration property, defended in the client. A sentence that picked one would teach
    // its reader something false about a server that was deliberately being careful.
    const sentence = failureSentence({ kind: 'not-found' })
    expect(sentence).toContain('no practice with this id')
    expect(sentence).toContain('not a member of it')
    expect(sentence).toContain('does not say which')
  })

  it('has a sentence for every failure kind, and none of them claims anything went well', () => {
    const kinds: PracticeFailure['kind'][] = [
      'invalid',
      'unauthorized',
      'step-up-required',
      'role-insufficient',
      'refused',
      'not-found',
      'conflict',
      'rate-limited',
      'server-error',
      'unreadable-answer',
      'no-answer',
    ]
    // Totality: a kind added without a sentence would render as `undefined` in a callout.
    for (const kind of kinds) {
      expect(FAILURE_SENTENCE[kind], kind).toBeTruthy()
      expect(FAILURE_SENTENCE[kind].length, kind).toBeGreaterThan(20)
    }
    expect(Object.keys(FAILURE_SENTENCE).sort()).toEqual([...kinds].sort())
    // The register: flat and adult. The detector is shown catching a planted example first.
    const CHEER = /!|sorry|oops|whoops|great|awesome|success/i
    expect(CHEER.test('Oops! Something went wrong.')).toBe(true)
    expect(Object.values(FAILURE_SENTENCE).filter((s) => CHEER.test(s))).toEqual([])
  })
})

describe('a failure is not automatically evidence that nothing happened', () => {
  it('is true for the refusals a handler emits before touching the store', () => {
    for (const failure of [
      { kind: 'invalid', serverSaid: '' },
      { kind: 'unauthorized' },
      { kind: 'step-up-required' },
      { kind: 'role-insufficient' },
      { kind: 'refused', serverSaid: '' },
      { kind: 'not-found' },
      { kind: 'conflict', serverSaid: '' },
      { kind: 'rate-limited' },
    ] as PracticeFailure[]) {
      expect(nothingChanged(failure), failure.kind).toBe(true)
    }
  })

  it('is false for everything the server did not itself decide', () => {
    // The expensive lesson from PortalClient.enrollTotp: a write commits before it responds, so a
    // 502 from a proxy, an unreadable body and a dropped socket are all states this client cannot
    // know. Reading them as "it did not happen" is a guess about a row that may well exist.
    expect(nothingChanged({ kind: 'server-error', status: 502 })).toBe(false)
    expect(nothingChanged({ kind: 'unreadable-answer', detail: '' })).toBe(false)
    expect(nothingChanged({ kind: 'no-answer', detail: '' })).toBe(false)
  })

  it('turns a rejected fetch into no-answer rather than into a refusal', async () => {
    const client = new PracticeClient('', (async () => {
      throw new TypeError('Failed to fetch')
    }) as unknown as typeof fetch)

    const result = await client.addMember('p1', 'ada', 'clinician', { csrf: 'C', stepUpCode: '1' })

    expect(result.ok).toBe(false)
    expect(!result.ok && result.failure.kind).toBe('no-answer')
    expect(!result.ok && failureSentence(result.failure)).toContain('Failed to fetch')
    expect(!result.ok && failureSentence(result.failure)).toContain('not the same as nothing happening')
  })

  it('treats a 2xx whose body cannot be read as unknown, not as an empty member', async () => {
    const client = new PracticeClient(
      '',
      fakeFetch([{ match: '/members', respond: () => new Response('<html>proxy</html>', { status: 201 }) }]),
    )

    const result = await client.addMember('p1', 'ada', 'clinician', { csrf: 'C', stepUpCode: '1' })

    expect(result.ok).toBe(false)
    expect(!result.ok && result.failure.kind).toBe('unreadable-answer')
    expect(!result.ok && nothingChanged(result.failure)).toBe(false)
  })

  it('treats an empty 2xx body the same way', async () => {
    const client = new PracticeClient(
      '',
      fakeFetch([{ match: '/members', respond: () => new Response('', { status: 200 }) }]),
    )
    const result = await client.roster('p1')
    expect(!result.ok && result.failure.kind).toBe('unreadable-answer')
  })

  it('defaults a roster response with no members array to an empty list', async () => {
    // A shape this build does not expect must not throw inside a click handler. An empty list is
    // the safe reading here because the panel labels it as what the server returned.
    const client = new PracticeClient(
      '',
      fakeFetch([{ match: '/members', respond: () => json({ orgId: 'p1' }) }]),
    )
    const result = await client.roster('p1')
    expect(result.ok && result.value).toEqual([])
  })
})

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   Identifiers and timestamps.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('identifiers, as the server defines them', () => {
  it('accepts what the server’s charset accepts', () => {
    expect(isMemberId('ada')).toBe(true)
    expect(isMemberId('ada-lovelace_1')).toBe(true)
    expect(isMemberId('a'.repeat(64))).toBe(true)
  })

  it('refuses what the server refuses, including the empty string and a separator', () => {
    expect(isMemberId('')).toBe(false)
    expect(isMemberId('a'.repeat(65))).toBe(false)
    expect(isMemberId('ada lovelace')).toBe(false)
    // The colon in particular: membership rows are keyed on `orgId:memberId`, and the charset
    // excluding the separator is what makes that encoding injective.
    expect(isMemberId('org:ada')).toBe(false)
    expect(isMemberId('a/b')).toBe(false)
  })

  it('says what is wrong rather than answering "invalid"', () => {
    expect(memberIdProblem('')).toMatch(/Enter/)
    expect(memberIdProblem('a'.repeat(65))).toMatch(/64/)
    expect(memberIdProblem('ada lovelace')).toMatch(/no spaces/)
    expect(memberIdProblem('ada')).toBeNull()
  })

  it('bounds a practice name and refuses control characters', () => {
    expect(isPracticeName('Riverside Practice')).toBe(true)
    expect(isPracticeName('   ')).toBe(false)
    expect(isPracticeName('x'.repeat(121))).toBe(false)
    // A bell character, which `OrgStore.isOrgName` rejects through `Char.isISOControl`.
    expect(isPracticeName('Riverside\u0007Practice')).toBe(false)
    expect(practiceNameProblem('Riverside\u0007Practice')).toMatch(/control characters/)
    expect(practiceNameProblem('Riverside Practice')).toBeNull()
    expect(practiceNameProblem('')).toMatch(/Enter a name/)
    expect(practiceNameProblem('x'.repeat(121))).toMatch(/120/)
  })
})

describe('rendering a wire timestamp', () => {
  it('renders in UTC, so two administrators in two countries read one log the same way', () => {
    expect(formatInstant(1_700_000_000_000)).toBe('2023-11-14 22:13 UTC')
  })

  it('never renders a missing timestamp as a date in 1970', () => {
    // Several server paths answer `0L` when a row is read back immediately after a write. A date
    // there is a fabricated fact, and a fabricated fact is indistinguishable from a bug.
    expect(formatInstant(0)).toBe('not recorded')
    expect(formatInstant(null)).toBe('not recorded')
    expect(formatInstant(undefined)).toBe('not recorded')
    expect(formatInstant(Number.NaN)).toBe('not recorded')
    expect(formatInstant(-1)).toBe('not recorded')
  })
})
