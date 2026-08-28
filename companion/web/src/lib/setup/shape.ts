/*
 * WHAT THIS MACHINE IS FOR — the model behind the first-run entry.
 *
 * ─────────────────────────────────────────────────────────────────────────────────────────────
 * THE PROBLEM THIS EXISTS TO FIX
 * ─────────────────────────────────────────────────────────────────────────────────────────────
 *
 * There was no first-run screen. index.html opened straight onto the six-destination menu, and
 * the maintainer's own words on landing there were "i'm still so fucking confused about this
 * initial screen, there's like 10 different buttons.. why". lib/onboarding/audience.ts already
 * fixed half of that — it says who each of the three PAGES is for, and ranks the owner's six
 * entry points. What nothing anywhere asked was the question that comes before all of it:
 *
 *     what is this machine for?
 *
 * docs/PLAN_2026-08-COMPANION-NEXT.md §3.11 names three answers and they are not variations of
 * one product. Solo and Paired are one product with a flag — same trust model, same threat model,
 * one clinician switched on. Practice INVERTS the arrangement the product exists to offer (§3.11.3):
 * the clinic owns the machine and the person is a tenant on it, which is a different posture, not
 * a bigger one. A screen that asks the question once, records the answer, and then gets out of the
 * way is the smallest honest way to hold that distinction.
 *
 * ─────────────────────────────────────────────────────────────────────────────────────────────
 * WHAT THE OPENING SENTENCES ARE ALLOWED TO CLAIM, AND WHAT THEY ARE NOT
 * ─────────────────────────────────────────────────────────────────────────────────────────────
 *
 * The lede opens with cause and effect rather than with a noun phrase, because "Daymark Companion
 * is a self-hosted sync server" tells someone who already knows. The two sentences in
 * [SETUP_LEDE] are the version that read clearest: the phone does something, this machine receives
 * it, and the passphrase is the thing you type.
 *
 * Two claims are deliberately NOT made, and both are the kind of claim a setup screen reaches for
 * without noticing:
 *
 *   NOT "your journal is safe here". §3.11.1 is blunt about what this actually is — one disk, and
 *   nothing backing it up. Calling a single unreplicated copy safe is the sentence someone would
 *   remember on the day the disk dies.
 *
 *   NOT "you can get it back if you lose your phone". The recovery property is real (the key
 *   derives from the passphrase alone, nothing is bound to the handset — SyncCrypto.kt) and it is
 *   exactly why someone stands a machine up. But it holds only while THIS machine still has the
 *   copy, and this screen is the wrong place to promise a thing whose other half — forget the
 *   passphrase and the data is gone, to everyone, permanently — belongs where the passphrase is
 *   chosen, on the phone. So [SETUP_LIMITS] states the disk and the passphrase as facts and
 *   promises nothing about either.
 *
 * NO GREEN, NO TICK, NO SCORE (app.css invariant 2). There is no "you're all set" state here and
 * no completion of any kind. Choosing a shape routes a page; it does not verify a deployment, and
 * a screen that congratulated someone for answering one question would be claiming otherwise. The
 * vocabulary lives in this module rather than in the component so that ban is testable over every
 * word — the arrangement lib/onboarding/audience.ts arrived at after a planted "All good — your
 * server is healthy and you are all set" passed its entire suite by living in markup.
 *
 * ─────────────────────────────────────────────────────────────────────────────────────────────
 * PLACEHOLDERS ARE MARKED IN THE INTERFACE, NOT ONLY IN THE COMMENTS
 * ─────────────────────────────────────────────────────────────────────────────────────────────
 *
 * Two of the three shapes are wired end to end on THIS page and one is not. Solo and Paired are
 * reachable from the owner's viewer; a practice is administered on its own document
 * (practice.html), the way the clinician's portal and the server console already are, and what
 * this page can honestly offer for that shape is a signpost. The difference is carried as
 * [DeploymentShape.buildState] and printed at the point of CHOICE, not discovered afterwards,
 * because a shape that quietly leads somewhere that cannot do the thing it implied is
 * indistinguishable from a broken one.
 *
 * Note the limit of what `buildState` claims: where a surface is, never how finished it is. The
 * console states its own build state on its own page, and a second-hand copy of that claim here
 * would go stale without anyone noticing. `buildState` is the field on this model most likely to
 * age, and it ages by a surface MOVING, not by one being completed.
 *
 * PURE. No fetch, no DOM, no timers, no Svelte. The component does the I/O and hands the readings
 * in; every function here is (input) → view model. The one exception is [defaultSetupStorage],
 * which reaches for localStorage exactly as audience.ts does and for the same reason: the caller
 * would otherwise have to write the same try/catch.
 */
import { OWNER_ROUTES, type OwnerRouteId } from '../onboarding/audience'

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   1. The three shapes.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

