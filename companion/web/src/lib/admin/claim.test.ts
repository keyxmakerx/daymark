import { describe, it, expect } from 'vitest'
import {
  CLAIM_REFUSAL,
  SEED_BYTES,
  SIGN_IN_REFUSAL,
  UNEXPECTED,
  UNREACHABLE,
  adminApi,
  base32,
  base64url,
  groupSecret,
  newSeed,
  otpauthUri,
  plausibleSetupCode,
  validName,
  validTotp,
} from './claim'

interface Recorded {
  url: string
  init: RequestInit
}

function fakeFetch(status: number, body: unknown, log: Recorded[] = []): typeof fetch {
  return (async (input: RequestInfo | URL, init?: RequestInit) => {
    log.push({ url: String(input), init: init ?? {} })
    return new Response(body === undefined ? null : JSON.stringify(body), { status })
  }) as unknown as typeof fetch
}

const offline = (async () => {
  throw new TypeError('offline')
}) as unknown as typeof fetch

describe('the seed', () => {
  it('is 160 bits from the source it is given', () => {
    const seed = newSeed((n) => new Uint8Array(n).fill(7))
    expect(seed.length).toBe(SEED_BYTES)
    expect(SEED_BYTES).toBe(20)
  })

  it('refuses a source that returns the wrong length', () => {
    expect(() => newSeed(() => new Uint8Array(4))).toThrow()
  })

  it('draws a different seed each time from the real source', () => {
    expect(base32(newSeed())).not.toBe(base32(newSeed()))
  })
})

describe('encodings', () => {
  it('base32 matches the RFC 4648 vectors, unpadded', () => {
    const enc = (s: string) => base32(new TextEncoder().encode(s))
    expect(enc('')).toBe('')
    expect(enc('f')).toBe('MY')
    expect(enc('fo')).toBe('MZXQ')
    expect(enc('foo')).toBe('MZXW6')
    expect(enc('foob')).toBe('MZXW6YQ')
    expect(enc('fooba')).toBe('MZXW6YTB')
    expect(enc('foobar')).toBe('MZXW6YTBOI')
  })

  it('base32 of a full seed is 32 symbols', () => {
    expect(base32(new Uint8Array(20).fill(0xff))).toBe('7'.repeat(32))
  })

  it('base64url is unpadded and URL-safe, as the server reads it', () => {
    expect(base64url(new Uint8Array([0xfb, 0xff]))).toBe('-_8')
    expect(base64url(new Uint8Array([0xfb, 0xff]))).not.toMatch(/[+/=]/)
  })

  it('groups the secret losslessly', () => {
    const s = base32(new Uint8Array(20).map((_, i) => i * 13))
    expect(groupSecret(s).join('')).toBe(s)
    expect(groupSecret(s).every((g) => g.length === 4)).toBe(true)
  })
})

describe('the authenticator link', () => {
  it('names the host and the role, and states the parameters the server implements', () => {
    const uri = otpauthUri('ABCD', 'companion.example')
    expect(uri.startsWith('otpauth://totp/Daymark:')).toBe(true)
    expect(decodeURIComponent(uri)).toContain('companion.example (server administrator)')
    const params = new URLSearchParams(uri.slice(uri.indexOf('?') + 1))
    expect(params.get('secret')).toBe('ABCD')
    expect(params.get('algorithm')).toBe('SHA1')
    expect(params.get('digits')).toBe('6')
    expect(params.get('period')).toBe('30')
  })
})

describe('what a person types', () => {
  it('names follow the server rule', () => {
    expect(validName('Sam')).toBe(true)
    expect(validName('  Ana-María_2.0 ')).toBe(true)
    expect(validName('')).toBe(false)
    expect(validName('a'.repeat(65))).toBe(false)
    expect(validName('<script>')).toBe(false)
  })

  it('six digits, spaces allowed', () => {
    expect(validTotp('123 456')).toBe(true)
    expect(validTotp('12345')).toBe(false)
    expect(validTotp('12345a')).toBe(false)
  })

  it('a setup code is thirty symbols once dashes, spaces and case are gone', () => {
    expect(plausibleSetupCode('abcde-fghjk-mnpqr-stvwx-yz234-56789')).toBe(true)
    expect(plausibleSetupCode('ABCDE FGHJK MNPQR STVWX YZ234 56789')).toBe(true)
    expect(plausibleSetupCode('ABCDE-FGHJK')).toBe(false)
  })
})

