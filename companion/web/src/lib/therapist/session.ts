/*
 * Therapist-portal SESSION client. Thin wrapper over the server's therapist-auth + relationship
 * blob API. See companion/server .../routes/TherapistAuthRoutes.kt + RelationRoutes.kt.
 *
 * IMPORTANT — real server contract (NOT the spec's assumed `/portal/*`): the server built in the
 * prior slices exposes:
 *   POST /v1/invite/{inviteId}/redeem   → { relRef, scope, enrollTicket }  (capped-backoff, no-referrer)
 *   POST /v1/totp/enroll                → 204   (single-use enrollTicket-gated; relRef derived server-side)
 *   POST /v1/totp/verify                → sets HttpOnly daymark_session cookie + { csrfToken, absoluteExpiry }
 *   POST /v1/session/logout             → 204                         (needs cookie + X-CSRF-Token)
 *   GET/PUT /v1/rel/{relRef}/{channel}/...  (session cookie = THERAPIST role, X-Rel-Token routing)
 *
 * The session cookie is HttpOnly, so the SPA CANNOT read it; state-changing requests instead carry
 * the anti-CSRF token returned in the verify body (X-CSRF-Token) and always use
 * credentials:'include'. There is no server-side per-action step-up in this build — step-up is a
 * client-side ceremony (fresh confirmation + zeroize-on-idle), documented honestly, and the
 * WebAuthn assertion path is out of scope (server returns 501). See LowerAssuranceBanner copy.
 *
 * Pure TS, unit-testable with an injected fetch (mirrors SyncClient.doFetch).
 */

export interface SessionInfo {
  relRef: string
  /** Opaque per-relationship inbox token (OOB, hashes to relRef server-side). In-memory only. */
  inboxToken?: string
  credentialKind: 'totp'
  csrf: string
  /** epoch ms — absolute session expiry from the server; the client also enforces idle locally. */
  absoluteExpiresAt: number
  /** epoch ms — local idle deadline; refreshed on activity, drives the client guard/zeroize. */
  idleExpiresAt: number
}

export interface LoginResult {
  ok: boolean
  session?: SessionInfo
  error?: string
}

export interface RedeemResult {
  ok: boolean
  relRef?: string
  scope?: string[]
  /** Single-use enrollment ticket the server minted; enrollTotp must present it. */
  enrollTicket?: string
  error?: string
}

type FetchLike = typeof fetch

export class PortalError extends Error {
  constructor(
    message: string,
    readonly status?: number,
  ) {
    super(message)
  }
}

/** Default client-side idle window (server also enforces its own idle/absolute limits). */
export const DEFAULT_IDLE_MS = 15 * 60 * 1000

export class PortalClient {
  private readonly base: string

  constructor(
    baseUrl: string,
    private readonly doFetch: FetchLike = fetch,
    private readonly idleMs: number = DEFAULT_IDLE_MS,
  ) {
    this.base = baseUrl.replace(/\/+$/, '')
  }

  private async req(path: string, init: RequestInit = {}): Promise<Response> {
    // credentials:'include' so the HttpOnly session cookie rides state-changing + blob calls.
    return this.doFetch(this.base + path, { credentials: 'include', ...init })
  }

