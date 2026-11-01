<script lang="ts">
  /*
   * The clinician's own notes: write, tag, search, and keep a plain copy.
   *
   * What this screen must keep true:
   * - The notes are the clinician's writing, locked to the clinician's own key in this browser
   *   (notes/vault.ts). Nothing here sends them anywhere, and nothing reads the client's share.
   * - A notebook that did not open is reported, never overwritten (vault.ts save()).
   * - Search and tags are rules over the clinician's own words (notes.ts); nothing is ranked,
   *   suggested or written for them.
   * - The plain copy is said to be unlocked at the point of the click, because it is.
   */
  import { onDestroy } from 'svelte'
  import {
    draftProblem, exportText, findNotes, keepDraft, removeNote, tagCounts, upsert,
    type Note, type NoteSection,
  } from '../../notes/notes'
  import {
    defaultNotesStorage, forgetLock, load, notesLock, save, NotesNotKept,
    type Loaded, type NotesLock,
  } from '../../notes/vault'

  let {
    boxPrivateKey,
    therapistFp,
    kind,
    sections,
    now,
  }: {
    boxPrivateKey: Uint8Array
    therapistFp: string
    /** What a new note follows, from the discipline (discipline.ts noteKind). */
    kind: string
    /** The parts a new note offers, from the discipline. */
    sections: readonly { id: string; label: string }[]
    now: number
  } = $props()

  const storage = defaultNotesStorage()
  let lock: NotesLock | null = null
  let loaded = $state<Loaded['state'] | 'opening'>('opening')
  let notes = $state<Note[]>([])
  let problem = $state('')

  $effect(() => {
    const sk = boxPrivateKey
    const fp = therapistFp
    let cancelled = false
    void (async () => {
      const l = await notesLock(sk, fp)
      if (cancelled) return forgetLock(l)
      forgetLock(lock)
      lock = l
      const r = await load(storage, l)
      if (cancelled) return
      loaded = r.state
      notes = r.state === 'ready' ? r.notes : []
    })()
    return () => {
      cancelled = true
    }
  })
  onDestroy(() => forgetLock(lock))

  /* ---- finding ---- */
  let query = $state('')
  let chosenTags = $state<string[]>([])
  const shown = $derived(findNotes(notes, { query, tags: chosenTags }))
  const tags = $derived(tagCounts(notes))
  function toggleTag(t: string) {
    chosenTags = chosenTags.includes(t) ? chosenTags.filter((x) => x !== t) : [...chosenTags, t]
  }
  function clearFilters() {
    query = ''
    chosenTags = []
  }

  /* ---- writing ---- */
  const today = (t: number) => {
    const d = new Date(t)
    return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
  }
  let editing = $state<Note | null>(null)
  let writing = $state(false)
  let draftKind = $state('')
  let draftDay = $state('')
  let draftSections = $state<NoteSection[]>([])
  let draftTags = $state('')
  let draftError = $state('')

  function startNew() {
    editing = null
    draftKind = kind
    draftDay = today(now)
    draftSections = sections.map((s) => ({ label: s.label, text: '' }))
    draftTags = ''
    draftError = ''
    writing = true
  }
  function startEdit(n: Note) {
    editing = n
    draftKind = n.kind
    draftDay = n.day
    // The note's own parts first, then any the current discipline offers that it lacks.
    const own = n.sections.map((s) => ({ ...s }))
    for (const s of sections) if (!own.some((o) => o.label === s.label)) own.push({ label: s.label, text: '' })
    draftSections = own
    draftTags = n.tags.join(', ')
    draftError = ''
    writing = true
  }
  function cancel() {
    writing = false
    editing = null
  }

  async function persist(next: Note[]): Promise<boolean> {
    if (!lock || loaded === 'opening') return false
    try {
      await save(storage, lock, loaded, next)
      notes = next
      problem = ''
      return true
    } catch (e) {
      problem = e instanceof NotesNotKept ? e.message : 'The note was not kept.'
      return false
    }
  }

  async function keep() {
    const p = draftProblem({ day: draftDay, sections: draftSections })
    if (p) {
      draftError = p
      return
    }
    const n = keepDraft(
      { kind: draftKind.trim() || kind, day: draftDay, sections: draftSections, tagsText: draftTags },
      Date.now(),
      () => crypto.randomUUID(),
      editing ?? undefined,
    )
    if (await persist(upsert(notes, n))) {
      writing = false
      editing = null
    }
  }

  let confirmingDelete = $state<string | null>(null)
  async function remove(id: string) {
    if (await persist(removeNote(notes, id))) confirmingDelete = null
  }

  function download() {
    const blob = new Blob([exportText(notes)], { type: 'text/plain;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `notes-${today(now)}.txt`
    a.click()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  }

  const dayLabel = (day: string) =>
    new Date(day + 'T12:00:00').toLocaleDateString(undefined, { weekday: 'short', day: 'numeric', month: 'short', year: 'numeric' })
</script>

<p class="lede">
  Your own notes about your work with this client. They are locked to your key and kept only in
  this browser. The server never receives them, the client cannot read them, and they stay with you
  if the client stops sharing.
</p>

{#if loaded === 'opening'}
  <p class="small">Opening your notes…</p>
{:else if loaded === 'unreadable'}
  <div class="callout" role="alert">
    <b>Notes are kept here, but they did not open with this key.</b>
    <span>Nothing has been written over them. New notes cannot be kept until they open.</span>
  </div>
{:else}
  {#if loaded === 'no-storage'}
    <div class="callout" role="status">
      <b>This browser is not keeping anything.</b>
      <span>You can write a note, but it is gone when you close the page.</span>
    </div>
  {/if}
  {#if problem}<p class="err" role="alert">{problem}</p>{/if}

  {#if writing}
    <form class="card editor" onsubmit={(e) => { e.preventDefault(); void keep() }} aria-label={editing ? 'Edit note' : 'New note'}>
      <div class="row2">
        <div class="field">
          <label for="note-kind">Follows</label>
          <input id="note-kind" bind:value={draftKind} maxlength="60" />
        </div>
        <div class="field">
          <label for="note-day">Day</label>
          <input id="note-day" type="date" bind:value={draftDay} />
        </div>
      </div>
      {#each draftSections as s, i (s.label + i)}
        <div class="field">
          <label for="note-sec-{i}">{s.label}</label>
          <textarea id="note-sec-{i}" bind:value={s.text} rows="4"></textarea>
        </div>
      {/each}
      <div class="field">
        <label for="note-tags">Tags</label>
        <input id="note-tags" bind:value={draftTags} placeholder="sleep, homework, exposure" />
        <span class="small soft">Separate tags with commas. They help you find notes later.</span>
      </div>
      {#if draftError}<p class="err" role="alert">{draftError}</p>{/if}
      <div class="actions">
        <button type="submit" class="primary">Keep note</button>
        <button type="button" class="quiet" onclick={cancel}>{editing ? 'Leave it as it was' : 'Discard this note'}</button>
      </div>
    </form>
  {:else}
    <div class="actions">
      <button type="button" class="primary" onclick={startNew}>New {kind.toLowerCase()} note</button>
      {#if notes.length}
        <button type="button" onclick={download}>Download a plain copy</button>
        <span class="small soft">The download is not locked. Keep it wherever you keep your other records.</span>
      {/if}
    </div>
  {/if}

  {#if notes.length}
    <div class="find" role="search">
      <label for="note-search" class="sec">Search your notes</label>
      <input id="note-search" type="search" bind:value={query} placeholder="Words from any part, a tag, or a date" />
      {#if tags.length}
        <div class="tags" role="group" aria-label="Narrow by tag">
          {#each tags as t (t.tag)}
            <button type="button" class="tag" class:on={chosenTags.includes(t.tag)} aria-pressed={chosenTags.includes(t.tag)} onclick={() => toggleTag(t.tag)}>
              {t.tag} <span class="n">{t.count}</span>
            </button>
          {/each}
        </div>
      {/if}
      <p class="small soft" aria-live="polite">
        {#if query || chosenTags.length}
          {shown.length} of {notes.length} {notes.length === 1 ? 'note' : 'notes'} match.
          <button type="button" class="quiet" onclick={clearFilters}>Show all</button>
        {:else}
          {notes.length} {notes.length === 1 ? 'note' : 'notes'}, newest first.
        {/if}
      </p>
    </div>

    <ol class="list">
      {#each shown as n (n.id)}
        <li class="card note">
          <div class="head">
            <span class="kick">{n.kind}</span>
            <b class="day">{dayLabel(n.day)}</b>
          </div>
          {#each n.sections as s}
            <div class="part">
              <span class="plabel">{s.label}</span>
              <p>{s.text}</p>
            </div>
          {/each}
          {#if n.tags.length}
            <div class="tags">{#each n.tags as t}<span class="tag static">{t}</span>{/each}</div>
          {/if}
          <div class="actions">
            <button type="button" class="quiet" onclick={() => startEdit(n)}>Edit</button>
            {#if confirmingDelete === n.id}
              <span class="small">Delete this note? It cannot be brought back.</span>
              <button type="button" onclick={() => remove(n.id)}>Delete</button>
              <button type="button" class="quiet" onclick={() => (confirmingDelete = null)}>Keep it</button>
            {:else}
              <button type="button" class="quiet" onclick={() => (confirmingDelete = n.id)}>Delete</button>
            {/if}
          </div>
        </li>
      {/each}
    </ol>
    {#if !shown.length}<p class="small soft">No notes match. Try fewer words or fewer tags.</p>{/if}
  {:else if !writing}
    <p class="small soft">No notes yet. Notes you write appear here, newest first.</p>
  {/if}
{/if}

<style>
  .lede { color: var(--ink-soft); margin: 0 0 1rem; max-width: 42rem; }
  .small { font-size: 0.86rem; }
  .soft { color: var(--ink-soft); }
  .err { font-size: 0.86rem; color: var(--clay); }
  .sec { font-size: 0.78rem; font-weight: 600; letter-spacing: 0.08em; text-transform: uppercase; color: var(--ink-soft); }
  .kick { font-size: 0.7rem; letter-spacing: 0.08em; text-transform: uppercase; color: var(--ink-soft); font-weight: 650; }
  .actions { display: flex; flex-wrap: wrap; gap: 0.5rem; align-items: center; margin: 0 0 1rem; }
  .quiet { border-color: transparent; background: none; text-decoration: underline; text-underline-offset: 3px; color: var(--ink-soft); }
  .primary { background: var(--indigo); color: var(--paper-sheet); border-color: var(--indigo); }
  .callout { display: flex; flex-direction: column; gap: 0.2rem; background: var(--amber-wash); border-left: 3px solid var(--amber); border-radius: var(--radius-sm); padding: 0.75rem 1rem; margin-bottom: 1rem; }
  .card { background: var(--paper-sheet); border: 1px solid var(--hairline); border-radius: var(--radius); padding: 1rem; min-width: 0; }
  .editor { display: flex; flex-direction: column; gap: 0.9rem; margin-bottom: 1.25rem; max-width: 46rem; }
  .row2 { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 1rem; }
  @media (max-width: 34rem) { .row2 { grid-template-columns: minmax(0, 1fr); } }
  .field { display: flex; flex-direction: column; gap: 0.35rem; min-width: 0; }
  .field label { font-weight: 600; font-size: 0.95rem; }
  input, textarea { font: inherit; padding: 0.55rem 0.75rem; border: 1px solid var(--border-strong); border-radius: var(--radius-sm); background: var(--paper-bg); color: var(--ink-text); width: 100%; min-width: 0; }
  input { min-height: 2.75rem; }
  textarea { resize: vertical; line-height: 1.5; }
  .find { display: flex; flex-direction: column; gap: 0.5rem; margin: 0.5rem 0 1rem; max-width: 46rem; }
  .tags { display: flex; flex-wrap: wrap; gap: 0.4rem; }
  .tag { font-size: 0.82rem; padding: 0.2rem 0.65rem; border: 1px solid var(--border-strong); border-radius: 999px; background: var(--paper-sheet); color: var(--ink-text); }
  .tag.on { border-color: var(--indigo); background: var(--indigo-wash); color: var(--indigo-deep); }
  .tag.static { border-color: var(--hairline); color: var(--ink-soft); }
  .tag .n { font-family: var(--font-mono); font-size: 0.72rem; color: var(--ink-soft); margin-left: 0.2rem; }
  .list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 0.75rem; max-width: 46rem; }
  .note { display: flex; flex-direction: column; gap: 0.6rem; }
  .note .actions { margin: 0; }
  .head { display: flex; flex-wrap: wrap; align-items: baseline; gap: 0.25rem 0.75rem; }
  .day { font-family: var(--font-display); font-weight: 500; font-size: 1.05rem; }
  .part { display: flex; flex-direction: column; gap: 0.15rem; }
  .plabel { font-size: 0.82rem; font-weight: 600; color: var(--ink-soft); }
  .part p { margin: 0; white-space: pre-wrap; overflow-wrap: anywhere; }
</style>
