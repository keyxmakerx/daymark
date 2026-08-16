/*
 * "You are missing this setting" — computed from what an anonymous caller can already get.
 *
 * ─────────────────────────────────────────────────────────────────────────────────────────────
 * THE PROBLEM THIS EXISTS FOR
 * ─────────────────────────────────────────────────────────────────────────────────────────────
 *
 * Two real deployments broke this week and the UI said nothing about either. The sync API was off
 * because its access token was never set, and the server was NOT READY because the directory it
 * writes to would not take a write. Both were visible only in the container log. A person who
 * opened the portal saw a normal-looking page and a feature that silently did not work.
 *
 * ─────────────────────────────────────────────────────────────────────────────────────────────
 * THE CONSTRAINT THAT SHAPES EVERYTHING BELOW
 * ─────────────────────────────────────────────────────────────────────────────────────────────
 *
 * index.html is served to ANYONE who can reach the server, with no credential. admin.html is too:
 * src/admin.ts mounts the operator console with no gate at all, so "put the sensitive detail on the
 * admin page" is not an available answer on this build.
 *
 * So the rule this module is written against, and which its test suite enforces over every string
 * it can emit:
 *
 *     A diagnostic may only restate what the server ALREADY tells an unauthenticated caller.
 *
 * Read out of companion/server, not assumed:
 *
 *   GET /healthz    Application.kt — 200 {"ok":true}, always. Liveness only.
 *   GET /readyz     Application.kt — 200 {"ok":true} / 503 {"ok":false}. Readiness.kt probes the
 *                   data directory with a 4 KiB write + fsync + delete, cached 5 s. THE FAILURE
 *                   BODY CARRIES NO REASON — Readiness.Result.reason is for the log and the
 *                   response is the literal string {"ok":false}. This module therefore never says
 *                   which failure it is, because the server never said.
 *   GET /v1/config  Application.kt — {"smtpEnabled":bool}. One flag, registered unconditionally.
 *
 * Everything else an operator wants is either not published or not publishable here. The gaps are
 * modelled as output (see SYNC_NOT_PROBED) rather than filled in by inference, on the same
 * reasoning lib/admin/health.ts states at length: a screen that shows something adjacent and lets
 * the reader infer has manufactured a measurement.
 *
 * ─────────────────────────────────────────────────────────────────────────────────────────────
 * WHY THERE IS NO SYNC PROBE, THOUGH THE SIGNAL IS PUBLIC
 * ─────────────────────────────────────────────────────────────────────────────────────────────
 *
 * An unconfigured server answers 503 {"error":"sync API not configured"} on every sync path, so
 * "sync is not set up here" is already public and would be safe to restate. The reason there is no
 * probe is not disclosure — it is the side effect on the other branch.
 *
 * When sync IS configured, those same paths go through AuthGuard.authorize(source, null)
 * (SyncRoutes.kt `authorized`). A request with no Authorization header is not a lookup; it is a
 * FAILED ATTEMPT, and AuthGuard.recordFailure counts it against the caller's source identity. The
 * default threshold is 8. Every path that yields the fail-closed 503 is a guarded path once
 * configured — the two sets are the same paths — so the honest-looking probe is never free.
 *
 * The one variant that avoids the guard was considered and rejected: a deliberately deep path such
 * as /v1/snapshots/a/b/c matches the unconfigured tailcard handler but no configured route, so it
 * would fall through to the static-file default and answer with the portal's own HTML. That reads
 * "configured" by inference from an SPA fallback — it depends on DAYMARK_BASE_PATH, on the static
 * handler's default, and on nothing in front of the server having its own 404 page, and it is
 * indistinguishable from a proxy interposing. A wrong answer here sends someone to debug a setting
 * that is fine, which is worse than no answer.
 *
 * A page that anyone can open, that quietly consumes attempts on a shared counter every time it is
 * opened, is a worse thing to ship than an unanswered question. So the question is left unanswered
 * and said to be unanswered.
 *
 * THE FIX IS ONE FIELD, AND IT DISCLOSES NOTHING NEW: add `syncEnabled` to /v1/config's
 * ServerConfigDto. Both branches of that boolean are already obtainable by anyone with curl, and
 * reading it costs no attempt against anything. Until that field exists, this module states the
 * gap. See the handoff note accompanying this file.
 *
 * ─────────────────────────────────────────────────────────────────────────────────────────────
 * NO GREEN, NO TICK, NO "ALL SET"
 * ─────────────────────────────────────────────────────────────────────────────────────────────
 *
 * The last state below is called `answering`, not `ok`. Three HTTP responses cannot establish that
 * a deployment is working, and a first-run screen is exactly where the temptation to say so is
 * strongest. The copy for that state spends most of its words on what it does not cover, and the
 * vocabulary is fixed here rather than in the component so the ban is testable — see
 * serverState.test.ts, which walks every string this module can produce.
 *
 * PURE. No fetch, no DOM, no timers, no Svelte. The caller does the I/O and hands the responses in.
 */

