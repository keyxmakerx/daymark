/*
 * THE PRACTICE CONTROL-PLANE CLIENT — request shaping and, mostly, error handling.
 *
 * A thin wrapper over `/v1/orgs`, which is built and tested in
 * companion/server/.../routes/OrgRoutes.kt. Pure TypeScript with an injected `fetch`, like
 * PortalClient beside it, so every path below is exercised in a node test without a server.
 *
 * ─── WHAT THIS FILE IS ACTUALLY FOR, WHICH IS NOT THE HAPPY PATH ─────────────────────────────────
 *
 * Shaping six requests is twenty lines. The rest of this file is about what the answers MEAN, and
 * the reason it is worth the space is that this surface has three refusals that look alike and are
 * not:
 *
 *   404 IS NOT "THAT PRACTICE DOES NOT EXIST". The server answers a stranger and a nonexistent
 *   practice identically, on purpose: distinguishing them would let anyone holding any session walk
 *   practice ids and separate the real from the imaginary, which is a map of every clinic on the
 *   deployment. So this client refuses to translate 404 into either sentence, and [FAILURE_SENTENCE]
 *   says both possibilities out loud. A console that guessed would teach its user something false
 *   about a server that was being careful.
 *
 *   403 IS TWO REFUSALS. "insufficient role" means this account may never do this; "step-up
 *   required" means this account may, and this request did not carry fresh proof of presence. One
 *   is a wall and the other is a missing six-digit code, and a person told the wrong one either
 *   goes hunting for an administrator they do not need or gives up on a code they do.
 *
 *   A FAILED WRITE IS NOT EVIDENCE THAT NOTHING WAS WRITTEN. `POST /members` commits before it
 *   responds. A proxy answering 502, a gateway timing out, a laptop lid closing between the request
 *   and its answer — none of those is a fact about the server's state, and a client that reported
 *   "could not add member" would be guessing about a row that may well exist. [nothingChanged] is
 *   the honest predicate: it is true ONLY for the statuses this route's own handlers emit from a
 *   branch that writes nothing, and false for everything else, which the console renders as "read
 *   the roster again" rather than as a failure. Same reasoning as PortalClient.enrollTotp, which
 *   learned it the expensive way.
 *
 * ─── WHAT THIS CLIENT CANNOT DO, STRUCTURALLY ────────────────────────────────────────────────────
 *
 * Nothing here fetches, unwraps, decrypts or transmits key material, ciphertext or a grant, because
 * there is no route under `/v1/orgs` that carries any. That is not a discipline this file is
 * exercising; it is the shape of the surface it talks to. The types below are made of identifiers,
 * role names, timestamps and booleans, and there is no version of these responses with a wrapped key
 * in it.
 */
import type { OrgRoleWire } from './orgRoles'

type FetchLike = typeof fetch

/*
 * Bound once at module load. An unbound `fetch` reference throws "Illegal invocation" in some
 * engines when called with the wrong receiver, and this module is imported by a component that
 * hands the client straight to a click handler.
 */
const AMBIENT_FETCH: FetchLike | undefined =
  typeof fetch === 'function' ? fetch.bind(globalThis) : undefined

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   1. What comes back.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

/** `OrgDto` — a practice, as the wire describes it. */
export interface Practice {
  orgId: string
  name: string
  createdAt: number
}

/**
 * `OrgMemberDto` — one person's standing in one practice. Four fields, and the list of fields is
 * the feature: no wrapped key, no grant handle, no patient reference, and no room in the type for
 * one.
 *
 * `accepted` is membership metadata like the other three and is the field a roster must never drop:
 * it is the difference between a colleague and an outstanding offer, and it is why withdrawing an
 * unaccepted seat cuts nothing.
 */
export interface Member {
  memberId: string
  role: string
  addedAt: number
  accepted: boolean
}

/**
 * `OrgMemberRemovedDto` — who was removed, and how many live portal sessions went with it.
 *
 * `sessionsCut` is a count of SESSIONS. It is not a count of grants withdrawn, keys rotated or
 * anything un-read, and it is zero for a seat that was never accepted because nothing was cut. The
 * console is careful to render it as the small fact it is; see REMOVAL_ENDS_A_MEMBERSHIP in copy.ts.
 */
