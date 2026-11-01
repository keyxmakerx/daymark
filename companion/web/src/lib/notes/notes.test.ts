import { describe, expect, it } from 'vitest'
import {
  MAX_TAGS, asNotebook, deleteFromNotebook, draftProblem, emptyNotebook, exportText, findNotes, isNoteList,
  keepDraft, mergeNotebooks, normaliseTag, parseTags, removeNote, sameNotebook, tagCounts, upsert,
  type Note, type Notebook,
} from './notes'
import { forgetLock, load, notesLock, open, save, seal, storageKey, type NotesStorage } from './vault'
import { initAssignmentCrypto } from '../assignments/crypto'

let seq = 0
const id = () => `n${++seq}`
function note(over: Partial<Note> & { text?: string }): Note {
  const { text = 'worked on sleep routine', ...rest } = over
  return {
    id: id(), kind: 'Session', day: '2026-10-01', sections: [{ label: 'What we worked on', text }],
    tags: [], createdAt: seq, updatedAt: seq, ...rest,
  }
}

describe('tags', () => {
  it('normalises case, spaces and a leading hash', () => {
    expect(normaliseTag('  #Sleep   Hygiene ')).toBe('sleep hygiene')
    expect(normaliseTag('###')).toBe('')
  })
  it('parses a comma list without duplicates, capped', () => {
    expect(parseTags('Sleep, sleep, , mood')).toEqual(['sleep', 'mood'])
    const many = Array.from({ length: 20 }, (_, i) => `t${i}`).join(',')
    expect(parseTags(many)).toHaveLength(MAX_TAGS)
  })
  it('counts tags, most used first', () => {
    const ns = [note({ tags: ['b', 'a'] }), note({ tags: ['a'] })]
    expect(tagCounts(ns)).toEqual([{ tag: 'a', count: 2 }, { tag: 'b', count: 1 }])
  })
})

describe('finding notes', () => {
  const a = note({ day: '2026-10-01', text: 'Talked about sleep and the new routine', tags: ['sleep'] })
  const b = note({ day: '2026-10-08', text: 'Exposure exercise, café visit', tags: ['exposure', 'homework'] })
  const c = note({ day: '2026-10-05', kind: 'Assessment', text: 'Gave PHQ-9', tags: ['homework'] })
  const all = [a, b, c]

  it('lists everything newest first with no filter', () => {
    expect(findNotes(all).map((n) => n.id)).toEqual([b.id, c.id, a.id])
  })
  it('needs every word, in any field, ignoring case and accents', () => {
    expect(findNotes(all, { query: 'CAFE exposure' }).map((n) => n.id)).toEqual([b.id])
    expect(findNotes(all, { query: 'sleep exposure' })).toEqual([])
    expect(findNotes(all, { query: 'assessment' }).map((n) => n.id)).toEqual([c.id])
    expect(findNotes(all, { query: '2026-10-01' }).map((n) => n.id)).toEqual([a.id])
  })
  it('narrows by every chosen tag', () => {
    expect(findNotes(all, { tags: ['homework'] }).map((n) => n.id)).toEqual([b.id, c.id])
    expect(findNotes(all, { tags: ['homework', 'exposure'] }).map((n) => n.id)).toEqual([b.id])
    // Positive control for the tag filter: a tag no note carries finds nothing.
    expect(findNotes(all, { tags: ['nobody'] })).toEqual([])
  })
  it('finds a tag by search too', () => {
    expect(findNotes(all, { query: 'homework' }).map((n) => n.id)).toEqual([b.id, c.id])
  })
})

describe('drafts', () => {
  const sections = [{ label: 'What we worked on', text: 'x' }, { label: 'Next time', text: '  ' }]
  it('refuses a draft with no day or no words', () => {
    expect(draftProblem({ day: '', sections })).toMatch(/day/)
    expect(draftProblem({ day: '2026-02-30x', sections })).toMatch(/day/)
    expect(draftProblem({ day: '2026-10-01', sections: [{ label: 'a', text: ' ' }] })).toMatch(/at least one/)
    expect(draftProblem({ day: '2026-10-01', sections })).toBeNull()
  })
  it('keeps out empty parts and keeps id and first-written time on edit', () => {
    const n = keepDraft({ kind: 'Session', day: '2026-10-01', sections, tagsText: 'Sleep' }, 100, () => 'fixed')
    expect(n.sections.map((s) => s.label)).toEqual(['What we worked on'])
    expect(n.tags).toEqual(['sleep'])
    const edited = keepDraft({ kind: 'Session', day: '2026-10-02', sections, tagsText: '' }, 200, () => 'other', n)
    expect(edited.id).toBe('fixed')
    expect(edited.createdAt).toBe(100)
    expect(edited.updatedAt).toBe(200)
  })
  it('upserts and removes by id', () => {
    const n = note({})
    const m = { ...n, kind: 'Assessment' }
    expect(upsert([n], m)).toEqual([m])
    expect(upsert([], n)).toEqual([n])
    expect(removeNote([n], n.id)).toEqual([])
  })
})

describe('the plain copy', () => {
  it('carries the day, kind, tags and every part', () => {
    const t = exportText([note({ day: '2026-10-03', tags: ['sleep'], text: 'hello' })])
    expect(t).toContain('2026-10-03 · Session')
    expect(t).toContain('Tags: sleep')
    expect(t).toContain('What we worked on\nhello')
  })
})

