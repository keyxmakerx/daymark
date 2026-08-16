/*
 * WHO EACH SURFACE IS FOR — the model behind the first screen.
 *
 * ─────────────────────────────────────────────────────────────────────────────────────────────
 * THE PROBLEM THIS EXISTS TO FIX
 * ─────────────────────────────────────────────────────────────────────────────────────────────
 *
 * The viewer opened on six flat tabs — "Open a backup file", "Connect to sync", "Self-checks",
 * "Build a tool", "Owner console", "Recover access" — with no hierarchy and no statement of who
 * any of them were for. Three completely different people can reach this bundle:
 *
 *   index.html      the OWNER, looking at their own data
 *   therapist.html  a CLINICIAN, invited by an owner, holding a pinned key
 *   admin.html      whoever OPERATES the server
 *
 * Nothing on the first screen said that a clinician belongs on a different page, so a therapist
 * landing on the owner's viewer had no way to find out except by failing. This module is the
 * model of those three audiences, of the owner's six entry points and their ranking, and of the
 * two things the first screen is permitted to say about the server.
 *
 * ─────────────────────────────────────────────────────────────────────────────────────────────
 * THE DISCLOSURE RULE, AND WHY THIS FILE IS SO QUIET ABOUT CONFIGURATION
 * ─────────────────────────────────────────────────────────────────────────────────────────────
 *
 * index.html is served to ANYONE who can reach the server, with no authentication. admin.html is
 * also unauthenticated on this build — src/admin.ts mounts AdminConsole with no credential, and
 * Config.kt defines no administrator identity for it to present. So "put the sensitive detail on
 * the admin page" is not an available answer, and a setup screen that listed what this server has
 * and has not been configured with would be reading an attacker their checklist of which
 * protections are off.
 *
 * The rule this file obeys: A DIAGNOSTIC MAY ONLY RESTATE WHAT THE SERVER ALREADY TELLS AN
 * UNAUTHENTICATED CALLER. Two endpoints qualify and both are already modelled in lib/admin/health.ts,
 * which is reused here rather than copied:
 *
 *   GET /healthz   liveness. 200 {"ok":true}, always.
 *   GET /readyz    readiness. 200 / 503, body content-free by design.
 *
 * Anyone can curl those. Restating them discloses nothing new. Everything else was declined, and
 * the reasons are recorded next to [ORIENTATION_PROBES] rather than left as an unexplained
 * absence — including the one that is genuinely public and still not shown.
 *
 * ─────────────────────────────────────────────────────────────────────────────────────────────
 * NO REASSURANCE, ANYWHERE
 * ─────────────────────────────────────────────────────────────────────────────────────────────
 *
 * A setup screen is exactly where someone reaches for a green tick and "All good". There is no
 * such state here. A server that answers both probes gets [readServerReach]'s neutral statement
 * of what was observed, paired with a sentence saying what that observation does not establish —
 * because two endpoints answering says nothing about whether sync is configured, whether a token
 * still works, or whether anything sent would be stored. The vocabulary lives in this module so
 * the ban is testable (audience.test.ts), the same reasoning lib/admin/health.ts gives for
 * keeping its own words out of its component.
 *
 * PURE. No fetch, no DOM, no timers, no Svelte. The component does the I/O and hands raw readings
 * in; every function here is (input) → view model.
 */
import { ENDPOINTS, type Endpoint, type ProbeId, type ProbeReading } from '../admin/health'

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   1. The three audiences.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

import type { OriginKind } from './serverState'
export type AudienceId = 'owner' | 'clinician' | 'operator'

