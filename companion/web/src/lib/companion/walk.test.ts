import { describe, it, expect } from 'vitest'
import { start, view, choose, reachableNodes, type WalkState } from './walk'
import { COMPANION_DIALOGUE, destinationFor } from './content'
import { validateDialogue, type Dialogue, type DialogueSignals } from './dialogue'
import { SIGNAL_NAMES } from './signals'

/*
 * The conversation, executed rather than read.
 *
 * The component that renders this can only be source-asserted (no DOM harness), so everything with
 * behaviour lives in walk.ts and is tested here against the real shipped dialogue — not a fixture,
 * because a fixture would drift and the property that matters is about the content people see.
 */

/** Every combination worth walking: signals absent, at boundaries, and past them. */
function signalMatrix(): DialogueSignals[] {
  const out: DialogueSignals[] = [{}]
  for (const days of [0, 5, 14]) {
    for (const hard of [0, 3, 5]) {
      for (const checkIns of [0, 1, 4]) {
        for (const plan of [true, false]) {
          for (const time of ['morning', 'evening', 'night'] as const) {
            out.push({
              daysSinceLastOpen: days,
              hardDaysLast7: hard,
              checkInsLast7: checkIns,
              hasSafetyPlan: plan,
              timeOfDay: time,
              prescribedModules: hard >= 3 ? ['compassion-hard-moment'] : [],
            })
          }
        }
      }
    }
  }
  return out
}

const MATRIX = signalMatrix()

/** Walk to an ending by always taking `optionIndex`, bounded so a loop cannot hang the suite. */
function walkTo(signals: DialogueSignals, optionIndex: number, limit = 40): WalkState[] {
  let s = start(COMPANION_DIALOGUE)
  const trail: WalkState[] = [s]
  for (let i = 0; i < limit && !s.ended; i++) {
    const v = view(s, COMPANION_DIALOGUE, signals)
    if (!v.options.length) break
    s = choose(s, Math.min(optionIndex, v.options.length - 1), COMPANION_DIALOGUE, signals)
    trail.push(s)
  }
  return trail
}

describe('the shipped dialogue is well formed', () => {
  it('passes its own validator', () => {
    expect(validateDialogue(COMPANION_DIALOGUE)).toEqual([])
  })

  it('has no unreachable nodes', () => {
    const reachable = reachableNodes(COMPANION_DIALOGUE)
    const declared = COMPANION_DIALOGUE.nodes.map((n) => n.id)
    expect(declared.filter((id) => !reachable.has(id))).toEqual([])
  })

  it('was authored against signals that still exist', () => {
    // Content outliving its vocabulary is the drift failure the whole design worries about.
    const refs = new Set<string>()
    const collect = (p: unknown): void => {
      if (!p || typeof p !== 'object') return
      const o = p as Record<string, unknown>
      if (Array.isArray(o.all)) o.all.forEach(collect)
      if (Array.isArray(o.any)) o.any.forEach(collect)
      if (typeof o.ref === 'string') refs.add(o.ref)
    }
    for (const n of COMPANION_DIALOGUE.nodes) for (const l of n.lines) collect(l.when)
    expect(refs.size).toBeGreaterThan(0) // the sweep must have found something to check
    for (const r of refs) expect(SIGNAL_NAMES, `signal "${r}" no longer exists`).toContain(r)
  })
})

describe('every walk terminates and always has something to say', () => {
  it('never leaves a node without a line, for any signal set', () => {
    for (const signals of MATRIX) {
      for (const node of COMPANION_DIALOGUE.nodes) {
        const v = view({ nodeId: node.id, visited: [], ended: false, destination: null }, COMPANION_DIALOGUE, signals)
        expect(v.line, `node ${node.id} said nothing`).toBeTruthy()
      }
    }
  })

  it('reaches an ending from every entry path, under every signal set', () => {
    for (const signals of MATRIX) {
      for (let opt = 0; opt < 4; opt++) {
        const trail = walkTo(signals, opt)
        expect(trail[trail.length - 1].ended, `option ${opt} never ended`).toBe(true)
      }
    }
  })

  it('works with no signals at all — the new-install case', () => {
    const trail = walkTo({}, 0)
    expect(trail[trail.length - 1].ended).toBe(true)
    expect(view(trail[0], COMPANION_DIALOGUE, {}).line).toBeTruthy()
  })
})

