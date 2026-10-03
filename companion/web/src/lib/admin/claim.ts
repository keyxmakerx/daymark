/*
 * Claiming a new server, and signing in as its administrator (#322).
 *
 * WHAT THIS GUARDS. A server nobody has claimed must not belong to whoever reaches it first. So a
 * new server prints a one-time setup code to its own log, the one place only the person who
 * installed it can read, and this console makes the first administrator only with that code. The
 * code is ~147 bits, works once, and the server prints a fresh one every hour and at each start
 * until it is claimed (server admin/SetupCode.kt).
 *
 * WHAT THE ADMINISTRATOR HOLDS. A name and a TOTP authenticator. The seed is made HERE, in this
 * browser, from the platform's CSPRNG, shown once for the authenticator to take, and sent once with
 * six digits proving the authenticator holds it; the server keeps it, as any TOTP verifier must.
 * Signing in sets an HttpOnly session cookie this page cannot read, plus an anti-CSRF token that
 * this page keeps in memory only and sends on every state-changing request. Nothing here is
 * written to storage, and nothing here logs.
 *
 * WHAT IT NEVER ASKS FOR. The owner's token. An administrator's session opens membership, health
 * and counts, and nothing a relationship owns.
 *
 * SHAPE, matching health.ts and chainHead.ts: the words live here as tested constants, every
 * outcome is a value (no request throws), and the one I/O seam is an injectable fetch. Refusals are
 * mapped from the STATUS to sentences authored here; the server's own wording is never shown, and
 * nothing a person typed is ever echoed back.
 */

type FetchLike = typeof fetch

/* ── The authenticator seed ─────────────────────────────────────────────────────────────── */

/** 160 bits, the size RFC 4226 recommends for HMAC-SHA1. The server takes 16 to 64 bytes. */
export const SEED_BYTES = 20

/** A fresh seed from the platform CSPRNG. Injectable so a test can pin it. */
export function newSeed(random: (n: number) => Uint8Array = defaultRandom): Uint8Array {
  const seed = random(SEED_BYTES)
  if (seed.length !== SEED_BYTES) throw new Error('seed source returned the wrong length')
  return seed
}

function defaultRandom(n: number): Uint8Array {
  return crypto.getRandomValues(new Uint8Array(n))
}

const BASE32_ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567'

/**
 * RFC 4648 base32, unpadded, as authenticator apps take a secret. Restated rather than imported
 * from therapist/inviteAccept.ts because that module pulls libsodium in behind it, and none of
 * that belongs in the server console's bundle for a bit-shifting loop.
 */
export function base32(bytes: Uint8Array): string {
  let bits = 0
  let value = 0
  let out = ''
  for (const byte of bytes) {
    value = ((value << 8) | byte) & 0xffff
    bits += 8
    while (bits >= 5) {
      out += BASE32_ALPHABET[(value >>> (bits - 5)) & 31]
      bits -= 5
    }
  }
  if (bits > 0) out += BASE32_ALPHABET[(value << (5 - bits)) & 31]
  return out
}

