import { describe, it, expect } from 'vitest'
import {
  COMPANION_DIALOGUE,
  COMPANION_DESTINATIONS,
  LAST_OFFER_OUTCOME,
  TIME_OF_DAY,
  destinationFor,
} from './content'
import { planLine, validateDialogue } from './dialogue'
import type { DialogueNode, DialogueSignals } from './dialogue'
import { SIGNAL_NAMES, SIGNAL_VOCABULARY_VERSION } from './signals'
import type { SignalName } from './signals'
import { CATALOG } from '../instruments/catalog'

/*
 * The dialogue is copy, and copy is the part of this product a test cannot fully check. What a
 * test CAN do is make the promises structural, so that the next person to edit a line has to
 * break something visible in order to break a rule.
 *
 * Four kinds of check live here:
 *
 *   1. SHAPE. It validates, every node has its mandatory fallback, and no branch is a dead end.
 *   2. TOTALITY. A sweep over thousands of signal combinations — including the empty substrate a
 *      new install actually has, and a hostile one a buggy host might send — always produces a
 *      line and never throws. Plus the inverse: every authored line is reachable by SOME
 *      combination, so no copy is quietly dead.
 *   3. VOICE. Regexes for the things the copy must never do — label somebody, promise them
 *      improvement, thank them for engaging, keep score. Each one is proved against a
 *      deliberately-bad sample string BEFORE being run over the content, because a regex that
 *      matches nothing passes every corpus and tests nothing at all.
 *   4. THE TWO PROMISES WITH TEETH. "I just wanted to say it out loud" offers nothing further,
 *      and the offer branch never claims something exists when it does not.
 */

const nodes = COMPANION_DIALOGUE.nodes
const nodeById = (id: string): DialogueNode => {
  const n = nodes.find((x) => x.id === id)
  if (!n) throw new Error(`no node "${id}" — the test and the content disagree`)
  return n
}

/** Every authored string a person can actually read, tagged with where it came from. */
const allLines = nodes.flatMap((n) => n.lines.map((l, i) => ({ where: `${n.id}#${i}`, text: l.text })))
const allLabels = nodes.flatMap((n) => n.options.map((o, i) => ({ where: `${n.id} option ${i}`, text: o.label })))

/* ==========================================================================================
 * 1 — shape
 * ========================================================================================*/

describe('the dialogue is well formed', () => {
  it('passes validateDialogue', () => {
    expect(validateDialogue(COMPANION_DIALOGUE)).toEqual([])
  })

  it('passes the app-author partition, because it is app-authored', () => {
    expect(validateDialogue(COMPANION_DIALOGUE, 'app')).toEqual([])
  })

  it('would NOT pass as therapist-authored — which is what makes the check above mean something', () => {
    // This content branches on mood counts, safety-plan existence and the reception ledger.
    // A therapist may not branch on any of those (COMPANION_DIALOGUE.md, Finding 3): the two
    // arms of such a branch differ in ways they can already observe, so the branch is an oracle.
    // If this ever returns [], the partition has been widened and that is a decision, not a fix.
    const errors = validateDialogue(COMPANION_DIALOGUE, 'therapist')
    expect(errors.length).toBeGreaterThan(0)
    expect(errors.join(' ')).toMatch(/hardDaysLast7|hasSafetyPlan|lastOfferOutcome/)
  })

  it('stamps the signal vocabulary it was authored against', () => {
    expect(COMPANION_DIALOGUE.signalVocabularyVersion).toBe(SIGNAL_VOCABULARY_VERSION)
  })

  it('gives every node a fallback: the last line carries no predicate', () => {
    for (const n of nodes) {
      expect(n.lines.length, `${n.id} has no lines`).toBeGreaterThan(0)
      const last = n.lines[n.lines.length - 1]
      expect(last.when, `${n.id} has no unconditional fallback line`).toBeUndefined()
      expect(last.text.trim().length, `${n.id} fallback is empty`).toBeGreaterThan(0)
    }
  })

  it('gates every line except that fallback, so no candidate is stranded behind an always-match', () => {
    for (const n of nodes) {
      n.lines.slice(0, -1).forEach((l, i) => {
        expect(l.when, `${n.id} line ${i} always matches, so nothing after it can be chosen`).toBeDefined()
      })
    }
  })

  it('branches only on the closed signal vocabulary', () => {
    const refs = new Set<string>()
    const walk = (p: unknown): void => {
      if (!p || typeof p !== 'object') return
      const n = p as { all?: unknown[]; any?: unknown[]; ref?: unknown }
      if (Array.isArray(n.all)) n.all.forEach(walk)
      else if (Array.isArray(n.any)) n.any.forEach(walk)
      else if (typeof n.ref === 'string') refs.add(n.ref)
    }
    for (const n of nodes) for (const l of n.lines) walk(l.when)
    for (const ref of refs) expect(SIGNAL_NAMES, `${ref} is not a signal`).toContain(ref as SignalName)
  })

  it('offers fixed choices only — every option is an authored label that leads somewhere or ends', () => {
    // The absence of a free-text field is the whole reason this design is safe (§D1b, §D6).
    // There is no item type here that could carry one; this pins that the options stay a list.
    for (const n of nodes) {
      expect(n.options.length, `${n.id} offers nothing`).toBeGreaterThan(0)
      for (const o of n.options) {
        expect(o.label.trim().length).toBeGreaterThan(0)
        const leadsSomewhere = typeof o.next === 'string' && o.next.length > 0
        expect(leadsSomewhere !== (o.end === true), `${n.id} → "${o.label}" must lead on XOR end`).toBe(true)
      }
    }
  })
})

