import { describe, it, expect, vi } from 'vitest'
import { PortalClient, requestAccessRecovery, confirmAccessRecovery } from './portal'

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
  })
})
