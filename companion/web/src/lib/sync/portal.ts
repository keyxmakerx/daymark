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

/** Owner notification-email registration + per-event preferences (COMPANION_SECURITY.md §6). */
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

/** The owner's published public keys, as the server relays them. Vouched for by nobody. */
export interface OwnerKeyRecord {
  signPubB64: string
  boxPubB64: string
  registeredAt: number
}

export class PortalClient {
  private readonly base: string
  constructor(
    baseUrl: string,
    private readonly token: string,
    /*
     * BOUND, and the binding is load-bearing. A bare `fetch` stored as a default and later invoked
     * as `this.doFetch(...)` arrives with `this` set to this class instance, and Chromium refuses
     * that with "Illegal invocation" — so every server call this client makes fails in a real
     * browser while every test passes, because tests inject their own fetch and never evaluate
     * this default. Found by driving the built page in Chromium; a node suite cannot see it.
     */
    private readonly doFetch: FetchLike = fetch.bind(globalThis),
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

  /**
   * End an invitation the owner no longer trusts. This is the ONE thing that kills an invitation
   * (COMPANION_PAIRING.md §7: wrong codes never do; only a human report). The owner path is the
   * bearer token itself — no body, no secret — and the server refuses everything minted under that
   * invitation from then on. Nothing already shared changes, and nobody is told.
   */
  async reportInvite(inviteId: string): Promise<void> {
    const res = await this.req(`/v1/invite/${encodeURIComponent(inviteId)}/report`, { method: 'POST' })
    if (!res.ok) throw new PortalError('could not end the invitation', res.status)
  }

  /**
   * Withdraw the share lineage for one relationship (issue #105).
   *
   * OWNER ONLY, and the server enforces that rather than trusting this client: the same route on
   * the assignments or gameplans channel would hand a clinician an irreversible kill switch over
   * the owner's own material, so it answers 404 for every channel but `shares` and 403 for every
   * role but owner.
   *
   * `copiesNotRemoved` is the half worth carrying back. Revoking marks every version and then tries
   * to delete the bytes; a delete can fail (a read-only mount, a file already gone). Those failures
   * used to be swallowed, so an owner could be told a share was withdrawn while copies of it sat on
   * disk. The count is returned so a screen can say so instead.
   */
  async revokeShare(inboxToken: string, lineage = 'share'): Promise<{ revokedVersions: number; copiesNotRemoved: number }> {
    const relRef = await relRefOf(inboxToken)
    const res = await this.req(this.relPath(relRef, 'shares', `/${encodeURIComponent(lineage)}/revoke`), {
      method: 'POST',
      headers: { 'X-Rel-Token': inboxToken },
    })
    if (!res.ok) throw new PortalError('could not withdraw the share', res.status)
    const body = (await res.json()) as { revokedVersions?: number; copiesNotRemoved?: number }
    return { revokedVersions: body.revokedVersions ?? 0, copiesNotRemoved: body.copiesNotRemoved ?? 0 }
  }

  // --- the owner's own public keys (issue #121) ---

  /**
   * Publish the owner's two public keys for one relationship.
   *
   * `owner_keys` is INSERT-ONLY by primary key: the first key published for a relationship is the
   * key forever, and the server answers 409 for any second attempt. That is deliberate — a clinician
   * pins this key, and a table that could be updated would make a server substituting its own key
   * indistinguishable from the owner rotating theirs.
   *
   * So 409 is a RESULT, not an error, and it is returned rather than thrown: the caller has to read
   * back what is actually published and compare, because "already published" covers both a harmless
   * repeat of the same key and the serious case where the frozen key is one the owner can no longer
   * produce. Those need different things said to a person, and a thrown error erases the
   * difference.
   */
  async publishOwnerKeys(
    relRef: string,
    signPubB64: string,
    boxPubB64: string,
  ): Promise<'published' | 'alreadyPublished'> {
    const res = await this.req(`/v1/relations/${encodeURIComponent(relRef)}/owner-keys`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ signPubB64, boxPubB64 }),
    })
    if (res.status === 409) return 'alreadyPublished'
    if (!res.ok) throw new PortalError('could not publish your keys', res.status)
    return 'published'
  }

  /**
   * What is actually published for this relationship, read with the owner's own token.
   *
   * `null` when nothing has been published, which is an ordinary state rather than an error: it is
   * what every relationship looks like before the owner has sent their key, and drawing it as a
   * failure would be drawing an absence as one.
   */
  async publishedOwnerKeys(relRef: string): Promise<OwnerKeyRecord | null> {
    const res = await this.req(`/v1/relations/${encodeURIComponent(relRef)}/owner-keys`)
    if (res.status === 404) return null
    if (!res.ok) throw new PortalError('could not read the published keys', res.status)
    return (await res.json()) as OwnerKeyRecord
  }

  /**
   * When the clinician ended this relationship, or null while it is live (issue #91).
   *
   * `null` IS THE ORDINARY ANSWER and is not an error: almost every relationship is live, and this
   * is read by a strip that sits on every owner screen, so drawing an absence as a failure would put
   * a permanent alarm in front of people whose relationships are perfectly fine.
   *
   * A FAILURE TO READ IS ALSO NOT AN ENDING. This throws rather than returning null on an
   * unreachable server, for the same reason the sharing strip refuses to render a timeout as "not
   * sharing": an answer that never arrived says nothing about whether somebody has access, and
   * quietly deciding it means "still live" would let the owner go on publishing to a reader who is
   * gone. The caller decides what to say about not knowing.
   */
  async relationshipEnding(relRef: string): Promise<{ endedAt: number } | null> {
    const res = await this.req(`/v1/relations/${encodeURIComponent(relRef)}/ending`)
    if (res.status === 404) return null
    if (!res.ok) throw new PortalError('could not check whether this relationship has ended', res.status)
    return (await res.json()) as { endedAt: number }
  }

  // --- owner notification-email registration (COMPANION_SECURITY.md §6) ---

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

// --- access-token recovery — deliberately UNAUTHENTICATED; no token exists yet ---

/**
 * Request access-token recovery. Always resolves — the server responds identically whether the
 * email matches the registered one or not (non-enumerating), so this never throws on mismatch.
 */
export async function requestAccessRecovery(baseUrl: string, email: string, doFetch: FetchLike = fetch.bind(globalThis)): Promise<void> {
  const base = baseUrl.replace(/\/+$/, '')
  await doFetch(base + '/v1/recovery/request', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email }),
  })
}

/** Confirm a recovery link (the link's `t=` fragment value) and receive the new owner access token once. */
export async function confirmAccessRecovery(baseUrl: string, confirmToken: string, doFetch: FetchLike = fetch.bind(globalThis)): Promise<RecoveryConfirmResult> {
  const base = baseUrl.replace(/\/+$/, '')
  const res = await doFetch(base + '/v1/recovery/confirm', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ confirmToken }),
  })
  if (!res.ok) throw new PortalError('recovery link invalid or expired', res.status)
  return (await res.json()) as RecoveryConfirmResult
}
