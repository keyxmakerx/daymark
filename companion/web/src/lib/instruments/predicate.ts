/*
 * Pure, sandboxed evaluator for the branching DSL (`visibleWhen`). No eval, no executable
 * code — a definition can never run arbitrary JS in the portal. Unknown shapes throw.
 */
import type { Predicate, PredicateOp } from './types'

export type Answers = Record<string, unknown>

/**
 * Recursion bound. A definition may be authored by a clinician and runs on someone else's
 * device, so a hostile or merely buggy tree must not be able to take the runner down: 50k
 * nested `all` nodes previously threw "Maximum call stack size exceeded". Validation rejects
 * over-deep trees at authoring time; this is the belt to that braces, and it is deliberately
 * generous — real dialogue nests two or three deep.
 */
export const MAX_PREDICATE_DEPTH = 16

export function evalPredicate(p: Predicate, answers: Answers, depth = 0): boolean {
  if (depth > MAX_PREDICATE_DEPTH) {
    throw new Error(`predicate nested deeper than ${MAX_PREDICATE_DEPTH}`)
  }
  if ('all' in p) return p.all.every((c) => evalPredicate(c, answers, depth + 1))
  if ('any' in p) return p.any.some((c) => evalPredicate(c, answers, depth + 1))
  if ('ref' in p) return evalLeaf(p.ref, p.op, p.value, answers)
  throw new Error('invalid predicate node')
}

function evalLeaf(ref: string, op: PredicateOp, value: unknown, answers: Answers): boolean {
  /*
   * UNKNOWN REF FAILS CLOSED — for every operator, including `ne`.
   *
   * Without this, `ne` alone failed OPEN: `undefined !== 3` is true, so a branch gated on a
   * signal that does not exist would SHOW rather than hide. Every other operator already
   * returned false. The realistic trigger is not malice but drift — content authored against a
   * signal a later release renames, or an unanswered item in a branching questionnaire — and the
   * failure was silent either way.
   *
   * Absence is not a value to compare against. If we have not been told, we do not show.
   */
  // `Object.hasOwn`, NOT `ref in answers`: `in` walks the prototype chain, so `constructor`,
  // `toString`, `__proto__` and `hasOwnProperty` all tested as PRESENT and `ne` returned true on
  // them — the exact fail-open this guard exists to close, reachable from any authored definition.
  // The first version of this fix used `in` and its tests only covered a genuinely-absent key.
  if (!Object.hasOwn(answers, ref)) return false
  const a = answers[ref]
  switch (op) {
    case 'eq':
      return a === value
    case 'ne':
      return a !== value
    case 'gt':
      return num(a) > num(value)
    case 'gte':
      return num(a) >= num(value)
    case 'lt':
      return num(a) < num(value)
    case 'lte':
      return num(a) <= num(value)
    case 'in':
      return Array.isArray(value) && (value as unknown[]).includes(a)
    case 'includes':
      return Array.isArray(a) && (a as unknown[]).includes(value)
    default:
      throw new Error(`unknown predicate op: ${op}`)
  }
}

function num(v: unknown): number {
  const n = typeof v === 'number' ? v : Number(v)
  return Number.isFinite(n) ? n : NaN
}

/** Which items are visible given current answers (hidden items contribute nothing to scoring). */
export function visibleItemIds(
  items: { id: string; visibleWhen?: Predicate }[],
  answers: Answers,
): Set<string> {
  const out = new Set<string>()
  for (const it of items) {
    if (!it.visibleWhen || evalPredicate(it.visibleWhen, answers)) out.add(it.id)
  }
  return out
}