export interface Audience {
  id: AudienceId
  /**
   * First person, as the reader would say it to themselves. This is the line the eye lands on,
   * so it is a description of the READER rather than a name for a feature — "I am a clinician
   * someone invited" is answerable in a second; "Therapist portal" requires already knowing.
   */
  question: string
  /** Who that person is, in the product's own words. */
  who: string
  /**
   * Where they belong. Relative and sibling-scoped, because all three pages ship from one bundle
   * and may be served under a base path; `./therapist.html` resolves correctly wherever
   * index.html is served from. Null means "the page you are on".
   */
  href: string | null
  /** What the link calls the destination. Null for the owner, who has nowhere to be sent. */
  linkLabel: string | null
  /**
   * The one thing this audience needs to know BEFORE clicking. For a clinician that is the
   * invitation requirement — an uninvited one cannot use the portal and should learn it here
   * rather than after signing in. For an operator it is that the console currently holds no
   * credential of its own.
   */
  entryCondition: string
}

/**
 * The owner is first and is the default, because this page is theirs. A clinician is the visitor
 * here, not the subject, and an operator is a third party to both.
 */
export const AUDIENCES: readonly Audience[] = [
  {
    id: 'owner',
    question: 'I am looking at my own Daymark data',
    who:
      'You exported a backup from the Daymark app, or you run a sync server of your own. This ' +
      'page is yours, and everything below is on it.',
    href: null,
    linkLabel: null,
    entryCondition:
      'Nothing here asks who you are until you pick something that talks to your server. Opening ' +
      'a backup file and running a self-check ask for nothing at all.',
  },
  {
    id: 'clinician',
    question: 'I am a clinician someone invited',
    who:
      'You were invited by the person whose data it is, and you hold the key they pinned for you. ' +
      'Your portal is a different page from this one.',
    href: './therapist.html',
    linkLabel: 'the therapist portal',
    entryCondition:
      'You need an invitation from the person whose data it is. There is no sign-up on that page ' +
      'and no way to request access from here — without an invitation there is nothing for you to ' +
      'sign in to. Everything you can see there is scoped to what that person granted, and they ' +
      'can withdraw it.',
  },
  {
    id: 'operator',
    question: 'I run the server this page came from',
    who:
      'You operate the box. You are not the owner of the data and not a clinician, and the ' +
      'console shows you neither — no entries, no names, no record of who shared what with whom.',
    href: './admin.html',
    linkLabel: 'the server console',
    entryCondition:
      'Operational health only. On this build that console asks for no credential of its own, ' +
      'because this build defines no administrator identity for it to present — so anyone who can ' +
      'reach it can open it. Serve it where you would serve a shell, not where you serve this page.',
  },
]

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   2. The owner's six entry points, grouped and ranked.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

/**
 * The ids are App.svelte's `Source` union, unchanged, so wiring this screen to the existing tab
 * state is a rename of nothing. audience.test.ts pins the set.
 */
export type OwnerRouteId = 'file' | 'sync' | 'assess' | 'build' | 'owner' | 'recover'

/**
 * The six are not equal in weight, which was the flat tab bar's whole problem.
 *
 *   arrive      what a person is here to do. Two of them put their own data on the screen; the
 *               third needs no data at all, which makes it the one thing a first-time visitor can
 *               actually do.
 *   occasional  real, and not what today is about. Kept visible rather than hidden behind a
 *               "more" affordance, because "recover access" is exactly the thing someone needs to
 *               find while already unsettled.
 */
export type RouteGroup = 'arrive' | 'occasional'

/** Whether an entry point stays in this browser or talks to the server. Drives the reach note. */
export type RouteNeeds = 'this-browser' | 'the-server'

export interface OwnerRoute {
  id: OwnerRouteId
  /** A phrase, never a bare verb. */
  label: string
  /** What it actually is, in one sentence, so the label does not have to carry the whole meaning. */
  blurb: string
  group: RouteGroup
  /** Order within the group; lower first. */
  order: number
  needs: RouteNeeds
}

export const GROUP_ORDER: readonly RouteGroup[] = ['arrive', 'occasional']

export const GROUP_HEADING: Record<RouteGroup, string> = {
  arrive: 'What most people are here for',
  occasional: 'Now and then',
}

export const GROUP_NOTE: Record<RouteGroup, string> = {
  arrive:
    'Two ways to put your own data on this screen, and one thing that needs no data at all.',
  occasional:
    'Three places you will not need on most visits. They are listed rather than tucked away so ' +
    'they are findable on the visit you do need them.',
}

