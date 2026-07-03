import { describe, it, expect } from 'vitest'
import { PortalClient } from './portal'

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
  })
})
