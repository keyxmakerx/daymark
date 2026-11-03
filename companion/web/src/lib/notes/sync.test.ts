import { describe, expect, it } from 'vitest'
import { emptyNotebook, type Note, type Notebook } from './notes'
import { MAX_ATTEMPTS, reconcile, type NotesRemote } from './sync'
import { notesLock, open, seal } from './vault'
import { initAssignmentCrypto } from '../assignments/crypto'

let seq = 0
const note = (updatedAt = ++seq): Note => ({
  id: `n${++seq}`, kind: 'Session', day: '2026-10-01', sections: [{ label: 'Worked on', text: `t${seq}` }],
  tags: [], createdAt: updatedAt, updatedAt,
})

/** A server that behaves like the real route: compare-and-swap on the version. */
function fakeServer(start: { version: number; blob: string | null } = { version: 0, blob: null }) {
  const s = { ...start, writes: 0, reads: 0 }
  const remote: NotesRemote = {
    read: async () => (s.reads++, { version: s.version, blob: s.blob }),
    write: async (basedOn, blob) => {
      s.writes++
      if (basedOn !== s.version) return { state: 'conflict', current: s.version }
      s.version++
      s.blob = blob
      return { state: 'written', version: s.version }
    },
  }
  return { s, remote }
}

describe('keeping the server copy in step', async () => {
  const so = await initAssignmentCrypto()
  const kp = so.crypto_box_keypair()
  const lock = await notesLock(kp.privateKey, 'fp-a')
  const book = (...notes: Note[]): Notebook => ({ notes, removed: {} })

  it('writes the first copy, sealed, and says it is kept', async () => {
    const { s, remote } = fakeServer()
    const local = book(note())
    const r = await reconcile(lock, remote, local)
    expect(r.remote).toEqual({ state: 'kept', version: 1 })
    expect(await open(lock, s.blob!)).toEqual(local)
  })

  it('brings in notes written elsewhere and writes the merge', async () => {
    const elsewhere = note()
    const { s, remote } = fakeServer({ version: 4, blob: await seal(lock, book(elsewhere)) })
    const mine = note()
    const r = await reconcile(lock, remote, book(mine))
    expect(r.book.notes.map((n) => n.id).sort()).toEqual([elsewhere.id, mine.id].sort())
    expect(r.remote).toEqual({ state: 'kept', version: 5 })
    expect((await open(lock, s.blob!))!.notes).toHaveLength(2)
  })

  it('writes nothing when the server already holds the same notebook', async () => {
    const same = book(note())
    const { s, remote } = fakeServer({ version: 2, blob: await seal(lock, same) })
    const r = await reconcile(lock, remote, same)
    expect(r.remote).toEqual({ state: 'kept', version: 2 })
    expect(s.writes).toBe(0)
  })

  it('never writes over a server copy that does not open', async () => {
    const other = await notesLock(so.crypto_box_keypair().privateKey, 'fp-a')
    const theirs = await seal(other, book(note()))
    const { s, remote } = fakeServer({ version: 3, blob: theirs })
    const r = await reconcile(lock, remote, book(note()))
    expect(r.remote).toEqual({ state: 'unreadable' })
    expect(s.blob).toBe(theirs)
    expect(s.writes).toBe(0)
  })

  it('merges and tries again when another write lands first, a bounded number of times', async () => {
    const { s, remote } = fakeServer()
    let racing = 0
    const busy: NotesRemote = {
      read: remote.read,
      // Another tab writes between every read and write.
      write: async (basedOn, blob) => {
        racing++
        s.version++
        s.blob = await seal(lock, book(note()))
        return remote.write(basedOn, blob)
      },
    }
    const r = await reconcile(lock, busy, book(note()))
    expect(r.remote).toEqual({ state: 'unreached' })
    expect(racing).toBe(MAX_ATTEMPTS)
  })

  it('a server that cannot be reached loses nothing', async () => {
    const down: NotesRemote = {
      read: async () => { throw new Error('offline') },
      write: async () => { throw new Error('offline') },
    }
    const local = book(note())
    const r = await reconcile(lock, down, local)
    expect(r.remote).toEqual({ state: 'unreached' })
    expect(r.book).toEqual(local)
  })

  it('a deletion here removes the note from the server copy too', async () => {
    const gone = note(10)
    const { s, remote } = fakeServer({ version: 1, blob: await seal(lock, book(gone)) })
    const local: Notebook = { notes: [], removed: { [gone.id]: 20 } }
    await reconcile(lock, remote, local)
    expect((await open(lock, s.blob!))!.notes).toEqual([])
    expect(emptyNotebook().notes).toEqual([])
  })
})
