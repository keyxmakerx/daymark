/*
 * THE APP-AUTHORED COMPANION DIALOGUE — every line written in advance, by a person, here.
 *
 * This is the conversation from DECISIONS_2026-08.md §D1b, as data in the ./dialogue format.
 * Nothing in it is generated, templated or filled in at runtime: what you read below is exactly
 * what someone is shown. That is the point of the design — a dialogue of fixed choices has no
 * "typed something real into a box that cannot answer" failure mode, and it can be read, argued
 * with and corrected by a clinician the way a leaflet can.
 *
 * The five rules the copy is written under, each of them checked in content.test.ts:
 *
 * 1. REFLECT, NEVER LABEL. "Three of the last seven days have a check-in on the harder end"
 *    hands someone their own count back. "You seem depressed" is a claim about them, which is
 *    §D1a — diagnosis by side-effect, from a system with no instrument and no consent. Every
 *    line that touches the mood signal names a COUNT and the words of the scale the person
 *    picked from, and never a state, a cause, a trend or a prognosis.
 *
 * 2. NEVER CHEERFUL AT SOMEONE HAVING A BAD DAY. The branch reached by "it's been rough" does
 *    not brighten, reframe or look for the silver lining, and does not promise improvement.
 *
 * 3. NEVER GRATEFUL FOR ENGAGEMENT. No "thanks for checking in", no "good to see you", no
 *    "you're doing great". Gratitude for opening an app is the tell of a product that wants
 *    something, and this one does not get to want anything.
 *
 * 4. NOT EVERY BRANCH LEADS SOMEWHERE. `said` — "I just wanted to say it out loud" — is a
 *    first-class ending that offers nothing further, and it is what stops the companion being a
 *    funnel with a friendly voice.
 *
 * 5. THE OFFER IS HONEST ABOUT BEING EMPTY. `offer` adapts to what is actually available to
 *    this person — a module their therapist set up, their own safety plan, or nothing — and
 *    when it is nothing it says so plainly. An app that always has something to offer is an app
 *    that will eventually make something up.
 *
 * NO INTERPOLATION. The counts are spelled out one line per count rather than substituted into
 * a template. It is more text and it is worth it: a template is a place where a number renders
 * as "undefined of the last seven", and the phrasing that reads well at three ("three of the
 * last seven days") is not the phrasing that reads well at seven ("every one of the last seven
 * days"). Prose that bends around its own numbers has to be written, not generated.
 *
 * MONOTONIC (§D1a). Nothing here can make the app ask MORE. `lastOfferOutcome` is read in one
 * place and only to make an opener quieter, and `stopOffering` lets someone turn suggestions off
 * from inside the conversation rather than by going to look for the setting.
 *
 * AUTHORSHIP. This file is app-authored, so it may branch on all eight signals. Therapist-
 * authored dialogue is a different artifact and gets the narrow partition of Finding 3;
 * `validateDialogue(d, 'therapist')` is what enforces that, and it is not this content's
 * concern beyond the test that pins which side of the line this file sits on.
 */

import type { Predicate } from '../instruments/types'
import type { Dialogue, DialogueNode } from './dialogue'
import { SIGNAL_VOCABULARY_VERSION } from './signals'

/* ------------------------------------------------------------------------------------------
 * Host contract — the vocabulary values this content expects, and where an ending leads
 * ----------------------------------------------------------------------------------------*/

/**
 * The values `timeOfDay` may take. Coarse on purpose: an exact clock reading is a behavioural
 * trace; four buckets are a greeting.
 */
export const TIME_OF_DAY = ['morning', 'day', 'evening', 'night'] as const
export type TimeOfDay = (typeof TIME_OF_DAY)[number]

/** The values `lastOfferOutcome` may take — the reception ledger's `outcome` column. */
export const LAST_OFFER_OUTCOME = ['accepted', 'dismissed', 'snoozed', 'stop'] as const
export type LastOfferOutcome = (typeof LAST_OFFER_OUTCOME)[number]

