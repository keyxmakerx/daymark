import { describe, it, expect, vi } from 'vitest'
import { PortalClient, requestAccessRecovery, confirmAccessRecovery } from './portal'
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
type FetchMock = (url: string, init?: RequestInit) => Promise<Response>

function jsonResponse(body: unknown, ok = true, status = 200): Response {
  return {
    ok,
    status,
    json: async () => body,
  } as Response
}

describe('PortalClient notification settings (Track T2)', () => {
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

describe('access-token recovery client functions (Track T2)', () => {
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
