/*
 * The companion signal vocabulary — closed, typed, and partitioned by author.
 *
 * Two documents meet in this file.
 *
 * 1. docs/COMPANION_DIALOGUE.md, "The signal vocabulary". These eight facts are the whole
 *    substrate a dialogue may branch on. The list is CLOSED on purpose: every signal is a
 *    coupling point, and the closed list is what keeps the companion from becoming the beast
 *    DECISIONS_2026-08.md §D1 warns about. Adding a ninth should require someone to make a
 *    case — the test file asserts the count so that argument cannot be skipped.
 *
 * 2. docs/COMPANION_DIALOGUE.md, Finding 3 — the branch-shaped side channel. Predicates
 *    evaluate on the person's device against private data, and the therapist never sees the
 *    result. But the arms of a branch differ in ways a therapist CAN observe (a module
 *    delivered, accepted, completed), so an authored branch is an oracle for whatever it
 *    tested. The rule that closes it:
 *
 *      > A predicate may only reference signals its author is already permitted to see.
 *
 *    Hence `authors` on every spec. Therapist-authored dialogue may branch on
 *    `prescribedModules` (they issued them) and `timeOfDay` (carries nothing private).
 *    Everything else is app-only, and `hasSafetyPlan` is additionally never-disclosable.
 *
 * Nothing here infers anything about a person. `hardDaysLast7` is a count of what the person
 * logged, not a judgement about them (§D1b, reflect-never-label).
 *
 * This module is pure data plus three pure functions: no I/O, no clock, no device state. It
 * describes the vocabulary; computing the values is the host's job.
 */

/** Who may author a dialogue definition, and therefore who may branch on a signal. */
export type SignalAuthor = 'app' | 'therapist'

/** Shape of the value the host supplies for a signal. */
export type SignalType = 'int' | 'bool' | 'enum' | 'stringArray'

/**
 * The closed vocabulary. Exactly eight. A ninth is a design decision, not a patch.
 */
export type SignalName =
  | 'daysSinceLastOpen'
  | 'daysSinceLastCheckIn'
  | 'checkInsLast7'
  | 'hardDaysLast7'
  | 'hasSafetyPlan'
  | 'prescribedModules'
  | 'lastOfferOutcome'
  | 'timeOfDay'

export interface SignalSpec {
  /** Redundant with the key in SIGNALS, kept so a spec passed around alone still names itself. */
  name: SignalName
  type: SignalType
  /** What the number/flag actually counts. Plain enough to show in an authoring UI. */
  description: string
  /** Who may BRANCH on it. 'app' is always present; 'therapist' only where Finding 3 permits. */
  authors: readonly SignalAuthor[]
  /**
   * Why a therapist may not branch on it, phrased as the consequence rather than the rule —
   * this text is read by a clinician in the authoring UI, so it has to explain, not scold.
   * Present exactly on the signals that are app-only.
   */
  whyAppOnly?: string
  /**
   * Never disclosed to a clinician by any route, including inference from a branch. Stronger
   * than app-only: app-only is a partition, this is a prohibition that outlives any future
   * widening of that partition by consent.
   */
  neverDisclosable?: true
}

/**
 * Bumped when a signal is added, removed or renamed. A definition records the version it was
 * authored against so drift fails loudly rather than silently changing behaviour: content
 * outlives the app version it was written for (docs/COMPANION_DIALOGUE.md — robustness,
 * Finding 1).
 */
export const SIGNAL_VOCABULARY_VERSION = 1

const spec = (s: SignalSpec): SignalSpec => Object.freeze({ ...s, authors: Object.freeze([...s.authors]) })