/* ==========================================================================================
 * 2 — totality: never throws, always says something, and nothing authored is dead
 * ========================================================================================*/

/** The five signals held fixed while the two mood counts and the clock are swept exhaustively. */
const PRESETS: Array<{ name: string; signals: DialogueSignals }> = [
  { name: 'fresh install — the empty substrate', signals: {} },
  {
    name: 'here today, nothing prescribed, no plan',
    signals: {
      daysSinceLastOpen: 0,
      daysSinceLastCheckIn: 0,
      hasSafetyPlan: false,
      prescribedModules: [],
      lastOfferOutcome: 'accepted',
    },
  },
  {
    name: 'away a fortnight, has a plan',
    signals: {
      daysSinceLastOpen: 14,
      daysSinceLastCheckIn: 14,
      hasSafetyPlan: true,
      prescribedModules: [],
      lastOfferOutcome: 'dismissed',
    },
  },
  {
    name: 'away six days, compassion module prescribed',
    signals: {
      daysSinceLastOpen: 6,
      daysSinceLastCheckIn: 6,
      hasSafetyPlan: false,
      prescribedModules: ['compassion-hard-moment'],
      lastOfferOutcome: 'snoozed',
    },
  },
  {
    name: 'told it to stop; values module prescribed, has a plan',
    signals: {
      daysSinceLastOpen: 1,
      daysSinceLastCheckIn: 8,
      hasSafetyPlan: true,
      prescribedModules: ['values-what-matters'],
      lastOfferOutcome: 'stop',
    },
  },
  {
    name: 'both modules prescribed, has a plan',
    signals: {
      daysSinceLastOpen: 2,
      daysSinceLastCheckIn: 1,
      hasSafetyPlan: 1, // a host that encodes booleans as 1/0 — both must work
      prescribedModules: ['compassion-hard-moment', 'values-what-matters'],
      lastOfferOutcome: 'accepted',
    },
  },
]

/** Presets × checkInsLast7 0-7 × hardDaysLast7 0-7 × the four times of day and none. */
function cleanCombos(): DialogueSignals[] {
  const out: DialogueSignals[] = []
  for (const p of PRESETS)
    for (let checkIns = 0; checkIns <= 7; checkIns++)
      for (let hard = 0; hard <= 7; hard++)
        for (const timeOfDay of [...TIME_OF_DAY, undefined])
          out.push({ ...p.signals, checkInsLast7: checkIns, hardDaysLast7: hard, timeOfDay })
  return out
}

/**
 * A host that is wrong. Negative counts, NaN, nulls, strings where ints were promised, an enum
 * member nobody defined, an array where a scalar belongs. None of this should ever arrive, and
 * all of it eventually does — a signal computed from a corrupt row, an old app version, a bug.
 * The requirement is not that the dialogue understand it, only that it never throw and always
 * have something to say.
 */
