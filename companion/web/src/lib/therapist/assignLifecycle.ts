/*
 * ASSIGNMENT LIFECYCLE, PREVIEWED IN THE COMPOSER.
 *
 * ─────────────────────────────────────────────────────────────────────────────────────────────
 * WHAT THIS IS FOR. A therapist composing an assignment is deciding what to send. What they are
 * not deciding — because today they cannot see it — is what the thing they send will DO: which
 * states it can move through, what the person on the other end may do to it, what happens if it
 * is never opened, and, above all, what will and will not come back. All of that is currently
 * discovered after publishing, which is how someone assigns a thing believing it behaves
 * differently than it does.
 *
 * This module answers those questions from the draft, before the step-up confirm. It renders
 * nothing and it publishes nothing: {@link buildLifecyclePreview} is a pure function of a draft
 * and a verified grant, and AssignSurface.svelte is a projector for it. The publish path in
 * assignClient.ts is untouched and must stay untouched — it is the security-relevant one.
 *
 * ─────────────────────────────────────────────────────────────────────────────────────────────
 * THE RULE THAT SHAPED EVERY SENTENCE IN HERE: NEVER CLAIM A SIGNAL THE PRODUCT DOES NOT SEND.
 *
 * A lifecycle preview is an unusually easy place to lie, because the states read like a progress
 * bar and a progress bar implies someone is watching it. In this build almost nobody is. Walked
 * end to end, this is what the tree actually supports:
 *
 *   · The therapist seals an Assignment and PUTs it on the `assignments` relationship channel
 *     (assignClient.publishAssignment). The return value is `{ ok, version }` — the outcome of a
 *     write, and nothing about a person.
 *   · The owner's device fetches the blob, opens it against the PINNED therapist key, and runs
 *     validateAssignment against the grant AS IT IS AT THAT MOMENT (assignments/inbox.ts). A
 *     capability withdrawn between publishing and fetching means the item arrives REJECTED.
 *   · The owner accepts / declines / snoozes. `Decision` (assignments/inbox.ts) is held in the
 *     owner console's own component state. There is no channel that carries it back, no writer
 *     for such a channel, and PortalClient exposes no read the therapist could make for it.
 *   · Results reach the therapist through ONE route and only one: a curated share the owner
 *     builds, opts record types into, date-bounds, redacts and seals (assignments/share.ts →
 *     share/sharecrypto.ts). A ShareBundle carries checkIns, moods, journal and sleep. It has no
 *     slot for a task result, a goal, a reminder or a setting, and no field anywhere that
 *     references an assignment.
 *
 * So: for a self-check, a completed run MAY become visible, as a dated run with a band, if the
 * owner later chooses to share check-ins over that range — and it arrives unattributed, because
 * `ShareBundle.checkIns[]` is `{ instrumentId, at, score, band }` and carries no assignment id. A
 * run in a share may be one the person took entirely on their own. For a task, a goal, a reminder
 * or a setting, nothing comes back at all, ever, through the product.
 *
 * `AssignmentStatus` therefore describes what happens to the assignment ON THE PERSON'S DEVICE.
 * It is not a feed. Every step below states its `told` and `notTold` explicitly rather than
 * leaving the reader to assume that a named state is a reported one, and for six of the seven
 * states `told` is empty. That emptiness is the finding, not a gap in this file.
 *
 * ─────────────────────────────────────────────────────────────────────────────────────────────
 * "NOT OPENED" IS NOT A FAILURE. `noResponse` is the state where an assignment is simply never
 * opened. It is written here as a fact about the assignment — nothing expires, nothing retries,
 * nothing escalates, nothing marks anyone — and never as non-compliance, a lapse, or a thing the
 * person did wrong. Silence is also not refusal: `noResponse` and `declined` are separate states
 * (components/ui/status.ts says so) and separate steps here, because reporting a decision the
 * person never made would be a lie about their behaviour.
 *
 * NO FIGURES. No score, no percentage, no streak, no adherence number, no count-against-target,
 * in any string this module produces. assignLifecycle.test.ts pins that with a detector that is
 * first proved to fire on real examples.
 *
 * ─────────────────────────────────────────────────────────────────────────────────────────────
 * WHAT IS BORROWED RATHER THAN RESTATED, AND WHY.
 *
 *   · STATUS_LABEL (components/ui/status.ts) — the one spelling of each state. A second copy
 *     would drift into "No reply" beside StatusPill's "No response" and a reader would
 *     reasonably conclude they were two different things. The label is carried on the step so a
 *     renderer never spells a state itself. Imported from the plain .ts, never from the ui
 *     barrel, so this module stays free of the component graph.
 *   · describeCadence (assignments/describe.ts) — the cadence phrasing the owner's own preview
 *     uses.
 *   · describeCapability (assignments/describe.ts) — the capability's title, as the grant
 *     manager writes it.
 *   · shouldAutoApply (assignments/validate.ts) — whether an accepted-by-default capability
 *     really applies without the owner. `suggest.setting` is never auto, and that is a
 *     security-relevant decision that must have exactly one implementation. See {@link autoApplies}.
 *
 * NO SVELTE, NO DOM, NO CLOCK. Nothing here reads Date.now(): a preview of a lifecycle has no
 * instant in it, and a `$derived` in Svelte 5 cannot see a clock read inside a callee anyway —
 * the bug that shipped in TherapistPortal.svelte. The test suite pins it by stubbing Date.now()
 * to throw.
 */

