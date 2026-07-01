/*
 * Plain-language, NON-DIAGNOSTIC descriptions of assignments, capabilities, and settings.
 *
 * These are the "what this will do" previews the owner sees before accepting an assignment,
 * and the labels the grant manager shows per capability. Copy is deliberately MECHANICAL
 * ("Assign the X self-check, weekly") — never clinical framing, never a diagnosis. Pure
 * functions only; unit-tested for stable output.
 */
import type { Assignment, Capability, Cadence } from './types'
import { getInstrument } from '../instruments'
import { ATTENTION_TASK } from '../tasks/attention'

/** Human title for a catalog instrument id (falls back to the raw id if unknown). */
function instrumentTitle(id: string): string {
  return getInstrument(id)?.title ?? id
}

function taskTitle(id: string): string {
  return id === ATTENTION_TASK.taskId ? ATTENTION_TASK.title : id
}

/** "every week" / "every 2 weeks" / "twice a month" — mechanical cadence phrasing. */
export function describeCadence(c: Cadence | undefined): string {
  if (!c) return ''
  const unit = c.every
  if (c.count === 1) return `every ${unit}`
  return `every ${c.count} ${unit}s`
}

/** A one-line, non-diagnostic preview of exactly what an assignment does. */
export function describeAssignment(a: Assignment): string {
  const cad = a.cadence ? `, ${describeCadence(a.cadence)}` : ''
  const p = a.payload as Record<string, unknown>
  switch (a.type) {
    case 'questionnaire':
      return `Assign the ${instrumentTitle(String(p.instrumentId))} self-check${cad}.`
    case 'task':
      return `Assign the ${taskTitle(String(p.taskId))} task${cad}.`
    case 'largeAssessment': {
      const bundle = (p.bundle as Array<{ kind: string; id: string }> | undefined) ?? []
      const names = bundle.map((it) => (it.kind === 'task' ? taskTitle(it.id) : instrumentTitle(it.id)))
      const list = names.length ? names.join(', ') : '(empty bundle)'
      return `Assign an assessment bundle (${list})${cad}.`
    }
    case 'reminder': {
      const every = typeof p.every === 'string' ? p.every : ''
      const count = typeof p.count === 'number' ? p.count : ''
      return `Set a check-in reminder ${describeCadence({ every: every as Cadence['every'], count: count as number })}.`.replace('  ', ' ')
    }
    case 'goal':
      return `Add the goal "${String(p.title)}"${cad}.`
    case 'setting':
      return `Suggest the app setting ${describeSetting(String(p.key), p.value as string | number | boolean)}.`
  }
}

/** Title + short description for a capability row in the grant manager. */
export function describeCapability(cap: Capability): { title: string; desc: string } {
  return CAPABILITY_COPY[cap]
}

/** Human phrasing of a proposed setting change. Non-diagnostic; only allowlisted keys occur. */
export function describeSetting(key: string, value: string | number | boolean): string {
  const label = SETTING_LABELS[key] ?? key
  return `${label} → ${formatSettingValue(key, value)}`
}

function formatSettingValue(_key: string, value: string | number | boolean): string {
  if (typeof value === 'boolean') return value ? 'on' : 'off'
  return String(value)
}

const SETTING_LABELS: Record<string, string> = {
  visibleSelfChecks: 'Visible self-checks',
  reminderTime: 'Reminder time',
  reminderCadence: 'Reminder cadence',
  theme: 'App theme',
}

const CAPABILITY_COPY: Record<Capability, { title: string; desc: string }> = {
  'read.share': {
    title: 'View shared data',
    desc: 'Read the curated data you choose to share (scores and bands only — never raw entries).',
  },
  'assign.questionnaire': {
    title: 'Assign self-checks',
    desc: 'Suggest a catalog self-check questionnaire for you to take.',
  },
  'assign.task': {
    title: 'Assign tasks',
    desc: 'Suggest an in-app task (e.g. the attention task) for you to try.',
  },
  'assign.largeAssessment': {
    title: 'Assign assessment bundles',
    desc: 'Suggest a bundle of several self-checks/tasks to complete together.',
  },
  'assign.reminder': {
    title: 'Suggest reminders',
    desc: 'Suggest a check-in reminder cadence.',
  },
  'assign.goal': {
    title: 'Suggest goals',
    desc: 'Suggest a goal for you to add to your tracking.',
  },
  authorGamePlan: {
    title: 'Write a game plan',
    desc: 'Send you a written plan (goals, exercises, notes). Guidance only — non-diagnostic.',
  },
  'suggest.setting': {
    title: 'Suggest app settings',
    desc: 'Propose a change to an allowlisted app setting. Always requires your acceptance — never automatic.',
  },
}