/**
 * Where an ending hands off to. The dialogue never navigates; it names a destination and the
 * host decides whether that screen exists and how to get there.
 *
 * `module:<instrumentId>` ids are real catalog ids (../instruments/catalog), not placeholders,
 * so a typo here is caught by a test rather than by someone tapping a button that does nothing.
 */
export type CompanionDestination =
  | 'check-in'
  | 'journal'
  | 'safety-plan'
  | 'module:compassion-hard-moment'
  | 'module:values-what-matters'

/**
 * Destinations, keyed `<nodeId>#<lineIndex>`.
 *
 * WHY BY LINE AND NOT BY NODE. In `toOffer` the destination depends on which of the four
 * candidate lines fired, and that is precisely what `planLine` already returned as
 * `PlannedLine.index`. Keying on it means the branch is decided once, in the authored
 * predicates, and read back — rather than re-derived by a second copy of the same conditions in
 * host code, which is the shape that eventually disagrees with itself.
 *
 * Most endings are absent from this map and open nothing. `said`, `stopOffering` and the plain
 * close all go nowhere, by design.
 */
export const COMPANION_DESTINATIONS: Readonly<Record<string, CompanionDestination | undefined>> = Object.freeze({
  'toCheckIn#0': 'check-in',
  'toJournal#0': 'journal',
  'toOffer#0': 'module:compassion-hard-moment',
  'toOffer#1': 'module:values-what-matters',
  'toOffer#2': 'safety-plan',
  'toOffer#3': 'check-in',
})

/**
 * What the host should open, given the node it landed on and the line `planLine` chose. Null
 * means this ending opens nothing, which is the common case and not a failure.
 */
export function destinationFor(nodeId: string, lineIndex: number): CompanionDestination | null {
  return COMPANION_DESTINATIONS[`${nodeId}#${lineIndex}`] ?? null
}

/* ------------------------------------------------------------------------------------------
 * Predicate shorthands — they exist so the copy below reads as copy, which is this file's job
 * ----------------------------------------------------------------------------------------*/

const daysSinceOpenAtLeast = (n: number): Predicate => ({ ref: 'daysSinceLastOpen', op: 'gte', value: n })
const daysSinceCheckInAtLeast = (n: number): Predicate => ({
  ref: 'daysSinceLastCheckIn',
  op: 'gte',
  value: n,
})
const checkInsAtLeast = (n: number): Predicate => ({ ref: 'checkInsLast7', op: 'gte', value: n })
const checkInsAtMost = (n: number): Predicate => ({ ref: 'checkInsLast7', op: 'lte', value: n })
const hardDaysAtLeast = (n: number): Predicate => ({ ref: 'hardDaysLast7', op: 'gte', value: n })
const hardDaysExactly = (n: number): Predicate => ({ ref: 'hardDaysLast7', op: 'eq', value: n })
const at = (t: TimeOfDay): Predicate => ({ ref: 'timeOfDay', op: 'eq', value: t })
const prescribed = (instrumentId: string): Predicate => ({
  ref: 'prescribedModules',
  op: 'includes',
  value: instrumentId,
})

/**
 * `hasSafetyPlan` is a bool, but `Predicate.value` is `number | string | array` — the DSL has no
 * boolean literal. `gte 1` is the encoding-proof way to ask: the evaluator coerces with
 * `Number()`, so a host supplying `true`/`false` and a host supplying `1`/`0` both get the right
 * answer. `eq 1` would silently fail against a host that supplies a real boolean, and it would
 * fail in the direction of not showing someone the plan they wrote for themselves.
 *
 * An absent signal fails closed, so a host that does not supply it at all takes the "nothing to
 * offer" branch rather than claiming a plan exists.
 */
const hasSafetyPlan: Predicate = { ref: 'hasSafetyPlan', op: 'gte', value: 1 }

/** Used once, in an opener, and only to make it quieter. Never to add anything. */
const suggestionsTurnedOff: Predicate = { ref: 'lastOfferOutcome', op: 'eq', value: 'stop' }

