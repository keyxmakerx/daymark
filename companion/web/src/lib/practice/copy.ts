/*
 * THE PRACTICE CONSOLE'S STANDING STATEMENTS — every fixed sentence the screen says, in one place.
 *
 * ─── WHY THE WORDS LIVE IN A MODULE AND NOT IN THE MARKUP ────────────────────────────────────────
 *
 * Same arrangement as lib/admin/health.ts and lib/onboarding/audience.ts, and for the same reason:
 * the sentences below are not decoration, they are the product's claims about what it does and does
 * not do, and a claim needs a test watching it. Markup cannot be asserted over in a node test
 * environment; a module can. So console.test.ts reads this file and checks the register (no cheer,
 * no reassurance, no invented figures), checks that the two sentences this console exists to get
 * right are present in full, and checks that every placeholder actually calls itself one.
 *
 * ─── THE TWO SENTENCES THIS SCREEN EXISTS TO GET RIGHT ───────────────────────────────────────────
 *
 * [MEMBERSHIP_IS_NOT_READ_ACCESS] and [REMOVAL_ENDS_A_MEMBERSHIP]. Both correct a thing an
 * administrative screen tends to imply on its own, without anybody deciding it should:
 *
 *   A roster with an "Add member" button beside it looks like a list of people who can see things.
 *   It is not. Adding somebody creates a row saying what they may DO in this practice. Reading
 *   anyone's material takes a grant — a key wrapped on a patient's own device, for one named
 *   person — and there is no button on this console, or route on this server, that makes one.
 *
 *   A "Remove" button looks like it cuts somebody off. It ends a MEMBERSHIP. It does not disable
 *   their sign-in credential (no route in this server does, and it would not be this practice's
 *   credential to disable), and it withdraws no grant, because grants belong to patients. The
 *   server's own file says the product copy for this must say "removed from the practice" and never
 *   "access revoked", so that is what it says.
 *
 * An administrator who believes the second button did more than it did will make a worse decision
 * than one who knows — which is the whole argument for spending this much prose on a screen with
 * six controls on it.
 *
 * ─── ON PLACEHOLDERS ─────────────────────────────────────────────────────────────────────────────
 *
 * Several things in docs/COMPANION_ACCESS_CONTROL.md have no interface and, in some cases, no
 * server route either. They are rendered as marked placeholders rather than omitted, because an
 * omission is indistinguishable from a feature that was quietly dropped, and rather than faked,
 * because a fake row of plausible names is indistinguishable from a bug when somebody is trying to
 * decide whether the thing works. Every entry in [PLACEHOLDERS] carries the word "Placeholder" into
 * the interface and says what would be there instead.
 */

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   1. What this console is.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

export const CONSOLE_TITLE = 'Practice console'

/** The one-line subject, under the title. States the plane it works in and the one it cannot reach. */
export const CONSOLE_LEDE =
  'Administer one practice: who is a member, what role they hold, and what the practice’s own log ' +
  'records. Nothing on this surface opens clinical content, and no request it can make would return any.'

/**
 * The build status, said first, because everything below reads differently once you know it.
 *
 * The server side of this is built and tested; the interface is a first pass whose job is to be
 * clickable end to end. Saying so is not modesty — a maintainer deciding whether a thing works
 * needs to know which half they are looking at, and unmarked scaffolding is the most expensive kind
 * of confusion to unpick later.
 */
export const CONSOLE_BUILD_STATE =
  'First interface for the practice shape. The server routes underneath it are built and tested; ' +
  'these screens are a first pass, and the parts that are not built are marked as placeholders ' +
  'rather than left out.'

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   2. The two corrections.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

/** Rendered wherever a member is added or re-roled. The single most important sentence on the screen. */
export const MEMBERSHIP_IS_NOT_READ_ACCESS =
  'Adding someone to this practice does not give them access to anyone’s journal, notes or ' +
  'answers. A role decides what a person may do here. Reading is separate: it comes only from a ' +
  'grant a patient signs on their own device, for one named person, and nothing on this console ' +
  'can create one.'

