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

/**
 * What happened when this therapist's public keys were offered to the relationship.
 *
 * `already-registered` is not an error and is not success either — it is the server refusing to
 * overwrite keys that were already on file. Returned as a value rather than thrown so a caller
 * cannot handle it as a transient failure and retry into a loop, and so the screen showing it has
 * to say which of the two things it means (see registerTherapistKeys).
 */
export type KeyRegistration = 'registered' | 'already-registered'

/**
 * What happened when an enrolment was offered — three answers, because a boolean lied about one.
 *
 * WHAT WAS WRONG WITH THE BOOLEAN. `enrollTotp` used to answer `res.status === 204`, and every
 * caller read `false` as "the server did not enrol you". That is true for the answers the enrol
 * handler itself composes and false for everything else, and the difference is the whole
 * relationship. `POST /v1/totp/enroll` is IRREVERSIBLE on the server: it inserts the credential,
 * consumes the enrolment ticket and drives the invite to CONSUMED, in one transaction, and there is
 * no route anywhere that un-enrols one — enrolment is insert-only per rel_ref (AuthStore.enrollTotp)
 * and a second attempt for the same relationship answers ALREADY_ENROLLED forever. So a client that
 * treats "I did not hear back" as "it did not happen" will roll back the only copy of the
 * clinician's secret keys, discard the authenticator secret it never showed them, and leave a live
 * credential nobody holds the secret for — a relationship that cannot be repaired by a fresh invite,
 * by this product, or by anything short of deleting the row by hand.
 *
 * A reverse proxy answering 502, a gateway timing out at 504, a VPN dropping mid-flight, a mobile
 * tab backgrounded between the request and the response: every one of those produces a non-204 or
 * no response at all while the transaction may already have committed. None of them is evidence
 * about the server's state, and the client's job is to say so rather than to guess.
 *
 *  - `enrolled`  — 204. The credential exists and the secret this browser generated is its secret.
 *  - `refused`   — the enrol handler decided not to insert and said so: 400 (the secret was not a
 *                  plausible key), 401 (no live ticket — spent, expired, or its invite is dead),
 *                  409 (a credential already exists for this relationship). These are the three
 *                  statuses this route's own code produces on a path that writes nothing, so they
 *                  are the only ones safe to read as "nothing happened".
 *  - `unknown`   — anything else. Not an error to retry into; a state the caller must carry.
 */
export type EnrolOutcome = 'enrolled' | 'refused' | 'unknown'

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
   *
   * WHY THE STATUS MAPPING IS A SHORT ALLOWLIST RATHER THAN "not 204 means no". See [EnrolOutcome]
   * for the whole argument; the shape of it is that this call commits something the product cannot
   * undo, so the only statuses read as "nothing was written" are the ones this route's own handler
   * emits from a branch that writes nothing. Everything else — a 5xx from the app or from anything
   * between here and it, a 429 from a proxy, a status this client has never heard of — is reported
   * as `unknown` and left for the caller to carry honestly.
   *
   * A REJECTED FETCH IS ALSO UNKNOWN, and it is the caller's to catch: DNS, TLS, a dropped socket
   * and a request that was answered but whose answer never arrived are indistinguishable to
   * `fetch`, and the last of those is precisely the case that must not be read as a refusal.
   */
  async enrollTotp(enrollTicket: string, credentialId: string, secret: string): Promise<EnrolOutcome> {
    const res = await this.req('/v1/totp/enroll', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ enrollTicket, credentialId, secret }),
    })
    if (res.status === 204) return 'enrolled'
    if (res.status === 400 || res.status === 401 || res.status === 409) return 'refused'
    return 'unknown'
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

  /**
   * Publish this therapist's two PUBLIC keys for the relationship — X25519 (what the owner seals
   * shares to) and Ed25519 (what the owner verifies assignments and game plans against).
   *
   * WHY THE SERVER HOLDS THESE AT ALL, GIVEN IT HOLDS NOTHING ELSE. It is a relay: the owner has to
   * learn which key to seal to, and until this route existed the only way for that to happen was
   * the owner typing base64 a clinician had read to them. Public keys are the one kind of key
   * material a zero-knowledge relay can carry without contradicting itself — they decrypt nothing,
   * and the owner console still refuses to trust one that changes (see pinStore.ts, and the
   * fingerprint the acceptance page asks the therapist to read aloud, which is what catches a
   * server that substituted one).
   *
   * INSERT-ONLY, and the 409 is the interesting answer rather than the error. A relationship that
   * already has keys registered means one of two things: this clinician has been through the
   * ceremony before on another device, or somebody else's keys are on file for their relationship.
   * The client cannot tell those apart, so it returns the outcome as a value and leaves the caller
   * to say so honestly — never to retry, and never to overwrite, because an overwrite is precisely
   * the key substitution the pin exists to catch.
   *
   * THE relRef IN THE PATH COMES FROM THE SESSION. `session.relRef` is bound from the redeem, and
   * the server independently derives the relRef from the session cookie; a path that disagrees with
   * the session is a 403 rather than a hint. So this method has no relRef parameter of its own —
   * there is no caller-supplied value here for a bug or a tampered page to point somewhere else.
   *
   * Cookie plus `X-CSRF-Token`, exactly as `POST /v1/session/logout` is gated: the session cookie is
   * HttpOnly and rides automatically, so on its own it would authorize a cross-site form post.
   */
  async registerTherapistKeys(session: SessionInfo, boxPubB64: string, signPubB64: string): Promise<KeyRegistration> {
    const res = await this.req(`/v1/relations/${encodeURIComponent(session.relRef)}/therapist-keys`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': session.csrf },
      body: JSON.stringify({ boxPubB64, signPubB64 }),
    })
    if (res.status === 204) return 'registered'
    if (res.status === 409) return 'already-registered'
    if (res.status === 401 || res.status === 403) throw new PortalError('not authorized to register keys for this relationship', res.status)
    throw new PortalError('key registration failed', res.status)
  }

  /**
   * The owner's published public keys for this relationship.
   *
   * This is what replaces two of the sign-in form's nine fields. The clinician used to paste the
   * owner's signing and encryption keys out of an email on every visit, because the product had no
   * route to carry them — the mirror of the gap that made them paste their own.
   *
   * THE SERVER DOES NOT VOUCH FOR THESE. It relays them, and a compromised one can hand back keys
   * it controls, which would make every forged share verify and every genuine one fail. What
   * catches that is the clinician comparing the fingerprint against what the owner reads aloud, so
   * the caller must pin on first use and refuse a change — never treat this as trusted input.
   *
   * `null` for a relationship whose owner has not published yet, which is a real state rather than
   * an error: it means the sign-in cannot complete automatically and the person has to be told
   * why, rather than shown a failure that looks like their own mistake.
   */
  async ownerKeys(session: SessionInfo): Promise<{ signPubB64: string; boxPubB64: string; registeredAt: number } | null> {
    const res = await this.req(`/v1/relations/${encodeURIComponent(session.relRef)}/owner-keys`)
    if (res.status === 404) return null
    if (res.status === 401 || res.status === 403) throw new PortalError('not authorized to read owner keys', res.status)
    if (!res.ok) throw new PortalError('could not read owner keys', res.status)
    return (await res.json()) as { signPubB64: string; boxPubB64: string; registeredAt: number }
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