export type ShapeId = 'solo' | 'paired' | 'practice'

/** Declaration order is presentation order: the one most people want is first. */
export const SHAPE_IDS: readonly ShapeId[] = ['solo', 'paired', 'practice']

export function isShapeId(value: unknown): value is ShapeId {
  return typeof value === 'string' && (SHAPE_IDS as readonly string[]).includes(value)
}

/**
 * Where this shape's screens live, relative to the page the reader is on.
 *
 *   built           every screen the shape needs is reachable from THIS page.
 *   separate-page   the shape's own surface is a different document in this bundle, the way the
 *                   clinician's portal and the server console already are. Choosing it here lands
 *                   on a panel that points at it and holds nothing of its own.
 *
 * The distinction is stated at the point of CHOICE rather than discovered after arriving
 * somewhere that cannot do what the choice implied. Note what this field does NOT claim: nothing
 * here asserts that the separate page is finished. This page cannot check that, and the console
 * states its own build state on its own surface, which is where a claim about it belongs.
 */
export type BuildState = 'built' | 'separate-page'

/**
 * Where this page lands after the shape is chosen.
 *
 * The six route ids are `OwnerRouteId`, imported rather than restated — they are App.svelte's
 * `Source` union and audience.ts already models them. 'practice' is the seventh destination and
 * is deliberately NOT added to that union: it is not one of the owner's entry points, it is the
 * marked placeholder standing where the practice console will be.
 */
export type SetupSurface = OwnerRouteId | 'practice'

export interface DeploymentShape {
  id: ShapeId
  /** Names the shape and, in the same breath, who is on it. A bare "Solo" answers nothing. */
  label: string
  /** Who owns the machine and whose data is on it — §3.11.2's table, in one line. */
  arrangement: string
  /** What choosing it gets you, in one sentence. */
  summary: string
  /**
   * The ranking sentence, and it is not decoration. The brief for this screen is explicit that
   * Solo is marked as what most people want and Practice as more work that makes a personal
   * backup no better — an unranked list of three would leave someone standing up a clinic
   * server for themselves.
   */
  ranking: string
  buildState: BuildState
  /** What is and is not built for this shape, said plainly, at the point of choosing it. */
  buildNote: string
  /** The surface this shape opens on. */
  primary: SetupSurface
}

export const SHAPES: readonly DeploymentShape[] = [
  {
    id: 'solo',
    label: 'Solo — one person, this machine',
    arrangement: 'You run the machine and the only journal on it is yours.',
    summary:
      'A copy of your journal arrives here and you can read it on a bigger screen than a phone.',
    ranking: 'This is what most people want.',
    buildState: 'built',
    buildNote:
      'Both ways in work today: open an exported backup file, or pull the encrypted copy from ' +
      'your own server and unlock it here.',
    primary: 'file',
  },
  {
    id: 'paired',
    label: 'Paired — you, and one clinician',
    arrangement:
      'You still run the machine and the journal is still yours. One clinician you invite is ' +
      'shown the slices you pick, and you can withdraw that at any time.',
    summary:
      'Everything Solo does, plus an invitation you mint for one clinician, whose key you check ' +
      'and pin before anything is shared.',
    ranking: 'Choose this if you are showing some of your own journal to one clinician.',
    buildState: 'built',
    buildNote:
      'The pairing path is wired: you mint the invitation, they accept it on the therapist page, ' +
      'and you confirm their key fingerprint and pin it before any share goes out.',
    primary: 'owner',
  },
  {
    id: 'practice',
    label: 'Practice — a clinic runs this machine',
    arrangement:
      'The clinic owns the machine and the people using it are tenants on it. That is the ' +
      'opposite arrangement from the other two, where the journal sits on its own owner’s hardware.',
    summary:
      'Membership, roles and an audit trail for a clinic, governed by the access-control model ' +
      'in docs/COMPANION_ACCESS_CONTROL.md.',
    ranking:
      'More work to run, and it makes a personal backup no better. Only pick it if a clinic ' +
      'runs this machine for other people.',
    buildState: 'separate-page',
    buildNote:
      'Administering a practice happens on its own page in this build, not on this one — the same ' +
      'arrangement as the clinician’s portal. Choosing this opens a panel here that says what ' +
      'exists, points at that page, and holds no data of its own.',
    primary: 'practice',
  },
]

export function shapeById(id: ShapeId): DeploymentShape {
  const found = SHAPES.find((s) => s.id === id)
  /* Unreachable through the type, and a thrown error beats a silent `undefined` reaching markup. */
  if (!found) throw new Error(`unknown deployment shape: ${id}`)
  return found
}

/**
 * The name of the surface a shape opens on, taken from audience.ts's own route labels rather than
 * restated here. Two spellings of "Open a backup file" on one screen is exactly the drift the
 * OWNER_ROUTES model exists to prevent.
 */