  /**
   * Redeem a single-use invite secret (from the OOB short code / invite link). Best-effort
   * convenience: on success the server returns the relRef + granted scope; the security-bearing
   * OOB pairing (SAS pin) still governs trust. Capped-backoff on wrong secret (410/401/429).
   */
  async redeemInvite(inviteId: string, secret: string): Promise<RedeemResult> {
    const res = await this.req(`/v1/invite/${encodeURIComponent(inviteId)}/redeem`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ secret }),
    })
    if (res.status === 200) {
      const body = (await res.json()) as { relRef: string; scope: string[]; enrollTicket: string }
      return { ok: true, relRef: body.relRef, scope: body.scope, enrollTicket: body.enrollTicket }
    }
    if (res.status === 429) return { ok: false, error: 'Too many attempts — wait and try again.' }
    if (res.status === 410) return { ok: false, error: 'This invite is no longer available.' }
    return { ok: false, error: 'Invite could not be redeemed.' }
  }

  /**
   * Enrol a TOTP credential with a client-set, high-entropy secret (base64url). Gated on the
   * single-use `enrollTicket` returned by redeemInvite; the relRef is derived server-side from the
   * ticket, so it is NOT sent (and cannot be spoofed) here. Insert-only: a 409 means a credential
   * already exists for this relationship.
   */
  async enrollTotp(enrollTicket: string, credentialId: string, secret: string): Promise<boolean> {
    const res = await this.req('/v1/totp/enroll', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ enrollTicket, credentialId, secret }),
    })
    return res.status === 204
  }

  /**
   * Verify a TOTP code. On success the server sets the HttpOnly session cookie and returns the
   * anti-CSRF token + absolute expiry. We compute the local idle deadline from `now`.
   */
  async loginTotp(credentialId: string, code: string, now: number = Date.now()): Promise<LoginResult> {
    const res = await this.req('/v1/totp/verify', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ credentialId, code }),
    })
    if (res.status === 200) {
      const body = (await res.json()) as { csrfToken: string; absoluteExpiry: number }
      // relRef is not echoed by verify; the caller supplies it (from redeem/enroll). We expose a
      // session with a placeholder relRef the portal fills in via `bindRelRef`.
      return {
        ok: true,
        session: {
          relRef: '',
          credentialKind: 'totp',
          csrf: body.csrfToken,
          absoluteExpiresAt: body.absoluteExpiry,
          idleExpiresAt: now + this.idleMs,
        },
      }
    }
    if (res.status === 429) return { ok: false, error: 'Too many attempts — temporarily locked.' }
    return { ok: false, error: 'Code not accepted.' }
  }

  /** Hard-delete the server session (needs the cookie + the anti-CSRF token). */
  async logout(csrf: string): Promise<void> {
    await this.req('/v1/session/logout', { method: 'POST', headers: { 'X-CSRF-Token': csrf } }).catch(() => {})
  }

  // --- opaque relationship-blob channels (THERAPIST role via the session cookie) ---

  private relPath(relRef: string, channel: string, rest = ''): string {
    return `/v1/rel/${encodeURIComponent(relRef)}/${channel}${rest}`
  }

  async listVersions(session: SessionInfo, channel: string, lineage: string): Promise<RelMeta[]> {
    const res = await this.req(this.relPath(session.relRef, channel, `/${encodeURIComponent(lineage)}`), {
      headers: { 'X-Rel-Token': session.inboxToken ?? '' },
    })
    if (res.status === 404) return []
    if (!res.ok) throw new PortalError('list versions failed', res.status)
    return ((await res.json()) as { versions: RelMeta[] }).versions
  }

  async getCurrent(session: SessionInfo, channel: string, lineage: string): Promise<{ version: number; bytes: Uint8Array } | null> {
    const res = await this.req(this.relPath(session.relRef, channel, `/${encodeURIComponent(lineage)}/current`), {
      headers: { 'X-Rel-Token': session.inboxToken ?? '' },
    })
    if (res.status === 404) return null
    if (!res.ok) throw new PortalError('current fetch failed', res.status)
    const version = Number(res.headers.get('X-Version') ?? '0')
    return { version, bytes: new Uint8Array(await res.arrayBuffer()) }
  }

  async putBlob(
    session: SessionInfo,
    channel: string,
    lineage: string,
    version: number,
    bytes: Uint8Array,
    extraHeaders: Record<string, string> = {},
  ): Promise<RelMeta> {
    const res = await this.req(this.relPath(session.relRef, channel, `/${encodeURIComponent(lineage)}/${version}`), {
      method: 'PUT',
      headers: {
        'Content-Type': 'application/octet-stream',
        'X-Rel-Token': session.inboxToken ?? '',
        'X-CSRF-Token': session.csrf,
        ...extraHeaders,
      },
      body: bytes as unknown as BodyInit,
    })
    if (res.status === 409) throw new PortalError('version already exists (append-only)', 409)
    if (res.status === 403) throw new PortalError('wrong direction for this channel', 403)
    if (res.status === 422) throw new PortalError('setting key not allowlisted', 422)
    if (!res.ok) throw new PortalError('blob store failed', res.status)
    return (await res.json()) as RelMeta
  }
}

export interface RelMeta {
  version: number
  size: number
  contentHash: string
  settingKey?: string | null
  createdAt: number
}

/** Idle-guard helpers: whether the session is still live, and the refreshed idle deadline. */
export function isLive(session: SessionInfo, now: number = Date.now()): boolean {
  return now < session.absoluteExpiresAt && now < session.idleExpiresAt
}

export function touch(session: SessionInfo, idleMs: number = DEFAULT_IDLE_MS, now: number = Date.now()): SessionInfo {
  return { ...session, idleExpiresAt: now + idleMs }
}
