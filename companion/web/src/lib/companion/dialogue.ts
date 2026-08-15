/*
 * Companion dialogue — the format, and the pure planner that walks it.
 *
 * docs/COMPANION_DIALOGUE.md, "Two editors, one format": the app authors dialogue and so may a
 * clinician, through the direct editor or the guided one, and all of them produce THIS shape.
 * One artifact, two ways in. Two formats would fork the honesty gate, the signing path and the
 * security partition, and would eventually disagree.
 *
 * Everything here is pure: no I/O, no clock, no device state, no Svelte. The host computes the
 * eight signals (./signals.ts) and hands them in.
 *
 * Three robustness rules from that document are structural here rather than left to an author's
 * discipline:
 *
 *   1. EVERY BRANCH SET IS TOTAL. A node's `lines` are ordered candidates, first match wins, and
 *      the LAST candidate carries no predicate. The dialogue can never fall through to nothing,
 *      so there is always something to say.
 *   2. NO DEAD ENDS. Every node offers options, and each option either names the next node or
 *      ends the conversation explicitly. A panel with no line and no option is a validation
 *      error, not a blank box. "I just wanted to say it out loud" is a first-class ending
 *      (DECISIONS_2026-08.md §D1b) — it is an option carrying `end`, and it needs no further node.
 *   3. MISSING SIGNALS ARE NORMAL. A new install has no history. The empty substrate is the first
 *      case the planner is written for, not an exception it defends against: with no signals at
 *      all, every node still produces its fallback line and nothing throws.
 *
 * What this module deliberately does NOT do:
 *
 *   - It does not decide whether the companion may speak at all. That is the arbiter's single
 *     question (§D1) and it is not asked here.
 *   - It does not infer anything about anybody. A predicate reflects what the person logged and
 *     selects an authored line; no line is ever chosen because of what someone is taken to *be*
 *     (§D1b, reflect-never-label).
 *   - It does not accept free text. Options are a fixed, authored list (§D1b, and §D6 records
 *     that a chat box is deliberately not built).
 */
import { evalPredicate, MAX_PREDICATE_DEPTH } from '../instruments/predicate'
import type { Answers } from '../instruments/predicate'
import type { Predicate } from '../instruments/types'
import { MAX_PREDICATE_NODES } from '../instruments/validate'
import { SIGNAL_NAMES, assertRefsAllowed } from './signals'
import type { SignalAuthor, SignalName } from './signals'

/* --- the format --------------------------------------------------------------------------- */

/**
 * One candidate line.
 *
 * `when` absent means "always" — which is why the last candidate in a node must not have one.
 * There is no line id: a line is identified by its position in its node, which is what
 * `PlannedLine.index` reports, and inventing a second identifier only creates something for two
 * editors to disagree about.
 */
export interface DialogueLine {
  text: string
  /** Evaluated against the signals. Omitted on the mandatory fallback. */
  when?: Predicate
}

/**
 * One thing the person can choose. Either it leads to another node or it ends the conversation,
 * never both and never neither — `end` is the only way out, so an ending is always deliberate.
 */
export type DialogueOption =
  | { label: string; next: string; end?: never }
  | { label: string; end: true; next?: never }

export interface DialogueNode {
  id: string
  /** Ordered candidates. First match wins; the last must carry no `when`. */
  lines: readonly DialogueLine[]
  /** At least one. A node with none is a dead end, which is a validation error. */
  options: readonly DialogueOption[]
}

export interface Dialogue {
  id: string
  /** Id of the node the conversation opens at. Must name a node in `nodes`. */
  entry: string
  /**
   * Which signal set this was authored against (./signals.ts, SIGNAL_VOCABULARY_VERSION).
   * Recorded so that content outliving the app version it was written for fails loudly rather
   * than silently changing behaviour. Comparing it against the running vocabulary is the
   * loader's job — it is the one that knows what "current" means and can refuse the content
   * whole; validation here only insists the stamp exists.
   */
  signalVocabularyVersion: number
  nodes: readonly DialogueNode[]
}

/**
 * The substrate a dialogue branches on. Keys are the closed vocabulary; every one is optional
 * because a new install has none of them.
 *
 * Values are loosely typed on purpose: ./signals.ts owns what each signal *is*, and spelling the
 * enum members out again here would create a second source of truth for them.
 */
export type SignalValue = number | boolean | string | readonly string[]
export type DialogueSignals = Partial<Record<SignalName, SignalValue>>

/* --- the planner -------------------------------------------------------------------------- */

/** The chosen line, and why it was chosen. */
export interface PlannedLine {
  line: DialogueLine
  /** Position of the chosen candidate within `node.lines`. */
  index: number
  /**
   * The predicate that fired, or null when the fallback was reached. This is the authored
   * predicate itself, not a rendering of it: it is what lets an editor answer "why this line",
   * and how that gets phrased is the UI's decision, not this module's.
   */
  when: Predicate | null
  /** True when the line was chosen because nothing was asked of it. Equivalent to `when === null`. */
  fallback: boolean
}