export function primaryLabel(id: ShapeId): string {
  const shape = shapeById(id)
  if (shape.primary === 'practice') return LABELS.practiceSurface
  return OWNER_ROUTES.find((r) => r.id === shape.primary)?.label ?? LABELS.practiceSurface
}

/** Where this page will open for a shape, said on the strip so the routing is not a surprise. */
export function opensOnStatement(id: ShapeId): string {
  return `This page opens on “${primaryLabel(id)}” while it is set up this way.`
}

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   2. What the screen says.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

export const SETUP_TITLE = 'What this machine is for'

/**
 * The opening, and the one part of this file whose exact wording was arrived at by reading it
 * aloud rather than by reasoning about it. Cause and effect first: the phone does a thing, this
 * machine receives it, here is what you type. No noun-phrase definition, no feature list.
 */
export const SETUP_LEDE =
  'Daymark on your phone locks a copy of your journal and sends it to this machine. You type the ' +
  'passphrase you chose on the phone to read it here.'

/**
 * The two facts that are not allowed to be softened, stated as facts rather than as warnings —
 * a callout here would read as an alarm about something that is simply how it works.
 *
 * "One copy, on one disk" is §3.11.1's other half. "Nobody can open it without the passphrase"
 * is the same sentence from the other side, and is the reason this machine can be run by someone
 * who does not trust whoever hosts it. Neither is reassurance and neither is a promise: the first
 * says what would be lost with the disk, the second says who cannot help you if the passphrase is.
 */
export const SETUP_LIMITS =
  'One copy on one disk here, and nothing copies it elsewhere. Lose the disk and it is gone. ' +
  'Without that passphrase nobody can open it, including whoever runs this server.'

/** The question itself. Kept short: it is a heading, and the lede has already done the work. */
export const CHOICE_HEADING = 'What is this machine for?'

/**
 * Said next to the choices rather than after them, because someone deciding between three things
 * they half understand needs to know the cost of getting it wrong before they commit, not after.
 * The claim is exact: the choice routes this page and touches nothing on the server or the disk.
 */
export const CHOICE_IS_REVERSIBLE =
  'This decides what this page opens on and nothing else. It changes nothing on the server, ' +
  'moves nothing on the disk, and can be changed later.'

/**
 * WHY THE CHOICE IS REMEMBERED IN THIS BROWSER AND NOT ON THE SERVER.
 *
 * The server has a place for this — it is the configuration flag §5 below reads — and putting it
 * there would make the answer follow a person to every browser they open the page in. It is also
 * a decision that would then need an authenticated way to change it, on a page that has no
 * sign-in, from a bundle any visitor can load. The whole of what is stored is one entry naming
 * one of three words, so a shared machine learns that someone here opened Daymark and picked a
 * shape — which the browser's own history already says more loudly.
 */
export const WHAT_IS_REMEMBERED =
  'The answer is kept in this browser only — one entry holding one of three words, no name, no ' +
  'date, nothing about your journal. Another browser, or another person’s profile on this ' +
  'machine, is asked the same question.'

/* ── The configured case ──────────────────────────────────────────────────────────────────── */

/**
 * WHY A SCREEN THAT SKIPS A QUESTION HAS TO SAY IT SKIPPED IT.
 *
 * A deployment that pins the shape in configuration should not be asked, and every part of that
 * is easy to get wrong in the same direction: the screen simply does not appear, the operator
 * never learns why, and the setting that decided it is invisible from the page it decided. So
 * the configured state is LOUDER than the asking state, not quieter — it names the endpoint, the
 * field, the value, and what to remove to be asked again.
 *
 * The disclosure question was checked rather than assumed. audience.ts's rule is that a screen
 * may only restate what the server already tells an unauthenticated caller; GET /v1/config is
 * unauthenticated and answers anyone who can reach the page, so a value published THERE is
 * already public and restating it discloses nothing new. A configuration subject the server keeps
 * to itself would be a different matter, and none is named here.
 */
export const CONFIG_PATH = '/v1/config'

/** The field this screen looks for beside whatever else that endpoint publishes. */
export const CONFIG_FIELD = 'setupMode'

/** The setting an operator would remove. Named exactly, because "your config" helps nobody. */
export const CONFIG_SETTING = 'DAYMARK_SETUP_MODE'

export function configuredStatement(shape: ShapeId): string {
  return (
    `Configuration answered this. ${CONFIG_PATH} publishes ${CONFIG_FIELD}: “${shape}”, so this ` +
    'page did not ask.'
  )
}

export function configuredHowToChange(): string {
  return (
    `To be asked again, remove ${CONFIG_SETTING} from the server’s environment and restart it. ` +
    'Nothing in this browser overrides it while it is set.'
  )
}

