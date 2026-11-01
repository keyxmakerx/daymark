/*
 * A clinician's own notes: the shape, and the rules that keep them organised and findable.
 *
 * Pure. No crypto, no storage, no DOM; vault.ts locks and keeps them, NotesScreen draws them.
 *
 * WHAT A NOTE IS. The clinician's own writing about their work with this client, never the
 * client's data. Each note keeps the LABELS of its sections, not just ids, because the discipline
 * switcher (discipline.ts) can change which sections a new note offers, and a note written as a
 * psychologist must still read the same after the clinician switches to therapist.
 *
 * SEARCH IS RULES, NOT A MODEL (CLAUDE.md §0). A query is split into words; a note matches when
 * every word appears somewhere in it (its kind, its date, a section, a tag), ignoring case and
 * accents. Tags narrow with AND: choosing two tags shows the notes that carry both. Nothing is
 * ranked or summarised; matches are listed newest first, as written.
 */

export interface NoteSection {
  label: string
  text: string
}

export interface Note {
  id: string
  /** What the note follows: "Session", "Assessment", "Medication review" (discipline.ts noteKind). */
  kind: string
  /** The day it is about, YYYY-MM-DD, as the clinician chose it. */
  day: string
  sections: NoteSection[]
  tags: string[]
  createdAt: number
  updatedAt: number
}

export const MAX_TAGS = 12
export const MAX_TAG_LENGTH = 32
export const MAX_SECTION_LENGTH = 20_000

const DAY = /^\d{4}-\d{2}-\d{2}$/

/** Lowercase, trimmed, inner spaces collapsed, a leading # dropped. Empty means "not a tag". */
export function normaliseTag(raw: string): string {
  return raw.trim().replace(/^#+/, '').replace(/\s+/g, ' ').toLowerCase().slice(0, MAX_TAG_LENGTH).trim()
}

/** Comma-separated input to a clean, de-duplicated tag list, in the order typed, capped. */
export function parseTags(raw: string): string[] {
  const out: string[] = []
  for (const part of raw.split(',')) {
    const t = normaliseTag(part)
    if (t && !out.includes(t)) out.push(t)
    if (out.length === MAX_TAGS) break
  }
  return out
}

/** Folds case and accents so "Sleep", "sleep" and "sléep" find each other. */
export function fold(s: string): string {
  return s.normalize('NFD').replace(/\p{Diacritic}/gu, '').toLowerCase()
}

export function queryWords(query: string): string[] {
  return fold(query).split(/\s+/).filter(Boolean)
}

function haystack(n: Note): string {
  return fold([n.kind, n.day, ...n.tags, ...n.sections.flatMap((s) => [s.label, s.text])].join('\n'))
}

export interface NoteFilter {
  query?: string
  tags?: string[]
}

/** The notes that match every query word and carry every chosen tag, newest day first. */
export function findNotes(notes: readonly Note[], filter: NoteFilter = {}): Note[] {
  const words = queryWords(filter.query ?? '')
  const tags = filter.tags ?? []
  return notes
    .filter((n) => tags.every((t) => n.tags.includes(t)))
    .filter((n) => {
      if (!words.length) return true
      const h = haystack(n)
      return words.every((w) => h.includes(w))
    })
    .sort(newestFirst)
}

export function newestFirst(a: Note, b: Note): number {
  if (a.day !== b.day) return a.day < b.day ? 1 : -1
  return b.createdAt - a.createdAt
}

/** Every tag in use, with how many notes carry it, most used first, then alphabetically. */
export function tagCounts(notes: readonly Note[]): { tag: string; count: number }[] {
  const m = new Map<string, number>()
  for (const n of notes) for (const t of n.tags) m.set(t, (m.get(t) ?? 0) + 1)
  return [...m].map(([tag, count]) => ({ tag, count })).sort((a, b) => b.count - a.count || (a.tag < b.tag ? -1 : 1))
}

/** Why a draft cannot be kept yet, in words, or null when it can. */
export function draftProblem(d: { day: string; sections: NoteSection[] }): string | null {
  if (!DAY.test(d.day) || Number.isNaN(Date.parse(d.day + 'T00:00:00Z'))) return 'Choose the day this note is about.'
  if (!d.sections.some((s) => s.text.trim())) return 'Write something in at least one part before keeping the note.'
  if (d.sections.some((s) => s.text.length > MAX_SECTION_LENGTH)) return `Each part holds up to ${MAX_SECTION_LENGTH.toLocaleString('en')} characters.`
  return null
}

/**
 * Builds or updates a note from a draft. Empty sections are kept out, so a search never matches a
 * heading with nothing under it, and an edit keeps the note's id and when it was first written.
 */
export function keepDraft(
  draft: { kind: string; day: string; sections: NoteSection[]; tagsText: string },
  now: number,
  newId: () => string,
  previous?: Note,
): Note {
  return {
    id: previous?.id ?? newId(),
    kind: draft.kind,
    day: draft.day,
    sections: draft.sections.filter((s) => s.text.trim()).map((s) => ({ label: s.label, text: s.text.trimEnd() })),
    tags: parseTags(draft.tagsText),
    createdAt: previous?.createdAt ?? now,
    updatedAt: now,
  }
}

/** Replaces the note with the same id, or adds it. */
export function upsert(notes: readonly Note[], note: Note): Note[] {
  const i = notes.findIndex((n) => n.id === note.id)
  if (i === -1) return [...notes, note]
  const next = notes.slice()
  next[i] = note
  return next
}

export function removeNote(notes: readonly Note[], id: string): Note[] {
  return notes.filter((n) => n.id !== id)
}

/**
 * The plain-text copy a clinician downloads for their own records. It is deliberately not locked:
 * it leaves Daymark for wherever the clinician keeps records, and the screen says so.
 */
export function exportText(notes: readonly Note[]): string {
  return [...notes]
    .sort(newestFirst)
    .map((n) => {
      const head = `${n.day} · ${n.kind}` + (n.tags.length ? `\nTags: ${n.tags.join(', ')}` : '')
      const body = n.sections.map((s) => `${s.label}\n${s.text}`).join('\n\n')
      return `${head}\n\n${body}`
    })
    .join('\n\n----------------------------------------\n\n')
}

/** Checks a decrypted notebook is the shape this file writes. Anything else is refused whole. */
export function isNotebook(v: unknown): v is Note[] {
  if (!Array.isArray(v)) return false
  return v.every(
    (n) =>
      n && typeof n === 'object' &&
      typeof n.id === 'string' && typeof n.kind === 'string' && typeof n.day === 'string' &&
      typeof n.createdAt === 'number' && typeof n.updatedAt === 'number' &&
      Array.isArray(n.tags) && n.tags.every((t: unknown) => typeof t === 'string') &&
      Array.isArray(n.sections) &&
      n.sections.every((s: unknown) => !!s && typeof (s as NoteSection).label === 'string' && typeof (s as NoteSection).text === 'string'),
  )
}