export interface Removal {
  memberId: string
  sessionsCut: number
}

/** One line of the practice's own control-plane history. Metadata, never content. */
export interface OrgAuditEvent {
  seq: number
  ts: number
  actor: string
  action: string
  objectRef?: string | null
  meta?: Record<string, string> | null
  entryHash: string
}

export interface OrgAuditPage {
  events: OrgAuditEvent[]
  /** Feed back as `before` to page further into the past. Absent when the server sent a short page. */
  nextCursor?: number | null
}

/**
 * A wire timestamp, rendered.
 *
 * TWO DECISIONS, both about not inventing anything.
 *
 * UTC AND ISO-SHAPED, not a locale format. This is chrome — a machine fact about when a row was
 * written — and the audit chain it sits beside is ordered by the server's clock. Rendering it in
 * the reader's local zone would quietly make two administrators in two countries disagree about
 * when somebody was removed, which is exactly the question a log is consulted for.
 *
 * A MISSING OR ZERO TIMESTAMP IS NOT 1970. Several server paths fall back to `0L` when a row is read
 * back immediately after a write, and rendering that as a date in 1970 is a fabricated fact — the
 * kind that reads as a bug to anyone trying to decide whether this software works. It says the
 * timestamp is not there, because that is what is true.
 */
export function formatInstant(ts: number | null | undefined): string {
  if (typeof ts !== 'number' || !Number.isFinite(ts) || ts <= 0) return 'not recorded'
  const iso = new Date(ts).toISOString()
  return `${iso.slice(0, 10)} ${iso.slice(11, 16)} UTC`
}

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   2. What can go wrong, as values rather than as thrown strings.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

/**
 * Every way a request to this surface can fail to produce an answer the caller can use.
 *
 * Modelled as a closed union so a screen has to handle all of them — an `Error` with a message
 * would have let every call site invent its own reading of a 403, which is how the two different
 * 403s above end up rendered as one sentence.
 */
export type PracticeFailure =
  /** 400. The body was malformed, or an id or practice name fell outside the server's charset. */
  | { kind: 'invalid'; serverSaid: string }
  /** 401. Expired, revoked, never existed, or the anti-CSRF token did not match — one answer for all four. */
  | { kind: 'unauthorized' }
  /** 403, "step-up required". The role is sufficient; this request carried no fresh authenticator code. */
  | { kind: 'step-up-required' }
  /** 403, "insufficient role". This member's role does not carry this action, in this practice. */
  | { kind: 'role-insufficient' }
  /** 403, something else. Quoted rather than paraphrased, because this build has not seen it. */
  | { kind: 'refused'; serverSaid: string }
  /** 404. No such practice, OR the caller is not a member of it. The server does not distinguish. */
  | { kind: 'not-found' }
  /** 409. Already a member, or the practice would have been left with no admin. */
  | { kind: 'conflict'; serverSaid: string }
  /** 429. The per-source budget for this surface is spent. */
  | { kind: 'rate-limited' }
  /** Any other status. Not evidence that nothing happened — see [nothingChanged]. */
  | { kind: 'server-error'; status: number }
  /** The server answered and the answer could not be read as the shape this build expects. */
  | { kind: 'unreadable-answer'; detail: string }
  /** No answer at all: DNS, TLS, a dropped socket, or a reply that never arrived. */
  | { kind: 'no-answer'; detail: string }

export type PracticeResult<T> = { ok: true; value: T } | { ok: false; failure: PracticeFailure }

/**
 * Is this failure evidence that the server wrote NOTHING?
 *
 * True only for the statuses an `/v1/orgs` handler emits from a branch that has not touched the
 * store: a malformed body, an unauthenticated session, a refusal, a practice the caller cannot see,
 * a conflicting write the store declined, a spent rate-limit budget. Everything else — an unexpected
 * status, an unreadable body, no answer at all — is a state this client cannot know, and it says so
 * rather than guessing in the direction that reads better.
 *
 * The consequence for the console is deliberate: after a failure where this returns false, the only
 * honest next sentence is "re-read the roster", never "the member was not added".
 */
