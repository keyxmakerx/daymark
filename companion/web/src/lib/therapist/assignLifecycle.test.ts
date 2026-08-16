import { describe, it, expect, afterEach, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import {
  buildLifecyclePreview,
  lifecycleProse,
  LIFECYCLE_ORDER,
  type LifecycleDraft,
  type LifecyclePreview,
} from './assignLifecycle'
import { buildAssignment } from './assignClient'
import { shouldAutoApply } from '../assignments/validate'
import {
  ALL_CAPABILITIES,
  TYPE_CAPABILITY,
  type ApplyMode,
  type AssignmentType,
  type Capability,
  type CapabilityGrant,
  type Grant,
} from '../assignments/types'
import { STATUS_LABEL, type AssignmentStatus } from '../components/ui/status'

/*
 * WHAT THIS SUITE IS FOR.
 *
 * Nearly every claim this module makes is a claim about an ABSENCE — the therapist is NOT told
 * that the item arrived, is NOT told it was declined, will NOT learn that a run came from this
 * assignment, and the copy contains NO figure and NO verdict. An absence assertion is the
 * easiest kind of test to write badly: `expect(text).not.toContain('adherence')` passes just as
 * happily against an empty string, a renamed field, or a function that returned nothing at all.
 * This repo has shipped exactly that failure before.
 *
 * So every absence check below is paired with a POSITIVE CONTROL that proves the detector fires
 * when the thing it hunts is really present. Where the control is its own `it`, it says so;
 * where it is inline, a comment names the line that is the control.
 */

/* ── Fixtures ───────────────────────────────────────────────────────────────────────────── */

const ALLOW = (apply: ApplyMode = 'propose'): CapabilityGrant => ({ granted: true, apply })

function grant(over: Partial<Record<Capability, CapabilityGrant>> = {}): Grant {
  return { therapistFingerprint: 'fp-therapist', capabilities: { ...over } }
}

/** Every capability granted, in the given apply mode. The ordinary, fully-permitted case. */
function fullGrant(apply: ApplyMode = 'propose'): Grant {
  const caps: Partial<Record<Capability, CapabilityGrant>> = {}
  for (const cap of ALL_CAPABILITIES) caps[cap] = ALLOW(apply)
  return grant(caps)
}

const ALL_TYPES: AssignmentType[] = [
  'questionnaire',
  'task',
  'largeAssessment',
  'reminder',
  'goal',
  'setting',
]

function draft(over: Partial<LifecycleDraft> = {}): LifecycleDraft {
  return { type: 'questionnaire', grant: fullGrant(), ...over }
}

/** Every preview this module can produce, so the copy guards run over all of it. */
function everyPreview(): LifecyclePreview[] {
  const out: LifecyclePreview[] = []
  for (const type of ALL_TYPES) {
    for (const apply of ['propose', 'auto'] as ApplyMode[]) {
      out.push(buildLifecyclePreview({ type, grant: fullGrant(apply) }))
      // read.share withheld; the assign capability still granted.
      out.push(
        buildLifecyclePreview({
          type,
          grant: grant({ [TYPE_CAPABILITY[type]]: ALLOW(apply) }),
        }),
      )
      // nothing granted at all.
      out.push(buildLifecyclePreview({ type, grant: grant() }))
    }
    out.push(buildLifecyclePreview({ type, grant: fullGrant(), cadence: { every: 'week', count: 2 } }))
  }
  out.push(
    buildLifecyclePreview({
      type: 'largeAssessment',
      grant: fullGrant(),
      payload: { bundle: [{ kind: 'questionnaire', id: 'a' }, { kind: 'task', id: 'b' }] },
    }),
    buildLifecyclePreview({
      type: 'largeAssessment',
      grant: fullGrant(),
      payload: { bundle: [{ kind: 'task', id: 'b' }] },
    }),
  )
  return out
}

const ALL_PROSE = () => everyPreview().flatMap(lifecycleProse)

/* ── Detectors, each proved before it is trusted ────────────────────────────────────────── */

/**
 * A compliance figure: a percentage, a ratio, a streak, a rate, or the vocabulary that dresses
 * one up in words. The product rule is that no number describing how much of an assignment a
 * person did may ever be produced, in any form.
 */
const FIGURE = [
  /\d+\s*(?:%|percent)/i,
  /\b\d+\s*(?:of|out of|\/)\s*\d+\b/i,
  /\b\d+[- ]?(?:day|week|session)s?\s+streak\b/i,
  /\bstreak\b/i,
  /\bcompliance|complian(?:t|ce)\b/i,
  /\badheren(?:t|ce)\b/i,
  /\bcompletion rate|response rate|hit rate\b/i,
  /\b(?:on|off) track\b/i,
  /\bquota\b/i,
]

const figuresIn = (text: string) => FIGURE.filter((re) => re.test(text)).map((re) => re.source)

/**
 * Blame: the vocabulary that turns "this was not opened" into a report on a person. The word
 * list is what a well-meaning developer reaches for when they think they are being informative.
 */
const BLAME = [
  /\bnon-?complian/i,
  /\blapse[sd]?\b/i,
  /\bfail(?:ed|ure|s)\s+to\b/i,
  /\bneglect/i,
  /\bshould have\b/i,
  /\bdisengag/i,
  /\bunresponsive\b/i,
  /\bdropped off\b/i,
  /\bnot bother/i,
  /\bexcuse\b/i,
]

const blameIn = (text: string) => BLAME.filter((re) => re.test(text)).map((re) => re.source)

/** Refusal vocabulary — used to prove silence is never written as a decision. */
const REFUSAL = /\b(?:refus|declin|reject|turned it down|said no)/i

describe('the detectors detect', () => {
  it('the compliance-figure detector fires on real figures', () => {
    // POSITIVE CONTROL for every `figuresIn(...)` assertion below.
    expect(figuresIn('72% adherence this month').length).toBeGreaterThan(0)
    expect(figuresIn('completed 3 of 5 assigned self-checks').length).toBeGreaterThan(0)
    expect(figuresIn('a 4-day streak').length).toBeGreaterThan(0)
    expect(figuresIn('compliance was low').length).toBeGreaterThan(0)
    expect(figuresIn('they are on track this week').length).toBeGreaterThan(0)
    expect(figuresIn('completion rate: 40 percent').length).toBeGreaterThan(0)
    // And does not fire on ordinary prose, so a green result below means something.
    expect(figuresIn('It waits in their inbox until they decide.')).toEqual([])
  })

  it('the blame detector fires on blaming prose', () => {
    // POSITIVE CONTROL for every `blameIn(...)` assertion below.
    expect(blameIn('a lapse in engagement').length).toBeGreaterThan(0)
    expect(blameIn('the client failed to complete this').length).toBeGreaterThan(0)
    expect(blameIn('non-compliance with the assignment').length).toBeGreaterThan(0)
    expect(blameIn('they should have opened it').length).toBeGreaterThan(0)
    expect(blameIn('the client has disengaged').length).toBeGreaterThan(0)
    expect(blameIn('Not opened is a fact about the assignment.')).toEqual([])
  })

  it('the refusal detector fires on refusal words', () => {
    // POSITIVE CONTROL for the "silence is not refusal" assertion.
    expect(REFUSAL.test('They can refuse it.')).toBe(true)
    expect(REFUSAL.test('They declined it.')).toBe(true)
    expect(REFUSAL.test('The assignment may simply never be opened.')).toBe(false)
  })
})

/* ── Coverage of the shared vocabulary ──────────────────────────────────────────────────── */

/** Which states of the vocabulary a step list fails to mention. */
function statesMissingFrom(states: AssignmentStatus[]): AssignmentStatus[] {
  const seen = new Set(states)
  return (Object.keys(STATUS_LABEL) as AssignmentStatus[]).filter((s) => !seen.has(s))
}

describe('the lifecycle covers the one shared vocabulary', () => {
  it('the coverage check notices a missing state', () => {
    // POSITIVE CONTROL: the assertions below say "nothing is missing", which would also be true
    // of a checker that could not see a gap. This proves it can.
    const short = LIFECYCLE_ORDER.filter((s) => s !== 'noResponse')
    expect(statesMissingFrom(short)).toEqual(['noResponse'])
    expect(statesMissingFrom(LIFECYCLE_ORDER)).toEqual([])
  })

  it('every state in STATUS_LABEL gets exactly one step, in every configuration', () => {
    expect(Object.keys(STATUS_LABEL).length).toBe(7) // the vocabulary really was read
    for (const preview of everyPreview()) {
      const states = preview.steps.map((s) => s.status)
      expect(statesMissingFrom(states)).toEqual([])
      expect(new Set(states).size).toBe(states.length) // no state twice
      expect(states).toEqual(LIFECYCLE_ORDER)
    }
  })

  it('each step carries STATUS_LABEL’s spelling of its own state', () => {
    for (const step of buildLifecyclePreview(draft()).steps) {
      expect(step.label).toBe(STATUS_LABEL[step.status])
    }
  })

  it('the module does not keep a second copy of any label', () => {
    // The whole point of importing STATUS_LABEL is that "No response" is spelled once in the
    // product. A literal here would drift from the pill without anything noticing.
    const src = readFileSync(fileURLToPath(new URL('./assignLifecycle.ts', import.meta.url)), 'utf8')
    const code = src
      .replace(/\/\*[\s\S]*?\*\//g, '')
      .replace(/(?<!:)\/\/[^\n]*/g, '')
    expect(code).toContain('STATUS_LABEL[status]') // the source really was read and stripped
    const copies = Object.values(STATUS_LABEL).filter((label) => code.includes(`'${label}'`) || code.includes(`"${label}"`))
    expect(copies).toEqual([])
    // POSITIVE CONTROL: the same scan finds a literal when one is really there.
    const doctored = code + `\nconst oops = 'No response'\n`
    expect(Object.values(STATUS_LABEL).filter((l) => doctored.includes(`'${l}'`))).toEqual(['No response'])
  })
})

/* ── No figures, no verdicts, anywhere in the copy ──────────────────────────────────────── */

describe('the copy states facts and counts nothing', () => {
  it('the prose collector reaches every field it is meant to', () => {
    // POSITIVE CONTROL for the two sweeps below: they are only meaningful if the collector is
    // actually returning the strings. Counted against the shape rather than a magic number.
    const preview = buildLifecyclePreview(draft())
    const prose = lifecycleProse(preview)
    const expected =
      1 + // feedbackNote
      (preview.note ? 1 : 0) +
      preview.steps.reduce(
        (n, s) => n + 2 + s.told.length + s.notTold.length + s.ownerCan.length,
        0,
      )
    expect(prose.length).toBe(expected)
    expect(prose.length).toBeGreaterThan(40)
    expect(prose.some((p) => p.includes('inbox'))).toBe(true)
  })

  it('no sentence anywhere contains a compliance figure', () => {
    const prose = ALL_PROSE()
    expect(prose.length).toBeGreaterThan(500) // the sweep really covered every configuration
    const offenders = prose.filter((p) => figuresIn(p).length > 0)
    expect(offenders).toEqual([])
  })

  it('no sentence anywhere blames the person', () => {
    const prose = ALL_PROSE()
    expect(prose.length).toBeGreaterThan(500)
    const offenders = prose.filter((p) => blameIn(p).length > 0)
    expect(offenders).toEqual([])
  })

  it('the only number this module ever prints is the cadence the therapist typed', () => {
    // Stronger and more specific than the figure detector. There is exactly one legitimate
    // number in a lifecycle preview: the count in the cadence the composer supplied, restated
    // in describeCadence's words. Any other digit is a count of something a person did or did
    // not do, and there is no honest one of those.
    const prose = ALL_PROSE()
    const withDigits = prose.filter((p) => /\d/.test(p))
    expect(withDigits.length).toBeGreaterThan(0) // the cadence sentences really are in the sweep
    for (const line of withDigits) {
      expect(line).toContain('A cadence of every 2 weeks') // the fixture's own count, echoed back
      expect(line.match(/\d+/g)).toEqual(['2']) // and it is the only number in the sentence
    }
    // And a draft with no cadence prints no number anywhere at all.
    const noCadence = everyPreview()
      .flatMap(lifecycleProse)
      .filter((p) => !p.includes('A cadence of'))
    expect(noCadence.filter((p) => /\d/.test(p))).toEqual([])
    // POSITIVE CONTROL: the scan sees a digit when one is there.
    expect([...noCadence, 'assigned 3 items'].filter((p) => /\d/.test(p))).toEqual(['assigned 3 items'])
  })
})

/* ── "Not opened" is a fact, and silence is not refusal ─────────────────────────────────── */

describe('the “not opened” state', () => {
  const stepOf = (p: LifecyclePreview, status: AssignmentStatus) =>
    p.steps.find((s) => s.status === status)!

  it('states that nothing escalates and that the reasons belong to the person', () => {
    const step = stepOf(buildLifecyclePreview(draft()), 'noResponse')
    expect(step.what).toContain('never be opened')
    expect(step.what).toContain('Not opened is a fact about the assignment')
    expect(step.what).toContain('nothing escalates')
    expect(step.what).toMatch(/reasons are\s+theirs/)
  })

  it('is never written as a refusal — and the declined step is, which proves the check works', () => {
    const preview = buildLifecyclePreview(draft())
    const silence = stepOf(preview, 'noResponse')
    const refusal = stepOf(preview, 'declined')
    // POSITIVE CONTROL: the declined step really does use refusal vocabulary, so the negative
    // assertion on the noResponse step is not passing against a detector that sees nothing.
    expect(REFUSAL.test([refusal.what, ...refusal.notTold, ...refusal.ownerCan].join(' '))).toBe(true)
    expect(REFUSAL.test([silence.what, ...silence.told, ...silence.notTold, ...silence.ownerCan].join(' '))).toBe(false)
    expect(silence.what).not.toBe(refusal.what)
  })

  it('says the therapist is not told, and offers nothing to act on', () => {
    const step = stepOf(buildLifecyclePreview(draft()), 'noResponse')
    expect(step.told).toEqual([])
    expect(step.notTold.join(' ')).toContain('No lifecycle state is reported back')
    expect(step.ownerCan.length).toBeGreaterThan(0)
  })
})

/* ── What actually comes back ───────────────────────────────────────────────────────────── */

describe('what the therapist will and will not learn', () => {
  const completedOf = (p: LifecyclePreview) => p.steps.find((s) => s.status === 'completed')!

  it('a self-check with read.share is the ONE case where anything can come back', () => {
    // This is the positive control for every `told).toEqual([])` in this block: it proves an
    // occupied `told` is representable and that the field is really being read.
    const preview = buildLifecyclePreview(draft({ type: 'questionnaire' }))
    expect(preview.feedback).toBe('sharedRuns')
    const step = completedOf(preview)
    expect(step.told.length).toBe(1)
    expect(step.told[0]).toContain('includes check-ins')
    expect(step.told[0]).toContain('the band it recorded')
  })

  it('and even then, the run is not attributable to the assignment', () => {
    const step = completedOf(buildLifecyclePreview(draft({ type: 'questionnaire' })))
    expect(step.notTold.join(' ')).toContain('no assignment reference')
    expect(step.notTold.join(' ')).toContain('may be one they took on their own')
    expect(step.notTold.join(' ')).toContain('expire')
  })

  it('a task, a goal, a reminder or a setting can never come back, even with read.share', () => {
    for (const type of ['task', 'reminder', 'goal', 'setting'] as AssignmentType[]) {
      const preview = buildLifecyclePreview(draft({ type }))
      expect(preview.feedback).toBe('nothingShareable')
      const step = completedOf(preview)
      expect(step.told).toEqual([]) // control: the self-check case above has told.length === 1
      expect(step.notTold.join(' ')).toContain('no ')
      expect(step.notTold.join(' ')).toContain('slot')
      expect(step.notTold.join(' ')).toContain('conversation with them')
    }
  })

  it('without read.share nothing comes back at all, not even a self-check run', () => {
    const preview = buildLifecyclePreview(
      draft({ type: 'questionnaire', grant: grant({ 'assign.questionnaire': ALLOW() }) }),
    )
    expect(preview.feedback).toBe('noShareGranted')
    expect(completedOf(preview).told).toEqual([]) // control: the granted case above is non-empty
    expect(completedOf(preview).notTold.join(' ')).toContain('View shared data')
    expect(completedOf(preview).ownerCan.join(' ')).toContain('nothing for them to curate')
  })

  it('a bundle reports on its parts: self-checks may come back, tasks cannot', () => {
    const mixed = buildLifecyclePreview(
      draft({
        type: 'largeAssessment',
        payload: { bundle: [{ kind: 'questionnaire', id: 'a' }, { kind: 'task', id: 'b' }] },
      }),
    )
    expect(mixed.feedback).toBe('sharedRunsPartial')
    expect(completedOf(mixed).told.join(' ')).toContain('self-checks in this bundle')
    expect(completedOf(mixed).notTold.join(' ')).toContain('no slot a task result could go in')

    const questionnairesOnly = buildLifecyclePreview(
      draft({ type: 'largeAssessment', payload: { bundle: [{ kind: 'questionnaire', id: 'a' }] } }),
    )
    expect(questionnairesOnly.feedback).toBe('sharedRuns')

    const tasksOnly = buildLifecyclePreview(
      draft({ type: 'largeAssessment', payload: { bundle: [{ kind: 'task', id: 'b' }] } }),
    )
    expect(tasksOnly.feedback).toBe('nothingShareable')
    expect(completedOf(tasksOnly).told).toEqual([])

    // No payload yet: the composer has a type but not a bundle. The partial wording is used
    // because it is true of every bundle — a bundle's tasks can never come back.
    expect(buildLifecyclePreview(draft({ type: 'largeAssessment' })).feedback).toBe('sharedRunsPartial')
  })

  it('only the publish result is ever reported, and it is reported at the first step', () => {
    for (const preview of everyPreview()) {
      const reporting = preview.steps.filter((s) => s.told.length > 0).map((s) => s.status)
      // 'scheduled' always reports the write's own outcome. 'completed' joins it only in the two
      // share-carrying cases, which the tests above pin individually.
      expect(reporting[0]).toBe('scheduled')
      const rest = reporting.slice(1)
      const expectedRest =
        preview.feedback === 'sharedRuns' || preview.feedback === 'sharedRunsPartial' ? ['completed'] : []
      expect(rest).toEqual(expectedRest)
    }
  })

  it('the delivered, accepted, declined and overdue steps report nothing at all', () => {
    for (const preview of everyPreview()) {
      for (const status of ['delivered', 'accepted', 'declined', 'overdue'] as AssignmentStatus[]) {
        const step = preview.steps.find((s) => s.status === status)!
        expect(step.told).toEqual([])
        expect(step.notTold.length).toBeGreaterThan(0)
      }
    }
  })

  it('every step says something about what is not told and what the person can do', () => {
    for (const preview of everyPreview()) {
      for (const step of preview.steps) {
        expect(step.what.length).toBeGreaterThan(20)
        expect(step.notTold.length).toBeGreaterThan(0)
        expect(step.ownerCan.length).toBeGreaterThan(0)
      }
    }
  })
})

/* ── Apply mode, and the one rule that must not be restated ─────────────────────────────── */

describe('acceptance', () => {
  const acceptedOf = (p: LifecyclePreview) => p.steps.find((s) => s.status === 'accepted')!

  it('requiresAccept agrees with validate.ts for every type and mode', () => {
    // The rule "suggest.setting is never auto" is security-relevant and lives in validate.ts.
    // This pins the preview against the real function rather than against a second copy of it.
    for (const type of ALL_TYPES) {
      for (const apply of ['propose', 'auto'] as ApplyMode[]) {
        const real = buildAssignment({ type, payload: { title: 'x' } }, 'fp-therapist', 1)
        const preview = buildLifecyclePreview({ type, grant: fullGrant(apply) })
        expect(preview.applyMode).toBe(apply)
        expect(preview.requiresAccept).toBe(!shouldAutoApply(real, apply))
      }
    }
  })

  it('a setting always waits for the person, even where the grant says auto', () => {
    const preview = buildLifecyclePreview({ type: 'setting', grant: fullGrant('auto') })
    expect(preview.applyMode).toBe('auto')
    expect(preview.requiresAccept).toBe(true)
    expect(acceptedOf(preview).what).toContain('always waits for them')
    // Control: the same field on a type that DOES auto-apply says the opposite.
    const task = buildLifecyclePreview({ type: 'task', grant: fullGrant('auto') })
    expect(task.requiresAccept).toBe(false)
    expect(acceptedOf(task).what).toContain('without a further step')
  })

  it('propose spells out that not deciding is one of the options', () => {
    const step = acceptedOf(buildLifecyclePreview(draft()))
    expect(step.what).toContain('no time limit on deciding')
    expect(step.ownerCan).toContain('Leave it and never decide.')
  })

  it('auto names the withdrawal the person can make without telling the therapist', () => {
    const step = acceptedOf(buildLifecyclePreview(draft({ type: 'task', grant: fullGrant('auto') })))
    expect(step.ownerCan.join(' ')).toContain('without telling you')
  })
})

/* ── A path the draft cannot take is not described as if it could ───────────────────────── */

describe('an ungranted capability', () => {
  it('is stated, and every step past delivery is marked unreachable', () => {
    const preview = buildLifecyclePreview(draft({ type: 'goal', grant: grant() }))
    expect(preview.granted).toBe(false)
    expect(preview.applyMode).toBeNull()
    expect(preview.note).not.toBeNull()
    expect(preview.note!).toContain('Suggest goals') // describeCapability's own title
    expect(preview.note!).toContain('arrive refused')

    const reachable = preview.steps.filter((s) => s.reachable).map((s) => s.status)
    expect(reachable).toEqual(['scheduled', 'delivered'])
    expect(preview.steps.find((s) => s.status === 'delivered')!.what).toContain('refuses it')
  })

  it('and a granted one has no such note, which is what makes the check above mean something', () => {
    // POSITIVE CONTROL for `note !== null` and the reachability split above.
    const preview = buildLifecyclePreview(draft({ type: 'goal' }))
    expect(preview.granted).toBe(true)
    expect(preview.note).toBeNull()
    expect(preview.steps.every((s) => s.reachable)).toBe(true)
    expect(preview.steps.find((s) => s.status === 'delivered')!.what).toContain('AT THAT MOMENT')
  })
})

/* ── Cadence, and the composer's own phrasing ───────────────────────────────────────────── */

describe('cadence', () => {
  it('is stated at the first step when the draft has one', () => {
    const step = buildLifecyclePreview(draft({ cadence: { every: 'week', count: 2 } })).steps[0]!
    expect(step.status).toBe('scheduled')
    // describeCadence's own words, not a second phrasing.
    expect(step.what).toContain('every 2 weeks')
    expect(step.what).toContain('each occurrence begins again here')
  })

  it('is absent when the draft has none — the control for the assertion above', () => {
    const step = buildLifecyclePreview(draft()).steps[0]!
    expect(step.what).not.toContain('cadence of')
    expect(step.what).toContain('Published and waiting')
  })
})

/* ── Purity ─────────────────────────────────────────────────────────────────────────────── */

describe('purity', () => {
  afterEach(() => vi.restoreAllMocks())

  it('reads no clock', () => {
    // The bug this guards against shipped in TherapistPortal.svelte: a $derived cannot see a
    // Date.now() inside a callee, so it never re-ran. Nothing here may read one.
    vi.spyOn(Date, 'now').mockImplementation(() => {
      throw new Error('assignLifecycle read a clock')
    })
    expect(() => everyPreview()).not.toThrow()
    // Control: the stub really is armed.
    expect(() => Date.now()).toThrow('assignLifecycle read a clock')
  })

  it('does not mutate the draft or the grant it is given', () => {
    const g = fullGrant()
    const snapshot = JSON.stringify(g)
    const d = draft({ grant: g, cadence: { every: 'day', count: 1 } })
    const before = JSON.stringify(d.cadence)
    buildLifecyclePreview(d)
    expect(JSON.stringify(g)).toBe(snapshot)
    expect(JSON.stringify(d.cadence)).toBe(before)
  })

  it('is deterministic', () => {
    const a = buildLifecyclePreview(draft())
    const b = buildLifecyclePreview(draft())
    expect(a).toEqual(b)
  })
})