/**
 * The offer ladder, defined once and walked twice — by `offer`, which describes what is there,
 * and by `toOffer`, which says what is opening. Two nodes reading one list is what keeps the
 * description and the destination from drifting apart; content.test.ts pins that they stay in
 * step, because the day they do not is the day someone is told about an exercise and handed a
 * different screen.
 *
 * The ORDER is not a ranking of what this person needs — nothing here knows that. It is a
 * ranking of whose idea each thing was: something their clinician set up with them, then
 * something they wrote for themselves, then the truth.
 */
const OFFER_LADDER: readonly Predicate[] = Object.freeze([
  prescribed('compassion-hard-moment'),
  prescribed('values-what-matters'),
  hasSafetyPlan,
])

/* ------------------------------------------------------------------------------------------
 * The conversation
 * ----------------------------------------------------------------------------------------*/

/**
 * THE OPENER. Varies on `daysSinceLastOpen`, `checkInsLast7`, `hardDaysLast7` and `timeOfDay`,
 * most specific first, so the same person in a different week meets a different sentence.
 *
 * Two things it never does. It never treats an absence as a lapse — "it's been a couple of
 * weeks" states a fact and immediately says nothing was accruing, because "you haven't written
 * in 3 days" is the one documented harm signal in the notification literature (§D6) and is no
 * better said out loud than in a push. And it is never bright on a week of harder check-ins:
 * where the count is high the opener goes quiet and takes the pressure off instead.
 */
const open: DialogueNode = {
  id: 'open',
  lines: [
    {
      when: { all: [daysSinceOpenAtLeast(14), hardDaysAtLeast(3)] },
      // "Some", not "most": this fires at three of seven, and a line that reflects has to be
      // true at the edge of its own predicate or it has quietly become an interpretation.
      text: "It's been a couple of weeks. Some of the days you did log were on the harder end. No agenda — I'm just here.",
    },
    {
      when: daysSinceOpenAtLeast(14),
      text: "It's been a couple of weeks. Nothing has been piling up in here — the app doesn't keep count.",
    },
    {
      when: { all: [daysSinceOpenAtLeast(5), hardDaysAtLeast(3)] },
      text: "It's been a little while, and the days you did log were on the harder end. No agenda.",
    },
    {
      when: daysSinceOpenAtLeast(5),
      text: "It's been about a week. No agenda — I just leave the door open.",
    },
    {
      when: { all: [hardDaysAtLeast(4), checkInsAtLeast(4)] },
      text: "You've been here most days this week, and several of those check-ins sat on the heavier end.",
    },
    {
      when: hardDaysAtLeast(3),
      text: "A few of this week's check-ins were on the harder end. Nothing needs doing about that right now.",
    },
    {
      when: checkInsAtMost(0),
      text: "Nothing logged in the last seven days. That's allowed — an empty week is still a week.",
    },
    {
      when: checkInsAtMost(1),
      text: "Not much written down this week. That's fine; there's no quota.",
    },
    {
      when: suggestionsTurnedOff,
      text: "No suggestions from me — you turned those off and they've stayed off. Just the door being open.",
    },
    { when: at('night'), text: 'Late one. Nothing here needs doing tonight.' },
    { when: at('morning'), text: "Morning. Nothing pressing — I'm here if you want a minute." },
    { when: at('evening'), text: "Evening. No agenda; the door's open if you want it." },
    // The mandatory fallback. Every node has one, so the conversation can never fall through
    // to nothing and nobody ever meets a blank panel.
    { text: "Hello. Nothing pressing — just checking the door's open." },
  ],
  options: [
    { label: "I'm doing alright", next: 'alright' },
    { label: "It's been rough", next: 'rough' },
    { label: 'Just looking around', next: 'browsing' },
    { label: 'Not now', end: true },
  ],
}

/**
 * "I'm doing alright." Believed, and not cross-examined against the data.
 *
 * The first line is the delicate one. Saying nothing when the logged count is high would read
 * as not listening; saying "but you logged four hard days" would be arguing with someone about
 * their own day. It names the count once and then says both can be true, which is the only
 * honest move available to something that can see a number and not a person.
 */