describe('notebook shape', () => {
  it('accepts what it writes and refuses anything else', () => {
    expect(isNoteList([note({})])).toBe(true)
    expect(isNoteList([{ ...note({}), tags: [1] }])).toBe(false)
    expect(isNoteList({})).toBe(false)
  })
  it('reads a bare list as a notebook with no deletions, and refuses a bad deletion record', () => {
    const n = note({})
    expect(asNotebook([n])).toEqual({ notes: [n], removed: {} })
    expect(asNotebook({ notes: [n], removed: { x: 5 } })).toEqual({ notes: [n], removed: { x: 5 } })
    expect(asNotebook({ notes: [n], removed: { x: 'soon' } })).toBeNull()
    expect(asNotebook({ notes: [n] })).toBeNull()
  })
})

describe('merging two copies', () => {
  const book = (notes: Note[], removed: Record<string, number> = {}): Notebook => ({ notes, removed })
  it('keeps notes from both, and the later edit of the same note', () => {
    const a = note({ updatedAt: 10, text: 'first' })
    const later = { ...a, updatedAt: 20, sections: [{ label: 'What we worked on', text: 'edited' }] }
    const b = note({})
    const m = mergeNotebooks(book([a, b]), book([later]))
    expect(m.notes.find((n) => n.id === a.id)?.sections[0].text).toBe('edited')
    expect(m.notes.map((n) => n.id).sort()).toEqual([a.id, b.id].sort())
  })
  it('does not bring back a note deleted after its last edit', () => {
    const a = note({ updatedAt: 10 })
    const here = deleteFromNotebook(book([a]), a.id, 15)
    expect(mergeNotebooks(here, book([a])).notes).toEqual([])
    // Control: the same copy merged without the deletion keeps the note.
    expect(mergeNotebooks(book([]), book([a])).notes).toEqual([a])
  })
  it('keeps a note edited after it was deleted elsewhere', () => {
    const a = note({ updatedAt: 30 })
    expect(mergeNotebooks(book([], { [a.id]: 20 }), book([a])).notes).toEqual([a])
  })
  it('gives the same result in either order', () => {
    const a = note({ updatedAt: 10 })
    const b = note({ updatedAt: 11 })
    const x = book([a], { gone: 3 })
    const y = book([b, { ...a, updatedAt: 12 }], { gone: 5 })
    expect(sameNotebook(mergeNotebooks(x, y), mergeNotebooks(y, x))).toBe(true)
    expect(sameNotebook(x, y)).toBe(false)
    expect(sameNotebook(emptyNotebook(), book([], {}))).toBe(true)
  })
})

function memory(): NotesStorage & { map: Map<string, string> } {
  const map = new Map<string, string>()
  return { map, getItem: (k) => map.get(k) ?? null, setItem: (k, v) => void map.set(k, v), removeItem: (k) => void map.delete(k) }
}

describe('the vault', async () => {
  const so = await initAssignmentCrypto()
  const kp = so.crypto_box_keypair()
  const other = so.crypto_box_keypair()

  it('round-trips under the same key and relationship', async () => {
    const lock = await notesLock(kp.privateKey, 'fp-a')
    const ns = { notes: [note({ text: 'private words' })], removed: { old: 1 } }
    const blob = await seal(lock, ns)
    expect(blob).not.toContain('private')
    expect(await open(lock, blob)).toEqual(ns)
  })
  it('does not open under another key or another relationship', async () => {
    const lock = await notesLock(kp.privateKey, 'fp-a')
    const blob = await seal(lock, { notes: [note({})], removed: {} })
    expect(await open(await notesLock(other.privateKey, 'fp-a'), blob)).toBeNull()
    expect(await open(await notesLock(kp.privateKey, 'fp-b'), blob)).toBeNull()
  })
  it('does not open a changed blob', async () => {
    const lock = await notesLock(kp.privateKey, 'fp-a')
    const bytes = so.from_base64(await seal(lock, { notes: [note({})], removed: {} }), so.base64_variants.URLSAFE_NO_PADDING)
    const i = bytes.length - 1
    bytes[i] = bytes[i] ^ 1
    const changed = so.to_base64(bytes, so.base64_variants.URLSAFE_NO_PADDING)
    expect(await open(lock, changed)).toBeNull()
  })
  it('loads empty, keeps, and loads again', async () => {
    const st = memory()
    const lock = await notesLock(kp.privateKey, 'fp-a')
    const first = await load(st, lock)
    expect(first).toEqual({ state: 'ready', book: { notes: [], removed: {} } })
    const ns = { notes: [note({})], removed: {} }
    await save(st, lock, first.state, ns)
    expect(await load(st, lock)).toEqual({ state: 'ready', book: ns })
  })
  it('never writes over notes that did not open', async () => {
    const st = memory()
    await save(st, await notesLock(kp.privateKey, 'fp-a'), 'ready', { notes: [note({})], removed: {} })
    const before = st.map.get(storageKey('fp-a'))
    const wrong = await notesLock(other.privateKey, 'fp-a')
    const loaded = await load(st, wrong)
    expect(loaded.state).toBe('unreadable')
    await expect(save(st, wrong, loaded.state, emptyNotebook())).rejects.toThrow(/nothing was written/)
    expect(st.map.get(storageKey('fp-a'))).toBe(before)
  })
  it('says so when the browser keeps nothing', async () => {
    const lock = await notesLock(kp.privateKey, 'fp-a')
    expect(await load(null, lock)).toEqual({ state: 'no-storage' })
    await expect(save(null, lock, 'no-storage', emptyNotebook())).rejects.toThrow(/not kept/)
  })
  it('wipes the derived key', async () => {
    const lock = await notesLock(kp.privateKey, 'fp-a')
    forgetLock(lock)
    expect(lock.key.every((b) => b === 0)).toBe(true)
  })
})
