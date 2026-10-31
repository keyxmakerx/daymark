/*
 * THE OWNER'S OWN ACCOUNT (#324): the sign-in that replaces the shared access token.
 *
 * On an install that predates accounts, the owner connects once more with the token and makes an
 * account: a name and an authenticator. The server takes the account on the owner's existing id,
 * so everything already stored and every paired phone stays theirs, and from then on the token
 * signs nobody in (server: routes/OwnerAccountRoutes.kt). After that the owner signs in with the
 * name and six digits, and the session is an HttpOnly cookie this page cannot read, with an
 * anti-CSRF token held in memory and sent on every request that changes anything.
 *
 * WHAT A CREDENTIAL IS, to every owner client (sync/client.ts, sync/portal.ts and the rest): either
 * the token, a string, sent as `Authorization: Bearer`, or a signed-in session, sent as nothing
 * but its anti-CSRF token, the cookie riding along because the console is served by the server it
 * talks to. [ownerAuthHeaders] is the one place that turns one into headers.
 *
 * Every refusal is a fixed sentence chosen by status, never the server's text, and none repeats a
 * name, a seed, a code, a token or a CLI credential.
 */
import { base64url } from '../admin/claim'

type FetchLike = (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>

/** A signed-in owner session: its anti-CSRF token, in memory only. The cookie is the browser's. */
export interface AccountSession {
  readonly csrf: string
}

/** What every owner client authenticates with: the shared token while it lasts, or the session. */
export type OwnerCredential = string | AccountSession

export function isSession(c: OwnerCredential): c is AccountSession {
  return typeof c !== 'string'
}

/**
 * The headers an owner request carries for [credential]. The token goes as a bearer; a session as
 * its anti-CSRF token alone, which the server checks only on requests that change something and
 * ignores on reads. Never both, so a request is judged by exactly one credential.
 */
export function ownerAuthHeaders(credential: OwnerCredential): Record<string, string> {
  return isSession(credential) ? { 'X-CSRF-Token': credential.csrf } : { Authorization: `Bearer ${credential}` }
}

/* ── The words ──────────────────────────────────────────────────────────────────────────── */

export const MAKE_ACCOUNT_TITLE = 'Make your own sign-in'

export const MAKE_ACCOUNT_LEDE =
  'This server still signs you in with its shared access token. Make a sign-in of your own: a ' +
  'name, and an authenticator app on your phone. Everything already on the server stays yours, ' +
  'and your paired phones keep working. After this the access token signs nobody in, and the ' +
  'command-line writer needs a credential of its own, which you can make here.'

export const MAKE_ACCOUNT_SEED =
  'Add this key to an authenticator app, by scanning or pasting the link or typing the groups ' +
  'below. It is shown once. After this, your name and the six digits the app shows are how you ' +
  'sign in.'

export const ACCOUNT_MADE =
  'Your sign-in is made. The access token no longer opens anything on this server.'

export const SIGN_IN_LEDE = 'Sign in with your name and the six digits from your authenticator.'

export const CLI_TITLE = 'Command-line writer'

export const CLI_LEDE =
  'A credential for `pnpm push`, which writes a backup to this server from a computer. It can ' +
  'read and write snapshots and nothing else: it cannot pair a phone, add a clinician or change ' +
  'your key. Set it as DAYMARK_CLI_TOKEN. It is shown once.'

export const CLI_REVOKED = 'Revoked. Anything still using it is refused from now on.'

/** Each refusal names what happened and what to do, never a cause the console cannot know. */
export const CREATE_REFUSAL: Record<number, string> = {
  400: 'The server could not take that name or key. Names are 1 to 64 letters, digits, spaces, dots, dashes or underscores.',
  401: 'The access token was not accepted. If your sign-in is already made, sign in with it instead.',
  409: 'This server’s sign-in is already made. Sign in with it instead.',
  422: 'The six digits did not match the authenticator. Nothing was made, so try the next six.',
  429: 'Too many attempts from here. Wait a minute and try again.',
}

export const SIGN_IN_REFUSAL: Record<number, string> = {
  /* One sentence for every refusal the server will not tell apart (OwnerAccountRoutes.kt OWNER_NO_MATCH). */
  401: 'That name and code do not match. After several wrong codes a name is paused for a few minutes, so wait before trying again.',
  429: 'Too many attempts from here. Wait a minute and try again.',
}

export const CLI_REFUSAL: Record<number, string> = {
  400: 'Labels are 1 to 64 letters, digits, spaces, dots, dashes or underscores.',
  401: 'Your session has ended. Sign in again; nothing was changed.',
  403: 'Only your own sign-in can manage these credentials. Nothing was changed.',
  404: 'That credential is already revoked, or does not exist here.',
}

export const UNREACHABLE = 'The server did not answer. Check that it is running and reachable from here.'
export const UNEXPECTED = 'The server answered in a way this console does not expect. Nothing was changed.'

/* ── The requests ───────────────────────────────────────────────────────────────────────── */

export interface AccountStatus {
  exists: boolean
  tokenAccepted: boolean
}

export interface SignedIn {
  name: string
  csrf: string
}

export interface CliCredential {
  credentialId: string
  label: string
  createdAt: number
  revokedAt: number | null
}

export interface MintedCli {
  credentialId: string
  /** Shown once by the console and held nowhere else. */
  token: string
  createdAt: number
}

export type Outcome<T> = { ok: true; value: T } | { ok: false; status: number | null; message: string }

function refusal<T>(status: number | null, words: Record<number, string>): Outcome<T> {
  if (status === null) return { ok: false, status, message: UNREACHABLE }
  return { ok: false, status, message: words[status] ?? UNEXPECTED }
}

const isObj = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null
const JSON_HEADERS = { 'content-type': 'application/json', accept: 'application/json' }

function readSignedIn(body: unknown, nameKey = 'name', csrfKey = 'csrfToken'): SignedIn | null {
  if (!isObj(body)) return null
  const name = body[nameKey]
  const csrf = body[csrfKey]
  if (typeof name !== 'string' || typeof csrf !== 'string' || csrf === '') return null
  return { name, csrf }
}

export interface OwnerAccountApi {
  status(): Promise<Outcome<AccountStatus>>
  /** Make the account with the shared [token], once. On success the token is retired. */
  create(token: string, req: { name: string; seed: Uint8Array; totpCode: string }): Promise<Outcome<SignedIn>>
  signIn(name: string, code: string): Promise<Outcome<SignedIn>>
  /** The live session's name and anti-CSRF token, for a reloaded console; 401 when there is none. */
  session(): Promise<Outcome<SignedIn>>
  signOut(session: AccountSession): Promise<Outcome<null>>
  listCli(): Promise<Outcome<CliCredential[]>>
  mintCli(session: AccountSession, label: string): Promise<Outcome<MintedCli>>
  revokeCli(session: AccountSession, credentialId: string): Promise<Outcome<null>>
}

export function ownerAccountApi(baseUrl = '', doFetch: FetchLike = fetch.bind(globalThis)): OwnerAccountApi {
  const base = baseUrl.replace(/\/+$/, '')

  async function send(path: string, init: RequestInit = {}): Promise<{ status: number; body: unknown } | null> {
    try {
      const res = await doFetch(base + path, { credentials: 'same-origin', cache: 'no-store', ...init })
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

  return {
    async status() {
      const r = await send('/v1/owner/account', { headers: { accept: 'application/json' } })
      if (r === null) return refusal(null, {})
      const b = r.body
      if (r.status === 200 && isObj(b) && typeof b.exists === 'boolean' && typeof b.tokenAccepted === 'boolean') {
        return { ok: true, value: { exists: b.exists, tokenAccepted: b.tokenAccepted } }
      }
      return refusal(r.status, {})
    },

    async create(token, { name, seed, totpCode }) {
      const r = await send('/v1/owner/account', {
        method: 'POST',
        headers: { ...JSON_HEADERS, Authorization: `Bearer ${token}` },
        body: JSON.stringify({ name: name.trim(), totpSecret: base64url(seed), totpCode: totpCode.replace(/\s/g, '') }),
      })
      if (r === null) return refusal(null, CREATE_REFUSAL)
      const made = r.status === 200 ? readSignedIn(r.body) : null
      return made ? { ok: true, value: made } : refusal(r.status, CREATE_REFUSAL)
    },

    async signIn(name, code) {
      const r = await send('/v1/owner/session', {
        method: 'POST',
        headers: JSON_HEADERS,
        body: JSON.stringify({ name: name.trim(), code: code.replace(/\s/g, '') }),
      })
      if (r === null) return refusal(null, SIGN_IN_REFUSAL)
      const signedIn = r.status === 200 ? readSignedIn(r.body) : null
      return signedIn ? { ok: true, value: signedIn } : refusal(r.status, SIGN_IN_REFUSAL)
    },

    async session() {
      const r = await send('/v1/owner/session', { headers: { accept: 'application/json' } })
      if (r === null) return refusal(null, {})
      const signedIn = r.status === 200 ? readSignedIn(r.body) : null
      return signedIn ? { ok: true, value: signedIn } : refusal(r.status, { 401: SIGN_IN_LEDE })
    },

    async signOut(session) {
      const r = await send('/v1/owner/session/logout', { method: 'POST', headers: ownerAuthHeaders(session) })
      if (r === null) return refusal(null, {})
      return r.status === 204 ? { ok: true, value: null } : refusal(r.status, {})
    },

    async listCli() {
      const r = await send('/v1/owner/cli', { headers: { accept: 'application/json' } })
      if (r === null) return refusal(null, CLI_REFUSAL)
      const list = isObj(r.body) && Array.isArray(r.body.credentials) ? r.body.credentials : null
      if (r.status !== 200 || list === null) return refusal(r.status, CLI_REFUSAL)
      const out: CliCredential[] = []
      for (const c of list) {
        if (!isObj(c) || typeof c.credentialId !== 'string' || typeof c.label !== 'string' || typeof c.createdAt !== 'number') {
          return refusal(r.status === 200 ? -1 : r.status, CLI_REFUSAL)
        }
        out.push({ credentialId: c.credentialId, label: c.label, createdAt: c.createdAt, revokedAt: typeof c.revokedAt === 'number' ? c.revokedAt : null })
      }
      return { ok: true, value: out }
    },

    async mintCli(session, label) {
      const r = await send('/v1/owner/cli', {
        method: 'POST',
        headers: { ...JSON_HEADERS, ...ownerAuthHeaders(session) },
        body: JSON.stringify({ label: label.trim() }),
      })
      if (r === null) return refusal(null, CLI_REFUSAL)
      const b = r.body
      if (r.status === 201 && isObj(b) && typeof b.credentialId === 'string' && typeof b.token === 'string' && typeof b.createdAt === 'number') {
        return { ok: true, value: { credentialId: b.credentialId, token: b.token, createdAt: b.createdAt } }
      }
      return refusal(r.status, CLI_REFUSAL)
    },

    async revokeCli(session, credentialId) {
      const r = await send(`/v1/owner/cli/${encodeURIComponent(credentialId)}/revoke`, { method: 'POST', headers: ownerAuthHeaders(session) })
      if (r === null) return refusal(null, CLI_REFUSAL)
      return r.status === 204 ? { ok: true, value: null } : refusal(r.status, CLI_REFUSAL)
    },
  }
}