import type {
  ApplyMode,
  Assignment,
  AssignmentPayload,
  AssignmentType,
  Cadence,
  Capability,
  Grant,
} from '../assignments/types'
import { TYPE_CAPABILITY } from '../assignments/types'
import { shouldAutoApply } from '../assignments/validate'
import { describeCadence, describeCapability } from '../assignments/describe'
import { STATUS_LABEL, type AssignmentStatus } from '../components/ui/status'

/* ── Inputs ─────────────────────────────────────────────────────────────────────────────── */

/**
 * The draft as the composer holds it, plus the grant it will be checked against.
 *
 * `payload` is optional because a composer has a type selected before it has a complete payload,
 * and the lifecycle is worth showing from the moment the type is chosen. It is read for exactly
 * one purpose — deciding whether a `largeAssessment` bundle contains self-checks, tasks or both,
 * which is the only case where the payload changes what can come back. Everything else is
 * decided by the type and the grant.
 */
export type LifecycleDraft = {
  type: AssignmentType
  /** The VERIFIED local grant (therapist/grant.ts). A UX pre-flight; the owner's device gates. */
  grant: Grant
  cadence?: Cadence
  payload?: AssignmentPayload
}

/* ── Outputs ────────────────────────────────────────────────────────────────────────────── */

/**
 * Where a step sits in the shape of the lifecycle.
 *
 * `spine` is the path an assignment takes when nothing interrupts it; `branch` is a place it can
 * come to rest instead. The distinction is for the renderer's benefit only — it is a shape, not
 * a ranking, and specifically `declined` and `noResponse` being branches does not make them
 * deviations from a correct path. There is no correct path.
 */
export type StepShape = 'spine' | 'branch'

export type LifecycleStep = {
  status: AssignmentStatus
  /** `STATUS_LABEL[status]`, carried so a renderer never spells a lifecycle state itself. */
  label: string
  /** What happens at this step. Premade prose; a renderer prints it and does not compose it. */
  what: string
  /**
   * What the therapist IS told, here, by the product. EMPTY IS THE COMMON CASE and it is the
   * honest one — see the header. A renderer must print an explicit "nothing" for an empty list
   * rather than leaving the row blank, because a blank row reads as "not written yet".
   */
  told: string[]
  /** What the therapist is NOT told. Never empty at any step. */
  notTold: string[]
  /** What the person on the other end can do to the assignment here. Never empty. */
  ownerCan: string[]
  shape: StepShape
  /**
   * False when the draft cannot reach this step at all — today, only when the capability is not
   * granted, in which case the item is refused on arrival and nothing past `delivered` happens.
   * A preview that described acceptance of an assignment that will be refused would be the exact
   * over-claim this module exists to prevent.
   */
  reachable: boolean
}