export function nothingChanged(failure: PracticeFailure): boolean {
  switch (failure.kind) {
    case 'invalid':
    case 'unauthorized':
    case 'step-up-required':
    case 'role-insufficient':
    case 'refused':
    case 'not-found':
    case 'conflict':
    case 'rate-limited':
      return true
    case 'server-error':
    case 'unreadable-answer':
    case 'no-answer':
      return false
  }
}

/**
 * One plain sentence per failure, in the register the rest of the product uses: what happened, and
 * where that leaves the reader. No apology, no exclamation, and no advice the client cannot stand
 * behind.
 *
 * The 404 line is the one to read twice. It states both possibilities because the server states
 * neither, and it says why — a reader who is told "this practice does not exist" about a practice
 * that does exist will spend their afternoon on the wrong problem.
 */
export const FAILURE_SENTENCE: Record<PracticeFailure['kind'], string> = {
  invalid:
    'The server refused the request as malformed. Member ids may contain letters, digits, ' +
    'hyphens and underscores only, and a practice name may not be blank.',
  unauthorized:
    'Your session was not accepted. It may have expired, been ended elsewhere, or the request may ' +
    'have been missing its anti-forgery token. The server gives one answer for all of those. Sign ' +
    'in again.',
  'step-up-required':
    'This act needs a current code from your authenticator, and the request did not carry one. ' +
    'Adding a member and changing a role widen who may be offered access, so they ask for proof ' +
    'that you are present now.',
  'role-insufficient':
    'Your role in this practice does not carry this action. The refusal was recorded in the ' +
    'practice’s log.',
  refused: 'The server refused the request.',
  'not-found':
    'No answer for that practice. That means either there is no practice with this id, or you are ' +
    'not a member of it — the server deliberately does not say which, so that nobody can discover ' +
    'which practices exist by asking.',
  conflict: 'The server declined to make this change.',
  'rate-limited':
    'Too many requests from this address for the moment. Nothing was changed. Wait, then try again.',
  'server-error':
    'The server answered with something this page did not expect. Whether the change was made is ' +
    'not known from here — read the roster again.',
  'unreadable-answer':
    'The server answered and the answer could not be read. Whether the change was made is not ' +
    'known from here — read the roster again.',
  'no-answer':
    'No answer came back. That is not the same as nothing happening: a request can be carried out ' +
    'and its answer lost on the way home. Read the roster again.',
}

/**
 * The sentence for a failure, with the server's own words appended where it sent any.
 *
 * Quoted, not paraphrased. "the practice must keep an admin" and "already a member" are the
 * server's two conflict messages and both are more specific than anything this file could say
 * without re-implementing the store's rules — which is exactly how a client's copy ends up
 * disagreeing with the server it describes.
 */
export function failureSentence(failure: PracticeFailure): string {
  const base = FAILURE_SENTENCE[failure.kind]
  if ('serverSaid' in failure && failure.serverSaid.trim() !== '') {
    return `${base} The server said: ${failure.serverSaid.trim()}`
  }
  if (failure.kind === 'server-error') return `${base} (HTTP ${failure.status}.)`
  if (failure.kind === 'no-answer' || failure.kind === 'unreadable-answer') {
    return failure.detail.trim() === '' ? base : `${base} (${failure.detail.trim()})`
  }
  return base
}

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   3. Identifiers, as the server defines them.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

/**
 * `OrgStore.NAME` — the charset every path-ish identifier on this server takes, mirrored so a
 * console can say what is wrong with a typed id before spending a request on it.
 *
 * Mirrored, not invented: the server is the authority and rejects anything else with a 400. The
 * point of having it here is the field help, not the validation — a person typing a colleague's
 * credential id deserves to be told which character the server will object to, in the moment, rather
 * than after a round trip that also spends a single-use authenticator code.
 */
export const ID_CHARSET = /^[A-Za-z0-9_-]{1,64}$/

/** `OrgStore.MAX_ORG_NAME`. A practice name is shown to its members, so it is bounded and printable. */
export const MAX_PRACTICE_NAME = 120

export function isMemberId(s: string): boolean {
  return ID_CHARSET.test(s)
}

export function isPracticeId(s: string): boolean {
  return ID_CHARSET.test(s)
}