/**
 * PLACEHOLDER, and marked as one on the page.
 *
 * The server in this build publishes no setup mode. Adding the field is a one-line change to
 * ServerConfigDto in Application.kt and it was deliberately not made here — another agent owns
 * that file this week, and a merge conflict in the config endpoint is a worse outcome than a
 * screen that reads a field which is not there yet. The read path is written and live: the moment
 * the field exists, this screen stops asking, with no further change on this side.
 */
export const CONFIG_NOT_PUBLISHED_YET =
  `Placeholder: this server publishes no ${CONFIG_FIELD}, so this page asked instead. ` +
  `This screen reads ${CONFIG_PATH} for that field while this question is open, and will stop ` +
  'asking as soon as one is there. Nothing is being guessed in the meantime.'

/**
 * THE LIMIT ON THE PRECEDENCE ABOVE, SAID ON THE PAGE RATHER THAN ONLY IN A COMMENT.
 *
 * [resolveSetup] rule 1 says configuration outranks the browser, and it still does — every time
 * the page reads it. What changed is WHEN it reads it: only while the question is open. A page
 * that re-read configuration on every load would be reaching the server from the surface that
 * promises to reach none, which is the one thing the "Open a backup file" tab is not allowed to
 * do (see TrustBar.svelte, and lib/trust/posture.ts for the mapping that enforces it).
 *
 * That trade has a consequence for exactly one person — the operator who pins the shape AFTER
 * somebody has already answered in their browser — and it is invisible to them unless it is
 * stated. So it is stated, next to the answer it qualifies, rather than left to be discovered as
 * a setting that appeared not to work.
 */
export const CONFIGURATION_IS_NOT_RE_READ =
  `While this browser holds the answer, this page does not read ${CONFIG_PATH} on load: the ` +
  `question it would answer already has an answer here. A deployment that sets ${CONFIG_FIELD} ` +
  'afterwards takes effect in this browser the next time this question is reopened.'

/**
 * The other way configuration can answer: badly. A value that is not one of the three is not the
 * same as no value — somebody typed something, and silently falling back to asking would leave
 * them believing the machine was pinned. The unrecognised value is quoted back so the typo is
 * visible.
 */
export function configuredUnrecognised(value: string): string {
  return (
    `${CONFIG_PATH} published ${CONFIG_FIELD}: “${value}”, which is not one of the three shapes. ` +
    'The question below is being asked instead. Correct the setting or remove it.'
  )
}

/* ── The states this screen can be in for reasons other than the choice ───────────────────── */

/**
 * Storage refused the write. The person is NOT stopped: the shape holds for this visit and they
 * are told it will be asked again. Fail open, on exactly audience.ts's reasoning — nothing here
 * is a security control, so a browser that will not keep a note costs a repeated question and
 * must never cost someone access to their own machine.
 */
export const STORAGE_REFUSED =
  'This browser would not keep the answer, so this page will ask again next time. Everything ' +
  'still works this visit; private-browsing windows are the usual reason.'

/** The record could not be removed, so saying it was gone would be a claim that did not happen. */
export const FORGET_REFUSED =
  'This browser would not remove the stored answer, so it may come back on the next visit.'

/**
 * The gap between load and the configuration answer.
 *
 * A screen that asks a question configuration is about to answer, and then rearranges itself
 * under the reader, is worse than a moment of nothing. So the choices wait for the probe, and the
 * wait is labelled rather than left as a blank panel. It is bounded by the caller (see the
 * component), because an unreachable server must not hold this page open indefinitely — the
 * question is answerable with no server at all.
 */
export const CONFIG_READING = `Reading ${CONFIG_PATH} to see whether this deployment already answers this.`

/**
 * The recovery-link bypass. An emailed access-token recovery link lands on this page as `#t=…`,
 * and somebody following one at a bad moment must not meet a setup question first. The question
 * waits; it is not skipped, and the strip says so rather than leaving the machine looking set up.
 */
export const RECOVERY_LINK_BYPASS =
  'You arrived on a recovery link, so this page went straight there. It will ask what this ' +
  'machine is for another time.'

/* ── The practice panel ───────────────────────────────────────────────────────────────────── */

/**
 * WHY CHOOSING PRACTICE LANDS ON A PANEL RATHER THAN ON THE CONSOLE ITSELF.
 *
 * Administering a practice is a different job from reading your own journal, and this bundle
 * already splits surfaces on exactly that line: the clinician's portal and the server console are
 * separate documents, not tabs inside the owner's viewer. The practice console is the fourth, and
 * it is a separate page for the same reason.
 *
 * So what the owner's viewer can honestly offer, once someone says this machine belongs to a
 * clinic, is a signpost: what the server underneath actually has, where the administration
 * happens, and the two facts about the model somebody standing one up is least likely to know.
 *
 * WHAT THIS PANEL DOES NOT DO, AND SAYS IT DOES NOT DO. It reads nothing. No roster, no member, no
 * count, no date, no status — not because it is being careful with them, but because it makes no
 * request at all. Every one of those, invented, would be indistinguishable from a bug to the
 * person trying to work out whether this deployment works, which is the failure the whole product
 * is written against. It also makes no claim about how complete the console is: that page states
 * its own build state, and a second-hand assertion about somebody else's screen is exactly the
 * kind of claim that goes stale without anyone noticing.
 */
