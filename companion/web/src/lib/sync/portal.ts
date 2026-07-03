/*
 * PortalClient — owner-side calls to the therapist-portal server surface.
 *
 * The owner authenticates with the same Bearer sync token (the server resolves this to the OWNER
 * role on the /v1/rel channels). Routing is by an opaque per-relationship inbox token presented
 * in X-Rel-Token and hashed by the server to the relRef in the path — NEVER a fingerprint in the
 * URL (caseload-correlation defense). All blob bodies are opaque; the server is zero-knowledge.
 *
 *   grants       owner-PUT   / therapist-GET   (opaque owner-signed grant)
 *   shares       owner-PUT   / therapist-GET   (opaque sealed share)
 *   assignments  therapist-PUT / owner-GET     (opaque sealed assignment)
 *   gameplans    therapist-PUT / owner-GET
 */
import { initCrypto } from './crypto'
import _sodium from 'libsodium-wrappers-sumo'

export type Channel = 'grants' | 'shares' | 'assignments' | 'gameplans'

export interface RelMeta {
  version: number
  size: number
  contentHash: string
  settingKey?: string | null
  createdAt: number
}

export interface ServerConfig {
  smtpEnabled: boolean
}

export interface InviteResponse {
  inviteId: string
  link: string
  expiresAt: number
}

/** One owner-readable, metadata-only audit entry (COMPANION_SECURITY.md §9). */
export interface AuditEvent {
  seq: number
  ts: number
  actor: 'owner' | 'therapist'
  action: string
  objectRef?: string | null
  meta?: Record<string, string> | null
  entryHash: string
}

export interface AuditLogPage {
  events: AuditEvent[]
  nextCursor: number | null
}

/** Track T2 (email Option A): owner notification-email registration + per-event preferences. */
export interface NotificationSettings {
  email: string | null
  events: string[]
}

export interface RecoveryConfirmResult {
  newToken: string
}

type FetchLike = typeof fetch

export class PortalError extends Error {
  constructor(message: string, readonly status?: number) {
    super(message)
  }
}

/** relRef = base64url_nopad(BLAKE2b-256(inboxToken)) — must match server auth/Secrets.relRefOf. */
export async function relRefOf(inboxToken: string): Promise<string> {
  await initCrypto()
  const digest = _sodium.crypto_generichash(32, _sodium.from_string(inboxToken))
  return _sodium.to_base64(digest, _sodium.base64_variants.URLSAFE_NO_PADDING)
}

export class PortalClient {
  private readonly base: string
  constructor(
    baseUrl: string,
    private readonly token: string,
    private readonly doFetch: FetchLike = fetch,
  ) {
    this.base = baseUrl.replace(/\/+$/, '')
  }

  private headers(extra: Record<string, string> = {}): Record<string, string> {
    return { Authorization: `Bearer ${this.token}`, ...extra }
  }

  private async req(path: string, init: RequestInit = {}): Promise<Response> {
    return this.doFetch(this.base + path, {
      ...init,
      headers: { ...this.headers(), ...(init.headers as Record<string, string>) },
    })
  }

  /** Public, unauthenticated config probe — reveals only whether SMTP invites are available. */
  async getConfig(): Promise<ServerConfig> {
    const res = await this.doFetch(this.base + '/v1/config')
    if (!res.ok) return { smtpEnabled: false }
    return (await res.json()) as ServerConfig
  }

  private relPath(relRef: string, channel: Channel, rest = ''): string {
    return `/v1/rel/${encodeURIComponent(relRef)}/${channel}${rest}`
  }

  // --- opaque blob channels (owner role) ---

  async listVersions(inboxToken: string, channel: Channel, lineage: string): Promise<RelMeta[]> {
    const relRef = await relRefOf(inboxToken)
    const res = await this.req(this.relPath(relRef, channel, `/${encodeURIComponent(lineage)}`), {
      headers: { 'X-Rel-Token': inboxToken },
    })
    if (res.status === 404) return []
    if (!res.ok) throw new PortalError('list versions failed', res.status)
    return ((await res.json()) as { versions: RelMeta[] }).versions
  }

  async listLineages(inboxToken: string, channel: Channel): Promise<string[]> {
    const relRef = await relRefOf(inboxToken)
    const res = await this.req(this.relPath(relRef, channel), { headers: { 'X-Rel-Token': inboxToken } })
    if (!res.ok) throw new PortalError('list lineages failed', res.status)
    return ((await res.json()) as { lineages: string[] }).lineages
  }