/** What can ever come back to the therapist about this draft, once it is done. */
export type FeedbackKind =
  /** No `read.share`. Nothing of any kind reaches this portal, so nothing about this can. */
  | 'noShareGranted'
  /** `read.share` is granted, but a curated share has no slot this type could occupy. */
  | 'nothingShareable'
  /** Completed runs of this self-check may appear in a curated share — unattributed. */
  | 'sharedRuns'
  /** A bundle: its self-checks may appear in a curated share; its tasks cannot. */
  | 'sharedRunsPartial'

export type LifecyclePreview = {
  /** In the vocabulary's own order (components/ui/status.ts). Every state, exactly once. */
  steps: LifecycleStep[]
  capability: Capability
  /** Whether the owner currently grants the capability this type requires. */
  granted: boolean
  /** The grant's apply mode for that capability; null when it is not granted. */
  applyMode: ApplyMode | null
  /** Whether the owner must accept before anything applies. `suggest.setting` always must. */
  requiresAccept: boolean
  feedback: FeedbackKind
  /** One premade sentence stating what this therapist will learn overall. Always present. */
  feedbackNote: string
  /**
   * A premade sentence about the draft as a whole, or null when there is nothing to say beyond
   * the steps. Today it is set only for the ungranted case, which is the one situation where the
   * steps below describe a path the draft cannot take.
   */
  note: string | null
}

/* ── The vocabulary's own order ─────────────────────────────────────────────────────────── */

/**
 * Reading order: the spine first, then the places an assignment can come to rest, matching the
 * declaration order of `AssignmentStatus` so the composer and the pill enumerate one list.
 * Exported so the test can assert coverage against the vocabulary rather than against this array.
 */
export const LIFECYCLE_ORDER: AssignmentStatus[] = [
  'scheduled',
  'delivered',
  'accepted',
  'completed',
  'declined',
  'noResponse',
  'overdue',
]

const SHAPE: Record<AssignmentStatus, StepShape> = {
  scheduled: 'spine',
  delivered: 'spine',
  accepted: 'spine',
  completed: 'spine',
  declined: 'branch',
  noResponse: 'branch',
  overdue: 'branch',
}

/* ── Derivation ─────────────────────────────────────────────────────────────────────────── */

/** Everything the lifecycle panel renders, from one draft. */
export function buildLifecyclePreview(draft: LifecycleDraft): LifecyclePreview {
  const capability = TYPE_CAPABILITY[draft.type]
  const cap = draft.grant.capabilities[capability]
  const granted = cap?.granted === true
  const applyMode: ApplyMode | null = granted ? cap!.apply : null
  const auto = applyMode !== null && autoApplies(capability, applyMode)
  const feedback = feedbackFor(draft)

  const steps = LIFECYCLE_ORDER.map((status) => ({
    ...stepFor(status, draft, { granted, auto, feedback }),
    status,
    label: STATUS_LABEL[status],
    shape: SHAPE[status],
    // Nothing past delivery can happen to an item the device refuses on arrival.
    reachable: granted || status === 'scheduled' || status === 'delivered',
  }))

  return {
    steps,
    capability,
    granted,
    applyMode,
    requiresAccept: !auto,
    feedback,
    feedbackNote: FEEDBACK_NOTE[feedback],
    note: granted
      ? null
      : `You do not currently hold “${describeCapability(capability).title}”. Their device checks ` +
        `the permission when it opens this, so the assignment would arrive refused and nothing ` +
        `after delivery would happen. The grant is theirs to change, and only they can change it.`,
  }
}

/**
 * Whether an accepted capability really applies without the owner.
 *
 * Delegates to validate.ts rather than restating the rule, because the rule is security-relevant
 * — `suggest.setting` is NEVER auto, whatever the grant says — and a second implementation of it
 * is a second thing to get wrong. `shouldAutoApply` reads only `capability`, so a stub carrying
 * that one field answers correctly; the cast is confined to this line and the test pins the
 * result against the real function called on a fully-built Assignment.
 */