export const NEEDS_LABEL: Record<RouteNeeds, string> = {
  'this-browser': 'In this browser',
  'the-server': 'Needs your server',
}

export const OWNER_ROUTES: readonly OwnerRoute[] = [
  {
    id: 'file',
    label: 'Open a backup file',
    blurb:
      'Export a backup from the Daymark app under Settings → Export backup, then drop the .json ' +
      'file here. It is read in this browser and is not uploaded.',
    group: 'arrive',
    order: 1,
    needs: 'this-browser',
  },
  {
    id: 'sync',
    label: 'Connect to your sync server',
    blurb:
      'Pull your latest encrypted snapshot from the server you host, and open it here with your ' +
      'passphrase. The server stores the snapshot and cannot read it.',
    group: 'arrive',
    order: 2,
    needs: 'the-server',
  },
  {
    id: 'assess',
    label: 'Self-checks, on this device',
    blurb:
      'The questionnaires and exercises, run here. They need no backup and no server. ' +
      'Self-tracking tools rather than medical assessments; scores and bands are descriptive.',
    group: 'arrive',
    order: 3,
    needs: 'this-browser',
  },
  {
    id: 'owner',
    label: 'Owner console — clinicians and shares',
    blurb:
      'Invite a clinician, see what you granted them, build a curated share of scores and bands, ' +
      'and read the metadata log of what was opened. Holds your keys in this browser while open.',
    group: 'occasional',
    order: 1,
    needs: 'the-server',
  },
  {
    id: 'recover',
    label: 'Recover access to your server',
    blurb:
      'If the access token for your sync server is lost, this is where recovery starts. A ' +
      'recovery link opened from a message also lands here.',
    group: 'occasional',
    order: 2,
    needs: 'the-server',
  },
  {
    id: 'build',
    label: 'Build a self-check of your own',
    blurb:
      'Write a questionnaire yourself and export it as a file. It is labelled custom-made ' +
      'wherever it is later used, and never presented as a validated instrument.',
    group: 'occasional',
    order: 3,
    needs: 'this-browser',
  },
]

export interface RankedGroup {
  group: RouteGroup
  heading: string
  note: string
  routes: OwnerRoute[]
}

/**
 * The six, grouped and ordered for rendering.
 *
 * Groups come back in [GROUP_ORDER], routes within a group by `order` with the id as a tiebreak
 * so the result is total and stable rather than dependent on however the array happened to be
 * written. An empty group is dropped rather than rendered as a heading with nothing under it —
 * which matters because the caller may pass a subset.
 *
 * Non-mutating: `filter` copies before `sort`, so the exported catalogue cannot be reordered by
 * rendering it.
 */
export function rankOwnerRoutes(routes: readonly OwnerRoute[] = OWNER_ROUTES): RankedGroup[] {
  return GROUP_ORDER.map((group) => ({
    group,
    heading: GROUP_HEADING[group],
    note: GROUP_NOTE[group],
    routes: routes
      .filter((r) => r.group === group)
      .sort((a, b) => a.order - b.order || a.id.localeCompare(b.id)),
  })).filter((g) => g.routes.length > 0)
}

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   3. The only two questions this screen asks the server.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