/**
 * Kotlin's `Char.isISOControl` covers C0 (U+0000-U+001F) and C1 (U+007F-U+009F); the class below is
 * those same two ranges, so a name this accepts is a name `OrgStore.isOrgName` accepts.
 */
const CONTROL_CHARS = /[\u0000-\u001F\u007F-\u009F]/

export function isPracticeName(s: string): boolean {
  return s.trim() !== '' && s.length <= MAX_PRACTICE_NAME && !CONTROL_CHARS.test(s)
}

/**
 * What is wrong with this id, in words, or null when nothing is.
 *
 * Returns the reason rather than a boolean because the caller is a form field, and "invalid" under
 * an input box is the least useful thing an interface can say.
 */
export function memberIdProblem(s: string): string | null {
  if (s === '') return 'Enter the id the person signs in with.'
  if (s.length > 64) return 'Too long — 64 characters at most.'
  if (!ID_CHARSET.test(s)) return 'Letters, digits, hyphens and underscores only — no spaces.'
  return null
}

/** What is wrong with this practice name, in words, or null when nothing is. */
export function practiceNameProblem(s: string): string | null {
  if (s.trim() === '') return 'Enter a name for the practice.'
  if (s.length > MAX_PRACTICE_NAME) return `Too long — ${MAX_PRACTICE_NAME} characters at most.`
  if (!isPracticeName(s)) return 'Remove any control characters from the name.'
  return null
}

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   4. The client.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

/** The two tokens a state-changing request carries, kept together so neither is forgotten alone. */
export interface WriteAuth {
  /** The anti-CSRF token from sign-in. The session cookie is HttpOnly and rides on its own. */
  csrf: string
  /**
   * A current authenticator code, for the two acts that widen who may be offered access.
   *
   * Optional in the type because two of the four writes do not take one, and spelling that as an
   * optional field rather than as two auth types is what stops a caller attaching a code to a
   * removal — which would spend a single-use step on the one act the annoyance budget insists stays
   * cheap.
   */
  stepUpCode?: string
}

export class PracticeClient {
  private readonly base: string

  constructor(
    baseUrl = '',
    /**
     * `null` means "this page has no way to make requests" and is answered as such rather than
     * thrown. `undefined` falls back to the ambient fetch, so a caller passing an optional prop
     * straight through gets the working default instead of a dead client.
     */
    private readonly doFetch: FetchLike | null | undefined = AMBIENT_FETCH,
  ) {
    // Trailing slashes stripped once, here, so every path below can be written with a leading one
    // and no call site has to think about whether the base ended with one.
    this.base = baseUrl.replace(/\/+$/, '')
  }