describe('it refuses to walk someone in a circle', () => {
  const looping: Dialogue = {
    id: 'loop',
    entry: 'a',
    signalVocabularyVersion: 1,
    nodes: [
      { id: 'a', lines: [{ text: 'a' }], options: [{ label: 'to b', next: 'b' }] },
      { id: 'b', lines: [{ text: 'b' }], options: [{ label: 'back to a', next: 'a' }] },
    ],
  } as Dialogue

  it('ends rather than revisiting a node', () => {
    let s = start(looping)
    s = choose(s, 0, looping, {}) // a -> b
    expect(s.nodeId).toBe('b')
    expect(s.ended).toBe(false)
    s = choose(s, 0, looping, {}) // b -> a would loop
    expect(s.ended).toBe(true)
  })

  it('ends on an option pointing at a node that does not exist', () => {
    const broken = { ...looping, nodes: [{ id: 'a', lines: [{ text: 'a' }], options: [{ label: 'nowhere', next: 'ghost' }] }] } as Dialogue
    expect(choose(start(broken), 0, broken, {}).ended).toBe(true)
  })

  it('ends on an out-of-range option index instead of throwing', () => {
    expect(() => choose(start(COMPANION_DIALOGUE), 99, COMPANION_DIALOGUE, {})).not.toThrow()
    expect(choose(start(COMPANION_DIALOGUE), 99, COMPANION_DIALOGUE, {}).ended).toBe(true)
  })

  it('is inert once ended — a second choice changes nothing', () => {
    const ended = choose(start(COMPANION_DIALOGUE), 99, COMPANION_DIALOGUE, {})
    expect(choose(ended, 0, COMPANION_DIALOGUE, {})).toEqual(ended)
  })

  it('renders no options once ended, so there is nothing left to press', () => {
    const ended = choose(start(COMPANION_DIALOGUE), 99, COMPANION_DIALOGUE, {})
    expect(view(ended, COMPANION_DIALOGUE, {}).options).toEqual([])
  })
})

describe('endings resolve their destination from the line that actually fired', () => {
  it('only ever produces a destination the content declares', () => {
    const declared = new Set(
      COMPANION_DIALOGUE.nodes.flatMap((n) => n.lines.map((_, i) => destinationFor(n.id, i))).filter(Boolean),
    )
    expect(declared.size).toBeGreaterThan(0) // the map must be non-empty or this proves nothing
    for (const signals of MATRIX) {
      for (let opt = 0; opt < 4; opt++) {
        const end = walkTo(signals, opt).at(-1) as WalkState
        if (end.destination) expect(declared).toContain(end.destination)
      }
    }
  })

  it('offers a prescribed module only when one is prescribed', () => {
    // The destination must follow the signals, not the option label.
    for (const signals of MATRIX) {
      for (let opt = 0; opt < 4; opt++) {
        const end = walkTo(signals, opt).at(-1) as WalkState
        if (end.destination?.startsWith('module:')) {
          const id = end.destination.slice('module:'.length)
          expect(signals.prescribedModules ?? [], `offered ${id} unprescribed`).toContain(id)
        }
      }
    }
  })

  it('never points at the safety plan when the person has not written one', () => {
    for (const signals of MATRIX) {
      for (let opt = 0; opt < 4; opt++) {
        const end = walkTo(signals, opt).at(-1) as WalkState
        if (end.destination === 'safety-plan') expect(signals.hasSafetyPlan).toBe(true)
      }
    }
  })
})

describe('the voice holds across every reachable line', () => {
  const LABELS = /\byou (are|seem|sound|might be|must be)\b|\byou'?re (depressed|anxious|struggling|unwell)\b/i
  const GRATITUDE = /thank you for (checking in|sharing|opening)|proud of you|well done/i
  const STREAK = /\bstreak\b|\bdays? in a row\b|keep it up/i

  it('the guards are not vacuous', () => {
    expect(LABELS.test('you seem depressed')).toBe(true)
    expect(GRATITUDE.test('Thank you for checking in')).toBe(true)
    expect(STREAK.test("that's a 5 day streak")).toBe(true)
  })

  it('no line labels the person, thanks them for engaging, or mentions a streak', () => {
    const lines = COMPANION_DIALOGUE.nodes.flatMap((n) => n.lines.map((l) => l.text))
    expect(lines.length).toBeGreaterThan(10)
    for (const text of lines) {
      expect(LABELS.test(text), `labels: ${text}`).toBe(false)
      expect(GRATITUDE.test(text), `gratitude: ${text}`).toBe(false)
      expect(STREAK.test(text), `streak: ${text}`).toBe(false)
    }
  })
})