/**
 * The probes this screen is permitted to read, and the record of what was declined.
 *
 * PERMITTED — both unauthenticated, both already answerable by anyone who can reach the app, both
 * modelled in lib/admin/health.ts:
 *   liveness     GET /healthz  — is there a Daymark server behind this page at all?
 *   writability  GET /readyz   — did its storage take a write when last asked?
 *
 * DECLINED, and why:
 *
 *   capability (GET /v1/config). Public, and still not shown. It publishes one boolean about
 *   outbound mail, and the whole subject of mail configuration is off this screen: an
 *   unauthenticated page is not where a stranger learns which delivery paths this deployment has.
 *   It is legitimately useful — it decides whether the emailed half of "Recover access" can work
 *   — and it belongs on the admin console once that console has a credential. Today it does not.
 *
 *   Whether the sync API is configured. This is genuinely already public: with sync unconfigured
 *   every sync path answers 503 "sync API not configured" (Application.kt), so restating it would
 *   disclose nothing. It is NOT probed anyway, for a reason that has nothing to do with
 *   disclosure. When sync IS configured those same paths go through AuthGuard.authorize(), and a
 *   request with no bearer token takes the BAD_TOKEN branch, which calls recordFailure() against
 *   the caller's source identity. A probe on page load would therefore burn a failed-auth attempt
 *   on every visit — and where the deployment sits behind a reverse proxy with no trusted-proxy
 *   configuration, every visitor keys to the same source, so a first screen that probed sync could
 *   walk the owner into their own lockout. A diagnostic that can lock people out is not a
 *   diagnostic.
 *
 *   Everything else — proxy configuration, mail credentials, the data directory, environment
 *   values, who the owner is, how many relationships exist, the presence of any secret. None of it
 *   is served to an unauthenticated caller, so none of it may be inferred, displayed or hinted at
 *   here. [WHY_SO_FEW_CHECKS] says so on the page rather than only in this comment, because a
 *   person who came looking for a configuration checklist will otherwise assume it was forgotten.
 */
export const ORIENTATION_PROBES: readonly ProbeId[] = ['liveness', 'writability']

/** The endpoint records for [ORIENTATION_PROBES], so the component never picks its own paths. */
export function orientationEndpoints(): Endpoint[] {
  return ENDPOINTS.filter((e) => ORIENTATION_PROBES.includes(e.id))
}

/**
 * Five states, and none of them is a pass.
 *
 * `answering` is the least interesting one on purpose: it means two endpoints answered, which is
 * a small fact, and [REACH_SILENT_ON] spells out how small. There is no state that means the
 * deployment is fine, because this page cannot know that.
 */
export type ReachState = 'not-checked' | 'answering' | 'refusing' | 'no-answer' | 'undocumented'

export interface ServerReach {
  state: ReachState
  /** One authored sentence stating what was observed. Never a verdict about the deployment. */
  statement: string
  /** What that observation does not establish. Never empty, including for `answering`. */
  silentOn: string
  /**
   * A short phrase to hang on the entry points that talk to the server, or null when there is
   * nothing to add. Null for `answering`: a route that is expected to work gets no badge, because
   * a badge saying so would be the reassurance this system does not offer.
   */
  routeNote: string | null
}

/** The word on the chip. No "healthy", no "up", no tick — see lib/admin/health.ts PROBE_STATE_WORD. */
export const REACH_WORD: Record<ReachState, string> = {
  'not-checked': 'Not checked',
  answering: 'Answered',
  refusing: 'Refusing writes',
  'no-answer': 'No answer',
  undocumented: 'Undocumented',
}

const REACH_STATEMENT: Record<ReachState, string> = {
  'not-checked': 'This page has not asked the server anything yet.',
  answering:
    'A server answered at this address, and reported that its storage took a write when it was ' +
    'last asked.',
  refusing:
    'A server answered at this address, and reported that its storage would not take a write ' +
    'when it was last asked.',
  'no-answer':
    'Nothing answered at this address. If you opened this page from a file on your own disk, ' +
    'that is expected — the viewer runs without a server. If you did not, then this browser could ' +
    'not reach one.',
  undocumented:
    'Something answered at this address, but not in a shape this build sends. That usually means ' +
    'something in between answered instead of the app.',
}

/**
 * The half that keeps the other half honest, kept beside it so the two are never read apart —
 * the same pairing lib/admin/health.ts uses for `answers` and `silentOn`.
 */