/** Unpadded base64url, the form the server's claim route reads the seed in. */
export function base64url(bytes: Uint8Array): string {
  let bin = ''
  for (const b of bytes) bin += String.fromCharCode(b)
  return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

/** The secret in groups of four, for typing into an authenticator by hand. Lossless. */
export function groupSecret(secret: string): string[] {
  const out: string[] = []
  for (let i = 0; i < secret.length; i += 4) out.push(secret.slice(i, i + 4))
  return out
}

/**
 * The `otpauth:` URI an authenticator takes. The label names the server's host and the
 * administrator's role, nothing else: several authenticators back their entries up to a cloud
 * account, and an administrator's name is not this server's to put there. SHA1, six digits and 30
 * seconds are stated because the server implements exactly those (Totp.kt).
 */
export function otpauthUri(secretBase32: string, host: string): string {
  const account = `${host || 'daymark'} (server administrator)`
  const params = new URLSearchParams({
    secret: secretBase32,
    issuer: 'Daymark',
    algorithm: 'SHA1',
    digits: '6',
    period: '30',
  })
  return `otpauth://totp/${encodeURIComponent('Daymark')}:${encodeURIComponent(account)}?${params.toString()}`
}

/* ── What a person types ────────────────────────────────────────────────────────────────── */

/** The same rule the server applies (AdminRoutes.kt validName), so the form can say so first. */
const NAME = /^[\p{L}\p{N} ._-]{1,64}$/u

export function validName(name: string): boolean {
  return NAME.test(name.trim())
}

/** Six digits, spaces allowed, as an authenticator shows them. */
export function validTotp(code: string): boolean {
  return /^\d{6}$/.test(code.replace(/\s/g, ''))
}

/**
 * Whether the setup code as typed could be one: thirty symbols once case, spaces and dashes are
 * gone. Only shape is checked here; whether it is THE code is the server's to say.
 */
export function plausibleSetupCode(typed: string): boolean {
  return /^[A-Z0-9]{30}$/.test(typed.toUpperCase().replace(/[\s-]/g, ''))
}

/* ── The words ──────────────────────────────────────────────────────────────────────────── */

export const CLAIM_INTRO =
  'This server has no administrator yet. The person who installed it can make one: the setup code ' +
  'is in the server’s log, on the line that begins “Setup code for this server”. It works once, ' +
  'and a new one is printed every hour and each time the server starts, until this is done.'

export const SEED_INSTRUCTION =
  'Add this key to an authenticator app, by scanning or pasting the link or typing the groups ' +
  'below. It is shown once. After this, the six digits the app shows are how you sign in.'

export const SIGN_IN_INTRO =
  'Sign in with your administrator name and the six digits from your authenticator.'

export const RESET_HINT =
  'Lost every administrator? Start the server once with DAYMARK_ADMIN_RESET=1 and it prints a ' +
  'new setup code for one more administrator, and records that it did.'

/** Each refusal names what happened and what to do, never a cause the console cannot know. */
export const CLAIM_REFUSAL: Record<number, string> = {
  400: 'The server could not take that name or key. Names are 1 to 64 letters, digits, spaces, dots, dashes or underscores.',
  401: 'That setup code does not work. Copy the newest one from the server’s log; a new one is printed every hour and at each start.',
  409: 'This server already has an administrator, or that name is taken. Sign in instead, or choose another name.',
  422: 'The six digits did not match the authenticator. The setup code still works, so try the next six.',
  429: 'Too many attempts from here. Wait a minute and try again.',
}

export const SIGN_IN_REFUSAL: Record<number, string> = {
  /* One sentence for every refusal the server will not tell apart (AdminRoutes.kt NO_MATCH). */
  401: 'That name and code do not match. After several wrong codes a name is paused for a few minutes, so wait before trying again.',
  429: 'Too many attempts from here. Wait a minute and try again.',
}

export const UNREACHABLE = 'The server did not answer. Check that it is running and reachable from here.'

export const UNEXPECTED = 'The server answered in a way this console does not expect. Nothing was changed.'

/* ── The requests ───────────────────────────────────────────────────────────────────────── */

export interface AdminStatus {
  claimed: boolean
  claimOpen: boolean
}

export interface AdminSession {
  name: string
  /** Sent as X-CSRF-Token on every state-changing request; kept in memory only. */
  csrfToken: string
  absoluteExpiry: number
}

export interface AdminOverview {
  name: string
  setupMode: string
  administrators: number
  /** This session's anti-CSRF token, so a reloaded console can sign out without signing in again. */
  csrfToken: string
}

export type Outcome<T> = { ok: true; value: T } | { ok: false; status: number | null; message: string }

function refusal<T>(status: number | null, words: Record<number, string>): Outcome<T> {
  if (status === null) return { ok: false, status, message: UNREACHABLE }
  return { ok: false, status, message: words[status] ?? UNEXPECTED }
}

async function send(
  doFetch: FetchLike,
  url: string,
  init: RequestInit,
): Promise<{ status: number; body: unknown } | null> {
  try {
    const res = await doFetch(url, { credentials: 'same-origin', cache: 'no-store', ...init })
    let body: unknown = null
    try {
      body = await res.json()
    } catch {
      body = null
    }
    return { status: res.status, body }
  } catch {
    return null
  }
}

const isObj = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null

function readSession(body: unknown): AdminSession | null {
  if (!isObj(body)) return null
  const { name, csrfToken, absoluteExpiry } = body
  if (typeof name !== 'string' || typeof csrfToken !== 'string' || csrfToken === '' || typeof absoluteExpiry !== 'number') {
    return null
  }
  return { name, csrfToken, absoluteExpiry }
}

const JSON_HEADERS = { 'content-type': 'application/json', accept: 'application/json' }

export interface AdminApi {
  status(): Promise<Outcome<AdminStatus>>
  claim(req: { setupCode: string; name: string; seed: Uint8Array; totpCode: string }): Promise<Outcome<AdminSession>>
  signIn(name: string, code: string): Promise<Outcome<AdminSession>>
  overview(): Promise<Outcome<AdminOverview>>
  signOut(csrfToken: string): Promise<Outcome<null>>
}

export function adminApi(baseUrl = '', doFetch: FetchLike = fetch.bind(globalThis)): AdminApi {
  const base = baseUrl.replace(/\/+$/, '')
  return {
    async status() {
      const r = await send(doFetch, `${base}/v1/admin/status`, { headers: { accept: 'application/json' } })
      if (r === null) return refusal(null, {})
      if (r.status === 200 && isObj(r.body) && typeof r.body.claimed === 'boolean' && typeof r.body.claimOpen === 'boolean') {
        return { ok: true, value: { claimed: r.body.claimed, claimOpen: r.body.claimOpen } }
      }
      return refusal(r.status, {})
    },

    async claim({ setupCode, name, seed, totpCode }) {
      const r = await send(doFetch, `${base}/v1/admin/claim`, {
        method: 'POST',
        headers: JSON_HEADERS,
        body: JSON.stringify({
          setupCode,
          name: name.trim(),
          totpSecret: base64url(seed),
          totpCode: totpCode.replace(/\s/g, ''),
        }),
      })
      if (r === null) return refusal(null, CLAIM_REFUSAL)
      const session = r.status === 200 ? readSession(r.body) : null
      return session ? { ok: true, value: session } : refusal(r.status, CLAIM_REFUSAL)
    },

    async signIn(name, code) {
      const r = await send(doFetch, `${base}/v1/admin/session`, {
        method: 'POST',
        headers: JSON_HEADERS,
        body: JSON.stringify({ name: name.trim(), code: code.replace(/\s/g, '') }),
      })
      if (r === null) return refusal(null, SIGN_IN_REFUSAL)
      const session = r.status === 200 ? readSession(r.body) : null
      return session ? { ok: true, value: session } : refusal(r.status, SIGN_IN_REFUSAL)
    },

    async overview() {
      const r = await send(doFetch, `${base}/v1/admin/overview`, { headers: { accept: 'application/json' } })
      if (r === null) return refusal(null, {})
      const b = r.body
      if (
        r.status === 200 && isObj(b) && typeof b.name === 'string' && typeof b.setupMode === 'string' &&
        typeof b.administrators === 'number' && typeof b.csrfToken === 'string' && b.csrfToken !== ''
      ) {
        return { ok: true, value: { name: b.name, setupMode: b.setupMode, administrators: b.administrators, csrfToken: b.csrfToken } }
      }
      return refusal(r.status, { 401: 'Sign in as this server’s administrator.' })
    },

    async signOut(csrfToken) {
      const r = await send(doFetch, `${base}/v1/admin/session/logout`, {
        method: 'POST',
        headers: { 'X-CSRF-Token': csrfToken },
      })
      if (r === null) return refusal(null, {})
      return r.status === 204 ? { ok: true, value: null } : refusal(r.status, {})
    },
  }
}