import { ENDPOINTS, describeLastChecked, type ProbeId, type ProbeResponse } from '../admin/health'

/**
 * Re-exported rather than re-implemented.
 *
 * The staleness vocabulary is already written, already pure and already tested next door, and a
 * second copy would drift. Re-exported through this module so the onboarding component has one
 * import and the reuse is visible at the boundary instead of buried in a component's import list.
 * Its `nowMs` argument is the reason it is shaped this way: `$derived` re-evaluates on tracked
 * dependency change only, so a clock read inside a callee is invisible to it.
 */
export { describeLastChecked }

/** The transport-or-response union. Reused from the admin console; the shape is the same question. */
export type { ProbeResponse, ProbeId }

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   1. Which URLs get asked.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

/**
 * The three paths, in the order this panel reads them.
 *
 * All three are registered at the server ROOT in Application.kt, outside DAYMARK_BASE_PATH, so an
 * absolute-from-origin path is correct even when the portal itself is served under a prefix.
 *
 * Kept as its own map rather than read off ENDPOINTS so this panel's list is explicit, and checked
 * against ENDPOINTS in the test suite so the two consoles cannot drift onto different URLs.
 */
export const PROBE_PATH: Record<ProbeId, string> = {
  liveness: '/healthz',
  writability: '/readyz',
  capability: '/v1/config',
}

/** Render order. Liveness first because the other two are only interesting once it answers. */
export const PROBE_ORDER: readonly ProbeId[] = ['liveness', 'writability', 'capability']

/** One response per probe. The caller fetches; this module never does. */
export type ProbeSet = Record<ProbeId, ProbeResponse>

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   2. Where the page itself came from.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

/**
 * Whether there is a server to ask at all.
 *
 * This matters more than it looks. The portal is designed to work as a file on disk — index.html
 * says so in as many words, and the whole backup-reading path never touches a network. A relative
 * fetch from a `file:` page fails instantly, and a panel that reported that as "the server is not
 * answering" would send someone to go and debug a machine that is fine, or that does not exist.
 *
 * Anything that is not http/https is treated as not-served: `file:`, and also the opaque cases
 * (`blob:`, `data:`, a wrapped-app scheme) where a relative request has no meaningful target.
 */
export type OriginKind = 'served' | 'local-file'

export function classifyOrigin(protocol: string): OriginKind {
  const p = protocol.trim().toLowerCase()
  return p === 'http:' || p === 'https:' ? 'served' : 'local-file'
}

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   3. Reading one probe.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

/**
 * What one endpoint did.
 *
 * `refused` is separate from `undocumented` on purpose: /readyz answering 503 {"ok":false} is a
 * documented answer this build is written to give, and it is the single most actionable thing this
 * panel can report. An answer nobody wrote is a different and also interesting thing — it usually
 * means something between the browser and the app answered instead of the app.
 */
export type ProbeVerdict = 'answered' | 'refused' | 'no-answer' | 'undocumented'