export const REACH_SILENT_ON: Record<ReachState, string> = {
  'not-checked': 'Nothing has been observed, so nothing here is a statement about the server.',
  answering:
    'Two endpoints answered, and that is the whole of it. It does not say whether sync is set up ' +
    'on this server, whether your access token still works, whether anything you sent would be ' +
    'kept, or that any of it is configured the way you meant. This page is not able to check those ' +
    'and does not try.',
  refusing:
    'The answer carries no reason — the reason goes to the server’s own log, deliberately, ' +
    'because that endpoint answers anyone. It does not say what is wrong or for how long.',
  'no-answer':
    'This is a fact about this browser’s request, not about the server. One that is running may ' +
    'still be unreachable from where you are.',
  undocumented:
    'It does not say what answered. Nothing here should be read as a statement about the Daymark ' +
    'server, in either direction.',
}

/*
 * WHAT THESE NOTES ARE ABOUT, AND WHY THE WORDING CHANGED.
 *
 * The probe asks THIS PAGE'S OWN ORIGIN. The routes these notes hang on ask a server the person
 * NAMES — sync, the owner console and recovery each carry a free-text address field that defaults
 * to this origin but very often does not stay there. Those are two different machines, and the
 * first version of these notes stated a verdict about the first as though it were about the
 * second.
 *
 * Two real deployments made it lie. A bundle on a static host with the sync API elsewhere gets the
 * host's 404 for /healthz and every server route was labelled "The answer at this address was not
 * one this build sends" — about a server that was healthy and had never been asked. And a page
 * opened from disk got "Nothing answered at this address" for the same reason.
 *
 * So each note now names the address it is actually about and says the route may point elsewhere.
 * `routeNoteFor` additionally withholds them entirely when the page is not served over http(s),
 * because then the probe established nothing about any server at all.
 */
const REACH_ROUTE_NOTE: Record<ReachState, string | null> = {
  'not-checked': null,
  answering: null,
  refusing:
    'The server at this page’s own address reported it could not write. If you point this ' +
    'somewhere else, that is a different server and this says nothing about it.',
  'no-answer':
    'Nothing answered at this page’s own address. If you point this somewhere else, that is a ' +
    'different server and this says nothing about it.',
  undocumented:
    'Something answered at this page’s own address, but not in a shape this build sends — often ' +
    'something in between. If you point this somewhere else, this says nothing about it.',
}

const reachOf = (state: ReachState): ServerReach => ({
  state,
  statement: REACH_STATEMENT[state],
  silentOn: REACH_SILENT_ON[state],
  routeNote: REACH_ROUTE_NOTE[state],
})

/**
 * Fold the two readings into one statement.
 *
 * Liveness leads, because it answers the question the other one presupposes: if nothing is there,
 * "the data directory would not take a write" is not a finding, it is noise. `undocumented` is
 * kept as its own outcome rather than folded into a failure — an answer this build does not send
 * is a real and separate thing to know, and it usually means the reader is talking to something
 * that is not the app.
 *
 * Total by construction: any set of readings, including an empty one or one missing an id,
 * produces a ServerReach. A screen that renders nothing when it does not recognise its input has
 * hidden the most interesting case it will ever see.
 */
export function readServerReach(readings: readonly ProbeReading[]): ServerReach {
  const liveness = readings.find((r) => r.id === 'liveness')
  if (!liveness) return reachOf('not-checked')
  if (liveness.state === 'unreachable') return reachOf('no-answer')
  if (liveness.state !== 'responding') return reachOf('undocumented')

  const writability = readings.find((r) => r.id === 'writability')
  // Liveness answered and readiness did not. Both are registered together at the server root, so
  // one answering without the other is not a shape this build produces.
  if (!writability || writability.state === 'unreachable') return reachOf('undocumented')
  if (writability.state === 'refusing') return reachOf('refusing')
  if (writability.state !== 'responding') return reachOf('undocumented')
  return reachOf('answering')
}

/**
 * The note for one entry point.
 *
 * An entry point that stays in this browser NEVER carries a server note, even while the server is
 * unreachable. Opening a backup file works perfectly well on a train, and marking it as impaired
 * because a fetch failed would be the screen lying about its own most reliable feature.
 */
