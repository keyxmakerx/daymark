/*
 * Deterministic scoring evaluator. Arithmetic over the person's own answers → a number →
 * a DESCRIPTIVE band label. No clinical cutoff, no positive/negative screen, no verdict.
 */
import type { InstrumentDefinition, Item, Scale, ScaleResult, Band } from './types'
import { visibleItemIds, type Answers } from './predicate'

function itemRange(item: Item): { lo: number; hi: number } {
  if (item.options && item.options.length) {
    const vals = item.options.map((o) => o.value ?? 0)
    return { lo: Math.min(...vals), hi: Math.max(...vals) }
  }
  return { lo: item.min ?? 0, hi: item.max ?? 0 }
}

function rawValue(item: Item, answer: unknown): number | null {
  if (answer == null) return null
  if (item.options && item.options.length) {
    // likert / singleSelect: answer is an option id → its numeric value.
    const opt = item.options.find((o) => o.id === answer)
    return opt ? opt.value ?? 0 : null
  }
  const n = typeof answer === 'number' ? answer : Number(answer)
  return Number.isFinite(n) ? n : null
}

function scaleScore(scale: Scale, itemsById: Map<string, Item>, answers: Answers, visible: Set<string>): number {
  const reverse = new Set(scale.reverse ?? [])
  let sum = 0
  let count = 0
  let maxPossible = 0
  for (const id of scale.items) {
    const item = itemsById.get(id)
    if (!item || !visible.has(id)) continue
    const { lo, hi } = itemRange(item)
    // maxPossible spans every VISIBLE scored item (answered or not) so percent_of_max
    // can't inflate when some items are left blank.
    maxPossible += hi
    const v = rawValue(item, answers[id])
    if (v == null) continue
    // Only reverse items that have a defined value range (avoids a 0−v sign flip).
    const canReverse = reverse.has(id) && (hi !== 0 || lo !== 0)
    sum += canReverse ? hi + lo - v : v
    count++
  }
  switch (scale.method) {
    case 'sum':
      return sum
    case 'mean':
      return count ? sum / count : 0
    case 'percent_of_max':
      return maxPossible ? (sum / maxPossible) * 100 : 0
  }
}

/** First band whose [min,max] contains the score (min inclusive, max inclusive). */
export function bandFor(score: number, bands: Band[]): Band | null {
  for (const b of bands) {
    const lo = b.min ?? Number.NEGATIVE_INFINITY
    const hi = b.max ?? Number.POSITIVE_INFINITY
    if (score >= lo && score <= hi) return b
  }
  return null
}

export function scoreInstrument(def: InstrumentDefinition, answers: Answers): ScaleResult[] {
  const itemsById = new Map(def.items.map((i) => [i.id, i]))
  const visible = visibleItemIds(def.items, answers)
  return def.scoring.scales.map((scale) => {
    const score = round2(scaleScore(scale, itemsById, answers, visible))
    const band = bandFor(score, scale.bands)
    return {
      scaleId: scale.id,
      score,
      bandLabel: band?.label ?? '—',
      tone: band?.tone ?? 'neutral',
    }
  })
}

function round2(n: number): number {
  return Math.round(n * 100) / 100
}
