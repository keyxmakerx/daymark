/*
 * Assignable-catalog PROJECTION for the therapist assign surface.
 *
 * The therapist may only assign catalog instruments (which have already passed the non-diagnostic
 * honesty gate at load) and the built-in tasks. `isAssignable` mirrors the EXACT predicates
 * validate.ts uses (instrumentExists / taskExists), so what the therapist can pick is precisely
 * what the owner's validator will accept. No new instrument definitions can be introduced here —
 * that is maintainer-only via the instrument ledger.
 */
import { CATALOG, getInstrument } from '../instruments'
import { ATTENTION_TASK } from '../tasks/attention'

export interface AssignableItem {
  kind: 'questionnaire' | 'task'
  id: string
  title: string
  estimatedMinutes?: number
}

/** Catalog questionnaires (honesty-gated). Titles/est-minutes come from the definitions. */
export function assignableQuestionnaires(): AssignableItem[] {
  return CATALOG.map((d) => ({
    kind: 'questionnaire' as const,
    id: d.instrumentId,
    title: d.title,
    estimatedMinutes: d.estimatedMinutes,
  }))
}

/** Built-in tasks the therapist may assign (currently just the steady-attention task). */
export function assignableTasks(): AssignableItem[] {
  return [{ kind: 'task', id: ATTENTION_TASK.taskId, title: ATTENTION_TASK.title }]
}

/** Same predicate the validator uses — a fabricated/non-catalog id is not assignable. */
export function isAssignable(kind: 'questionnaire' | 'task', id: string): boolean {
  return kind === 'questionnaire' ? !!getInstrument(id) : id === ATTENTION_TASK.taskId
}