export function routeNoteFor(
  route: OwnerRoute,
  reach: ServerReach,
  origin: OriginKind = 'served',
): string | null {
  // A page opened from disk has no origin to probe: a relative fetch fails instantly and says
  // nothing about anyone's server. Reporting that as a server problem is precisely the
  // "sends someone to debug a machine that is fine" failure, so nothing is said at all.
  if (origin !== 'served') return null
  return route.needs === 'the-server' ? reach.routeNote : null
}

/**
 * Whether the reach panel still shows after the orientation has been dismissed.
 *
 * Bad news does not get dismissed. A returning person who collapsed the explanation still needs
 * to be told that nothing is answering; what they were dismissing was the explanation of who the
 * three surfaces are for, not the state of their server. Only the two uninteresting states —
 * nothing asked yet, and both endpoints answered — collapse with it.
 */
export function showsReachWhenCompact(reach: ServerReach): boolean {
  return reach.state !== 'answering' && reach.state !== 'not-checked'
}

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   4. The return visit.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

/**
 * WHY ANYTHING IS PERSISTED AT ALL, AND WHY IT IS THIS LITTLE.
 *
 * Someone who has used this before should not re-read an orientation every visit. The three
 * options were a per-tab dismissal (costs a click every session, and forgets between them), a
 * remembered CHOICE of audience, and a remembered dismissal.
 *
 * The remembered choice was rejected. "This browser profile belongs to a clinician", or "to a
 * Daymark owner", is a durable statement about a person on a machine that may be shared,
 * borrowed or looked through — and it would buy almost nothing, because all three surfaces stay
 * one click apart either way.
 *
 * What is stored is one entry: the key [ORIENTATION_STORAGE_KEY] holding a single integer, the
 * orientation version that was dismissed. No audience, no name, no route, and deliberately NO
 * TIMESTAMP — when someone last opened Daymark is information about their care, and a dismissal
 * flag has no use for it. On a shared machine the entry says that someone here has opened this
 * page before, which the browser's own history and address bar already say more loudly.
 *
 * THE PRECEDENT AND HOW THIS DIFFERS. lib/therapist/pinStore.ts is the first persistent
 * client-side storage in this app, and it is candid that "fingerprints and timestamps only" is
 * true but not the whole truth: its record says this profile is an owner console with N
 * relationships pinned on these dates. It accepts that cost because a pin that does not outlive
 * the thing it checks is a tautology, and a key substitution would cost someone their journal.
 * Nothing here is a security control, so this record buys convenience only — which is exactly why
 * it is held to one integer, and why the failure behaviour is the opposite of pinStore's.
 *
 * FAIL OPEN, NOT CLOSED. pinStore throws when storage is unreadable, because falling back to "no
 * pins" would silently restore the hole it exists to close. Unreadable storage here means the
 * person sees the orientation again — mildly annoying, never wrong. Nothing in this section
 * throws, and nothing about it can block the page.
 *
 * A VERSION RATHER THAN A BOOLEAN. If the orientation later gains a surface or a route moves, a
 * stored `true` would hide the new information from everyone who had ever dismissed the old one.
 * The stored number lets a materially changed orientation show once more, and only then —
 * [ORIENTATION_VERSION] is bumped when the copy changes in a way a returning person needs to see,
 * not for a typo.
 */
export const ORIENTATION_STORAGE_KEY = 'daymark.orientation.dismissed.v1'

/** Bump only when a returning reader genuinely needs to see the orientation again. */
export const ORIENTATION_VERSION = 1

/** The slice of the Storage API this needs. Lets tests pass a plain object; Node has no DOM. */
export interface OrientationStorage {
  getItem(key: string): string | null
  setItem(key: string, value: string): void
  removeItem?(key: string): void
}

/** localStorage, or null where it does not exist or the browser refuses to hand it over. */
export function defaultOrientationStorage(): OrientationStorage | null {
  try {
    return globalThis.localStorage ?? null
  } catch {
    // Access itself throws in some blocked or partitioned contexts, not merely returns undefined.
    return null
  }
}