/** The chip word per verdict. No verdict here is reassuring; `answered` is a fact, not a grade. */
export const VERDICT_WORD: Record<ProbeVerdict, string> = {
  answered: 'Answered',
  refused: 'Refused',
  'no-answer': 'No answer',
  undocumented: 'Unrecognised',
}

export interface ProbeLine {
  id: ProbeId
  /** What the endpoint is being asked, in a question a person can answer. */
  label: string
  /** `GET /readyz`. Shown because a reader should be able to check this with curl. */
  endpoint: string
  verdict: ProbeVerdict
  /** One authored sentence stating what came back. */
  observed: string
  /** What that answer does NOT cover. Never separated from `observed`; the pair is the point. */
  silentOn: string
  /**
   * THE ONLY CHANNEL CARRYING BYTES THIS MODULE DID NOT AUTHOR — a transport error string, an
   * unrecognised status and body. Everything else on a line is this module's own vocabulary, and
   * that separation is what makes "no output mentions a withheld subject" a checkable claim rather
   * than a hope. Populated only for `no-answer` and `undocumented`.
   */
  detail: string | null
  /** Present only on the capability line, and only when its body parsed. */
  smtpEnabled?: boolean
}

/** How much of an unrecognised body is echoed. A proxy error page is a whole HTML document. */
export const DETAIL_LIMIT = 200

/** Collapse whitespace and cap the length, so an upstream page cannot take over the panel. */
export function summariseBody(body: string, limit: number = DETAIL_LIMIT): string {
  const flat = body.replace(/\s+/g, ' ').trim()
  if (flat === '') return '(empty body)'
  return flat.length <= limit ? flat : `${flat.slice(0, limit)}…`
}

/** `{"ok":true}` / `{"ok":false}` — the exact two bodies /healthz and /readyz send. */
function readOk(body: string): boolean | null {
  try {
    const parsed: unknown = JSON.parse(body)
    if (parsed && typeof parsed === 'object' && typeof (parsed as { ok?: unknown }).ok === 'boolean') {
      return (parsed as { ok: boolean }).ok
    }
  } catch {
    /* falls through to null: an unparseable body is an unrecognised answer, not a false */
  }
  return null
}

/** `{"smtpEnabled":bool}` — the whole of what /v1/config publishes. */
function readSmtp(body: string): boolean | null {
  try {
    const parsed: unknown = JSON.parse(body)
    if (
      parsed &&
      typeof parsed === 'object' &&
      typeof (parsed as { smtpEnabled?: unknown }).smtpEnabled === 'boolean'
    ) {
      return (parsed as { smtpEnabled: boolean }).smtpEnabled
    }
  } catch {
    /* as above */
  }
  return null
}

const LABEL: Record<ProbeId, string> = {
  liveness: 'Is the process up',
  writability: 'Can it still write where it keeps things',
  capability: 'Will it send email',
}

const SILENT_ON: Record<ProbeId, string> = {
  liveness:
    'Only that the process is up and routing. This endpoint answers the same way whether or not ' +
    'the server can still store anything, so on its own it is never evidence that the server is ' +
    'working.',
  writability:
    'One thing, recently. The server caches this answer for a few seconds, so it is a recent ' +
    'reading rather than a live one, and it covers whether a small write went through and nothing ' +
    'else.',
  capability:
    'One flag, and it is a setting rather than an outcome. It never says a message was accepted, ' +
    'sent, or arrived.',
}

const NO_ANSWER_OBSERVED =
  'No answer. The request did not complete, so this says nothing about the server — only that this ' +
  'browser did not get a reply.'

function line(id: ProbeId, verdict: ProbeVerdict, observed: string, detail: string | null): ProbeLine {
  return {
    id,
    label: LABEL[id],
    endpoint: `GET ${PROBE_PATH[id]}`,
    verdict,
    observed,
    silentOn: SILENT_ON[id],
    detail,
  }
}

/**
 * Turn one raw response into one line. Total by construction: every id × every response shape
 * produces a line, because a panel that renders nothing when it does not recognise an answer has
 * hidden the most interesting case it will ever see.
 */