function autoApplies(capability: Capability, mode: ApplyMode): boolean {
  return shouldAutoApply({ capability } as Assignment, mode)
}

/* ── What can come back ─────────────────────────────────────────────────────────────────── */

/**
 * The one route back is a curated share, and a ShareBundle has four slots: checkIns, moods,
 * journal, sleep. Only a self-check run can land in one of them. Everything else this composer
 * can assign — a task, a goal, a reminder, a setting — has nowhere to go, and that is a fact
 * about the wire type rather than a feature nobody has built yet.
 */
function feedbackFor(draft: LifecycleDraft): FeedbackKind {
  if (draft.grant.capabilities['read.share']?.granted !== true) return 'noShareGranted'
  if (draft.type === 'questionnaire') return 'sharedRuns'
  if (draft.type !== 'largeAssessment') return 'nothingShareable'

  // A bundle: read the payload where the composer has one. With no payload yet the partial
  // wording is used, which is true of every bundle — a bundle's tasks can never come back.
  const bundle = (draft.payload as { bundle?: Array<{ kind: string }> } | undefined)?.bundle
  if (!Array.isArray(bundle) || bundle.length === 0) return 'sharedRunsPartial'
  const hasQuestionnaire = bundle.some((it) => it.kind === 'questionnaire')
  const hasTask = bundle.some((it) => it.kind === 'task')
  if (hasQuestionnaire && !hasTask) return 'sharedRuns'
  if (!hasQuestionnaire) return 'nothingShareable'
  return 'sharedRunsPartial'
}

const FEEDBACK_NOTE: Record<FeedbackKind, string> = {
  noShareGranted:
    'Nothing about this assignment will come back to you. You do not hold “View shared data”, so ' +
    'no record of any kind reaches this portal — what happened to it is something to ask about.',
  nothingShareable:
    'Nothing about this assignment will come back to you. A curated share carries check-ins, ' +
    'moods, journal entries and sleep; there is no slot in it that this could occupy — what ' +
    'happened to it is something to ask about.',
  sharedRuns:
    'The only thing that can come back is a dated run of this self-check, and only if they later ' +
    'build a share that includes check-ins and covers that date. It arrives with no reference to ' +
    'this assignment, so a run you see may be one they took on their own.',
  sharedRunsPartial:
    'The only thing that can come back is the self-check part, as dated runs, and only if they ' +
    'later build a share that includes check-ins and covers those dates. The tasks in this bundle ' +
    'cannot come back at all, and a run you see carries no reference to this assignment.',
}

/* ── The steps ──────────────────────────────────────────────────────────────────────────── */

type StepContext = { granted: boolean; auto: boolean; feedback: FeedbackKind }

type StepBody = Pick<LifecycleStep, 'what' | 'told' | 'notTold' | 'ownerCan'>

function stepFor(status: AssignmentStatus, draft: LifecycleDraft, ctx: StepContext): StepBody {
  switch (status) {
    case 'scheduled':
      return scheduledStep(draft)
    case 'delivered':
      return deliveredStep(ctx)
    case 'accepted':
      return acceptedStep(draft, ctx)
    case 'completed':
      return completedStep(draft, ctx)
    case 'declined':
      return declinedStep()
    case 'noResponse':
      return noResponseStep()
    case 'overdue':
      return overdueStep()
  }
}

function scheduledStep(draft: LifecycleDraft): StepBody {
  const cadence = draft.cadence
    ? ` A cadence of ${describeCadence(draft.cadence)} means the same item recurs, and each ` +
      `occurrence begins again here.`
    : ''
  return {
    what:
      `Published and waiting. The assignment is signed by you, sealed to this person's key, and ` +
      `sits on the relationship channel as a blob the server cannot read. Nothing reaches them ` +
      `until their device fetches it, and their device decides when that is.${cadence} Publishing ` +
      `again on the same lineage writes a new version and the older one stops being the current ` +
      `one; nothing is edited in place and nothing already sent can be taken back.`,
    told: ['Whether the publish itself went through, and the version number it was written at.'],
    notTold: [
      'Whether their device has fetched it. There is no read receipt on this channel.',
      'When it will be fetched. This portal cannot reach their device — the device pulls, on its own schedule.',
    ],
    ownerCan: ['Nothing yet. It is not on their device.'],
  }
}