export const PRACTICE_SERVER_INTRO =
  'The server answers /v1/orgs today. These operations exist on it, and none of them is reachable ' +
  'from this page:'

export const PRACTICE_SERVER_HAS: readonly string[] = [
  'A roster of the practice’s members.',
  'Adding a member, and that member accepting.',
  'Changing a member’s role, and removing them.',
  'An audit trail of those changes.',
]

export const PRACTICE_MISSING =
  'Placeholder. This panel is not the practice console, and nothing on it is data: no roster has ' +
  'been read, no member is listed, and no number on this page came from your server. It exists so ' +
  'that choosing Practice lands somewhere that says what happens next.'

/**
 * The pointer, and the careful part of it is the last sentence. This page knows the console is a
 * separate document in this bundle — practice.html, alongside therapist.html and admin.html — and
 * it does not know, and must not imply, how much of that console is finished. Saying where it is
 * is a fact about this build; saying what it can do would be a claim about somebody else's screen.
 */
export const PRACTICE_CONSOLE_ELSEWHERE =
  'A practice is administered on its own page in this build, at practice.html — the same ' +
  'arrangement as the clinician’s portal and the server console. What that page covers, and which ' +
  'parts of it are still placeholders, is stated there rather than guessed at here.'

/**
 * The rule this whole layer hangs on, restated where somebody choosing Practice will read it,
 * because it is the one thing about the model that is counter-intuitive: administering a practice
 * is not the same as being able to read what is in it.
 */
export const PRACTICE_ROLE_NOTE =
  'Roles are presets over capabilities, and a role never carries a key. What a clinician can ' +
  'read comes only from a grant the person whose journal it is signed — administering a practice ' +
  'is not the same as being able to open what is in it. docs/COMPANION_ACCESS_CONTROL.md is ' +
  'authoritative here.'

/**
 * The unanswered question from §3.11.3, on the screen rather than in a plan document. It is the
 * gate on Practice being responsible to build at all, and someone standing one up should meet it
 * before they have staff depending on the answer.
 */
export const PRACTICE_OPEN_QUESTION =
  'One question is still open, and it is the one that decides whether a practice server can be ' +
  'run honestly: who can reset a forgotten passphrase? Today nobody can, which is what keeps the ' +
  'server unable to read anything. Every convenient answer to it means the practice can read the ' +
  'journals, and that has to be decided in the open rather than discovered later.'

/** Every heading, button and label the screen renders. Here so the copy tests reach all of it. */
export const LABELS = {
  /* The choices heading is CHOICE_HEADING; these are the controls and the small headings. */
  whatThisMeans: 'What this means',
  changeShape: 'Change what this machine is for',
  configured: 'Set by configuration',
  configuredBadValue: 'Configuration was not understood',
  /* The folded disclosure on the first-run screen. NOT 'Set by configuration': on this build it
     opens onto a note saying the server publishes no setup mode, and a summary claiming the
     opposite would be the one line on the screen that is untrue. */
  configurationSays: 'Whether configuration answers this',
  /* The two reasons somebody is being asked that are not "you have not been here before". The
     choices heading cannot double as this title — it appeared twice, one element apart. */
  whyAskingAgain: 'Why you are being asked',
  storageRefused: 'This browser would not keep the answer',
  /* A separate title from the one above: the two failures are opposites, and a shared heading
     would have the removal failure announce itself as a write failure. */
  forgetRefused: 'The stored answer is still there',
  reading: 'Reading configuration',
  stored: 'What this page remembers',
  /** The chip on a choice whose surface is elsewhere. Says the limit, not a severity. */
  separatePage: 'Not on this page',
  builtToday: 'Wired end to end',
  /** The chip on the panel itself, which is one. */
  placeholder: 'Placeholder',
  practiceSurface: 'The practice panel',
  practiceTitle: 'A clinic runs this machine',
  practiceWhatExists: 'What exists on the server',
  practiceWhatIsMissing: 'What this panel is',
  practiceWhereItHappens: 'Where a practice is administered',
  practiceOpenQuestion: 'The question this shape has not answered',
  /** The anchor out to the fourth page. Sibling-scoped, so it resolves under any base path. */
  practiceConsoleHref: './practice.html',
  openPractice: 'Open the practice console',
  /** Brings the panel back after a returning person has navigated away from it. */
  showPracticePanel: 'Show the practice panel',
  shapeStrip: 'This machine is set up as',
} as const

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   3. What the browser remembers.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