export function readProbeLine(id: ProbeId, res: ProbeResponse): ProbeLine {
  if (res.kind === 'transport') return line(id, 'no-answer', NO_ANSWER_OBSERVED, res.error)

  const unrecognised = () =>
    line(
      id,
      'undocumented',
      `Answered ${res.status}, which is not a shape this build sends here. What came back is quoted below.`,
      `${res.status} · ${summariseBody(res.body)}`,
    )

  if (id === 'capability') {
    if (res.status === 200) {
      const flag = readSmtp(res.body)
      if (flag !== null) {
        return {
          ...line(
            id,
            'answered',
            flag
              ? 'Answered 200. The one flag this endpoint publishes reads as switched on.'
              : 'Answered 200. The one flag this endpoint publishes reads as switched off.',
            null,
          ),
          smtpEnabled: flag,
        }
      }
    }
    return unrecognised()
  }

  const ok = readOk(res.body)

  if (res.status === 200 && ok === true) {
    return line(
      id,
      'answered',
      id === 'liveness'
        ? 'Answered 200 in the shape this build sends. The process is up and routing requests.'
        : 'Answered 200 in the shape this build sends. Its last check of the directory it writes to went through.',
      null,
    )
  }

  if (id === 'writability' && res.status === 503 && ok === false) {
    return line(
      id,
      'refused',
      'Answered 503 in the shape this build sends. Its last check of the directory it writes to ' +
        'did not go through. The body says only that it failed — it carries no reason at all, and ' +
        'that is deliberate: every caller gets the same answer.',
      null,
    )
  }

  return unrecognised()
}

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   4. What the three answers mean together.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

/**
 * Who a given line of advice is for.
 *
 * Modelled rather than left implicit because the confusion this panel sits inside is exactly an
 * audience confusion — the portal opens on six flat tabs with no statement of who any of them are
 * for. A person who does not run this server should be able to see, without reading, which line is
 * not addressed to them.
 */
export type Audience = 'anyone' | 'operator'

export const AUDIENCE_LABEL: Record<Audience, string> = {
  anyone: 'Anyone reading this',
  operator: 'Whoever runs this server',
}

export interface WhereToLook {
  audience: Audience
  text: string
}

export type ServerStateId =
  | 'page-not-served'
  | 'not-checked'
  | 'no-answer'
  | 'partial-answer'
  | 'not-ready'
  | 'undocumented-answer'
  | 'answering'

export interface ServerStateReading {
  id: ServerStateId
  /** The chip word. Fixed here so no call site can invent a warmer one. */
  word: string
  /** Maps to the callout tones. There is no reassuring tone, here or anywhere in this system. */
  tone: 'neutral' | 'warn' | 'critical'
  /** The fact: what was observed, in the endpoints' own terms. */
  observed: string
  /** What it means for what this person can do right now. */
  means: string
  /** Where to look, split by who it is addressed to. */
  lookHere: readonly WhereToLook[]
  /** The evidence rows. Empty when nothing was asked. */
  probes: readonly ProbeLine[]
  /** The mail-capability sentence, when /v1/config answered in its documented shape. */
  mail: string | null
  /** The sync question this page does not ask. Null when no server was involved at all. */
  syncNote: string | null
}

export interface ServerStateInput {
  /** `window.location.protocol`, verbatim. */
  protocol: string
  /** The three responses, or null when nothing has been asked yet. */
  probes: ProbeSet | null
}

/* ---- The fixed copy ------------------------------------------------------------------------ */

export const PANEL_LEDE =
  'This server publishes three endpoints that answer any caller, unauthenticated. This panel asks ' +
  'those three and restates what they said. It is not a check of your setup: it cannot see a ' +
  'single setting, and it will not report one.'

export const PANEL_FOOTER =
  'Every request behind this panel was unauthenticated, and nothing here is a claim about anything ' +
  'the three endpoints did not answer.'