/** Rendered at the remove control, and again in the roster's footer. */
export const REMOVAL_ENDS_A_MEMBERSHIP =
  'Removing someone ends their membership in this practice. It does not end their access. Their ' +
  'sign-in credential is not disabled and no grant is withdrawn, so a person who still holds a ' +
  'grant can sign in again and read exactly what a patient still lets them read. Only that patient ' +
  'can change that, from their own device.'

/**
 * What the session count on a removal is, and is not.
 *
 * Shown beside the number the server returns, because a bare "3 sessions ended" invites the reading
 * that three accesses were closed. They were three browser sessions.
 */
export const SESSIONS_CUT_MEANS =
  'That count is portal sessions ended, and nothing else. It is not a count of grants withdrawn, ' +
  'keys rotated, or anything un-read. It is zero for a seat the person never accepted, because ' +
  'nothing was cut.'

/** Shown wherever a seat is created. */
export const SEAT_IS_AN_OFFER =
  'A new member is an offer until they accept it from their own session. Until they do, the seat ' +
  'carries nothing and withdrawing it ends nothing.'

/** Shown beside the two controls that ask for an authenticator code. */
export const WHY_SOME_ACTS_COST_MORE =
  'Adding a member and changing a role ask for a current code from your authenticator, because ' +
  'both change who may be offered access. Removing a member does not ask for one. The safe ' +
  'direction is deliberately the cheap one.'

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   3. What this console does not hold.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

/**
 * There is no patient list, here or on the server, and the absence is the design rather than a
 * missing screen — which is why it is stated instead of placeheld.
 */
export const NO_PATIENT_LIST =
  'There is no list of this practice’s patients, on this console or on the server. A patient is ' +
  'not owned by a practice — they hold their own keys, and the practice is a way of addressing ' +
  'people. A roster that grew a patient list would turn that into a register of who is in therapy ' +
  'where, so no route assembles one.'

/** The practice's log, qualified where it is read. */
export const AUDIT_IS_METADATA_ONLY =
  'Metadata only, and not provably complete. This is the practice’s own record of memberships, ' +
  'roles and refusals — who, what and when, never content. Entries are chained so a tampered or ' +
  'reordered entry is detectable, but a dishonest server could still withhold the newest entries, ' +
  'and there is no proof this list is exhaustive.'

/** Why a patient's access history is not reachable from here. */
export const AUDIT_IS_A_DIFFERENT_CHAIN =
  'This is a different log from any patient’s. Who opened whose shared material is recorded in ' +
  'that patient’s own chain, in a different database, readable by them. Paging through this one ' +
  'cannot reach it.'

/** Rendered on the sign-in panel. */
export const SIGN_IN_IS_THE_PORTAL_CREDENTIAL =
  'Sign in with the same credential you use for the clinician portal: your member id and a current ' +
  'code from your authenticator. Your standing is looked up per practice on every request, so a ' +
  'role in one practice is nothing in another.'

/** Rendered on the create-practice panel, where the credential is a different one entirely. */
export const CREATE_USES_THE_SERVER_TOKEN =
  'Creating a practice uses the server’s own provisioning token — the deployment’s bearer token, ' +
  'held by whoever runs the machine — not a practice sign-in. In this build that token is also the ' +
  'owner’s credential, because a self-hosted server has one of each. Holding it confers no ' +
  'clinical read.'

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   4. Placeholders.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

/** The word every placeholder carries into the interface. One word, so a reader learns it once. */
export const PLACEHOLDER_WORD = 'Placeholder'

export interface PlaceholderNote {
  readonly id: string
  /** What the thing is called. */
  readonly title: string
  /** What would be here. Written as a description of the absent thing, never as a promise of a date. */
  readonly body: string
  /** Where the real thing is specified or already exists, so a reader can go and check. */
  readonly specifiedAt: string
}