function deliveredStep(ctx: StepContext): StepBody {
  const arrival = ctx.granted
    ? `checks it against the permissions they grant you AT THAT MOMENT — not the permissions you ` +
      `had when you published. If they withdraw this one in between, it arrives refused and can ` +
      `never be applied.`
    : `checks it against the permissions they grant you, and refuses it, because this capability ` +
      `is not among them. A refused item can never be applied.`
  return {
    what:
      `Their device has fetched the blob, opened it, verified the signature against the key they ` +
      `pinned for you, and ${arrival}`,
    told: [],
    notTold: [
      'That it arrived. Delivery is not acknowledged back to this portal in any form.',
      'That it was refused, if the permission changed. The refusal is applied on their device and is not reported back.',
    ],
    ownerCan: ['Open it.', 'Leave it unopened for as long as they like.'],
  }
}

function acceptedStep(draft: LifecycleDraft, ctx: StepContext): StepBody {
  const setting =
    draft.type === 'setting'
      ? ` A suggested setting always waits for them, even where the permission is marked to apply ` +
        `automatically — that is fixed and you cannot assign around it.`
      : ''
  const what = ctx.auto
    ? `They set this permission to apply automatically, so it applies on their device without a ` +
      `further step from them. That setting is theirs, it was not asked for by you, and they can ` +
      `withdraw it at any time.${setting}`
    : `It waits in their inbox until they decide. Nothing applies on their device before that, ` +
      `there is no time limit on deciding, and not deciding is one of the options.${setting}`
  return {
    what,
    told: [],
    notTold: [
      'Whether they accepted. The decision is recorded on their device and there is no channel that carries it back here.',
      'When they accepted, if they did.',
    ],
    ownerCan: ctx.auto
      ? ['Withdraw the permission that lets this apply automatically, at any time, without telling you.']
      : ['Accept it.', 'Decline it.', 'Snooze it.', 'Leave it and never decide.'],
  }
}

/** What "doing it" means on the person's device, per type. Mechanical; never clinical. */
const DOING: Record<AssignmentType, string> = {
  questionnaire:
    'They take the self-check on their own device. It is recorded there alongside every other ' +
    'run they have made, and nothing on their device marks it as yours.',
  task: 'They do the task on their own device. The result is recorded there.',
  largeAssessment:
    'They work through the bundle on their own device, in whatever order and over however long ' +
    'they like. Parts of it can be done and the rest left.',
  reminder:
    'A reminder has nothing to finish. It either sits among their reminder settings or it does not.',
  goal: 'The goal joins the goals they are already tracking, alongside the ones they set themselves.',
  setting: 'The setting takes effect on their device, if they accepted it.',
}

/** The noun used when saying a curated share has no slot for this. */
const NO_SLOT_NOUN: Record<AssignmentType, string> = {
  questionnaire: 'self-check run',
  task: 'task result',
  largeAssessment: 'task result',
  reminder: 'reminder',
  goal: 'goal',
  setting: 'setting',
}