/**
 * Choose the line a node says, given the signals. First match wins; the fallback is last.
 *
 * TOTAL BY CONSTRUCTION. It never throws, for any input, including an empty signal set, a
 * predicate naming a signal that does not exist, and a predicate that is malformed or too deeply
 * nested — those evaluate to no-match and the walk continues to the next candidate. Every failure
 * mode therefore drifts toward the fallback, which is the safe direction: the worst outcome is
 * the most ordinary line rather than a blank panel or a thrown error in front of someone who
 * opened this at 2am.
 *
 * Returns null only for a node with no lines at all, which `validateDialogue` rejects at
 * authoring time. Null rather than a throw for the same reason: the caller can render nothing
 * and log, and a content bug does not take the runner down.
 */
export function planLine(node: DialogueNode, signals: DialogueSignals): PlannedLine | null {
  const lines = Array.isArray(node?.lines) ? node.lines : []
  const answers = toAnswers(signals)

  for (let index = 0; index < lines.length; index++) {
    const line = lines[index]
    if (!line) continue
    if (!line.when) return { line, index, when: null, fallback: true }
    if (matches(line.when, answers)) return { line, index, when: line.when, fallback: false }
  }
  return null
}

/**
 * Signals in, `Answers` out — with two hardenings that belong at this boundary.
 *
 * A NULL PROTOTYPE. `evalPredicate` fails an unknown ref closed by testing `ref in answers`, and
 * on an ordinary object literal `'toString' in answers` is true. A predicate naming an inherited
 * property would otherwise compare against a function and, under `ne`, match. Nothing inherited
 * is a signal.
 *
 * ONLY THE CLOSED VOCABULARY, AND ONLY DEFINED VALUES. Copying by SIGNAL_NAMES means a host that
 * supplies an extra key gives no dialogue anything to branch on, and an explicitly `undefined`
 * signal is treated as an absent one rather than as a value to compare against. This is the
 * runtime half of Finding 1's "do both": validation catches the typo with a message someone can
 * act on, and the evaluator fails closed when content drifts past it anyway.
 */
function toAnswers(signals: DialogueSignals): Answers {
  const answers = Object.create(null) as Answers
  if (!signals || typeof signals !== 'object') return answers
  for (const name of SIGNAL_NAMES) {
    const value = signals[name]
    if (value !== undefined) answers[name] = value
  }
  return answers
}

/** Fail closed on a predicate that throws — over-deep nesting, or a node shape the DSL rejects. */
function matches(when: Predicate, answers: Answers): boolean {
  try {
    return evalPredicate(when, answers)
  } catch {
    return false
  }
}

/* --- validation --------------------------------------------------------------------------- */

interface PredicateShape {
  /** Deepest level reached, a bare leaf being 1. */
  depth: number
  /** Nodes visited; undercounts when the walk stopped early, which only happens once it has failed. */
  nodes: number
  /** Every `ref` named, de-duplicated, in first-seen order. */
  refs: string[]
}

/**
 * Measure one predicate tree: depth, size and the signals it names.
 *
 * ITERATIVE ON PURPOSE, for the reason instruments/validate.ts gives about its own private twin:
 * a recursive walker would blow its stack on precisely the input this check exists to reject. It
 * stops once both bounds are exceeded, since nothing further can change the verdict — which also
 * makes termination unconditional, because a hand-built cycle raises the level on every descent
 * and trips the depth bound. (The twin is module-private over there. If either grows a third
 * caller, share one instead of keeping two.)
 */
function measurePredicate(root: unknown): PredicateShape {
  let depth = 0
  let nodes = 0
  const refs: string[] = []
  const seen = new Set<string>()
  const stack: Array<[node: unknown, level: number]> = [[root, 1]]

  while (stack.length > 0) {
    const [node, level] = stack.pop() as [unknown, number]
    nodes++
    if (level > depth) depth = level
    if (nodes > MAX_PREDICATE_NODES && depth > MAX_PREDICATE_DEPTH) break
    if (!node || typeof node !== 'object') continue

    const n = node as { all?: unknown; any?: unknown; ref?: unknown }
    const children = Array.isArray(n.all) ? n.all : Array.isArray(n.any) ? n.any : null
    if (children) {
      for (const child of children) stack.push([child, level + 1])
    } else if (typeof n.ref === 'string' && !seen.has(n.ref)) {
      seen.add(n.ref)
      refs.push(n.ref)
    }
  }

  return { depth, nodes, refs }
}

const isText = (v: unknown): v is string => typeof v === 'string' && v.trim().length > 0

/**
 * Check a dialogue's shape. Returns one human-readable error per problem, in declaration order;
 * an empty array means it is well formed. Never throws, so an editor can show everything wrong
 * at once rather than the first thing.
 *
 * What it enforces:
 *   - every node has at least one line, and its last line has no `when` (the mandatory fallback);
 *   - no candidate before the last is unconditional, since nothing after such a line could win;
 *   - every option names a node that exists, or ends explicitly — never both, never neither;
 *   - every node is reachable from the entry node;
 *   - no node is a dead end;
 *   - predicates stay inside the depth and node-count bounds (Finding 2).
 *
 * `author` is optional and has no default, mirroring `validateDialogueDefinition`: omitted, this
 * checks shape only; supplied, every signal a predicate references must also survive the author
 * partition of Finding 3 — *a predicate may only reference signals its author is already
 * permitted to see* — and the upload path is expected to pass it. ./signals.ts is the single
 * source of truth for that partition; this function only collects the refs and asks.
 */
