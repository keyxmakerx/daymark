import { describe, it, expect } from 'vitest'
import { planLine, validateDialogue } from './dialogue'
import type { Dialogue, DialogueNode, DialogueOption, DialogueSignals } from './dialogue'
import type { Predicate } from '../instruments/types'
import { MAX_PREDICATE_DEPTH } from '../instruments/predicate'
import { MAX_PREDICATE_NODES } from '../instruments/validate'
import { SIGNAL_VOCABULARY_VERSION } from './signals'

/*
 * Two properties are load-bearing here and everything else is detail.
 *
 * ALWAYS SOMETHING TO SAY. docs/COMPANION_DIALOGUE.md: "Every branch set is total. A fallback
 * with no predicate is mandatory, so there is always a line to say. The dialogue can never fall
 * through to nothing." The empty-substrate case is the one that would slip through review — a new
 * install has none of the eight signals, and the person most likely to meet that state is
 * someone who just opened the app for the first time. So: with NO signals at all, every node
 * still produces a line and nothing throws. That is a test, not an assumption.
 *
 * NEVER A DEAD END. A node that renders no line and no option is a validation error rather than
 * a blank panel, and an ending is always something the author wrote down.
 *
 * The fixtures below deliberately include the failing shapes. Each negative test constructs the
 * violation and asserts the validator catches THAT violation, not merely that something failed.
 */

const V = SIGNAL_VOCABULARY_VERSION

const node = (id: string, lines: DialogueNode['lines'], options: DialogueNode['options']): DialogueNode => ({
  id,
  lines,
  options,
})

const dialogue = (nodes: DialogueNode[], entry = nodes[0]?.id ?? 'open'): Dialogue => ({
  id: 'test-dialogue',
  entry,
  signalVocabularyVersion: V,
  nodes,
})

/** A close-enough model of the illustrative flow in DECISIONS_2026-08.md §D1b. */
const companionDialogue = (): Dialogue =>
  dialogue([
    node(
      'open',
      [
        { text: 'It has been about a week. No agenda.', when: { ref: 'daysSinceLastOpen', op: 'gte', value: 7 } },
        { text: 'Morning. No agenda.', when: { ref: 'timeOfDay', op: 'eq', value: 'morning' } },
        { text: 'No agenda.' },
      ],
      [
        { label: 'I am doing alright', next: 'alright' },
        { label: 'It has been rough', next: 'rough' },
        { label: 'Just looking around', end: true },
        { label: 'Not now', end: true },
      ],
    ),
    node(
      'rough',
      [
        {
          text: 'Three of your last seven check-ins were on the harder end.',
          when: { ref: 'hardDaysLast7', op: 'gte', value: 3 },
        },
        { text: 'Thanks for saying so.' },
      ],
      [
        { label: 'Want something to try?', next: 'offer' },
        { label: 'I just wanted to say it out loud', end: true },
      ],
    ),
    node('alright', [{ text: 'Good to hear.' }], [{ label: 'Close', end: true }]),
    node(
      'offer',
      [
        {
          text: 'There is an exercise your therapist set up.',
          when: { ref: 'prescribedModules', op: 'includes', value: 'compassion-01' },
        },
        { text: 'There is something on your own list.' },
      ],
      [{ label: 'Close', end: true }],
    ),
  ])

/* --- planLine ----------------------------------------------------------------------------- */

describe('planLine — first match wins', () => {
  const n = node(
    'n',
    [
      { text: 'first', when: { ref: 'checkInsLast7', op: 'gte', value: 1 } },
      { text: 'second', when: { ref: 'checkInsLast7', op: 'gte', value: 0 } },
      { text: 'fallback' },
    ],
    [{ label: 'Close', end: true }],
  )

  it('takes the earliest candidate whose predicate matches, not the best fit', () => {
    const plan = planLine(n, { checkInsLast7: 4 })
    expect(plan?.line.text).toBe('first')
    expect(plan?.index).toBe(0)
  })

  it('skips candidates that do not match', () => {
    const plan = planLine(n, { checkInsLast7: 0 })
    expect(plan?.line.text).toBe('second')
    expect(plan?.index).toBe(1)
  })

  it('reaches the fallback when nothing matches', () => {
    const plan = planLine(n, { checkInsLast7: -1 })
    expect(plan?.line.text).toBe('fallback')
    expect(plan?.fallback).toBe(true)
    expect(plan?.when).toBeNull()
  })
})