export const SIGNALS: Record<SignalName, SignalSpec> = Object.freeze({
  daysSinceLastOpen: spec({
    name: 'daysSinceLastOpen',
    type: 'int',
    description: 'Whole days since the person last opened Daymark.',
    authors: ['app'],
    whyAppOnly: 'branching on it would let you infer how recently this person opened the app',
  }),
  daysSinceLastCheckIn: spec({
    name: 'daysSinceLastCheckIn',
    type: 'int',
    description: 'Whole days since the person last recorded a check-in.',
    authors: ['app'],
    whyAppOnly: 'branching on it would let you infer when this person last checked in',
  }),
  checkInsLast7: spec({
    name: 'checkInsLast7',
    type: 'int',
    description: 'How many check-ins the person recorded in the last seven days.',
    authors: ['app'],
    whyAppOnly: 'branching on it would let you infer how often this person has been checking in',
  }),
  hardDaysLast7: spec({
    name: 'hardDaysLast7',
    type: 'int',
    description:
      'How many of the last seven days carry a check-in the person logged in a lower mood band. ' +
      'A count of what they logged, never a judgement about them.',
    authors: ['app'],
    whyAppOnly:
      'branching on it would let you infer how many of this person’s recent check-ins landed in ' +
      'the lower mood bands — the private history the report shares only with their consent',
  }),
  hasSafetyPlan: spec({
    name: 'hasSafetyPlan',
    type: 'bool',
    description: 'Whether the person has written a safety plan of their own. Not whether they need one.',
    authors: ['app'],
    whyAppOnly:
      'branching on it would let you infer whether this person has written a safety plan, which is ' +
      'never disclosed to a clinician by any route, and inference is a route',
    neverDisclosable: true,
  }),
  prescribedModules: spec({
    name: 'prescribedModules',
    type: 'stringArray',
    description: 'Ids of the modules currently assigned to the person and accepted by them.',
    authors: ['app', 'therapist'],
  }),
  lastOfferOutcome: spec({
    name: 'lastOfferOutcome',
    type: 'enum',
    description: 'How the person answered the last thing the app offered, from the reception ledger.',
    authors: ['app'],
    whyAppOnly:
      'branching on it would let you infer how this person has been responding to the app’s ' +
      'prompts, which is between them and the app',
  }),
  timeOfDay: spec({
    name: 'timeOfDay',
    type: 'enum',
    description: 'Coarse part of the day on the device clock. Carries nothing private.',
    authors: ['app', 'therapist'],
  }),
})

/** Declaration order, stable. Typed by the Record above, so it is exhaustive by construction. */
export const SIGNAL_NAMES: readonly SignalName[] = Object.freeze(Object.keys(SIGNALS) as SignalName[])

function isSignalName(ref: string): ref is SignalName {
  return Object.prototype.hasOwnProperty.call(SIGNALS, ref)
}

/** The signals `author` may branch on, in declaration order. A fresh array each call. */
export function signalsFor(author: SignalAuthor): SignalName[] {
  return SIGNAL_NAMES.filter((n) => SIGNALS[n].authors.includes(author))
}

/**
 * Check a definition's signal references against the vocabulary and the author partition.
 *
 * Returns human-readable errors, one per offending ref, in the order the refs were given;
 * an empty array means every ref is allowed. It does not throw — the caller decides whether
 * this is a rejected upload or a red line in an editor — but a non-empty result must ALWAYS
 * mean refusal. Finding 1's fail-closed rule lives at evaluation time; this is the paired
 * authoring-time check that catches the same drift with a message someone can act on.
 *
 * The text for a forbidden-but-real signal explains what branching on it would reveal,
 * because a clinician reads it in the authoring UI and "not permitted" alone teaches nothing.
 */
export function assertRefsAllowed(refs: readonly string[], author: SignalAuthor): string[] {
  // Fail closed on an author role we do not recognise: this value can arrive from stored or
  // transmitted content, and an unknown role must not be treated as a permissive one.
  if (author !== 'app' && author !== 'therapist') {
    return [
      `unknown author role \`${String(author)}\` — no signal may be referenced. ` +
        `Dialogue is authored either by the app or by a therapist.`,
    ]
  }

  const allowed = signalsFor(author)
  const errors: string[] = []
  const seen = new Set<string>()
  for (const ref of refs) {
    if (seen.has(ref)) continue
    seen.add(ref)

    if (!isSignalName(ref)) {
      errors.push(
        `\`${ref}\` is not a signal in the v${SIGNAL_VOCABULARY_VERSION} vocabulary. ` +
          `Signals available here: ${allowed.join(', ')}.`,
      )
      continue
    }

    const s = SIGNALS[ref]
    if (s.authors.includes(author)) continue

    errors.push(
      `\`${ref}\` is a real signal, but ${author}-authored dialogue may not branch on it: ` +
        `${s.whyAppOnly ?? 'branching on it would disclose data this author is not permitted to see'}. ` +
        `The two arms of a branch differ in ways you can already observe — whether something was ` +
        `delivered, accepted or completed — so the branch itself becomes an oracle for what it tested. ` +
        `Nothing is transmitted; the inference comes from the shape of the interaction. ` +
        `You may branch on: ${allowed.join(', ')}.`,
    )
  }
  return errors
}
