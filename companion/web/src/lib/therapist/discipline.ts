/*
 * WHICH KIND OF CLINICIAN IS AT THIS CONSOLE — therapist, psychologist or psychiatrist.
 *
 * What this must keep true:
 *
 *  - It changes the console's arrangement and nothing it can read. A discipline is a preference
 *    the clinician picks for themselves; it carries no capability and gates no data. What a
 *    clinician can read still comes only from the client's grant (docs/COMPANION_ACCESS_CONTROL.md
 *    § Role catalog: roles gate actions, a patient's grant gates reading). So choosing
 *    "Psychiatrist" opens nothing a therapist could not open, and the test says so.
 *  - Each discipline differs in what it leads with and in the sections of its session notes: a
 *    therapist's what-we-worked-on, a psychologist's measures and observations, a psychiatrist's
 *    medication changes and next review. The words are fixed and human-written.
 *  - The choice is remembered in this browser only, like shareSeen.ts, and a stored value this
 *    build does not know falls back to the therapist rather than to nothing.
 */
import type { AlertStyle } from '../timers/timers'

export type Discipline = 'therapist' | 'psychologist' | 'psychiatrist'

export const DISCIPLINES: readonly Discipline[] = ['therapist', 'psychologist', 'psychiatrist']

/** The console tabs a discipline may reorder. Capability gating is unchanged by the order. */
export type ReadTab = 'today' | 'calendar' | 'record' | 'shared'

export interface DisciplineProfile {
  label: string
  /** One line under the switcher: what the arrangement leads with. */
  leadsWith: string
  /** The read tabs, in the order this discipline reaches for them. */
  tabOrder: readonly ReadTab[]
  /** The sections of a session note, in order. */
  noteSections: readonly { id: string; label: string }[]
  /** The name a new note takes. */
  noteKind: string
  /** The timer preset this discipline reaches for first. */
  firstPreset: string
  defaultAlert: AlertStyle
}

export const PROFILES: Record<Discipline, DisciplineProfile> = {
  therapist: {
    label: 'Therapist',
    leadsWith: 'Leads with today and the calendar. Notes follow a session.',
    tabOrder: ['today', 'calendar', 'record', 'shared'],
    noteSections: [
      { id: 'worked', label: 'What we worked on' },
      { id: 'homework', label: 'Homework agreed' },
      { id: 'next', label: 'Next time' },
    ],
    noteKind: 'Session',
    firstPreset: 'Session',
    defaultAlert: 'screen',
  },
  psychologist: {
    label: 'Psychologist',
    leadsWith: 'Leads with the record of self-checks over time. Notes follow an assessment.',
    tabOrder: ['record', 'today', 'calendar', 'shared'],
    noteSections: [
      { id: 'given', label: 'Measures given' },
      { id: 'observed', label: 'What I observed' },
      { id: 'plan', label: 'Plan' },
    ],
    noteKind: 'Assessment',
    firstPreset: 'Assessment',
    defaultAlert: 'screen',
  },
  psychiatrist: {
    label: 'Psychiatrist',
    leadsWith: 'Leads with today, where sleep sits beside mood. Notes follow a medication review.',
    tabOrder: ['today', 'record', 'calendar', 'shared'],
    noteSections: [
      { id: 'meds', label: 'Medication changes' },
      { id: 'effects', label: 'Side effects discussed' },
      { id: 'review', label: 'Next review' },
    ],
    noteKind: 'Medication review',
    firstPreset: 'Medication review',
    defaultAlert: 'screen',
  },
}

export const DISCIPLINE_STORAGE_KEY = 'daymark.clinician.discipline'

/** The slice of the Storage API this needs. Lets tests pass a plain object; Node has no DOM. */
export interface DisciplineStorage {
  getItem(key: string): string | null
  setItem(key: string, value: string): void
}

export function defaultDisciplineStorage(): DisciplineStorage | null {
  try {
    return globalThis.localStorage ?? null
  } catch {
    return null
  }
}

export function isDiscipline(v: unknown): v is Discipline {
  return typeof v === 'string' && (DISCIPLINES as readonly string[]).includes(v)
}

export function loadDiscipline(storage: DisciplineStorage | null): Discipline {
  try {
    const v = storage?.getItem(DISCIPLINE_STORAGE_KEY)
    return isDiscipline(v) ? v : 'therapist'
  } catch {
    return 'therapist'
  }
}

export function saveDiscipline(storage: DisciplineStorage | null, d: Discipline): void {
  try {
    storage?.setItem(DISCIPLINE_STORAGE_KEY, d)
  } catch {
    // A browser that refuses storage still gets the choice for this visit.
  }
}

/** Presets with this discipline's own first. Order only; no preset is added or dropped. */
export function presetsFor<T extends { label: string }>(d: Discipline, presets: readonly T[]): T[] {
  const first = PROFILES[d].firstPreset
  return [...presets.filter((p) => p.label === first), ...presets.filter((p) => p.label !== first)]
}