/**
 * The dismissed version, or null.
 *
 * Null covers every uncertainty — no storage, no entry, an entry that is not a non-negative safe
 * integer, a getItem that threw — and all of them mean "show the orientation". Strict about the
 * shape because a value this cannot read is a value it must not act on: coercing "" or "true" to
 * a number would hide the orientation on the strength of something this build did not write.
 */
export function readDismissedVersion(
  storage: OrientationStorage | null = defaultOrientationStorage(),
): number | null {
  if (!storage) return null
  let raw: string | null
  try {
    raw = storage.getItem(ORIENTATION_STORAGE_KEY)
  } catch {
    return null
  }
  if (raw === null) return null
  const trimmed = raw.trim()
  if (!/^\d+$/.test(trimmed)) return null
  const parsed = Number(trimmed)
  return Number.isSafeInteger(parsed) ? parsed : null
}

export type OrientationView = 'full' | 'compact'

/**
 * Which of the two the screen opens on.
 *
 * `>=` rather than `===`: a stored version AHEAD of this build's — a downgraded deployment, or a
 * profile that met a newer build first — means the person has already read a superset, so
 * reopening the full panel at them would be a regression dressed as caution.
 */
export function orientationView(
  dismissedVersion: number | null,
  current: number = ORIENTATION_VERSION,
): OrientationView {
  return dismissedVersion !== null && dismissedVersion >= current ? 'compact' : 'full'
}

/**
 * Record the dismissal. Returns false when the browser would not keep it.
 *
 * A boolean rather than a throw, because the caller's honest response is a sentence
 * ([STORAGE_REFUSED]) and not an error state: the person clicked "I know where I am", the panel
 * still collapses for this visit, and they are told it will be back. Quota failures — Safari
 * private browsing is the usual one — arrive as an exception from setItem rather than as a
 * return value, which is why the try wraps the write.
 */
export function rememberDismissal(
  storage: OrientationStorage | null = defaultOrientationStorage(),
  version: number = ORIENTATION_VERSION,
): boolean {
  if (!storage) return false
  try {
    storage.setItem(ORIENTATION_STORAGE_KEY, String(version))
    return true
  } catch {
    return false
  }
}

/**
 * Erase the record, so "show this again" leaves nothing behind.
 *
 * removeItem rather than writing a 0: an entry holding zero is still a
 * `daymark.orientation.dismissed.v1` key sitting in storage announcing that this profile has
 * opened Daymark. The existence of the entry is the part being cleared. Returns false when the
 * storage object cannot remove, so the caller can say the record is still there rather than
 * claiming a deletion that did not happen — the failure pinStore.ts had to grow a message for.
 */
export function forgetDismissal(
  storage: OrientationStorage | null = defaultOrientationStorage(),
): boolean {
  if (!storage || typeof storage.removeItem !== 'function') return false
  try {
    storage.removeItem(ORIENTATION_STORAGE_KEY)
    return true
  } catch {
    return false
  }
}

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   5. The fixed copy.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

export const ORIENTATION_TITLE = 'Daymark Companion'

/**
 * The lede. Three surfaces named in one sentence, because the reader's question is "am I in the
 * right place" and the answer has to arrive before they start reading features.
 */
export const ORIENTATION_LEDE =
  'Three different people can open this software, and they belong on three different pages. This ' +
  'one is for a person looking at their own Daymark data. A clinician who was invited has a ' +
  'separate portal, and whoever runs the server has a separate console.'

/** What runs where. Written to be true of the version that stores a dismissal flag. */
/*
 * WHY THE SERVER SENTENCE IS WORDED AS A WARNING RATHER THAN A GUARANTEE.
 *
 * This read "The parts that reach a server reach only the server this page was served from." That
 * is false, and falsely reassuring in the one place it must not be: every route this screen marks
 * "Needs your server" — sync, the owner console, recovery — carries a free-text server field
 * (SyncPanel.svelte, OwnerConsole.svelte, RecoverAccess.svelte). A person could read that
 * sentence, scroll up, paste a URL out of a support thread, and send their access token or
 * recovery email to a stranger, having just been told by the product that it could not happen.
 *
 * The field is not the bug — reaching your own self-hosted server is the entire point, and it can
 * be anywhere. The sentence was the bug. It now says what is actually true: nothing leaves unless
 * you send it, and where it goes is a box you fill in.
 */