describe('planLine — why this line', () => {
  it('hands back the predicate that fired, so an editor can show its reason', () => {
    const when: Predicate = { ref: 'hardDaysLast7', op: 'gte', value: 3 }
    const n = node('n', [{ text: 'reflected', when }, { text: 'fallback' }], [{ label: 'Close', end: true }])
    const plan = planLine(n, { hardDaysLast7: 3 })
    // The authored object itself, not a copy or a rendering of it: the UI decides the wording.
    expect(plan?.when).toBe(when)
    expect(plan?.fallback).toBe(false)
  })

  it('reports no predicate for the fallback, and says so twice over', () => {
    const plan = planLine(node('n', [{ text: 'fallback' }], [{ label: 'Close', end: true }]), {})
    expect(plan?.when).toBeNull()
    expect(plan?.fallback).toBe(true)
  })
})

describe('planLine — the empty substrate (a new install has no history)', () => {
  const d = companionDialogue()

  it('produces a line for every node with no signals at all', () => {
    for (const n of d.nodes) {
      const plan = planLine(n, {})
      expect(plan, `${n.id} produced nothing`).not.toBeNull()
      expect(plan?.line.text.length, `${n.id} produced an empty line`).toBeGreaterThan(0)
      // With nothing known, every predicate fails closed, so the mandatory fallback is what shows.
      expect(plan?.fallback, `${n.id} branched on a signal it was never given`).toBe(true)
    }
  })

  it('never throws for any node, under no signal set, including hostile ones', () => {
    const substrates: DialogueSignals[] = [
      {},
      { daysSinceLastOpen: 0 },
      { daysSinceLastOpen: 9, timeOfDay: 'morning', hardDaysLast7: 3, prescribedModules: ['compassion-01'] },
      { hasSafetyPlan: false },
      // Shapes a host should never send, which must still not take the runner down.
      { daysSinceLastOpen: undefined },
      { prescribedModules: [] },
      null as unknown as DialogueSignals,
      undefined as unknown as DialogueSignals,
      'nonsense' as unknown as DialogueSignals,
      { toString: 'shadowed' } as unknown as DialogueSignals,
    ]
    for (const signals of substrates) {
      for (const n of d.nodes) {
        expect(() => planLine(n, signals), `${n.id}`).not.toThrow()
        expect(planLine(n, signals), `${n.id}`).not.toBeNull()
      }
    }
  })

  it('still branches once the substrate fills in', () => {
    // The same dialogue, same nodes — only the signals changed. Nothing here is inferred: the
    // count is what the person logged.
    const open = d.nodes[0]
    expect(planLine(open, { daysSinceLastOpen: 8 })?.index).toBe(0)
    expect(planLine(open, { timeOfDay: 'morning' })?.index).toBe(1)
    expect(planLine(open, { daysSinceLastOpen: 1, timeOfDay: 'evening' })?.index).toBe(2)
  })
})

describe('planLine — unknown and absent signals fail closed', () => {
  const closedFor = (op: 'eq' | 'ne' | 'gte' | 'in') =>
    node(
      'n',
      [
        { text: 'branch', when: { ref: 'notASignal', op, value: op === 'in' ? [1, 2] : 1 } as Predicate },
        { text: 'fallback' },
      ],
      [{ label: 'Close', end: true }],
    )

  it('hides a branch on a signal that does not exist — for `ne` too', () => {
    // Finding 1: `undefined !== 1` is true, so `ne` alone used to fail OPEN and show content
    // authored against a signal a later release renamed.
    for (const op of ['eq', 'ne', 'gte', 'in'] as const) {
      expect(planLine(closedFor(op), {})?.line.text, op).toBe('fallback')
    }
  })

  it('treats an explicitly undefined signal as absent, not as a value to compare against', () => {
    const n = node(
      'n',
      [{ text: 'branch', when: { ref: 'checkInsLast7', op: 'ne', value: 3 } }, { text: 'fallback' }],
      [{ label: 'Close', end: true }],
    )
    expect(planLine(n, { checkInsLast7: undefined })?.line.text).toBe('fallback')
    expect(planLine(n, { checkInsLast7: 3 })?.line.text).toBe('fallback')
    expect(planLine(n, { checkInsLast7: 4 })?.line.text).toBe('branch')
  })

  it('does not resolve inherited object properties as signals', () => {
    for (const ref of ['toString', 'constructor', 'hasOwnProperty', '__proto__']) {
      const n = node(
        'n',
        [{ text: 'branch', when: { ref, op: 'ne', value: 'x' } }, { text: 'fallback' }],
        [{ label: 'Close', end: true }],
      )
      expect(planLine(n, {})?.line.text, ref).toBe('fallback')
    }
  })

  it('ignores a key the host supplied that is not in the closed vocabulary', () => {
    const n = node(
      'n',
      [{ text: 'branch', when: { ref: 'moodScore', op: 'gte', value: 1 } }, { text: 'fallback' }],
      [{ label: 'Close', end: true }],
    )
    expect(planLine(n, { moodScore: 9 } as unknown as DialogueSignals)?.line.text).toBe('fallback')
  })
})