const HOSTILE_POOLS: Record<SignalName, unknown[]> = {
  daysSinceLastOpen: [undefined, 0, 5, 14, 400, -1, NaN, Infinity, '7', null, [], {}],
  daysSinceLastCheckIn: [undefined, 0, 7, 99, -3, NaN, 'never', null],
  checkInsLast7: [undefined, 0, 1, 4, 7, 99, -2, NaN, '3', null],
  hardDaysLast7: [undefined, 0, 3, 7, 12, -1, NaN, '5', null, {}],
  hasSafetyPlan: [undefined, true, false, 1, 0, 'yes', 'no', null, NaN],
  prescribedModules: [
    undefined,
    [],
    ['compassion-hard-moment'],
    ['values-what-matters'],
    ['compassion-hard-moment', 'values-what-matters'],
    ['module-from-a-later-release'],
    'compassion-hard-moment',
    null,
    42,
  ],
  lastOfferOutcome: [undefined, ...LAST_OFFER_OUTCOME, 'STOP', '', null, 7],
  timeOfDay: [undefined, ...TIME_OF_DAY, 'Morning', 'dusk', '', null, 3],
}

/** Deterministic, so a failure is reproducible rather than a story about a seed. */
function lcg(seed: number): () => number {
  let s = seed >>> 0
  return () => {
    s = (Math.imul(s, 1664525) + 1013904223) >>> 0
    return s / 0x100000000
  }
}

function hostileCombos(count: number): DialogueSignals[] {
  const rand = lcg(20260815)
  const out: DialogueSignals[] = []
  for (let i = 0; i < count; i++) {
    const combo: Record<string, unknown> = {}
    for (const name of SIGNAL_NAMES) {
      const pool = HOSTILE_POOLS[name]
      combo[name] = pool[Math.floor(rand() * pool.length)]
    }
    out.push(combo as DialogueSignals)
  }
  return out
}

describe('every node always has a line, for every person', () => {
  it('speaks to a brand-new install with no history at all', () => {
    for (const n of nodes) {
      const planned = planLine(n, {})
      expect(planned, `${n.id} said nothing to an empty substrate`).not.toBeNull()
      expect(planned?.fallback, `${n.id} matched a predicate against no data`).toBe(true)
      expect(planned?.line.text.trim().length).toBeGreaterThan(0)
    }
  })

  it('produces a line for every node across the full clean sweep, and never throws', () => {
    const combos = cleanCombos()
    expect(combos.length).toBeGreaterThan(1500)
    for (const signals of combos) {
      for (const n of nodes) {
        const planned = planLine(n, signals)
        expect(planned, `${n.id} said nothing for ${JSON.stringify(signals)}`).not.toBeNull()
        expect(planned?.line.text.trim().length).toBeGreaterThan(0)
      }
    }
  })

  it('produces a line for every node against a host sending nonsense, and never throws', () => {
    for (const signals of hostileCombos(2000)) {
      for (const n of nodes) {
        const planned = planLine(n, signals)
        expect(planned, `${n.id} said nothing for ${JSON.stringify(signals)}`).not.toBeNull()
        expect(planned?.line.text.trim().length).toBeGreaterThan(0)
      }
    }
  })

  it('leaves no authored line unreachable — every branch is met by some real person', () => {
    // Dead copy is worse than missing copy: it reads as shipped, reviews as shipped, and is
    // never seen. If this fails after an edit, either a predicate got shadowed by the one above
    // it or a line was written for a state that cannot occur.
    const hit = new Set<string>()
    for (const signals of cleanCombos())
      for (const n of nodes) {
        const planned = planLine(n, signals)
        if (planned) hit.add(`${n.id}#${planned.index}`)
      }
    const missed = allLines.map((l) => l.where).filter((w) => !hit.has(w))
    expect(missed, 'these lines can never be chosen').toEqual([])
  })
})

describe('the safety-plan signal survives either encoding a host might use', () => {
  // `Predicate.value` has no boolean, so the branch asks `hasSafetyPlan >= 1`. `eq 1` would work
  // for a host sending 1/0 and silently fail for one sending true/false — failing in the
  // direction of not showing someone the plan they wrote for themselves.
  const offer = nodeById('offer')
  const planText = (hasSafetyPlan: unknown) =>
    planLine(offer, { prescribedModules: [], hasSafetyPlan } as DialogueSignals)?.line.text ?? ''

  it('points at the plan whether it arrives as true or as 1', () => {
    expect(planText(true)).toMatch(/plan you wrote/i)
    expect(planText(1)).toMatch(/plan you wrote/i)
  })

  it('does not claim a plan exists when it arrives as false, 0, or not at all', () => {
    for (const v of [false, 0, undefined]) expect(planText(v)).not.toMatch(/plan you wrote/i)
  })
})