export const WHAT_THIS_PAGE_IS =
  'This page is a viewer. It runs in your browser, holds no account of you, and has nothing of ' +
  'yours until you give it something. A backup you open is read here, never uploaded, and gone ' +
  'when you close the tab. Nothing is sent anywhere unless you ask for it — and where it is sent ' +
  'is an address you type, so check it before you paste a token or an email into a page you did ' +
  'not open yourself. The single thing it writes to your browser is a note that you have read ' +
  'this orientation.'

/** Named exactly, so the sentence and the code cannot drift apart. audience.test.ts checks that. */
export const WHAT_IS_STORED =
  'Dismissing this leaves one entry in this browser: the key ' +
  '“daymark.orientation.dismissed.v1”, holding the number 1 and nothing else. No name, no page, ' +
  'no date, nothing about your data — a later visit reads it, sees that this has been read, and ' +
  'shows the short version instead. On a machine you share it says only that someone here has ' +
  'opened this page before, which the browser’s own history already says. Choosing to show the ' +
  'orientation again removes it.'

/** Shown when the browser refused the write. Says what actually happened and what follows. */
export const STORAGE_REFUSED =
  'This browser would not keep that note, so the orientation will be here again next time. ' +
  'Nothing else on this page depends on it.'

/**
 * Why there is no configuration checklist, on the page rather than only in a comment.
 *
 * The same move lib/admin/health.ts makes with NO_SINGLE_FIGURE: someone who came expecting a
 * list of missing settings and does not find one will assume it was forgotten and go looking for
 * it, or worse, build it.
 */
export const WHY_SO_FEW_CHECKS =
  'There is no configuration checklist on this page, and there should not be one. Anyone who can ' +
  'reach this address can open it — there is no sign-in — so a list of what this server has and ' +
  'has not been set up with would be a list of its weak points, handed to whoever asked. The two ' +
  'readings above are the only ones here, and they are shown only because the server already ' +
  'answers them to anyone who asks: they are the same two endpoints a load balancer reads. ' +
  'Everything else belongs behind a credential, and the operator console does not have one yet.'

/** The compact line a returning reader gets in place of the panel. */
export const COMPACT_SUMMARY =
  'Your own data is on this page. A clinician you invited has a separate portal; whoever runs ' +
  'the server has a separate console.'

/**
 * The footer under the reading. It is the disclosure rule stated as a fact about the two
 * endpoints, so a reader can check the claim rather than take it.
 */
export const PROBES_ARE_PUBLIC =
  'The two readings above come from GET /healthz and GET /readyz. Both answer anyone who can ' +
  'reach this address, with no sign-in, so nothing above has been told to you that is not already ' +
  'told to everyone.'

/** Shown in place of the operator link when the deployment chooses not to link that console. */
export const ADMIN_NOT_LINKED =
  'That console is a separate page in this bundle, and this deployment has chosen not to link it ' +
  'from here.'

/**
 * Every remaining word the screen renders — headings, control labels, the two link verbs.
 *
 * They live here rather than in the component for one reason: audience.test.ts walks this
 * module's exported strings and asserts the whole screen's register (no cheer, no claim of
 * health, no figure, no withheld configuration subject). A heading authored in the .svelte file
 * would be the one sentence on the page outside every check this module makes — the same hole
 * lib/admin/health.ts found when its chip words were still living in AdminConsole.svelte.
 */
export const LABELS = {
  audiences: 'Who each page is for',
  routes: 'Where to start',
  reach: 'What answered at this address',
  reachLimits: 'What that does not establish',
  runsHere: 'What runs where',
  stored: 'What this page keeps in your browser',
  storageRefused: 'That note was not kept',
  youAreHere: 'You are here',
  open: 'Open',
  dismiss: 'I know where I am',
  reopen: 'Show the orientation',
  recheck: 'Ask again',
} as const