/**
 * The parts of the practice shape this interface does not have, each named and described.
 *
 * The selection rule: something is listed here if a person looking at this console would reasonably
 * expect it and not find it. Things that are absent BY DESIGN — a patient list, a way to read a
 * note — are not placeholders and are not in this list; they are stated as facts above, because
 * calling them "not built yet" would suggest they are coming.
 */
export const PLACEHOLDERS: readonly PlaceholderNote[] = [
  {
    id: 'practice-directory',
    title: 'Choosing a practice from a list',
    body:
      'This console asks you to type a practice id. There is no route that lists the practices you ' +
      'belong to, so there is nothing here to build a picker from yet.',
    specifiedAt: 'Would need a new server route; none exists today.',
  },
  {
    id: 'scheduling',
    title: 'Scheduling',
    body:
      'The front desk role carries scheduling and the logistics around it, and the server has the ' +
      'capability for it. There is no calendar or appointment surface in this console.',
    specifiedAt: 'docs/COMPANION_ACCESS_CONTROL.md, role catalog: front desk.',
  },
  {
    id: 'org-consent',
    title: 'Care team, and consenting to one',
    body:
      'A patient consenting once to “my care team at Practice X”, and pruning that team afterwards, ' +
      'is described in the design and is not built. It belongs on the patient’s own device, because ' +
      'issuing a grant means wrapping a key, and this plane holds none.',
    specifiedAt: 'docs/COMPANION_ACCESS_CONTROL.md, consent model: org-consent.',
  },
  {
    id: 'care-relationships',
    title: 'Referrals, transfers and cover',
    body:
      'Who is a person’s primary, co-treating, covering or supervising clinician — and the referral ' +
      'and handover motions that move those — has no table and no screen yet. None of it would move ' +
      'a key either.',
    specifiedAt: 'docs/COMPANION_ACCESS_CONTROL.md, clinician turnover.',
  },
  {
    id: 'guard-review',
    title: 'Behavioural guard review',
    body:
      'The design has a guard that watches behaviour rather than content and can pause a token ' +
      'pending an administrator’s review. Nothing watches, and there is nothing here to review.',
    specifiedAt: 'docs/COMPANION_ACCESS_CONTROL.md, behavioral guard.',
  },
  {
    id: 'kill-switch',
    title: 'Freezing an account at once',
    body:
      'The design gives a practice administrator a kill switch that freezes an account or a ' +
      'clinician’s access immediately. The only immediate act this console has is removing a ' +
      'membership, which ends fewer things than a freeze would.',
    specifiedAt: 'docs/COMPANION_ACCESS_CONTROL.md, revocation.',
  },
  {
    id: 'key-rotation',
    title: 'The cryptographic half of revocation',
    body:
      'Rotating a patient’s data key and re-wrapping it for whoever is still authorised is the ' +
      'durable half of revocation, and it happens on the patient’s device. No control-plane screen ' +
      'can do it, and this one does not pretend to.',
    specifiedAt: 'docs/COMPANION_ACCESS_CONTROL.md, revocation.',
  },
]

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   5. Empty states.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

/**
 * What each empty surface says. Flat and declarative: it states what the space holds and when
 * something appears in it, and stops. An empty roster is not a problem to be nudged about, and a
 * practice with one member is a practice.
 */
export const EMPTY_ROSTER_TITLE = 'No roster read yet'
export const EMPTY_ROSTER_BODY =
  'Sign in and name a practice, and its members appear here. This list is read fresh from the ' +
  'server every time; nothing is remembered by this page.'

export const EMPTY_AUDIT_TITLE = 'No entries read yet'
export const EMPTY_AUDIT_BODY =
  'The practice’s log fills in when it is read. Reading it needs a role that carries audit review.'

export const AUDIT_RETURNED_NOTHING =
  'The server returned no entries for this practice. That is what an unused practice looks like, ' +
  'and it is also what a withheld page would look like; this page cannot tell the two apart.'
