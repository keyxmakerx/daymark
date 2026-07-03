import { describe, it, expect } from 'vitest'
import { PortalClient, isLive, touch, type SessionInfo } from './session'

interface Recorded {
  url: string
  init: RequestInit
}

function fakeFetch(routes: Record<string, () => Response>, log: Recorded[] = []): typeof fetch {
  return (async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    log.push({ url, init: init ?? {} })
    const key = Object.keys(routes).find((k) => url.endsWith(k))
    if (!key) return new Response('not found', { status: 404 })
    return routes[key]()
  }) as unknown as typeof fetch
}

describe('therapist portal session client', () => {
  it('loginTotp posts the code and returns SessionInfo with csrf + expiry', async () => {
    const log: Recorded[] = []
    const client = new PortalClient(
      'https://s.example',
      fakeFetch({ '/v1/totp/verify': () => new Response(JSON.stringify({ csrfToken: 'CSRF', absoluteExpiry: 5000 }), { status: 200 }) }, log),
    )
    const res = await client.loginTotp('cred-1', '123456', 1000)
    expect(res.ok).toBe(true)
    expect(res.session?.csrf).toBe('CSRF')
    expect(res.session?.absoluteExpiresAt).toBe(5000)
    // credentials included so the HttpOnly cookie rides subsequent calls.
    expect(log[0].init.credentials).toBe('include')
    expect(JSON.parse(String(log[0].init.body))).toMatchObject({ credentialId: 'cred-1', code: '123456' })
  })

  it('surfaces lockout (429) as an error', async () => {
    const client = new PortalClient('https://s.example', fakeFetch({ '/v1/totp/verify': () => new Response('locked', { status: 429 }) }))
    const res = await client.loginTotp('c', '000000')
    expect(res.ok).toBe(false)
    expect(res.error).toMatch(/locked/i)
  })

  it('putBlob attaches X-CSRF-Token, X-Rel-Token, and credentials on state-changing writes', async () => {
    const log: Recorded[] = []
    const client = new PortalClient(
      'https://s.example',
      fakeFetch({ '/assignments/lin/0': () => new Response(JSON.stringify({ version: 0, size: 3, contentHash: 'h', createdAt: 1 }), { status: 200 }) }, log),
    )
    const session: SessionInfo = { relRef: 'R', inboxToken: 'TOK', csrf: 'CSRF', credentialKind: 'totp', absoluteExpiresAt: 9e15, idleExpiresAt: 9e15 }
    await client.putBlob(session, 'assignments', 'lin', 0, new Uint8Array([1, 2, 3]))
    const headers = log[0].init.headers as Record<string, string>
    expect(headers['X-CSRF-Token']).toBe('CSRF')
    expect(headers['X-Rel-Token']).toBe('TOK')
    expect(log[0].init.credentials).toBe('include')
  })

  it('logout posts the anti-CSRF header', async () => {
    const log: Recorded[] = []
    const client = new PortalClient('https://s.example', fakeFetch({ '/v1/session/logout': () => new Response(null, { status: 204 }) }, log))
    await client.logout('CSRF')
    const headers = log[0].init.headers as Record<string, string>
    expect(headers['X-CSRF-Token']).toBe('CSRF')
  })

  it('isLive / touch drive the idle + absolute guard', () => {
    const base: SessionInfo = { relRef: 'R', csrf: 'c', credentialKind: 'totp', absoluteExpiresAt: 2000, idleExpiresAt: 1500 }
    expect(isLive(base, 1000)).toBe(true)
    expect(isLive(base, 1600)).toBe(false) // idle passed
    expect(isLive(base, 2100)).toBe(false) // absolute passed
    const t = touch(base, 1000, 1200)
    expect(t.idleExpiresAt).toBe(2200)
  })
})