const alright: DialogueNode = {
  id: 'alright',
  lines: [
    {
      when: hardDaysAtLeast(3),
      text: "Good. Some of this week's check-ins landed on the harder end too — both of those can be true at once.",
    },
    {
      when: daysSinceCheckInAtLeast(7),
      text: "Good. There's been nothing written down for a while, so that's the only thing I know about the week.",
    },
    { text: "Glad to hear it. I won't make a project of it." },
  ],
  options: [
    { label: "Log today while I'm here", next: 'toCheckIn' },
    { label: "That's all", end: true },
  ],
}

/**
 * "It's been rough." The heart of it, and where reflect-never-label has to hold under pressure.
 *
 * Every line is a COUNT of what the person logged, in the words of the scale they picked from
 * ("the harder end"), with no interpretation attached. No "that sounds like a lot", no "things
 * will pick up", no cause, no trend, no encouragement. What the count does is show that the
 * thing they just said was already known — which is most of what being heard is — without
 * making a single claim about them.
 *
 * The nothing-logged line matters as much as the others: with no data, the honest move is to
 * say so and take their word for it rather than reach for a number that is not there.
 */
const rough: DialogueNode = {
  id: 'rough',
  lines: [
    {
      when: hardDaysExactly(7),
      text: "That fits what's here — every one of the last seven days has a check-in on the harder end.",
    },
    {
      when: hardDaysExactly(6),
      text: "That fits what's here — six of the last seven days have a check-in on the harder end.",
    },
    {
      when: hardDaysExactly(5),
      text: "That fits what's here — five of the last seven days have a check-in on the harder end.",
    },
    {
      when: hardDaysExactly(4),
      text: 'That tracks — four of the last seven days have a check-in on the harder end.',
    },
    {
      when: hardDaysExactly(3),
      text: 'That tracks — three of the last seven days have a check-in on the harder end.',
    },
    {
      when: hardDaysExactly(2),
      text: "Two of the last seven days have a check-in on the harder end. Today doesn't have to match them.",
    },
    {
      when: hardDaysExactly(1),
      text: "One of the last seven days has a check-in on the harder end. That doesn't have to be the whole of it.",
    },
    {
      when: checkInsAtMost(0),
      text: "There's nothing from this week in here, so I've only got what you just said. That's enough.",
    },
    { text: "I'm sorry it's been like that." },
  ],
  options: [
    { label: 'Want something to try?', next: 'offer' },
    { label: 'I just wanted to say it out loud', next: 'said' },
    { label: 'Show me what I wrote', next: 'toJournal' },
  ],
}

/** "Just looking around." Nothing is asked of them and nothing follows. */
const browsing: DialogueNode = {
  id: 'browsing',
  lines: [
    { when: at('night'), text: "Go ahead. I'll be in the corner; nothing here is waiting on you tonight." },
    { text: "Go ahead. I'll be in the corner if you want me." },
  ],
  options: [{ label: 'Thanks', end: true }],
}

/**
 * THE OFFER, which adapts to what this person actually has.
 *
 * The safety plan is pointed AT, never becomes. It is the person's own, authored in advance
 * (§D1b, §D3), and the wording describing it is deliberately flat — "the plan you wrote for
 * days like this one" — because dressing it up is how a plan turns into a crisis path.
 *
 * The last line is the one this node exists for. An offer branch with nothing to offer is
 * exactly where a product starts inventing, so it says there is nothing, in those words. What
 * it then names is not a new suggestion but the plainest thing the app already is: somewhere to
 * write the day down. It is offered hedged, and it is the only thing on the far side of "yes".
 */
