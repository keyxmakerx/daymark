import { describe, it, expect, vi } from 'vitest'
import { PortalClient, relRefOf, requestAccessRecovery, confirmAccessRecovery } from './portal'

interface Recorded {
  url: string
  init: RequestInit
}

function fakeFetch(routes: Record<string, () => Response>, log: Recorded[] = []): typeof fetch {
  return (async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    log.push({ url, init: init ?? {} })
    const key = Object.keys(routes).find((k) => url.includes(k))
    if (!key) return new Response('not found', { status: 404 })
    return routes[key]()
  }) as unknown as typeof fetch
}

const inboxToken = 'inbox-token-256-bit-example-xyz'

type FetchMock = (url: string, init?: RequestInit) => Promise<Response>

function jsonResponse(body: unknown, ok = true, status = 200): Response {
  return {
    ok,
    status,
    json: async () => body,
  } as Response
}

describe('PortalClient.getAuditLog', () => {
  it('sends the owner bearer token + X-Rel-Token and returns the page as-is', async () => {
    const log: Recorded[] = []
    const page = {
      events: [
        { seq: 2, ts: 1000, actor: 'therapist', action: 'share.open', objectRef: 'lin:0', entryHash: 'h2' },
        { seq: 1, ts: 900, actor: 'therapist', action: 'auth.success', entryHash: 'h1' },
      ],
      nextCursor: null,
    }
    const client = new PortalClient(
      'https://s.example',
      'owner-token',
      fakeFetch({ '/audit': () => new Response(JSON.stringify(page), { status: 200 }) }, log),
    )
    const result = await client.getAuditLog(inboxToken)
    expect(result).toEqual(page)
    expect(log[0].url).toMatch(/\/v1\/rel\/[^/]+\/audit\?limit=50/)
    const headers = log[0].init.headers as Record<string, string>
    expect(headers['X-Rel-Token']).toBe(inboxToken)
    expect(headers['Authorization']).toBe('Bearer owner-token')
  })

  it('forwards the before cursor and a custom limit as query params', async () => {
    const log: Recorded[] = []
    const client = new PortalClient(
      'https://s.example',
      'owner-token',
      fakeFetch({ '/audit': () => new Response(JSON.stringify({ events: [], nextCursor: null }), { status: 200 }) }, log),
    )
    await client.getAuditLog(inboxToken, 42, 10)
    expect(log[0].url).toContain('before=42')
    expect(log[0].url).toContain('limit=10')
  })

  it('treats 404 as an empty page rather than throwing', async () => {
    const client = new PortalClient('https://s.example', 'owner-token', fakeFetch({ '/audit': () => new Response('nope', { status: 404 }) }))
    const result = await client.getAuditLog(inboxToken)
    expect(result).toEqual({ events: [], nextCursor: null })
  })

  it('throws PortalError on other non-ok statuses', async () => {
    const client = new PortalClient('https://s.example', 'owner-token', fakeFetch({ '/audit': () => new Response('nope', { status: 401 }) }))
    await expect(client.getAuditLog(inboxToken)).rejects.toThrow('audit log fetch failed')
  })
})

describe('PortalClient notification settings', () => {
  it('gets notification settings with the owner bearer token', async () => {
    const fetchMock = vi.fn<FetchMock>(async () => jsonResponse({ email: 'owner@example.org', events: ['NEW_ASSIGNMENT'] }))
    const client = new PortalClient('https://host', 'owner-token', fetchMock as unknown as typeof fetch)
    const settings = await client.getNotificationSettings()
    expect(settings.email).toBe('owner@example.org')
    expect(settings.events).toEqual(['NEW_ASSIGNMENT'])
    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('https://host/v1/owner/notifications')
    expect((init?.headers as Record<string, string>).Authorization).toBe('Bearer owner-token')
  })

  it('sets notification settings via PUT with a JSON body', async () => {
    const fetchMock = vi.fn<FetchMock>(async () => jsonResponse({}, true, 204))
    const client = new PortalClient('https://host', 'owner-token', fetchMock as unknown as typeof fetch)
    await client.setNotificationSettings('owner@example.org', ['NEW_ASSIGNMENT', 'THERAPIST_ENROLLED'])
    const [, init] = fetchMock.mock.calls[0]
    expect(init?.method).toBe('PUT')
    expect(JSON.parse(init?.body as string)).toEqual({ email: 'owner@example.org', events: ['NEW_ASSIGNMENT', 'THERAPIST_ENROLLED'] })
  })

  it('throws PortalError when the settings fetch fails', async () => {
    const fetchMock = vi.fn<FetchMock>(async () => jsonResponse({}, false, 401))
    const client = new PortalClient('https://host', 'bad-token', fetchMock as unknown as typeof fetch)
    await expect(client.getNotificationSettings()).rejects.toMatchObject({ status: 401 })
  })
})