/* ==========================================================================================
 * 3 — voice
 * ========================================================================================*/

/**
 * Each rule carries `bad` samples it MUST match. They are asserted first, so a regex that was
 * broken by an edit (or written wrong to begin with) fails loudly instead of passing over the
 * content by matching nothing. A vacuous invariant is worse than no invariant, because it is
 * cited in review.
 */
interface VoiceRule {
  rule: string
  re: RegExp
  bad: string[]
  /** Option labels are the PERSON's words, not the companion's, so some rules apply to lines only. */
  labelsToo: boolean
}

const VOICE: VoiceRule[] = [
  {
    rule: 'never labels the person — §D1b reflect-never-label, §D1a no inferred clinical state',
    re: /\byou(?:'ve| ?'re| are| were| seem| sound| look| feel| have been| might be| may be| must be| probably are)\s+(?:very |really |quite |a bit |clearly |probably |obviously )*(?:depressed|anxious|unwell|ill|struggling|low|down|fragile|unstable|burnt ?out|at risk|in crisis|not ok(?:ay)?|not (?:doing )?well)\b/i,
    bad: [
      'You seem depressed.',
      'you might be anxious about it',
      "You're really struggling.",
      'You have been at risk for a while.',
    ],
    labelsToo: true,
  },
  {
    rule: 'never names a condition as theirs',
    re: /\byour (?:depression|anxiety|illness|condition|disorder|diagnosis|episode|symptoms?|mental (?:health|state))\b/i,
    bad: ['Your depression is worse this week.', 'your symptoms are improving'],
    labelsToo: true,
  },
  {
    rule: 'never speaks clinically about the data — nothing here is an instrument',
    re: /\b(?:diagnos(?:e|ed|is|tic|ing)|screen(?:ed|s) positive|clinically (?:depressed|significant)|indicat(?:e|es|ing) (?:depression|anxiety|risk)|your (?:score|band) (?:suggests|means))\b/i,
    bad: ['This indicates depression.', 'Your score suggests something.', 'You screened positive.'],
    labelsToo: true,
  },
  {
    rule: 'never promises improvement — §D1b, never cheerful at someone having a bad day',
    re: /\b(?:you(?:'ll| will) (?:feel|get|be) better|things (?:will|should) (?:get better|pick up|improve)|it (?:gets|will get) better|tomorrow (?:will|'ll) be better|chin up|look on the bright side|stay positive)\b/i,
    bad: ['Things will get better.', "you'll feel better tomorrow", 'Chin up!'],
    labelsToo: false,
  },
  {
    rule: 'never grateful for engagement — the app does not get to want anything',
    re: /\b(?:thanks for|thank you for|glad you (?:came|opened|checked|stopped)|good to see you|nice to see you|welcome back|we missed you|i missed you|well done|great job|proud of you|keep it up)\b/i,
    bad: ['Thanks for checking in!', 'Good to see you again.', 'Welcome back — we missed you.'],
    labelsToo: false,
  },
  {
    rule: 'no streaks, no scoring, no gamification — §D6',
    re: /\b(?:streak|badge|trophy|points?\b|level(?:ed)? up|days in a row|consecutive days|don'?t break|perfect week|you'?re on a roll|\d+% complete)\b/i,
    bad: ["You're on a 5 day streak.", 'Four days in a row!', "Don't break it now.", '80% complete'],
    labelsToo: true,
  },
  {
    rule: 'no green all-clear — there is no --success token and never a clean bill of health',
    re: /\b(?:all clear|you'?re (?:all )?(?:good|fine|okay) (?:now|then)|everything(?:'s| is) fine|no concerns|nothing to worry about|looking good)\b/i,
    bad: ['All clear this week.', 'Everything is fine.', 'Nothing to worry about.'],
    labelsToo: true,
  },
]

describe('the voice rules are not vacuous', () => {
  for (const { rule, re, bad } of VOICE) {
    it(`matches deliberately-bad copy for: ${rule}`, () => {
      for (const sample of bad) {
        expect(re.test(sample), `regex failed to catch "${sample}" — it would pass anything`).toBe(true)
      }
    })
  }
})