export function validateDialogue(d: Dialogue, author?: SignalAuthor): string[] {
  const errors: string[] = []

  if (!isText(d?.id)) errors.push('dialogue has no id')
  if (!Number.isInteger(d?.signalVocabularyVersion) || d.signalVocabularyVersion < 1)
    errors.push(
      'dialogue must record the signal vocabulary version it was authored against ' +
        '(signalVocabularyVersion), so a later rename fails loudly instead of silently changing ' +
        'what it says',
    )

  const nodes: readonly DialogueNode[] = Array.isArray(d?.nodes) ? d.nodes : []
  if (!Array.isArray(d?.nodes)) errors.push('dialogue has no nodes: `nodes` must be an array')
  else if (nodes.length === 0) errors.push('dialogue has no nodes')

  // Index first: every later check wants to know which ids exist.
  const byId = new Map<string, DialogueNode>()
  nodes.forEach((n, i) => {
    if (!isText(n?.id)) {
      errors.push(`node at index ${i} has no id`)
      return
    }
    if (byId.has(n.id)) {
      errors.push(`node "${n.id}" is declared more than once`)
      return
    }
    byId.set(n.id, n)
  })

  const entry = d?.entry
  if (!isText(entry)) errors.push('dialogue has no entry node')
  else if (!byId.has(entry)) errors.push(`entry node "${entry}" does not exist`)

  const refs: string[] = []
  const seenRefs = new Set<string>()

  nodes.forEach((n, i) => {
    const where = isText(n?.id) ? `node "${n.id}"` : `node at index ${i}`

    // --- lines: at least one, fallback last ---
    const lines: readonly DialogueLine[] = Array.isArray(n?.lines) ? n.lines : []
    if (lines.length === 0) {
      errors.push(`${where} has no lines — a node must always have something to say`)
    } else if (lines[lines.length - 1]?.when) {
      errors.push(
        `${where} has no fallback: its last line carries a \`when\`, so a person for whom no ` +
          `predicate matches would be shown nothing. The last candidate must be unconditional.`,
      )
    }

    lines.forEach((l, li) => {
      if (!isText(l?.text)) errors.push(`${where} line ${li} has no text`)
      if (!l?.when) {
        if (li !== lines.length - 1)
          errors.push(
            `${where} line ${li} has no \`when\`, so it always matches and no candidate after ` +
              `it can ever be chosen`,
          )
        return
      }
      const shape = measurePredicate(l.when)
      if (shape.depth > MAX_PREDICATE_DEPTH)
        errors.push(`${where} line ${li} nests its \`when\` deeper than ${MAX_PREDICATE_DEPTH} levels`)
      if (shape.nodes > MAX_PREDICATE_NODES)
        errors.push(`${where} line ${li} \`when\` has more than ${MAX_PREDICATE_NODES} predicate nodes`)
      for (const ref of shape.refs) {
        if (seenRefs.has(ref)) continue
        seenRefs.add(ref)
        refs.push(ref)
      }
    })

    // --- options: at least one, each leading somewhere or ending ---
    const options: readonly DialogueOption[] = Array.isArray(n?.options) ? n.options : []
    if (options.length === 0)
      errors.push(
        `${where} is a dead end: it offers no options. A conversation ends by an option ` +
          `carrying \`end\`, never by running out of them.`,
      )

    options.forEach((o, oi) => {
      if (!isText(o?.label)) errors.push(`${where} option ${oi} has no label`)
      const ends = o?.end === true
      const next = typeof o?.next === 'string' ? o.next : ''
      if (ends && next) errors.push(`${where} option ${oi} both ends and points at "${next}" — it must do one`)
      else if (!ends && !next)
        errors.push(`${where} option ${oi} is a dead end: it must name a next node or end explicitly`)
      else if (next && !byId.has(next))
        errors.push(`${where} option ${oi} points at node "${next}", which does not exist`)
    })
  })

  // --- reachability, once there is an entry to walk from ---
  if (isText(entry) && byId.has(entry)) {
    const reached = new Set<string>([entry])
    const queue: string[] = [entry]
    while (queue.length > 0) {
      const current = queue.shift() as string
      const options = byId.get(current)?.options
      if (!Array.isArray(options)) continue
      for (const o of options) {
        const next = typeof o?.next === 'string' ? o.next : ''
        if (!next || !byId.has(next) || reached.has(next)) continue
        reached.add(next)
        queue.push(next)
      }
    }
    for (const id of byId.keys()) {
      if (!reached.has(id)) errors.push(`node "${id}" is unreachable from the entry node "${entry}"`)
    }
  }

  if (author) for (const message of assertRefsAllowed(refs, author)) errors.push(message)

  return errors
}