/**
 * One entry, one line, and the version is in the VALUE as well as the key.
 *
 * audience.ts stores a bare integer because a dismissal has nothing else to say. This has to hold
 * a choice, so the value is `<version>:<shape>` — and the version is what lets a build that
 * materially changes the three shapes ask once more instead of silently honouring an answer to a
 * question that no longer exists. Bump [SETUP_CHOICE_VERSION] when the set of shapes changes in a
 * way a returning person needs to see, never for a wording fix.
 *
 * NO TIMESTAMP, for the reason audience.ts gives: when someone last opened Daymark is information
 * about their care, and a routing note has no use for it.
 */
export const SETUP_STORAGE_KEY = 'daymark.setup.shape.v1'

export const SETUP_CHOICE_VERSION = 1

/** The slice of the Storage API this needs. Lets tests pass a plain object; Node has no DOM. */
export interface SetupStorage {
  getItem(key: string): string | null
  setItem(key: string, value: string): void
  removeItem?(key: string): void
}

/** localStorage, or null where it does not exist or the browser refuses to hand it over. */
export function defaultSetupStorage(): SetupStorage | null {
  try {
    return globalThis.localStorage ?? null
  } catch {
    // Access itself throws in some blocked or partitioned contexts, not merely returns undefined.
    return null
  }
}

export interface StoredChoice {
  version: number
  shape: ShapeId
}

/**
 * The stored answer, or null.
 *
 * Null covers every uncertainty — no storage, no entry, an entry this build did not write, a
 * getItem that threw — and all of them mean "ask". Strict about the shape for the reason
 * audience.ts is: a value this cannot read is a value it must not act on, and a lenient parse
 * would route someone's machine on the strength of a string nothing here produced. The shape
 * alternation is built from SHAPE_IDS so it cannot drift from the catalog above.
 */
export function readStoredChoice(
  storage: SetupStorage | null = defaultSetupStorage(),
): StoredChoice | null {
  if (!storage) return null
  let raw: string | null
  try {
    raw = storage.getItem(SETUP_STORAGE_KEY)
  } catch {
    return null
  }
  if (raw === null) return null
  const match = new RegExp(`^(\\d{1,9}):(${SHAPE_IDS.join('|')})$`).exec(raw.trim())
  if (!match) return null
  const version = Number(match[1])
  if (!Number.isSafeInteger(version)) return null
  return { version, shape: match[2] as ShapeId }
}

/**
 * Record the choice. Returns false when the browser would not keep it.
 *
 * A boolean rather than a throw, because the caller's honest response is a sentence
 * ([STORAGE_REFUSED]) and not an error state: the person answered, the page routes, and they are
 * told the question will be back. Quota and private-mode failures arrive as an exception from
 * setItem rather than as a return value, which is why the try wraps the write.
 */
export function rememberShape(
  storage: SetupStorage | null,
  shape: ShapeId,
  version: number = SETUP_CHOICE_VERSION,
): boolean {
  if (!storage) return false
  try {
    storage.setItem(SETUP_STORAGE_KEY, `${version}:${shape}`)
    return true
  } catch {
    return false
  }
}

/**
 * Erase the record, so "ask me again" leaves nothing behind.
 *
 * removeItem rather than writing a sentinel: an entry saying "none" is still an entry announcing
 * that this profile has opened Daymark. Returns false when the storage object cannot remove, so
 * the caller can say the record is still there rather than claiming a deletion that did not
 * happen.
 */
export function forgetShape(storage: SetupStorage | null = defaultSetupStorage()): boolean {
  if (!storage || typeof storage.removeItem !== 'function') return false
  try {
    storage.removeItem(SETUP_STORAGE_KEY)
    return true
  } catch {
    return false
  }
}

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   4. What configuration said.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

/**
 * The four things GET /v1/config's body can mean to this screen, plus the two the caller reports
 * about the request itself.
 *
 * `unrecognised` is separate from `absent` on purpose, and it is the whole reason this is a union
 * rather than `ShapeId | null`. An operator who sets the value to `SOLO`, or `single`, or leaves
 * a trailing space, has stated an intention; collapsing that into "no answer" would have the page
 * ask a question they believe they already answered, and neither the page nor the operator would
 * ever find out why.
 */