describe('no line in the shipped content breaks a voice rule', () => {
  for (const { rule, re, labelsToo } of VOICE) {
    it(rule, () => {
      const corpus = labelsToo ? [...allLines, ...allLabels] : allLines
      const offenders = corpus.filter((c) => re.test(c.text)).map((c) => `${c.where}: ${c.text}`)
      expect(offenders).toEqual([])
    })
  }
})

describe('the mood count is handed back, never interpreted', () => {
  it('spells its numbers out rather than interpolating them', () => {
    // A template is where a count renders as "undefined of the last seven". There is no
    // substitution syntax in this content, and this keeps it that way.
    for (const { where, text } of allLines) {
      expect(text, `${where} looks like a template`).not.toMatch(/\{\{?\s*\w+\s*\}?\}/)
      expect(text, `${where} looks like a template`).not.toMatch(/\$\{/)
    }
  })

  it('says "the harder end" — the words of the scale — wherever it mentions the count', () => {
    const counted = allLines.filter((l) => /\b(?:one|two|three|four|five|six|seven|every one)\b.*seven/i.test(l.text))
    expect(counted.length, 'the reflecting lines have gone missing').toBeGreaterThan(5)
    for (const l of counted) expect(l.text, l.where).toMatch(/harder end|heavier end/i)
  })
})

/* ==========================================================================================
 * 4 — the two promises with teeth
 * ========================================================================================*/

describe('"I just wanted to say it out loud" is a first-class ending', () => {
  const rough = nodeById('rough')

  it('is offered on the branch where someone says it has been rough', () => {
    expect(rough.options.map((o) => o.label)).toContain('I just wanted to say it out loud')
  })

  it('offers nothing further: every option on the node it reaches ends the conversation', () => {
    // Not "has few options" — has NO option that leads anywhere. If someone later adds
    // "actually, show me something" here, this fails, and that is the point: the value of the
    // ending is that it is genuinely an ending, not a pause before another offer.
    const option = rough.options.find((o) => o.label === 'I just wanted to say it out loud')
    expect(option?.next).toBe('said')
    const said = nodeById('said')
    expect(said.options.length).toBeGreaterThan(0)
    for (const o of said.options) {
      expect(o.end, `"${o.label}" leads on from an ending`).toBe(true)
      expect(o.next).toBeUndefined()
    }
  })

  it('does not charge a price for having spoken', () => {
    for (const l of nodeById('said').lines) {
      expect(l.text).not.toMatch(/courage|brave|counts|credit|proud|well done|step forward/i)
    }
  })
})

describe('the offer branch is honest about what exists', () => {
  const offer = nodeById('offer')
  const toOffer = nodeById('toOffer')

  const signalsFor = (o: { modules?: string[]; plan?: boolean }): DialogueSignals => ({
    prescribedModules: o.modules ?? [],
    hasSafetyPlan: o.plan ?? false,
  })
  const say = (n: DialogueNode, s: DialogueSignals) => planLine(n, s)?.line.text ?? ''

  it('offers the prescribed module when there is one', () => {
    expect(say(offer, signalsFor({ modules: ['compassion-hard-moment'] }))).toMatch(/hard-moment exercise/i)
    expect(say(offer, signalsFor({ modules: ['values-what-matters'] }))).toMatch(/writing exercise/i)
  })

  it("offers the person's own plan when they have one and nothing is prescribed", () => {
    expect(say(offer, signalsFor({ plan: true }))).toMatch(/plan you wrote/i)
  })

  it('says plainly that there is nothing, rather than inventing a suggestion', () => {
    const text = say(offer, signalsFor({}))
    expect(text).toMatch(/^Honestly, nothing\./)
    expect(text).toMatch(/rather say that than invent something/i)
    // The one thing it names on the far side of "yes" is the app's own check-in — a place to
    // put the day, not a recommendation about it.
    expect(text).toMatch(/check-in/i)
  })

  it('says the same thing to someone with no signals at all', () => {
    expect(say(offer, {})).toMatch(/^Honestly, nothing\./)
  })

  it('never mentions the safety plan when the person has not written one', () => {
    for (const modules of [[], ['compassion-hard-moment'], ['values-what-matters']]) {
      expect(say(offer, signalsFor({ modules, plan: false }))).not.toMatch(/plan you wrote|your plan/i)
      expect(say(toOffer, signalsFor({ modules, plan: false }))).not.toMatch(/plan you wrote|your plan/i)
    }
  })

  it('describes and opens the same thing — the two nodes share one ladder', () => {
    // Referential identity, not deep equality: both nodes read the same predicate objects, so
    // editing the condition in one place cannot leave the other behind. The day these drift is
    // the day someone is told about an exercise and handed a different screen.
    expect(offer.lines.length).toBe(toOffer.lines.length)
    for (let i = 0; i < offer.lines.length - 1; i++) {
      expect(toOffer.lines[i].when, `ladder rung ${i} has drifted`).toBe(offer.lines[i].when)
    }
  })
})

describe('endings hand off to real places', () => {
  it('maps every destination key onto a line that exists', () => {
    for (const key of Object.keys(COMPANION_DESTINATIONS)) {
      const [id, index] = key.split('#')
      const node = nodes.find((n) => n.id === id)
      expect(node, `destination key "${key}" names no node`).toBeDefined()
      expect(node?.lines[Number(index)], `destination key "${key}" names no line`).toBeDefined()
    }
  })

  it('names only instruments that are actually in the catalog', () => {
    const ids = new Set(CATALOG.map((d) => d.instrumentId))
    for (const destination of Object.values(COMPANION_DESTINATIONS)) {
      if (destination?.startsWith('module:')) {
        expect(ids, `${destination} is not a catalog instrument`).toContain(destination.slice('module:'.length))
      }
    }
  })

  it('resolves each rung of the offer to its own screen', () => {
    const toOffer = nodeById('toOffer')
    const resolve = (s: DialogueSignals) => {
      const planned = planLine(toOffer, s)
      return planned ? destinationFor('toOffer', planned.index) : null
    }
    expect(resolve({ prescribedModules: ['compassion-hard-moment'] })).toBe('module:compassion-hard-moment')
    expect(resolve({ prescribedModules: ['values-what-matters'] })).toBe('module:values-what-matters')
    expect(resolve({ hasSafetyPlan: true })).toBe('safety-plan')
    expect(resolve({})).toBe('check-in')
  })

  it('opens nothing for the endings that are meant to open nothing', () => {
    for (const id of ['said', 'stopOffering', 'browsing', 'open', 'rough', 'alright', 'offer']) {
      for (let i = 0; i < nodeById(id).lines.length; i++) expect(destinationFor(id, i)).toBeNull()
    }
  })
})

describe('the opener varies with the person, not with the interface', () => {
  const open = nodeById('open')
  const say = (s: DialogueSignals) => planLine(open, s)?.line.text ?? ''

  it('varies with daysSinceLastOpen', () => {
    const away = new Set([say({ daysSinceLastOpen: 0 }), say({ daysSinceLastOpen: 6 }), say({ daysSinceLastOpen: 20 })])
    expect(away.size).toBe(3)
  })

  it('varies with hardDaysLast7 and checkInsLast7', () => {
    const base = { daysSinceLastOpen: 0, checkInsLast7: 5 }
    expect(say({ ...base, hardDaysLast7: 5 })).not.toBe(say({ ...base, hardDaysLast7: 0 }))
    expect(say({ daysSinceLastOpen: 0, checkInsLast7: 0 })).not.toBe(say({ daysSinceLastOpen: 0, checkInsLast7: 5 }))
  })

  it('varies with timeOfDay', () => {
    const quiet = { daysSinceLastOpen: 0, checkInsLast7: 4, hardDaysLast7: 0 }
    const byTime = new Set(TIME_OF_DAY.map((timeOfDay) => say({ ...quiet, timeOfDay })))
    expect(byTime.size).toBeGreaterThan(2)
  })

  it('never treats an absence as a lapse the person owes something for', () => {
    // §D6: "you haven't written in 3 days" is the only documented harm signal in the
    // notification literature. It is no better said out loud than pushed.
    for (const { where, text } of allLines) {
      expect(text, where).not.toMatch(/\byou (?:haven'?t|have not|still haven'?t|forgot|missed|skipped)\b/i)
      expect(text, where).not.toMatch(/\b(?:you owe|catch up|make up for|behind on|overdue)\b/i)
    }
  })

  it('reads the reception ledger only to go quieter', () => {
    // Monotonic, §D1a: no signal in any combination may make the app ask more. The one line
    // keyed to `lastOfferOutcome` promises nothing rather than offering something.
    const stopped = say({ daysSinceLastOpen: 1, checkInsLast7: 4, hardDaysLast7: 0, lastOfferOutcome: 'stop' })
    expect(stopped).toMatch(/no suggestions/i)
  })
})