describe('planLine — never throws', () => {
  const withWhen = (when: Predicate) =>
    node('n', [{ text: 'branch', when }, { text: 'fallback' }], [{ label: 'Close', end: true }])

  it('falls through a predicate too deep to evaluate rather than crashing the runner', () => {
    let deep: Predicate = { ref: 'checkInsLast7', op: 'gte', value: 0 }
    for (let i = 0; i < MAX_PREDICATE_DEPTH + 4; i++) deep = { all: [deep] }
    const n = withWhen(deep)
    expect(() => planLine(n, { checkInsLast7: 5 })).not.toThrow()
    expect(planLine(n, { checkInsLast7: 5 })?.line.text).toBe('fallback')
  })

  it('falls through a malformed predicate node', () => {
    const n = withWhen({ nonsense: true } as unknown as Predicate)
    expect(() => planLine(n, {})).not.toThrow()
    expect(planLine(n, {})?.line.text).toBe('fallback')
  })

  it('returns null for a node with no lines instead of throwing', () => {
    // validateDialogue rejects this at authoring time; the planner still has to survive it,
    // because content arrives from outside this process.
    expect(planLine(node('n', [], [{ label: 'Close', end: true }]), {})).toBeNull()
    expect(planLine({ id: 'n' } as unknown as DialogueNode, {})).toBeNull()
    expect(() => planLine(null as unknown as DialogueNode, {})).not.toThrow()
  })

  it('does not mutate the signals it was given', () => {
    const signals: DialogueSignals = { checkInsLast7: 2 }
    planLine(companionDialogue().nodes[0], signals)
    expect(signals).toEqual({ checkInsLast7: 2 })
  })
})

/* --- validateDialogue --------------------------------------------------------------------- */

describe('validateDialogue — a well-formed dialogue', () => {
  it('accepts the illustrative companion flow', () => {
    expect(validateDialogue(companionDialogue())).toEqual([])
  })

  it('accepts it for an app author, whose partition is the whole vocabulary', () => {
    expect(validateDialogue(companionDialogue(), 'app')).toEqual([])
  })

  it('returns errors rather than throwing, so an editor can show them all at once', () => {
    let errors: string[] = []
    expect(() => {
      errors = validateDialogue(dialogue([node('a', [], [])], 'nope'))
    }).not.toThrow()
    expect(errors.length).toBeGreaterThan(1)
  })
})

describe('validateDialogue — the mandatory fallback', () => {
  it('rejects a node whose last line is conditional', () => {
    const d = dialogue([
      node(
        'open',
        [
          { text: 'a', when: { ref: 'checkInsLast7', op: 'gte', value: 1 } },
          { text: 'b', when: { ref: 'checkInsLast7', op: 'lt', value: 1 } },
        ],
        [{ label: 'Close', end: true }],
      ),
    ])
    const errors = validateDialogue(d)
    expect(errors).toHaveLength(1)
    expect(errors[0]).toMatch(/fallback/i)
    expect(errors[0]).toContain('open')
  })

  it('catches the fallback that is only missing on one node of several', () => {
    const d = companionDialogue()
    const rough = d.nodes[1]
    const broken = node(
      rough.id,
      [{ text: rough.lines[0].text, when: rough.lines[0].when }],
      rough.options,
    )
    const errors = validateDialogue(dialogue([d.nodes[0], broken, d.nodes[2], d.nodes[3]]))
    expect(errors).toHaveLength(1)
    expect(errors[0]).toContain('rough')
  })

  it('proves the rule bites: without it, a node could say nothing', () => {
    // The same node the validator just rejected, planned with no signals.
    const n = node(
      'open',
      [{ text: 'a', when: { ref: 'checkInsLast7', op: 'gte', value: 1 } }],
      [{ label: 'Close', end: true }],
    )
    expect(validateDialogue(dialogue([n]))).toHaveLength(1)
    expect(planLine(n, {})).toBeNull()
  })

  it('rejects an unconditional candidate that is not last, since nothing after it can win', () => {
    const d = dialogue([
      node(
        'open',
        [{ text: 'always' }, { text: 'never', when: { ref: 'checkInsLast7', op: 'gte', value: 1 } }, { text: 'fallback' }],
        [{ label: 'Close', end: true }],
      ),
    ])
    const errors = validateDialogue(d)
    expect(errors).toHaveLength(1)
    expect(errors[0]).toMatch(/no candidate after it/i)
  })
})