export const BUSY_WORD = 'Reading'

/**
 * The sync gap, stated instead of guessed.
 *
 * Note what it does NOT say: it names no state of this deployment, in either direction. The text
 * is identical on every server, which is the property that makes it safe — a constant cannot leak
 * a variable. Naming the setting is fine for the same reason the 503 is: it is documentation, and
 * it is the same sentence whether or not the setting is present here.
 */
export const SYNC_NOT_PROBED =
  'This panel does not report whether the sync API is set up on this server, because it does not ' +
  'ask. The routes that would answer are ones an unauthenticated page must not send requests to — ' +
  'asking would change something on the server, and a page anyone can open should not be able to ' +
  'do that. So the question is left open here rather than answered by a side effect. The setting ' +
  'that switches sync on is DAYMARK_AUTH_TOKEN; companion/README.md covers it, and the server ' +
  'states which way it is at startup, in its own log.'

const MAIL_OFF =
  'This server answered that it will not send email. Anything it would otherwise send — including ' +
  'an access-recovery link — will not arrive, and has to be delivered some other way.'

const MAIL_ON =
  'This server answered that it can send email. That is the whole of what the answer says: it is a ' +
  'setting, not a delivery, and nothing here reports whether a message was ever accepted or arrived.'

/**
 * The base-path note, which is genuinely the most common cause of the `partial-answer` and
 * `undocumented-answer` shapes.
 *
 * /healthz, /readyz and /v1/config are registered at the server root in Application.kt, outside
 * DAYMARK_BASE_PATH — only the static portal sits under the prefix. A proxy that forwards one
 * sub-path and not the root will therefore serve this page perfectly while dropping all three of
 * these. Naming the setting states a routing fact of the published build; it says nothing about
 * whether the setting is in use here.
 */
const BASE_PATH_NOTE =
  'These three paths are registered at the server root, not under DAYMARK_BASE_PATH, so anything ' +
  'in front of the server that forwards only a sub-path will serve this page and drop these ' +
  'requests. companion/README.md covers what a proxy in front of this has to do.'

const FILE_PATH_RELIEF =
  'Reading a backup file on this device does not use a server at all, so that path is unaffected ' +
  'by anything on this panel.'

interface StateCopy {
  word: string
  tone: 'neutral' | 'warn' | 'critical'
  observed: string
  means: string
  lookHere: readonly WhereToLook[]
}