  /**
   * Create a practice and seat its first admin.
   *
   * The ONLY call in this file that authenticates with the deployment's bearer token rather than
   * with a practice member's session, because it is the only one that happens before any practice
   * exists to be a member of. In the server's vocabulary that is the platform plane — whoever runs
   * the machine — and in this build that token is also the owner's own credential, a conflation
   * inherited from the single-owner deployment model rather than introduced here. Nothing about
   * holding it confers a clinical read.
   */
  async createPractice(
    name: string,
    adminMemberId: string,
    bearerToken: string,
  ): Promise<PracticeResult<Practice>> {
    return this.request<Practice>('/v1/orgs', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${bearerToken}`,
      },
      body: JSON.stringify({ name, adminMemberId }),
    })
  }

  /**
   * The roster. Any member of the practice may read it.
   *
   * What comes back is membership metadata and nothing else. There is deliberately no companion
   * call listing the practice's PATIENTS, because there is no such route: a patient is not owned by
   * a practice, so that set is not one the server is entitled to assemble.
   */
  async roster(orgId: string): Promise<PracticeResult<Member[]>> {
    const res = await this.request<{ orgId: string; members: Member[] }>(
      `/v1/orgs/${encodeURIComponent(orgId)}/members`,
      { method: 'GET' },
    )
    return res.ok ? { ok: true, value: res.value.members ?? [] } : res
  }

  /**
   * Seat somebody in the practice with a role. Membership authority AND a current authenticator code.
   *
   * Creates a MEMBERSHIP and nothing else. It fetches nothing for the person seated, asks no patient
   * anything on their behalf, and causes the server to hand them nothing it was holding — there is
   * no key in this exchange in either direction. What it changes is who may be OFFERED access by a
   * patient who decides that themselves.
   */
  async addMember(
    orgId: string,
    memberId: string,
    role: OrgRoleWire,
    auth: WriteAuth,
  ): Promise<PracticeResult<Member>> {
    return this.request<Member>(`/v1/orgs/${encodeURIComponent(orgId)}/members`, {
      method: 'POST',
      headers: this.writeHeaders(auth, true),
      body: JSON.stringify({ memberId, role }),
    })
  }

  /**
   * Accept your own seat. Yours, and nobody else's — there is no parameter for whose.
   *
   * The subject is taken from the session on the server side, so an admin cannot accept on somebody's
   * behalf and this method has no id to pass. No authenticator code: accepting confers nothing on
   * the accepter, it exposes them to their own practice's revocation, and the safe direction is
   * never made expensive.
   */
  async acceptOwnSeat(orgId: string, auth: WriteAuth): Promise<PracticeResult<Member>> {
    return this.request<Member>(`/v1/orgs/${encodeURIComponent(orgId)}/members/me/accept`, {
      method: 'POST',
      headers: this.writeHeaders(auth, false),
    })
  }

  /** Change one member's role within this practice. Membership authority AND a current code. */
  async changeRole(
    orgId: string,
    memberId: string,
    role: OrgRoleWire,
    auth: WriteAuth,
  ): Promise<PracticeResult<Member>> {
    return this.request<Member>(
      `/v1/orgs/${encodeURIComponent(orgId)}/members/${encodeURIComponent(memberId)}/role`,
      {
        method: 'POST',
        headers: this.writeHeaders(auth, true),
        body: JSON.stringify({ role }),
      },
    )
  }

  /**
   * Remove somebody from the practice. Deliberately the cheapest write on this surface: a session
   * and its anti-forgery token, and no authenticator code.
   *
   * NO STEP-UP CODE IS SENT even if a caller put one in `auth`, and that is not tidiness. Revocation
   * and narrowing must always be easier than granting and widening — an admin who has just heard
   * that a colleague's laptop is gone should be able to cut the membership from whatever device is
   * to hand. Attaching a code here would also burn a single-use step on the one act that must never
   * be blocked by a missing authenticator.
   */
  async removeMember(
    orgId: string,
    memberId: string,
    auth: WriteAuth,
  ): Promise<PracticeResult<Removal>> {
    return this.request<Removal>(
      `/v1/orgs/${encodeURIComponent(orgId)}/members/${encodeURIComponent(memberId)}`,
      { method: 'DELETE', headers: this.writeHeaders(auth, false) },
    )
  }

  /**
   * The practice's own control-plane history. Admin only.
   *
   * A DIFFERENT chain from any patient's relationship log, keyed on the practice id and stored in a
   * different database — so paging through this can never wander into somebody's access history.
   * Metadata only: who was added, removed, re-roled, refused, by whom, when.
   */
  async audit(
    orgId: string,
    page: { before?: number; limit?: number } = {},
  ): Promise<PracticeResult<OrgAuditPage>> {
    const query = new URLSearchParams()
    // Only sent when asked for. An unconditional `before=` or `limit=` would push this page's
    // defaults onto the server's, and the server's are the ones the route documents.
    if (page.before !== undefined) query.set('before', String(page.before))
    if (page.limit !== undefined) query.set('limit', String(page.limit))
    const suffix = query.toString() === '' ? '' : `?${query.toString()}`
    return this.request<OrgAuditPage>(`/v1/orgs/${encodeURIComponent(orgId)}/audit${suffix}`, {
      method: 'GET',
    })
  }

  /**
   * The headers a state-changing request carries.
   *
   * `X-CSRF-Token` is not optional and is not omitted when blank: the session cookie is HttpOnly and
   * a browser attaches it to a cross-site POST unprompted, so without this header any page a
   * clinician visited could re-role their colleagues. Sending it empty gets a 401, which is the
   * correct outcome for a caller that has no token — the server must never see a missing header
   * validate against a missing stored value.
   */
  private writeHeaders(auth: WriteAuth, wantsStepUp: boolean): Record<string, string> {
    const headers: Record<string, string> = {
      'Content-Type': 'application/json',
      'X-CSRF-Token': auth.csrf,
    }
    // Trimmed and dropped when blank: an empty header would be spent as a wrong code, and the
    // server treats a failed step-up as a refusal of the request rather than a penalty against the
    // credential — but there is no reason to make it look like an attempt was made.
    const code = auth.stepUpCode?.trim() ?? ''
    if (wantsStepUp && code !== '') headers['X-Stepup-Code'] = code
    return headers
  }

  /**
   * One request, one result. Every status this surface produces is mapped here rather than at six
   * call sites, so the two different 403s cannot be collapsed by whichever method was written last.
   */
  private async request<T>(path: string, init: RequestInit): Promise<PracticeResult<T>> {
    if (!this.doFetch) {
      return {
        ok: false,
        failure: { kind: 'no-answer', detail: 'this page has no way to make requests' },
      }
    }

    let res: Response
    try {
      // credentials:'include' so the HttpOnly session cookie rides every call on this surface.
      res = await this.doFetch(this.base + path, { credentials: 'include', ...init })
    } catch (e) {
      // A rejected fetch cannot distinguish "never left this machine" from "was carried out and the
      // answer was lost". Reported as the second, because only that reading is safe for a write.
      return {
        ok: false,
        failure: { kind: 'no-answer', detail: e instanceof Error ? `${e.name}: ${e.message}` : String(e) },
      }
    }

    const text = await res.text().catch(() => '')

    if (!res.ok) return { ok: false, failure: readFailure(res.status, text) }

    // 204 is not in this surface's vocabulary today, and an empty 2xx body would still not be the
    // shape a caller is waiting for. Reported as unreadable rather than as an empty object, which
    // would render as a member with no id.
    if (text.trim() === '') {
      return { ok: false, failure: { kind: 'unreadable-answer', detail: 'the answer was empty' } }
    }

    try {
      return { ok: true, value: JSON.parse(text) as T }
    } catch {
      return {
        ok: false,
        failure: { kind: 'unreadable-answer', detail: 'the answer was not the expected format' },
      }
    }
  }
}

/**
 * A status and a body, read as a failure.
 *
 * Exported because it is the half of this module most worth testing directly, and because the two
 * 403s are decided here on the server's own message rather than on a guess about which act was
 * attempted.
 */
export function readFailure(status: number, body: string): PracticeFailure {
  const said = serverMessage(body)
  switch (status) {
    case 400:
      return { kind: 'invalid', serverSaid: said }
    case 401:
      return { kind: 'unauthorized' }
    case 403:
      // Matched on the server's exact words. An unrecognised 403 is quoted rather than sorted into
      // whichever of the two is more common — being told to fetch your authenticator for a refusal
      // no code can fix is worse than being told the server refused and what it said.
      if (said === 'step-up required') return { kind: 'step-up-required' }
      if (said === 'insufficient role') return { kind: 'role-insufficient' }
      return { kind: 'refused', serverSaid: said }
    case 404:
      return { kind: 'not-found' }
    case 409:
      return { kind: 'conflict', serverSaid: said }
    case 429:
      return { kind: 'rate-limited' }
    default:
      return { kind: 'server-error', status }
  }
}

/**
 * The server's own words out of an `ErrorDto`, or the raw body when it is not one.
 *
 * Capped, because a reverse proxy's HTML error page is a legitimate thing to receive here and
 * pasting a kilobyte of it into a callout helps nobody. Never an invented message: a body this
 * function cannot read yields an empty string, and the caller's own sentence stands alone.
 */
function serverMessage(body: string): string {
  const trimmed = body.trim()
  if (trimmed === '') return ''
  try {
    const parsed = JSON.parse(trimmed) as { error?: unknown }
    if (parsed && typeof parsed.error === 'string') return parsed.error
  } catch {
    /* Not JSON. Fall through and quote what arrived. */
  }
  return trimmed.length > 200 ? `${trimmed.slice(0, 200)}…` : trimmed
}