describe('validateDialogue — nodes and lines', () => {
  it('rejects a node with no lines', () => {
    const errors = validateDialogue(dialogue([node('open', [], [{ label: 'Close', end: true }])]))
    expect(errors).toHaveLength(1)
    expect(errors[0]).toMatch(/no lines/)
    expect(errors[0]).toContain('open')
  })

  it('rejects a line with no text — a rendered blank is still a blank', () => {
    const errors = validateDialogue(dialogue([node('open', [{ text: '  ' }], [{ label: 'Close', end: true }])]))
    expect(errors).toHaveLength(1)
    expect(errors[0]).toMatch(/no text/)
  })

  it('rejects a duplicate node id, which would make a `next` ambiguous', () => {
    const a = node('open', [{ text: 'a' }], [{ label: 'Close', end: true }])
    const b = node('open', [{ text: 'b' }], [{ label: 'Close', end: true }])
    const errors = validateDialogue(dialogue([a, b]))
    expect(errors).toHaveLength(1)
    expect(errors[0]).toMatch(/declared more than once/)
  })

  it('rejects a missing or unknown entry node', () => {
    const n = node('open', [{ text: 'a' }], [{ label: 'Close', end: true }])
    expect(validateDialogue(dialogue([n], 'elsewhere'))[0]).toMatch(/entry node "elsewhere" does not exist/)
    expect(validateDialogue(dialogue([n], ''))[0]).toMatch(/no entry node/)
  })

  it('requires the signal vocabulary version, so drift can fail loudly later', () => {
    const d = { ...dialogue([node('open', [{ text: 'a' }], [{ label: 'Close', end: true }])]) }
    delete (d as { signalVocabularyVersion?: number }).signalVocabularyVersion
    expect(validateDialogue(d)[0]).toMatch(/signalVocabularyVersion/)
  })
})

describe('validateDialogue — options, endings and dead ends', () => {
  it('rejects an option pointing at a node that does not exist', () => {
    const d = dialogue([node('open', [{ text: 'a' }], [{ label: 'Onward', next: 'ghost' }])])
    const errors = validateDialogue(d)
    expect(errors).toHaveLength(1)
    expect(errors[0]).toMatch(/points at node "ghost", which does not exist/)
  })

  it('rejects a node offering no options at all', () => {
    const d = dialogue([node('open', [{ text: 'a' }], [])])
    const errors = validateDialogue(d)
    expect(errors).toHaveLength(1)
    expect(errors[0]).toMatch(/dead end/)
  })

  it('rejects an option that neither leads anywhere nor ends', () => {
    const d = dialogue([node('open', [{ text: 'a' }], [{ label: 'Hmm' } as unknown as DialogueOption])])
    const errors = validateDialogue(d)
    expect(errors).toHaveLength(1)
    expect(errors[0]).toMatch(/must name a next node or end explicitly/)
  })

  it('rejects an option that both ends and leads somewhere', () => {
    const d = dialogue([
      node('open', [{ text: 'a' }], [{ label: 'Both', next: 'open', end: true } as unknown as DialogueOption]),
    ])
    expect(validateDialogue(d)[0]).toMatch(/both ends and points at/)
  })

  it('rejects an option with no label', () => {
    const d = dialogue([node('open', [{ text: 'a' }], [{ label: '', end: true }])])
    expect(validateDialogue(d)[0]).toMatch(/no label/)
  })

  it('accepts an explicit ending as a complete conversation', () => {
    // "I just wanted to say it out loud" is a first-class ending: nothing has to follow it.
    const d = dialogue([node('open', [{ text: 'a' }], [{ label: 'I just wanted to say it out loud', end: true }])])
    expect(validateDialogue(d)).toEqual([])
  })
})

