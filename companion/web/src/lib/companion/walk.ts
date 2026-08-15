/*
 * The companion conversation as a pure state machine.
 *
 * WHY THIS IS NOT IN THE COMPONENT. There is no DOM harness in this project (vite.config.ts sets
 * `environment: 'node'`), so anything living inside a `.svelte` file can only be tested by reading
 * its source. The conversation is the part with real behaviour — branching on signals, ending
 * deliberately, refusing to loop — so it lives here where it can be executed against thousands of
 * signal combinations, and the component becomes a renderer with nothing to get wrong.
 *
 * TOTALITY IS THE POINT. Every function here is defined for every input, including a dialogue with
 * a broken option target and a signal set that is entirely empty. Someone may open this at 2am on a
 * bad night; the worst outcome must be an ordinary line, never a blank panel and never a thrown
 * error. `planLine` already guarantees that at the line level (see its docstring) and this module
 * keeps the guarantee at the conversation level.
 */
import { planLine, type Dialogue, type DialogueNode, type DialogueSignals, type PlannedLine } from './dialogue'
import { destinationFor, type CompanionDestination } from './content'

/** Where the conversation is. Immutable; every transition returns a new one. */
export interface WalkState {
  /** Id of the node being shown. */
  readonly nodeId: string
  /** Node ids already visited, oldest first — the trail, used to refuse loops. */
  readonly visited: readonly string[]
  /** True once an option with `end` was taken. A conversation ends deliberately or not at all. */
  readonly ended: boolean
  /**
   * What the host should open because of how this ended, or null. Resolved at the moment the
   * ending is reached, from the line that actually fired — not re-derived later from conditions
   * that may since have changed.
   */
  readonly destination: CompanionDestination | null
}

/** What the UI needs to draw one turn. `line` is null only for a malformed node. */
export interface WalkView {
  readonly nodeId: string
  readonly line: string | null
  /** The predicate that chose this line, for the "why this line" affordance. Null on the fallback. */
  readonly planned: PlannedLine | null
  readonly options: readonly { readonly label: string; readonly ends: boolean }[]
  readonly ended: boolean
}

function nodeById(d: Dialogue, id: string): DialogueNode | null {
  return d.nodes.find((n) => n.id === id) ?? null
}

/** Open the conversation at the dialogue's entry node. */
export function start(d: Dialogue): WalkState {
  return { nodeId: d.entry, visited: [], ended: false, destination: null }
}

/**
 * What to render right now.
 *
 * Returns a view with `line: null` and no options for a node that does not exist or carries no
 * lines — both are rejected by `validateDialogue` at authoring time, so reaching either means a
 * content bug. The UI renders nothing rather than crashing, which is the same trade `planLine`
 * makes and for the same reason.
 */
export function view(state: WalkState, d: Dialogue, signals: DialogueSignals): WalkView {
  const node = nodeById(d, state.nodeId)
  if (!node) return { nodeId: state.nodeId, line: null, planned: null, options: [], ended: state.ended }
  const planned = planLine(node, signals)
  return {
    nodeId: node.id,
    line: planned?.line.text ?? null,
    planned,
    options: state.ended ? [] : node.options.map((o) => ({ label: o.label, ends: o.end === true })),
    ended: state.ended,
  }
}

/**
 * Take an option.
 *
 * REFUSES TO LOOP. If an option points at a node already visited, the conversation ends instead of
 * revisiting it. Authored content should not do this — `validateDialogue` has no cycle check — and
 * the failure it prevents is worse than a missing branch: a companion that can walk someone in a
 * circle is one that cannot be finished, and being unable to finish a conversation with software
 * is its own small indignity.
 *
 * An out-of-range index, or an option pointing at a node that does not exist, also ends the
 * conversation. Every unhandled case exits; none of them throws and none of them hangs.
 */
export function choose(state: WalkState, optionIndex: number, d: Dialogue, signals: DialogueSignals): WalkState {
  if (state.ended) return state
  const node = nodeById(d, state.nodeId)
  if (!node) return { ...state, ended: true }

  const option = node.options[optionIndex]
  if (!option) return endedAt(state, node, signals)
  if (option.end) return endedAt(state, node, signals)

  const nextId = option.next
  if (!nodeById(d, nextId) || state.visited.includes(nextId) || nextId === state.nodeId) {
    return endedAt(state, node, signals)
  }
  return {
    nodeId: nextId,
    visited: [...state.visited, state.nodeId],
    ended: false,
    destination: null,
  }
}

/**
 * End here, resolving the destination from the line that actually fired on the node being left.
 *
 * The destination is keyed by node AND line index because in `toOffer` the four candidate lines
 * open four different things — so the branch is decided once, in the authored predicates, and read
 * back here rather than re-derived by a second copy of the same conditions.
 */
function endedAt(state: WalkState, node: DialogueNode, signals: DialogueSignals): WalkState {
  const planned = planLine(node, signals)
  const destination = planned ? destinationFor(node.id, planned.index) : null
  return { nodeId: node.id, visited: [...state.visited, node.id], ended: true, destination }
}

/**
 * Every node reachable from the entry, for tests and tooling.
 *
 * Breadth-first and bounded by the node count, so a cyclic definition terminates here too.
 */
export function reachableNodes(d: Dialogue): Set<string> {
  const seen = new Set<string>()
  const queue = [d.entry]
  while (queue.length) {
    const id = queue.shift() as string
    if (seen.has(id)) continue
    seen.add(id)
    const node = nodeById(d, id)
    if (!node) continue
    for (const o of node.options) if (!o.end && o.next && !seen.has(o.next)) queue.push(o.next)
  }
  return seen
}