const STATE_COPY: Record<ServerStateId, StateCopy> = {
  'page-not-served': {
    word: 'No server involved',
    tone: 'neutral',
    observed:
      'This page was opened from a file on this device rather than delivered by a server. There is ' +
      'no address for it to ask about, so no request was made.',
    means:
      'Everything that reads a backup file works exactly as it does anywhere else — that path never ' +
      'touches a network. Anything that needs a server cannot be reached from a page opened this ' +
      'way, and nothing on this screen is a statement about any server.',
    lookHere: [
      {
        audience: 'anyone',
        text:
          'If you meant to use a server, open the address it is published at instead of this file. ' +
          'The same page is served from there, and this panel will have something to report.',
      },
    ],
  },

  'not-checked': {
    word: 'Not checked',
    tone: 'neutral',
    observed: 'No request has been made yet.',
    means: 'Nothing on this screen is a statement about any server until one has.',
    lookHere: [],
  },

  'no-answer': {
    word: 'No answer',
    tone: 'critical',
    observed:
      'Requests went back to the address this page came from, and none of them completed.',
    means:
      'This says the browser got no reply. It does not say the server stopped: from here, a request ' +
      'that never completes looks the same whether the process went away, something in front of it ' +
      'refused, the network dropped it, or this tab was left open across a restart. ' +
      FILE_PATH_RELIEF,
    lookHere: [
      {
        audience: 'anyone',
        text:
          'Reload the page. If it loads, the server answered that request, and only these checks ' +
          'did not — which is a different problem from the server being away.',
      },
      {
        audience: 'operator',
        text:
          'Read the server log, then whatever sits in front of it. This panel cannot tell those ' +
          'apart and does not try. ' + BASE_PATH_NOTE,
      },
    ],
  },

  'partial-answer': {
    word: 'Mixed answers',
    tone: 'warn',
    observed:
      'Some of these requests completed and some did not. The rows below say which.',
    means:
      'A server answering some of its own routes and not others is more often something between ' +
      'this page and the server than the server itself. Nothing here identifies which, and the rows ' +
      'below are the whole of the evidence. ' + FILE_PATH_RELIEF,
    lookHere: [
      {
        audience: 'anyone',
        text: 'Reload once. A single dropped request looks exactly like this.',
      },
      {
        audience: 'operator',
        text:
          'Compare the rows below against what the server logged it served. ' + BASE_PATH_NOTE,
      },
    ],
  },

  'not-ready': {
    word: 'Not ready',
    tone: 'critical',
    observed:
      'The server answered its liveness check, and answered its readiness check with 503 — the ' +
      'answer it gives when its last check of the directory it writes to did not go through. The ' +
      'body carries no reason; every caller gets the same answer.',
    means:
      'The process is running and serving pages, which is why this one loaded. Anything that asks ' +
      'this server to store something will fail while that lasts. ' + FILE_PATH_RELIEF,
    lookHere: [
      {
        audience: 'anyone',
        text:
          'There is nothing to do about this from here. If you do not run this server, the thing ' +
          'that helps is telling whoever does.',
      },
      {
        audience: 'operator',
        text:
          'The reason is written to the server log and to nowhere else, so it takes access to the ' +
          'host to read — start there. companion/README.md sets out what the directory named by ' +
          'DAYMARK_DATA_DIR has to allow.',
      },
    ],
  },

  'undocumented-answer': {
    word: 'Unrecognised',
    tone: 'warn',
    observed:
      'Every request completed, and at least one came back in a shape this build does not send. ' +
      'What arrived is quoted in the rows below.',
    means:
      'Something other than this application may be answering — a page from whatever sits in front ' +
      'of the server, a sign-in portal on the network, or a different version of the server. Until ' +
      'that is settled, no reading on this screen can be relied on, including the ones that look ' +
      'ordinary.',
    lookHere: [
      {
        audience: 'anyone',
        text:
          'If this network shows a sign-in page of its own, that page may be answering instead of ' +
          'the server.',
      },
      {
        audience: 'operator',
        text: 'Compare what is quoted below against what the server logged. ' + BASE_PATH_NOTE,
      },
    ],
  },

  answering: {
    word: 'Answering',
    tone: 'neutral',
    observed:
      'All three requests completed in the shapes this build sends. Liveness answered, readiness ' +
      'answered, and the capability endpoint answered.',
    means:
      'Nothing on this screen contradicts a working setup, and that is the whole of what it means. ' +
      'Three unauthenticated endpoints answered three narrow questions: the process is up, the ' +
      'directory it writes to took a write when it last looked, and one capability flag reads as it ' +
      'reads. They do not say that the rest of this server is set up the way you meant, that it can ' +
      'be reached from anywhere but this browser, or that anything else about it has been looked ' +
      'at — because nothing here looked.',
    lookHere: [
      {
        audience: 'anyone',
        text:
          'There is nothing to act on in these three answers. They are read when you ask for them, ' +
          'not continuously, so the reading has an age — it is stated beside the heading.',
      },
      {
        audience: 'operator',
        text:
          'This is a floor, not a review of your deployment. companion/README.md lists what else ' +
          'has to be true; none of it is observable from a page like this one.',
      },
    ],
  },
}