export type ConfigState =
  /**
   * Nothing has been read. Either the request is in flight, or no request was made because the
   * question already has an answer in this browser (see [shouldReadConfiguration]) — and the two
   * are the same thing to this screen, which must not ask in either case. Only the ASK waits on
   * this; a stored answer resolves past it, which is rule 4 of [resolveSetup].
   */
  | { kind: 'reading' }
  /** No answer at all — nothing there, wrong shape, or the request never landed. */
  | { kind: 'unreachable' }
  /** Answered, and carries no setup mode. The expected state on this build; see the placeholder. */
  | { kind: 'absent' }
  /** Answered with something that is not one of the three. */
  | { kind: 'unrecognised'; value: string }
  /** Answered, and pinned the shape. */
  | { kind: 'set'; shape: ShapeId }

/**
 * Read the body of GET /v1/config.
 *
 * Exact match against the three ids: no trimming of the value, no case folding, no aliases. A
 * configuration value is a machine-readable contract, and quietly accepting `Solo ` teaches an
 * operator a spelling the server may not accept tomorrow. A value that is present and wrong comes
 * back as `unrecognised` so the screen can say so.
 */
export function readSetupMode(body: string | null): ConfigState {
  if (body === null) return { kind: 'unreachable' }
  let parsed: unknown
  try {
    parsed = JSON.parse(body)
  } catch {
    /* An unparseable body is an unrecognised answer, not an absent field. */
    return { kind: 'unreachable' }
  }
  /* An array is JSON and an object to `typeof`, and is not the documented body shape. Rejecting
     it here rather than reading index `setupMode` off it keeps "answered in its documented shape"
     the only path to `absent`. */
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return { kind: 'unreachable' }
  const value = (parsed as Record<string, unknown>)[CONFIG_FIELD]
  if (value === undefined || value === null) return { kind: 'absent' }
  if (typeof value !== 'string') return { kind: 'unrecognised', value: String(value) }
  if (!isShapeId(value)) return { kind: 'unrecognised', value }
  return { kind: 'set', shape: value }
}

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   5. Which of the two states the screen is in.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

/**
 * Why the question is being asked. Not decoration: a person who answered this last week and is
 * being asked again is owed the reason, and "the choices changed" is a different sentence from
 * "you have not been here before".
 */
export type AskReason =
  /** Nothing stored, or nothing readable. The first-run case. */
  | 'not-yet-set-up'
  /** A stored answer from a build whose three shapes were not these three. */
  | 'choices-changed'
  /** Configuration named something that is not one of the three. */
  | 'configuration-not-understood'

export const ASK_REASON_NOTE: Record<AskReason, string | null> = {
  /* The ordinary case says nothing extra: the lede above it is already the explanation. */
  'not-yet-set-up': null,
  'choices-changed':
    'This was answered before, on a build whose choices were not these. The answer is being ' +
    'asked once more rather than assumed.',
  'configuration-not-understood':
    'Configuration named a shape this build does not have, so the question is being asked here.',
}

/** How the current shape came to be the current shape. Drives what the strip is allowed to offer. */
export type ChoiceOrigin =
  /** Chosen on this screen, this visit. */
  | 'this-visit'
  /** Read back out of this browser. */
  | 'this-browser'

export type SetupDecision =
  /** Configuration has not answered yet. Ask nothing; say why the choices are not here. */
  | { state: 'reading' }
  /** Not yet set up. Explain, then ask. */
  | { state: 'ask'; reason: AskReason }
  /** Set up here. Get out of the way; the strip states the shape and can change it. */
  | { state: 'chosen'; shape: ShapeId; origin: ChoiceOrigin }
  /** Set up by the deployment. Get out of the way; the strip names the setting and cannot change it. */
  | { state: 'configured'; shape: ShapeId }

export interface SetupInputs {
  /** What GET /v1/config said, or that it has not said anything yet. */
  config: ConfigState
  /** A choice made on this screen during this visit, whether or not storage kept it. */
  session: ShapeId | null
  /** What this browser had recorded. */
  stored: StoredChoice | null
  /** The version this build's three shapes are. Injectable so the re-ask rule is testable. */
  version?: number
}