describe('validateDialogue — reachability', () => {
  it('rejects a node nothing points at', () => {
    const d = dialogue([
      node('open', [{ text: 'a' }], [{ label: 'Close', end: true }]),
      node('orphan', [{ text: 'b' }], [{ label: 'Close', end: true }]),
    ])
    const errors = validateDialogue(d)
    expect(errors).toHaveLength(1)
    expect(errors[0]).toMatch(/node "orphan" is unreachable from the entry node "open"/)
  })

  it('rejects a cluster that only points at itself', () => {
    const d = dialogue([
      node('open', [{ text: 'a' }], [{ label: 'Close', end: true }]),
      node('island-a', [{ text: 'b' }], [{ label: 'Onward', next: 'island-b' }]),
      node('island-b', [{ text: 'c' }], [{ label: 'Back', next: 'island-a' }]),
    ])
    const errors = validateDialogue(d)
    expect(errors).toHaveLength(2)
    expect(errors.join(' ')).toMatch(/island-a/)
    expect(errors.join(' ')).toMatch(/island-b/)
  })

  it('follows options through several hops, and tolerates a loop back', () => {
    const d = dialogue([
      node('open', [{ text: 'a' }], [{ label: 'Onward', next: 'two' }]),
      node('two', [{ text: 'b' }], [{ label: 'Onward', next: 'three' }]),
      node('three', [{ text: 'c' }], [
        { label: 'Back to the start', next: 'open' },
        { label: 'Close', end: true },
      ]),
    ])
    expect(validateDialogue(d)).toEqual([])
  })

  it('does not also claim unreachability when the entry itself is missing', () => {
    const d = dialogue([node('open', [{ text: 'a' }], [{ label: 'Close', end: true }])], 'ghost')
    const errors = validateDialogue(d)
    expect(errors).toHaveLength(1)
    expect(errors[0]).toMatch(/entry node/)
  })
})

describe('validateDialogue — predicate bounds (finding 2)', () => {
  const withWhen = (when: Predicate) =>
    dialogue([node('open', [{ text: 'a', when }, { text: 'fallback' }], [{ label: 'Close', end: true }])])

  it('rejects a `when` nested deeper than the evaluator will walk', () => {
    let deep: Predicate = { ref: 'checkInsLast7', op: 'gte', value: 0 }
    for (let i = 0; i < MAX_PREDICATE_DEPTH + 2; i++) deep = { all: [deep] }
    expect(validateDialogue(withWhen(deep))[0]).toMatch(/deeper than/)
  })

  it('rejects a `when` with more nodes than the bound', () => {
    const wide: Predicate = {
      all: Array.from({ length: MAX_PREDICATE_NODES + 10 }, () => ({
        ref: 'checkInsLast7',
        op: 'gte' as const,
        value: 0,
      })),
    }
    expect(validateDialogue(withWhen(wide))[0]).toMatch(/predicate nodes/)
  })

  it('survives the tree that took the evaluator down, rather than blowing its own stack', () => {
    let huge: Predicate = { ref: 'checkInsLast7', op: 'gte', value: 0 }
    for (let i = 0; i < 50_000; i++) huge = { all: [huge] }
    let errors: string[] = []
    expect(() => {
      errors = validateDialogue(withWhen(huge))
    }).not.toThrow()
    expect(errors.length).toBeGreaterThan(0)
  })

  it('accepts ordinary nesting, which is two or three deep', () => {
    const ordinary: Predicate = {
      all: [
        { ref: 'hardDaysLast7', op: 'gte', value: 3 },
        { any: [{ ref: 'timeOfDay', op: 'eq', value: 'evening' }, { ref: 'checkInsLast7', op: 'gte', value: 1 }] },
      ],
    }
    expect(validateDialogue(withWhen(ordinary))).toEqual([])
  })
})