describe('access-token recovery client functions', () => {
  it('requestAccessRecovery posts the email and never throws, even on a non-ok response', async () => {
    const fetchMock = vi.fn<FetchMock>(async () => jsonResponse({}, false, 429))
    await expect(requestAccessRecovery('https://host', 'owner@example.org', fetchMock as unknown as typeof fetch)).resolves.toBeUndefined()
    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('https://host/v1/recovery/request')
    expect(JSON.parse(init?.body as string)).toEqual({ email: 'owner@example.org' })
  })

  it('confirmAccessRecovery returns the new token on success', async () => {
    const fetchMock = vi.fn<FetchMock>(async () => jsonResponse({ newToken: 'fresh-token-xyz' }))
    const result = await confirmAccessRecovery('https://host', 'confirm-tok', fetchMock as unknown as typeof fetch)
    expect(result.newToken).toBe('fresh-token-xyz')
  })

  it('confirmAccessRecovery throws PortalError when the link is gone', async () => {
    const fetchMock = vi.fn<FetchMock>(async () => jsonResponse({}, false, 410))
    await expect(confirmAccessRecovery('https://host', 'expired-tok', fetchMock as unknown as typeof fetch)).rejects.toMatchObject({ status: 410 })
  })
})

describe('PortalClient.auditChainHead', () => {
  it('reads the chain head with the owner token, at the relationship the inbox token names', async () => {
    const log: Recorded[] = []
    const body = JSON.stringify({ entryCount: 3, oldestSeq: 1, headSeq: 3, headHash: 'ab'.repeat(32), firstBreakSeq: null })
    const client = new PortalClient('https://s.example', 'owner-token', fakeFetch({ '/audit-chain': () => new Response(body, { status: 200 }) }, log))
    const res = await client.auditChainHead(inboxToken)
    expect(res).toEqual({ kind: 'response', status: 200, body })
    expect(log[0].url).toBe(`https://s.example/v1/relations/${encodeURIComponent(await relRefOf(inboxToken))}/audit-chain`)
    const headers = log[0].init.headers as Record<string, string>
    expect(headers['Authorization']).toBe('Bearer owner-token')
    // The route is keyed by the relRef alone; the inbox token itself stays on this device. The
    // getAuditLog test above is the control: the same lookup finds the header when it is sent.
    expect(headers['X-Rel-Token']).toBeUndefined()
    expect(log[0].url).not.toContain(inboxToken)
  })

  it('returns a transport failure as a value instead of throwing', async () => {
    const client = new PortalClient('https://s.example', 'owner-token', (async () => {
      throw new TypeError('offline')
    }) as unknown as typeof fetch)
    expect(await client.auditChainHead(inboxToken)).toEqual({ kind: 'transport', error: 'TypeError: offline' })
  })
})

describe('relRefOf', () => {
  it('is BLAKE2b-256 of the token text, base64url: the bytes the phone computes too (#174)', async () => {
    // Pinned with the same value in sync-crypto's ClinicianCeremonyTest.
    expect(await relRefOf('inbox-token-example')).toBe('PmoLo2aBLjo0CAzUlM2MhtNp2fCKQvpN17Qgod-1w2s')
  })
})

describe('PortalClient: a refusal for pace (#382)', () => {
  const paced = () => new Response('{"error":"rate limited"}', { status: 429 })
  const locked = () => new Response('{"error":"temporarily locked"}', { status: 429 })

  function scripted(answers: Array<() => Response>, log: Recorded[]): typeof fetch {
    return (async (input: RequestInfo | URL, init?: RequestInit) => {
      log.push({ url: String(input), init: init ?? {} })
      return (answers.shift() ?? (() => new Response('none left', { status: 500 })))()
    }) as unknown as typeof fetch
  }

  it('waits and sends once more, so a quick click is not told it failed', async () => {
    const log: Recorded[] = []
    const waits: number[] = []
    const client = new PortalClient('https://s.example', 'owner-token', scripted([paced, () => new Response(null, { status: 404 })], log), async (ms) => {
      waits.push(ms)
    })
    expect(await client.getCurrent(inboxToken, 'assignments', 'as-1')).toBeNull()
    expect(log).toHaveLength(2)
    expect(waits).toEqual([1_100])
    // The retry is the same request: the same address and the same owner credential.
    expect(log[1].url).toBe(log[0].url)
    expect((log[1].init.headers as Record<string, string>).Authorization).toBe('Bearer owner-token')
  })

  it('sends once more at most, and a second refusal reaches the caller as its status', async () => {
    const log: Recorded[] = []
    const client = new PortalClient('https://s.example', 'owner-token', scripted([paced, paced, paced], log), async () => {})
    await expect(client.getCurrent(inboxToken, 'assignments', 'as-1')).rejects.toMatchObject({ status: 429 })
    expect(log).toHaveLength(2)
  })

  it('does not retry a lockout, which lasts minutes', async () => {
    const log: Recorded[] = []
    const client = new PortalClient('https://s.example', 'owner-token', scripted([locked, paced], log), async () => {})
    await expect(client.getCurrent(inboxToken, 'assignments', 'as-1')).rejects.toMatchObject({ status: 429 })
    // The control for the test above: the same client does send twice when the first answer is for pace.
    expect(log).toHaveLength(1)
  })
})