const offer: DialogueNode = {
  id: 'offer',
  lines: [
    {
      when: OFFER_LADDER[0],
      text: "There's the hard-moment exercise your therapist set up. About four minutes, nothing scored, and nothing you write in it leaves the phone.",
    },
    {
      when: OFFER_LADDER[1],
      text: "There's the writing exercise your therapist set up, about what matters to you. Five minutes or so, nothing scored, nothing shared.",
    },
    {
      when: OFFER_LADDER[2],
      text: "Nothing new from me. But there's the plan you wrote for days like this one — I can open it.",
    },
    {
      text: "Honestly, nothing. There's no exercise set up for you and no plan of your own here to open, and I'd rather say that than invent something. I can open today's check-in, if somewhere to put it would help.",
    },
  ],
  options: [
    { label: 'Yes, open it', next: 'toOffer' },
    { label: 'Maybe later', end: true },
    { label: "Don't offer me things", next: 'stopOffering' },
  ],
}

/**
 * The far side of "yes" — the same ladder, said as an action. Kept as its own node so the
 * handoff has a line of its own instead of the panel vanishing mid-sentence, and so the line
 * that describes what is opening is the same authored branch that decides what opens.
 */
const toOffer: DialogueNode = {
  id: 'toOffer',
  lines: [
    {
      when: OFFER_LADDER[0],
      text: "Opening the hard-moment exercise. You can stop partway; that's a normal way to use it.",
    },
    {
      when: OFFER_LADDER[1],
      text: "Opening the writing exercise. You can stop partway; that's a normal way to use it.",
    },
    { when: OFFER_LADDER[2], text: 'Opening your plan.' },
    { text: "Opening today's check-in." },
  ],
  options: [{ label: 'Okay', end: true }],
}

/**
 * "I just wanted to say it out loud."
 *
 * A FIRST-CLASS ENDING THAT OFFERS NOTHING FURTHER. No exercise smuggled in as a question, no
 * "are you sure", no next node. Saying a thing is allowed to be the whole of what happens, and
 * a companion that cannot let it be is a funnel.
 *
 * The single option closes the conversation; the format requires one, and there is nothing here
 * a person could be led on to. It also does not congratulate them for having spoken — no "that
 * took courage", no "that counts as something you did today". Both charge a small price for
 * having said it.
 */
const said: DialogueNode = {
  id: 'said',
  lines: [
    { when: at('night'), text: "It's said. Nothing else needs doing tonight." },
    { text: "It's said, and it doesn't have to go anywhere." },
  ],
  options: [{ label: 'Okay', end: true }],
}

/**
 * Turning suggestions off, from inside the conversation rather than by going to hunt for it.
 * Escalation is the person's to ask for (§D1a); this is the other direction, and it is the one
 * the design is willing to make easy. The setting is named exactly as it is named in Settings,
 * so the sentence is a route and not a hint.
 */
const stopOffering: DialogueNode = {
  id: 'stopOffering',
  lines: [
    {
      text: "Done — no more suggestions. You can still open anything yourself, and it's in Settings under when Daymark asks if you ever want it back.",
    },
  ],
  options: [{ label: 'Okay', end: true }],
}

/** Handoff to today's check-in. A statement of what is happening, not a send-off. */
const toCheckIn: DialogueNode = {
  id: 'toCheckIn',
  lines: [{ text: "Opening today's check-in." }],
  options: [{ label: 'Okay', end: true }],
}

/**
 * Handoff to the journal. It OPENS the journal; it does not quote it. Note excerpts in the
 * companion are out of scope by decision (§D6) — the journal is the one place in the product
 * with no audience, and that is the asset the product cannot regenerate.
 */
const toJournal: DialogueNode = {
  id: 'toJournal',
  lines: [{ text: 'Opening your journal.' }],
  options: [{ label: 'Okay', end: true }],
}

/* ------------------------------------------------------------------------------------------
 * The definition
 * ----------------------------------------------------------------------------------------*/

/** The app-authored companion dialogue. One artifact, in the one format both editors produce. */
export const COMPANION_DIALOGUE: Dialogue = {
  id: 'companion-core',
  entry: 'open',
  signalVocabularyVersion: SIGNAL_VOCABULARY_VERSION,
  nodes: [open, alright, rough, browsing, offer, toOffer, said, stopOffering, toCheckIn, toJournal],
}