describe('the claim request', () => {
  const seed = new Uint8Array(20).fill(1)

  it('sends the code, trimmed name, base64url seed and digits, and returns the session', async () => {
    const log: Recorded[] = []
    const api = adminApi('https://s.example/', fakeFetch(200, { name: 'Sam', csrfToken: 'c', absoluteExpiry: 9 }, log))
    const out = await api.claim({ setupCode: 'AAAAA-BBBBB', name: ' Sam ', seed, totpCode: '123 456' })
    expect(out).toEqual({ ok: true, value: { name: 'Sam', csrfToken: 'c', absoluteExpiry: 9 } })
    expect(log[0].url).toBe('https://s.example/v1/admin/claim')
    expect(log[0].init.method).toBe('POST')
    expect(log[0].init.credentials).toBe('same-origin')
    expect(JSON.parse(String(log[0].init.body))).toEqual({
      setupCode: 'AAAAA-BBBBB',
      name: 'Sam',
      totpSecret: base64url(seed),
      totpCode: '123456',
    })
  })

  it('never sends an Authorization header: the owner token is not a credential here', async () => {
    const log: Recorded[] = []
    const api = adminApi('', fakeFetch(200, { name: 'Sam', csrfToken: 'c', absoluteExpiry: 9 }, log))
    await api.claim({ setupCode: 'x', name: 'Sam', seed, totpCode: '123456' })
    await api.signIn('Sam', '123456')
    await api.overview()
    await api.signOut('c')
    for (const r of log) {
      const headers = new Headers(r.init.headers)
      expect(headers.has('authorization')).toBe(false)
    }
    // Control: the same lookup sees a header that is there.
    expect(new Headers(log[3].init.headers).has('x-csrf-token')).toBe(true)
  })

  it('maps each refusal from its status to authored words, and never shows the server’s', async () => {
    for (const status of [400, 401, 409, 422, 429]) {
      const api = adminApi('', fakeFetch(status, { error: 'SERVER WORDS' }))
      const out = await api.claim({ setupCode: 'x', name: 'Sam', seed, totpCode: '123456' })
      expect(out).toEqual({ ok: false, status, message: CLAIM_REFUSAL[status] })
      expect(JSON.stringify(out)).not.toContain('SERVER WORDS')
    }
  })

  it('a refusal never echoes the code or the name that was typed', () => {
    for (const words of [...Object.values(CLAIM_REFUSAL), ...Object.values(SIGN_IN_REFUSAL)]) {
      expect(words).not.toContain('AAAAA')
      expect(words).not.toContain('Sam')
    }
  })

  it('a 200 without a usable session is not a success', async () => {
    const api = adminApi('', fakeFetch(200, { name: 'Sam' }))
    const out = await api.claim({ setupCode: 'x', name: 'Sam', seed, totpCode: '123456' })
    expect(out.ok).toBe(false)
  })

  it('a dead transport is a value', async () => {
    const out = await adminApi('', offline).claim({ setupCode: 'x', name: 'Sam', seed, totpCode: '123456' })
    expect(out).toEqual({ ok: false, status: null, message: UNREACHABLE })
  })
})

describe('sign-in, overview and sign-out', () => {
  it('signs in with the trimmed name and digits', async () => {
    const log: Recorded[] = []
    const api = adminApi('', fakeFetch(200, { name: 'Sam', csrfToken: 'c', absoluteExpiry: 9 }, log))
    const out = await api.signIn(' Sam ', '123 456')
    expect(out.ok).toBe(true)
    expect(log[0].url).toBe('/v1/admin/session')
    expect(JSON.parse(String(log[0].init.body))).toEqual({ name: 'Sam', code: '123456' })
  })

  it('maps sign-in refusals, and an unknown status to the neutral sentence', async () => {
    expect(await adminApi('', fakeFetch(401, {})).signIn('Sam', '1')).toEqual({ ok: false, status: 401, message: SIGN_IN_REFUSAL[401] })
    expect(await adminApi('', fakeFetch(429, {})).signIn('Sam', '1')).toEqual({ ok: false, status: 429, message: SIGN_IN_REFUSAL[429] })
    expect(await adminApi('', fakeFetch(500, {})).signIn('Sam', '1')).toEqual({ ok: false, status: 500, message: UNEXPECTED })
  })

  it('reads the overview, and a 401 as signed out', async () => {
    const ok = await adminApi('', fakeFetch(200, { name: 'Sam', setupMode: 'solo', administrators: 1, csrfToken: 'c' })).overview()
    expect(ok).toEqual({ ok: true, value: { name: 'Sam', setupMode: 'solo', administrators: 1, csrfToken: 'c' } })
    // Without the session's token there is nothing to sign out with, so it is not a usable answer.
    expect((await adminApi('', fakeFetch(200, { name: 'Sam', setupMode: 'solo', administrators: 1 })).overview()).ok).toBe(false)
    const out = await adminApi('', fakeFetch(401, {})).overview()
    expect(out.ok === false && out.status).toBe(401)
  })

  it('reads the status', async () => {
    expect(await adminApi('', fakeFetch(200, { claimed: false, claimOpen: true })).status()).toEqual({
      ok: true,
      value: { claimed: false, claimOpen: true },
    })
    expect((await adminApi('', fakeFetch(200, { claimed: 'no' })).status()).ok).toBe(false)
  })

  it('signs out with the anti-CSRF token in its header', async () => {
    const log: Recorded[] = []
    const out = await adminApi('', fakeFetch(204, undefined, log)).signOut('csrf-1')
    expect(out).toEqual({ ok: true, value: null })
    expect(new Headers(log[0].init.headers).get('x-csrf-token')).toBe('csrf-1')
    expect(log[0].init.method).toBe('POST')
  })
})
