/*
 * Assignment validation — the capability gate on the owner's device. An assignment applies
 * ONLY if the therapist's Grant currently allows its capability, the type matches, and the
 * payload is in bounds (catalog item exists + passes the non-diagnostic gate; setting key is
 * on the allowlist). Returns the apply mode so the caller knows whether to auto-apply or
 * queue for the owner to accept.
 */
import type { Assignment, Grant, ApplyMode } from './types'
import { TYPE_CAPABILITY, SETTING_ALLOWLIST } from './types'
import { getInstrument } from '../instruments'
import { ATTENTION_TASK } from '../tasks/attention'

export interface AssignmentCheck {
  ok: boolean
  errors: string[]
  applyMode: ApplyMode | null
}

function instrumentExists(id: string): boolean {
  // Only catalog instruments (which have already passed the honesty gate at load) are assignable.
  return !!getInstrument(id)
}
function taskExists(id: string): boolean {
  return id === ATTENTION_TASK.taskId
}

export function validateAssignment(a: Assignment, grant: Grant): AssignmentCheck {
  const errors: string[] = []
  const fail = (m: string) => errors.push(m)

  // 1. Type ↔ capability integrity.
  const requiredCap = TYPE_CAPABILITY[a.type]
  if (!requiredCap) fail(`unknown assignment type "${a.type}"`)
  else if (a.capability !== requiredCap) fail(`assignment type "${a.type}" requires capability "${requiredCap}", not "${a.capability}"`)

  // 2. The owner must currently grant this capability.
  const cap = grant.capabilities[a.capability]
  if (!cap?.granted) fail(`capability "${a.capability}" is not granted to this therapist`)

  // 3. Author must match the therapist this grant is for (defense in depth; signature is verified separately).
  if (a.authorFingerprint !== grant.therapistFingerprint) fail('assignment author does not match the granted therapist')

  // 4. Payload bounds.
  const p = a.payload as Record<string, unknown>
  switch (a.type) {
    case 'questionnaire':
      if (typeof p.instrumentId !== 'string' || !instrumentExists(p.instrumentId)) fail(`unknown or non-catalog instrument "${p.instrumentId}"`)
      break
    case 'task':
      if (typeof p.taskId !== 'string' || !taskExists(p.taskId)) fail(`unknown task "${p.taskId}"`)
      break
    case 'largeAssessment': {
      const bundle = p.bundle as Array<{ kind: string; id: string }> | undefined
      if (!Array.isArray(bundle) || bundle.length === 0) fail('largeAssessment bundle is empty')
      else for (const it of bundle) {
        const ok = it.kind === 'questionnaire' ? instrumentExists(it.id) : it.kind === 'task' ? taskExists(it.id) : false
        if (!ok) fail(`bundle references an unknown ${it.kind} "${it.id}"`)
      }
      break
    }
    case 'goal':
      if (typeof p.title !== 'string' || !p.title.trim()) fail('goal needs a title')
      break
    case 'reminder':
      if (!p.every || typeof p.count !== 'number') fail('reminder needs an every+count cadence')
      break
    case 'setting':
      if (typeof p.key !== 'string' || !(SETTING_ALLOWLIST as readonly string[]).includes(p.key)) {
        fail(`setting key "${p.key}" is not on the allowlist (security/privacy keys can never be assigned)`)
      }
      break
  }

  return { ok: errors.length === 0, errors, applyMode: errors.length === 0 ? cap!.apply : null }
}

/**
 * Whether an already-valid assignment should apply automatically. `auto` is honoured only for
 * low-risk capabilities; `suggest.setting` ALWAYS requires the owner to accept (never auto).
 */
export function shouldAutoApply(a: Assignment, mode: ApplyMode): boolean {
  if (a.capability === 'suggest.setting') return false
  return mode === 'auto'
}