describe('validateDialogue — the author partition (finding 3)', () => {
  const branchingOn = (ref: string) =>
    dialogue([
      node(
        'open',
        [{ text: 'a', when: { ref, op: 'gte', value: 3 } }, { text: 'fallback' }],
        [{ label: 'Close', end: true }],
      ),
    ])

  it('refuses a therapist-authored branch on the person’s private history', () => {
    // The arms of the branch differ in ways the therapist can already observe, so the branch is
    // an oracle for what it tested. Nothing is transmitted; the inference is in the shape.
    const errors = validateDialogue(branchingOn('hardDaysLast7'), 'therapist')
    expect(errors).toHaveLength(1)
    expect(errors[0]).toContain('hardDaysLast7')
  })

  it('refuses a therapist-authored branch on whether a safety plan exists', () => {
    const errors = validateDialogue(branchingOn('hasSafetyPlan'), 'therapist')
    expect(errors).toHaveLength(1)
    expect(errors[0]).toMatch(/safety plan/i)
  })

  it('allows a therapist the signals they may see', () => {
    expect(validateDialogue(branchingOn('prescribedModules'), 'therapist')).toEqual([])
    expect(validateDialogue(branchingOn('timeOfDay'), 'therapist')).toEqual([])
  })

  it('allows the app the whole vocabulary', () => {
    expect(validateDialogue(branchingOn('hardDaysLast7'), 'app')).toEqual([])
    expect(validateDialogue(branchingOn('hasSafetyPlan'), 'app')).toEqual([])
  })

  it('rejects a signal that is in no vocabulary, for either author', () => {
    expect(validateDialogue(branchingOn('moodScore'), 'app')).toHaveLength(1)
    expect(validateDialogue(branchingOn('moodScore'), 'therapist')).toHaveLength(1)
  })

  it('checks refs only when an author is supplied, and reports each one once', () => {
    // Omitted author = shape only, matching validateDialogueDefinition. The upload path passes one.
    expect(validateDialogue(branchingOn('hardDaysLast7'))).toEqual([])
    const twice = dialogue([
      node(
        'open',
        [
          { text: 'a', when: { ref: 'hardDaysLast7', op: 'gte', value: 3 } },
          { text: 'b', when: { ref: 'hardDaysLast7', op: 'gte', value: 1 } },
          { text: 'fallback' },
        ],
        [{ label: 'Close', end: true }],
      ),
    ])
    expect(validateDialogue(twice, 'therapist')).toHaveLength(1)
  })

  it('finds refs nested inside all/any, not just at the top', () => {
    const d = dialogue([
      node(
        'open',
        [
          {
            text: 'a',
            when: {
              all: [
                { ref: 'timeOfDay', op: 'eq', value: 'evening' },
                // `gte 1` rather than `eq true`: Predicate.value admits no boolean, and the
                // evaluator coerces with Number(), so this is how a bool signal is asked about.
                { any: [{ ref: 'hasSafetyPlan', op: 'gte', value: 1 }] },
              ],
            },
          },
          { text: 'fallback' },
        ],
        [{ label: 'Close', end: true }],
      ),
    ])
    const errors = validateDialogue(d, 'therapist')
    expect(errors).toHaveLength(1)
    expect(errors[0]).toContain('hasSafetyPlan')
  })
})

describe('validateDialogue — hostile and half-built input', () => {
  it('never throws on structurally broken content', () => {
    const junk: unknown[] = [
      null,
      undefined,
      {},
      { id: 'x' },
      { id: 'x', entry: 'a', signalVocabularyVersion: V, nodes: 'not-an-array' },
      { id: 'x', entry: 'a', signalVocabularyVersion: V, nodes: [null] },
      { id: 'x', entry: 'a', signalVocabularyVersion: V, nodes: [{ id: 'a', lines: 'no', options: 'no' }] },
      { id: 'x', entry: 'a', signalVocabularyVersion: V, nodes: [{ id: 'a', lines: [null], options: [null] }] },
    ]
    for (const d of junk) {
      expect(() => validateDialogue(d as Dialogue), JSON.stringify(d)).not.toThrow()
      expect(validateDialogue(d as Dialogue).length, JSON.stringify(d)).toBeGreaterThan(0)
    }
  })

  it('reports a cyclic predicate rather than hanging on it', () => {
    const cyclic: { all: unknown[] } = { all: [] }
    cyclic.all.push(cyclic)
    const d = dialogue([
      node(
        'open',
        [{ text: 'a', when: cyclic as unknown as Predicate }, { text: 'fallback' }],
        [{ label: 'Close', end: true }],
      ),
    ])
    let errors: string[] = []
    expect(() => {
      errors = validateDialogue(d)
    }).not.toThrow()
    expect(errors.length).toBeGreaterThan(0)
  })
})
