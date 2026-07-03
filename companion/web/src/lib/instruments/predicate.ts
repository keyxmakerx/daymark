/*
 * Pure, sandboxed evaluator for the branching DSL (`visibleWhen`). No eval, no executable
 * code — a definition can never run arbitrary JS in the portal. Unknown shapes throw.
 */
import type { Predicate, PredicateOp } from './types'

export type Answers = Record<string, unknown>

export function evalPredicate(p: Predicate, answers: Answers): boolean {
  if ('all' in p) return p.all.every((c) => evalPredicate(c, answers))
  if ('any' in p) return p.any.some((c) => evalPredicate(c, answers))
  if ('ref' in p) return evalLeaf(p.ref, p.op, p.value, answers)
  throw new Error('invalid predicate node')
}

function evalLeaf(ref: string, op: PredicateOp, value: unknown, answers: Answers): boolean {
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
