import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import {
  CLI_REFUSAL,
  CREATE_REFUSAL,
  SIGN_IN_REFUSAL,
  UNEXPECTED,
  UNREACHABLE,
  isSession,
  ownerAccountApi,
  ownerAuthHeaders,
} from './account'
import { base64url, otpauthUri } from '../admin/claim'

/* The owner's own sign-in (#324): the one place a credential becomes headers, and the requests. */

type Seen = { url: string; init: RequestInit }

function fake(status: number, body: unknown, seen: Seen[] = []) {
  const doFetch = async (input: RequestInfo | URL, init: RequestInit = {}) => {
    seen.push({ url: String(input), init })
    return new Response(body === undefined ? null : JSON.stringify(body), { status })
  }
  return { api: ownerAccountApi('https://companion.example/', doFetch), seen }
}

const headersOf = (s: Seen) => (s.init.headers ?? {}) as Record<string, string>

describe('ownerAuthHeaders: exactly one credential per request', () => {
  it('a token goes as a bearer and nothing else', () => {
    expect(ownerAuthHeaders('tok')).toEqual({ Authorization: 'Bearer tok' })
    expect(isSession('tok')).toBe(false)
  })

  it('a session goes as its anti-CSRF token alone, never as a bearer', () => {
    const h = ownerAuthHeaders({ csrf: 'c1' })
    expect(h).toEqual({ 'X-CSRF-Token': 'c1' })
    expect(Object.keys(h)).not.toContain('Authorization')
    // Positive control: the same key check does see a bearer when there is one.
    expect(Object.keys(ownerAuthHeaders('tok'))).toContain('Authorization')
  })
})

describe('making the account', () => {
  it('sends the token as a bearer, the seed as base64url, and the code without spaces', async () => {
    const seed = new Uint8Array(20).map((_, i) => i + 1)
    const { api, seen } = fake(200, { name: 'Sam', csrfToken: 'c', absoluteExpiry: 1 })
    const out = await api.create('the-token', { name: ' Sam ', seed, totpCode: '123 456' })
    expect(out).toEqual({ ok: true, value: { name: 'Sam', csrf: 'c' } })
    expect(seen[0].url).toBe('https://companion.example/v1/owner/account')
    expect(headersOf(seen[0]).Authorization).toBe('Bearer the-token')
    expect(JSON.parse(String(seen[0].init.body))).toEqual({ name: 'Sam', totpSecret: base64url(seed), totpCode: '123456' })
  })

  it('a 200 without an anti-CSRF token is not a success', async () => {
    const { api } = fake(200, { name: 'Sam', csrfToken: '' })
    const out = await api.create('t', { name: 'Sam', seed: new Uint8Array(20), totpCode: '123456' })
    expect(out).toEqual({ ok: false, status: 200, message: UNEXPECTED })
  })

  it.each([400, 401, 409, 422, 429])('a %i is its fixed sentence', async (status) => {
    const { api } = fake(status, { error: 'server words' })
    const out = await api.create('t', { name: 'Sam', seed: new Uint8Array(20), totpCode: '123456' })
    expect(out).toEqual({ ok: false, status, message: CREATE_REFUSAL[status] })
  })
})

describe('signing in', () => {
  it('every refusal the server will not tell apart reads the same, and never the server text', async () => {
    const { api } = fake(401, { error: 'no match for Sam' })
    const out = await api.signIn('Sam', '123456')
    expect(out).toEqual({ ok: false, status: 401, message: SIGN_IN_REFUSAL[401] })
    if (!out.ok) {
      expect(out.message).not.toContain('Sam')
      // Positive control: the same check sees a name when one is there.
      expect(`no match for Sam`).toContain('Sam')
    }
  })

  it('a server that does not answer is said to be unreachable', async () => {
    const api = ownerAccountApi('', async () => {
      throw new TypeError('network')
    })
    expect(await api.signIn('Sam', '123456')).toEqual({ ok: false, status: null, message: UNREACHABLE })
  })
})

describe('command-line credentials', () => {
  it('minting sends the session as its anti-CSRF token, and returns the credential once', async () => {
    const { api, seen } = fake(201, { credentialId: 'id1', token: 'dmcli_x', createdAt: 5 })
    const out = await api.mintCli({ csrf: 'c' }, ' laptop ')
    expect(out).toEqual({ ok: true, value: { credentialId: 'id1', token: 'dmcli_x', createdAt: 5 } })
    expect(headersOf(seen[0])['X-CSRF-Token']).toBe('c')
    expect(headersOf(seen[0]).Authorization).toBeUndefined()
    expect(JSON.parse(String(seen[0].init.body))).toEqual({ label: 'laptop' })
  })

  it('the list keeps revoked credentials, marked', async () => {
    const { api } = fake(200, {
      credentials: [
        { credentialId: 'a', label: 'laptop', createdAt: 1, revokedAt: null },
        { credentialId: 'b', label: 'old', createdAt: 1, revokedAt: 9 },
      ],
    })
    const out = await api.listCli()
    expect(out.ok && out.value.map((c) => c.revokedAt)).toEqual([null, 9])
  })

  it('a malformed entry fails the whole list rather than showing part of it', async () => {
    const { api } = fake(200, { credentials: [{ credentialId: 'a' }] })
    expect(await api.listCli()).toEqual({ ok: false, status: -1, message: UNEXPECTED })
  })

  it('revoking an already-revoked one is its fixed sentence', async () => {
    const { api, seen } = fake(404, { error: 'x' })
    expect(await api.revokeCli({ csrf: 'c' }, 'a/b')).toEqual({ ok: false, status: 404, message: CLI_REFUSAL[404] })
    // The id is a path segment, so it is escaped rather than able to reach another route.
    expect(seen[0].url).toBe('https://companion.example/v1/owner/cli/a%2Fb/revoke')
  })
})

describe('the panel, read as source', () => {
  const panel = readFileSync(new URL('../components/owner/OwnerAccountPanel.svelte', import.meta.url), 'utf8')
  const consoleSrc = readFileSync(new URL('../components/owner/OwnerConsole.svelte', import.meta.url), 'utf8')

  it('zeroes the seed once the account is made', () => {
    expect(panel).toMatch(/seed\.fill\(0\)\s*\n\s*seed = null/)
    // Positive control: the pattern does not match source that only drops the reference.
    expect('seed = null').not.toMatch(/seed\.fill\(0\)\s*\n\s*seed = null/)
  })

  it('labels the authenticator entry as the owner, not as the administrator', () => {
    expect(panel).toContain("otpauthUri(secret, host, 'owner')")
    expect(otpauthUri('ABCD', 'h')).toContain(encodeURIComponent('h (server administrator)'))
    expect(otpauthUri('ABCD', 'h', 'owner')).toContain(encodeURIComponent('h (owner)'))
  })

  it('is offered only on the server that serves the console, where the session cookie goes', () => {
    const at = consoleSrc.indexOf('<OwnerAccountPanel')
    expect(at).toBeGreaterThan(0)
    const guard = consoleSrc.lastIndexOf('{#if', at)
    expect(consoleSrc.slice(guard, at)).toContain("serverUrl.trim() === ''")
  })
})