function completedStep(draft: LifecycleDraft, ctx: StepContext): StepBody {
  const told: string[] = []
  const notTold: string[] = []

  if (ctx.feedback === 'sharedRuns' || ctx.feedback === 'sharedRunsPartial') {
    const plural = ctx.feedback === 'sharedRunsPartial'
    told.push(
      plural
        ? 'If they later build a share that includes check-ins and covers those dates, the ' +
          'self-checks in this bundle appear in it as dated runs, each with the band it recorded.'
        : 'If they later build a share that includes check-ins and covers that date, a run of ' +
          'this self-check appears in it as a dated run with the band it recorded.',
    )
    notTold.push(
      'That the run came from this assignment. A shared run carries the instrument, the date and ' +
        'the band, and no assignment reference at all — a run you see may be one they took on their own.',
      'Anything unless they build the share. Whether one is built, what range it covers, which ' +
        'record types it carries and which individual records are removed first are theirs to ' +
        'choose, every time.',
      'Anything once a share expires. The portal refuses to open an expired share, and what you ' +
        'read in it is no longer readable here.',
    )
    if (plural) {
      notTold.push(
        'Anything about the tasks in this bundle. A curated share carries check-ins, moods, ' +
          'journal entries and sleep, and has no slot a task result could go in.',
      )
    }
  } else if (ctx.feedback === 'nothingShareable') {
    notTold.push(
      `Anything. A curated share carries check-ins, moods, journal entries and sleep; there is no ` +
        `slot a ${NO_SLOT_NOUN[draft.type]} could go in, so nothing about this can reach you ` +
        `through the product.`,
      'Whether it was done at all. If that matters, it is a conversation with them, not a lookup.',
    )
  } else {
    notTold.push(
      'Anything. You do not hold “View shared data”, so no record of any kind reaches this portal.',
      'Whether it was done at all. If that matters, it is a conversation with them, not a lookup.',
    )
  }

  const sharing = ctx.feedback === 'noShareGranted'
  return {
    what: DOING[draft.type],
    told,
    notTold,
    ownerCan: sharing
      ? ['Nothing here reaches you, so there is nothing for them to curate.']
      : [
          'Decide whether a share is built at all.',
          'Choose its date range and which record types it carries.',
          'Remove individual records from it before it is sealed.',
          'Let a share they already sent expire.',
        ],
  }
}

function declinedStep(): StepBody {
  return {
    what:
      `They can refuse it. Refusal is a decision they are entitled to make, it needs no reason, ` +
      `and it applies nothing on their device. It is a different thing from silence, and the ` +
      `product keeps the two apart.`,
    told: [],
    notTold: [
      'That they declined. The decision is recorded on their device and there is no channel that carries it back here.',
      'Why. No reason is collected from them, and none would be sent if it were.',
    ],
    ownerCan: ['Decline without giving a reason.', 'Decline one version and accept a later one.'],
  }
}

function noResponseStep(): StepBody {
  return {
    what:
      `The assignment may simply never be opened, and this is where it rests if so. Nothing ` +
      `expires it, nothing retries it, nothing escalates, and nothing on their device marks them. ` +
      `Not opened is a fact about the assignment and not about the person: the reasons are ` +
      `theirs, they are usually ordinary, and they are never owed to you.`,
    told: [],
    notTold: [
      'That it has not been opened. No lifecycle state is reported back to this portal in this build.',
      'Anything to act on here. If it matters, it is a conversation, not a signal.',
    ],
    ownerCan: ['Leave it, indefinitely.', 'Open it much later and act on it then.'],
  }
}

function overdueStep(): StepBody {
  return {
    what:
      `A due date passing changes nothing on their device: nothing is removed, nothing is ` +
      `withdrawn, and the item can still be opened and done afterwards. Being past a date is a ` +
      `property of the date.`,
    told: [],
    notTold: [
      'That the date passed. This portal is sent no lifecycle state, so a due date is yours to keep track of.',
      'Whether it was done late. A run arriving in a later share carries its own date and no relation to this assignment.',
    ],
    ownerCan: ['Do it after the date.', 'Ignore the date entirely.'],
  }
}

/* ── For renderers and tests ────────────────────────────────────────────────────────────── */

/**
 * Every sentence in a preview, flattened.
 *
 * Exported because the guard that matters most about this module — that no string it produces
 * carries a figure or a verdict — has to be run over ALL of its prose, and a test that walks the
 * shape by hand goes quietly blind the day a field is added. One collector, used by the suite,
 * so a new field is covered by the existing assertions the moment it exists.
 */
export function lifecycleProse(preview: LifecyclePreview): string[] {
  const out: string[] = [preview.feedbackNote]
  if (preview.note) out.push(preview.note)
  for (const step of preview.steps) {
    out.push(step.label, step.what, ...step.told, ...step.notTold, ...step.ownerCan)
  }
  return out
}
