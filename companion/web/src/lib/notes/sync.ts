/*
 * Keeping the notebook in step with its locked copy on the server (#548).
 *
 * Pure apart from the ports, so ORDER is a node test. The server holds the same sealed blob the
 * browser holds (vault.ts) and never opens it; this file decides what to write where.
 *
 * WHAT IT MUST KEEP TRUE:
 * - A copy that does not open is never written over, here or on the server, on the strength of one
 *   bad read. It is reported, and the browser's own copy keeps working.
 * - Two writers cannot silently overwrite each other. The server refuses a write based on an old
 *   version; this reads the newer copy, merges note by note (notes.ts mergeNotebooks) and tries
 *   again, a bounded number of times.
 * - A failure to reach the server never loses a note: the browser's copy is kept first, and the
 *   server copy catches up on the next save or the next sign-in.
 */
import { mergeNotebooks, sameNotebook, type Notebook } from './notes'
import { open, seal, type NotesLock } from './vault'

export type RemoteRead = { version: number; blob: string | null }
export type RemoteWrite = { state: 'written'; version: number } | { state: 'conflict'; current: number }

export interface NotesRemote {
  read(): Promise<RemoteRead>
  write(basedOn: number, blob: string): Promise<RemoteWrite>
}

export type RemoteState =
  /** In step: the server's copy is this version. */
  | { state: 'kept'; version: number }
  /** The server holds a copy that did not open with this key. Nothing was written over it. */
  | { state: 'unreadable' }
  /** The server could not be reached or refused. The browser's copy is kept. */
  | { state: 'unreached' }

export const MAX_ATTEMPTS = 3

/** Reads the server copy and opens it. `null` book means there is none yet. */
export async function pull(lock: NotesLock, remote: NotesRemote): Promise<{ version: number; book: Notebook | null } | 'unreadable'> {
  const r = await remote.read()
  if (r.blob === null) return { version: r.version, book: null }
  const book = await open(lock, r.blob)
  return book ? { version: r.version, book } : 'unreadable'
}

/**
 * Brings the server copy up to date with `local`, merging in anything newer the server holds.
 * Returns the notebook both now hold (which may include notes written elsewhere) and where the
 * server stands.
 */
export async function reconcile(lock: NotesLock, remote: NotesRemote, local: Notebook): Promise<{ book: Notebook; remote: RemoteState }> {
  let book = local
  try {
    for (let attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
      const got = await pull(lock, remote)
      if (got === 'unreadable') return { book, remote: { state: 'unreadable' } }
      if (got.book) book = mergeNotebooks(book, got.book)
      if (got.book && sameNotebook(book, got.book)) return { book, remote: { state: 'kept', version: got.version } }
      const w = await remote.write(got.version, await seal(lock, book))
      if (w.state === 'written') return { book, remote: { state: 'kept', version: w.version } }
      // Somebody wrote first. Read theirs, merge, and try again.
    }
    return { book, remote: { state: 'unreached' } }
  } catch {
    return { book, remote: { state: 'unreached' } }
  }
}