/**
 * The whole panel, from three responses and one protocol string.
 *
 * ORDER OF THE CHECKS, AND WHY.
 *
 *   1. Not served → nothing was asked. This has to come first, or a `file:` page reports a broken
 *      server for the entirely ordinary reason that there is no server.
 *   2. Nothing asked yet → say so rather than render an empty panel that reads as a clean result.
 *   3. Nothing completed → `no-answer`. Distinguished from (4) because "all of it failed" and
 *      "some of it failed" have genuinely different causes.
 *   4. Some completed → `partial-answer`.
 *   5. Readiness refused → `not-ready`, ABOVE `undocumented-answer`. A 503 carrying exactly
 *      {"ok":false} is a very specific documented shape; receiving it is strong evidence that the
 *      app itself answered, and it is the single most actionable thing this panel can say. An
 *      unrecognised answer on a different probe is still visible in its own row.
 *   6. Anything unrecognised → `undocumented-answer`.
 *   7. Otherwise → `answering`.
 */
export function interpretServerState(input: ServerStateInput): ServerStateReading {
  const origin = classifyOrigin(input.protocol)

  if (origin === 'local-file') {
    return assemble('page-not-served', [], null, null)
  }
  if (input.probes === null) {
    return assemble('not-checked', [], null, null)
  }

  const responses = input.probes
  const probes = PROBE_ORDER.map((id) => readProbeLine(id, responses[id]))
  const capability = probes.find((p) => p.id === 'capability')!
  const readiness = probes.find((p) => p.id === 'writability')!

  const mail =
    capability.verdict === 'answered' ? (capability.smtpEnabled ? MAIL_ON : MAIL_OFF) : null

  const completed = probes.filter((p) => p.verdict !== 'no-answer').length

  const id: ServerStateId =
    completed === 0
      ? 'no-answer'
      : completed < probes.length
        ? 'partial-answer'
        : readiness.verdict === 'refused'
          ? 'not-ready'
          : probes.some((p) => p.verdict === 'undocumented')
            ? 'undocumented-answer'
            : 'answering'

  return assemble(id, probes, mail, SYNC_NOT_PROBED)
}

function assemble(
  id: ServerStateId,
  probes: readonly ProbeLine[],
  mail: string | null,
  syncNote: string | null,
): ServerStateReading {
  const copy = STATE_COPY[id]
  return {
    id,
    word: copy.word,
    tone: copy.tone,
    observed: copy.observed,
    means: copy.means,
    lookHere: copy.lookHere,
    probes,
    mail,
    syncNote,
  }
}

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   5. The corpus, so the disclosure rule is checkable rather than promised.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

/**
 * Every string this module AUTHORED for a given reading — deliberately excluding `detail`, which
 * is the one field carrying bytes the module did not write.
 *
 * Exported because the test suite's central claim is about this set: no string in it names a
 * withheld subject, for any input. Keeping the collector here rather than in the test means the
 * test cannot quietly stop covering a field that was added later — a new field on
 * ServerStateReading has to be added here to be rendered honestly, and is then in the corpus by
 * construction.
 */
export function authoredStrings(reading: ServerStateReading): string[] {
  const out: string[] = [reading.word, reading.observed, reading.means]
  if (reading.mail !== null) out.push(reading.mail)
  if (reading.syncNote !== null) out.push(reading.syncNote)
  for (const l of reading.lookHere) out.push(AUDIENCE_LABEL[l.audience], l.text)
  for (const p of reading.probes) {
    out.push(p.label, p.endpoint, p.observed, p.silentOn, VERDICT_WORD[p.verdict])
  }
  return out
}

/** The module-level constants, which render on every state and belong in the same scan. */
export const STANDING_STRINGS: readonly string[] = [
  PANEL_LEDE,
  PANEL_FOOTER,
  BUSY_WORD,
  SYNC_NOT_PROBED,
  MAIL_ON,
  MAIL_OFF,
  BASE_PATH_NOTE,
  FILE_PATH_RELIEF,
  ...Object.values(AUDIENCE_LABEL),
  ...Object.values(VERDICT_WORD),
  ...Object.values(LABEL),
  ...Object.values(SILENT_ON),
  NO_ANSWER_OBSERVED,
]

/** Parity handle for the test suite: the endpoint catalogue this panel is checked against. */
export const ADMIN_ENDPOINTS = ENDPOINTS