/**
 * THE ORDER OF PRECEDENCE, WHICH IS THE WHOLE OF THIS FUNCTION.
 *
 *   1. CONFIGURATION WINS, WHENEVER IT IS READ. A deployment that pins the shape has said what
 *      this machine is, and a per-browser answer must not quietly override the operator. It beats
 *      a stored answer and it beats a choice made ten seconds ago on this screen — which is why
 *      the strip does not offer to change it, and says what to remove instead.
 *
 *      WHENEVER IT IS READ is not a hedge, it is the scope. This function is handed whatever the
 *      caller last read, and the caller reads configuration only while the question is open —
 *      [shouldReadConfiguration] below is that rule, and CONFIGURATION_IS_NOT_RE_READ is the
 *      sentence the page shows about it. A settled browser therefore keeps its answer until the
 *      question is reopened, which is the price of the offline surface not calling out on every
 *      load. Ranking configuration first here is what makes the reopened question honour it.
 *
 *   2. THIS VISIT BEATS THIS BROWSER. Someone who just changed the answer sees the new one, even
 *      when the browser refused to keep it. That is the fail-open half: storage failing costs a
 *      repeated question next time and never a wrong screen now.
 *
 *   3. A STORED ANSWER FROM AN OLDER SET OF CHOICES IS NOT AN ANSWER to this build's question.
 *      `>=` rather than `===`, on audience.ts's reasoning: a version AHEAD of this build's means
 *      the person answered a superset, and re-asking would be a regression dressed as caution.
 *
 *   4. STILL READING MEANS DO NOT ASK — and note where this sits, because it moved. It was above
 *      the two local answers, which meant every returning person met a "reading configuration"
 *      panel where their own surface should have been, on every load, for as long as the probe
 *      took. That is the exact failure the second half of this screen's design exists to prevent:
 *      an answered question must not cost anybody a screen. What waiting on the probe protects is
 *      only the ASKING — putting a question on screen that configuration is about to answer, and
 *      then rearranging the page under the reader — so it now guards nothing but the ask. An
 *      unreachable server resolves to `unreachable`, never to a permanent wait, and the caller
 *      bounds the probe as well (see App.svelte).
 */
export function resolveSetup({
  config,
  session,
  stored,
  version = SETUP_CHOICE_VERSION,
}: SetupInputs): SetupDecision {
  if (config.kind === 'set') return { state: 'configured', shape: config.shape }
  if (session !== null) return { state: 'chosen', shape: session, origin: 'this-visit' }
  if (stored && stored.version >= version) {
    return { state: 'chosen', shape: stored.shape, origin: 'this-browser' }
  }
  if (config.kind === 'reading') return { state: 'reading' }
  if (config.kind === 'unrecognised') {
    return { state: 'ask', reason: 'configuration-not-understood' }
  }
  return { state: 'ask', reason: stored ? 'choices-changed' : 'not-yet-set-up' }
}

/**
 * WHETHER THE PAGE READS CONFIGURATION AT ALL ON THIS LOAD.
 *
 * ─────────────────────────────────────────────────────────────────────────────────────────────
 * WHY THIS IS NOT SIMPLY "ALWAYS"
 * ─────────────────────────────────────────────────────────────────────────────────────────────
 *
 * It was. The probe was unconditional, which meant index.html made a request to the server every
 * single time it opened — including the loads where the person went straight to "Open a backup
 * file", the tab whose whole promise is that it reads a file in the browser and sends nothing.
 * A server that is contacted on every load learns the address and the hour of every time the
 * offline viewer was opened, which is precisely the metadata the sync posture treats as worth
 * disclosing ("It can still see that you synced, and when"). Two sentences one viewport apart —
 * the strip saying nothing leaves, the setup panel saying it is reading /v1/config — cannot
 * both be true, and the one that had to give was the probe, not the promise.
 *
 * ─────────────────────────────────────────────────────────────────────────────────────────────
 * THE RULE, AND WHY IT IS THE MIRROR OF [resolveSetup] RATHER THAN A SECOND OPINION
 * ─────────────────────────────────────────────────────────────────────────────────────────────
 *
 * The probe exists to answer one question, so it runs exactly while that question is open. Open
 * means no local answer resolves — no choice this visit, and nothing current in this browser —
 * which is rules 2 and 3 of [resolveSetup] read backwards. Stating it as its own function rather
 * than deriving it from a `SetupDecision` keeps it independent of what configuration last said,
 * which matters because the caller writes that state: a predicate that read `config` would have
 * the effect that sets it re-run on its own write.
 *
 * The two must not drift, and they cannot silently: shape.test.ts asserts the equivalence over
 * every combination of inputs — this returns true exactly when a config-free resolve would `ask`.
 *
 * FAIL OPEN, as everywhere on this screen: unreadable storage returns `null` from
 * [readStoredChoice], which lands here as "no answer", which asks. A browser that will not keep a
 * note costs a repeated question and one request, never access to somebody's own machine.
 */
export function shouldReadConfiguration({
  session,
  stored,
  version = SETUP_CHOICE_VERSION,
}: Omit<SetupInputs, 'config'>): boolean {
  if (session !== null) return false
  if (stored && stored.version >= version) return false
  return true
}

/**
 * Whether this decision may be changed from the page.
 *
 * Only the browser's own answer can be. A configured deployment is changed where it was set, and
 * offering a button that appeared to change it — and then did not, because the next load reads
 * the same flag — would be the worst of both.
 */
export function isChangeable(decision: SetupDecision): boolean {
  return decision.state === 'chosen'
}

/** The shape a decision names, or null while there is not one. Saves every caller a switch. */
export function decidedShape(decision: SetupDecision): ShapeId | null {
  return decision.state === 'chosen' || decision.state === 'configured' ? decision.shape : null
}