  async getBlob(inboxToken: string, channel: Channel, lineage: string, version: number): Promise<Uint8Array> {
    const relRef = await relRefOf(inboxToken)
    const res = await this.req(this.relPath(relRef, channel, `/${encodeURIComponent(lineage)}/${version}`), {
      headers: { 'X-Rel-Token': inboxToken },
    })
    if (!res.ok) throw new PortalError('blob fetch failed', res.status)
    return new Uint8Array(await res.arrayBuffer())
  }

  async getCurrent(inboxToken: string, channel: Channel, lineage: string): Promise<{ version: number; bytes: Uint8Array } | null> {
    const relRef = await relRefOf(inboxToken)
    const res = await this.req(this.relPath(relRef, channel, `/${encodeURIComponent(lineage)}/current`), {
      headers: { 'X-Rel-Token': inboxToken },
    })
    if (res.status === 404) return null
    if (!res.ok) throw new PortalError('current fetch failed', res.status)
    const version = Number(res.headers.get('X-Version') ?? '0')
    return { version, bytes: new Uint8Array(await res.arrayBuffer()) }
  }

  async putBlob(
    inboxToken: string,
    channel: Channel,
    lineage: string,
    version: number,
    bytes: Uint8Array,
    extraHeaders: Record<string, string> = {},
  ): Promise<RelMeta> {
    const relRef = await relRefOf(inboxToken)
    const res = await this.req(this.relPath(relRef, channel, `/${encodeURIComponent(lineage)}/${version}`), {
      method: 'PUT',
      headers: { 'Content-Type': 'application/octet-stream', 'X-Rel-Token': inboxToken, ...extraHeaders },
      body: bytes as unknown as BodyInit,
    })
    if (res.status === 409) throw new PortalError('version already exists (append-only)', 409)
    if (res.status === 422) throw new PortalError('setting key not allowlisted', 422)
    if (res.status === 403) throw new PortalError('wrong direction for this channel', 403)
    if (!res.ok) throw new PortalError('blob store failed', res.status)
    return (await res.json()) as RelMeta
  }

  // --- owner-readable audit log (metadata only; never content — COMPANION_SECURITY.md §9) ---

  /** Fetch one page of the access log for [inboxToken]'s relationship, newest-first. */
  async getAuditLog(inboxToken: string, before?: number, limit = 50): Promise<AuditLogPage> {
    const relRef = await relRefOf(inboxToken)
    const params = new URLSearchParams({ limit: String(limit) })
    if (before != null) params.set('before', String(before))
    const res = await this.req(`/v1/rel/${encodeURIComponent(relRef)}/audit?${params}`, {
      headers: { 'X-Rel-Token': inboxToken },
    })
    if (res.status === 404) return { events: [], nextCursor: null }
    if (!res.ok) throw new PortalError('audit log fetch failed', res.status)
    return (await res.json()) as AuditLogPage
  }

  // --- invites (owner mints; link is ALWAYS returned in-band for OOB delivery) ---

  async mintInvite(relId: string, scope: string[], ttlSeconds?: number, email?: string): Promise<InviteResponse> {
    const res = await this.req('/v1/invite', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ relRef: relId, scope, ttlSeconds, email }),
    })
    if (!res.ok) throw new PortalError('invite mint failed', res.status)
    return (await res.json()) as InviteResponse
  }

  // --- owner notification-email registration (Track T2) ---

  async getNotificationSettings(): Promise<NotificationSettings> {
    const res = await this.req('/v1/owner/notifications')
    if (!res.ok) throw new PortalError('failed to load notification settings', res.status)
    return (await res.json()) as NotificationSettings
  }

  async setNotificationSettings(email: string | null, events: string[]): Promise<void> {
    const res = await this.req('/v1/owner/notifications', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email, events }),
    })
    if (!res.ok) throw new PortalError('failed to save notification settings', res.status)
  }
}

// --- access-token recovery (Track T2) — deliberately UNAUTHENTICATED; no token exists yet ---

/**
 * Request access-token recovery. Always resolves — the server responds identically whether the
 * email matches the registered one or not (non-enumerating), so this never throws on mismatch.
 */
export async function requestAccessRecovery(baseUrl: string, email: string, doFetch: FetchLike = fetch): Promise<void> {
  const base = baseUrl.replace(/\/+$/, '')
  await doFetch(base + '/v1/recovery/request', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email }),
  })
}

/** Confirm a recovery link (the link's `t=` fragment value) and receive the new owner access token once. */
export async function confirmAccessRecovery(baseUrl: string, confirmToken: string, doFetch: FetchLike = fetch): Promise<RecoveryConfirmResult> {
  const base = baseUrl.replace(/\/+$/, '')
  const res = await doFetch(base + '/v1/recovery/confirm', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ confirmToken }),
  })
  if (!res.ok) throw new PortalError('recovery link invalid or expired', res.status)
  return (await res.json()) as RecoveryConfirmResult
}
