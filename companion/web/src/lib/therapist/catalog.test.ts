import { describe, it, expect } from 'vitest'
import { assignableQuestionnaires, assignableTasks, isAssignable } from './catalog'
import { CATALOG } from '../instruments'
import { ATTENTION_TASK } from '../tasks/attention'

describe('therapist assignable catalog projection', () => {
  it('only surfaces catalog (honesty-gated) questionnaires', () => {
    const ids = assignableQuestionnaires().map((q) => q.id)
    expect(ids.sort()).toEqual(CATALOG.map((d) => d.instrumentId).sort())
  })

  it('surfaces the built-in attention task', () => {
    const tasks = assignableTasks()
    expect(tasks.map((t) => t.id)).toContain(ATTENTION_TASK.taskId)
  })

  it('isAssignable mirrors the validator predicates', () => {
    expect(isAssignable('questionnaire', CATALOG[0].instrumentId)).toBe(true)
    expect(isAssignable('task', ATTENTION_TASK.taskId)).toBe(true)
    expect(isAssignable('questionnaire', 'phq-9-fabricated')).toBe(false)
    expect(isAssignable('task', 'not-a-task')).toBe(false)
  })
})
