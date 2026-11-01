/*
 * Where a clinician's notes are kept, and how they are locked.
 *
 * LOCKED TO THE CLINICIAN'S OWN KEY. The notebook is one XChaCha20-Poly1305 blob under a key
 * derived from the clinician's X25519 secret key (keyStore.ts), which exists unwrapped only in
 * memory after sign-in. The client never holds that key, and the server never receives the notes
 * at all: the blob lives only in this browser's localStorage, beside the wrapped key record. So the
 * notes outlive the client's sharing (DECISIONS.md §D12), and they
 * share the key record's honest limit: a cleared browser loses them, which is why the screen
 * offers a plain copy for the clinician's own records.
 *
 *   key  = BLAKE2b-256(key = box.privateKey, msg = "daymark/notes/key/v1")
 *   aad  = "daymark/notes/v1|" + therapistFp
 *   blob = base64url( 0x01 || nonce(24) || XChaCha20-Poly1305(JSON(notes), aad, nonce, key) )
 *
 * The derivation's own label keeps this key apart from every other use of the box key, and the
 * AAD binds a blob to the key that wrote it, so a notebook copied under another relationship's
 * storage key fails to open instead of being read as that relationship's.
 *
 * A BLOB THAT DOES NOT OPEN IS NEVER OVERWRITTEN. load() reports it, and save() refuses while that
 * is the state: writing an empty notebook over notes that merely failed to open would destroy
 * them on the strength of one bad read.
 */
import _sodium from 'libsodium-wrappers-sumo'
import { initAssignmentCrypto } from '../assignments/crypto'
import { isNotebook, type Note } from './notes'

const VERSION = 1
const URLSAFE = () => _sodium.base64_variants.URLSAFE_NO_PADDING
const enc = new TextEncoder()
const dec = new TextDecoder()

export const NOTES_STORAGE_PREFIX = 'daymark.clinician.notes.v1.'

export interface NotesStorage {
  getItem(key: string): string | null
  setItem(key: string, value: string): void
  removeItem?(key: string): void
}

/** localStorage, or null where it does not exist or the browser refuses to hand it over. */
export function defaultNotesStorage(): NotesStorage | null {
  try {
    return globalThis.localStorage ?? null
  } catch {
    return null
  }
}

export interface NotesLock {
  key: Uint8Array
  fp: string
}

export async function notesLock(boxPrivateKey: Uint8Array, therapistFp: string): Promise<NotesLock> {
  const so = await initAssignmentCrypto()
  return { key: so.crypto_generichash(32, enc.encode('daymark/notes/key/v1'), boxPrivateKey), fp: therapistFp }
}

const aad = (fp: string) => enc.encode(`daymark/notes/v1|${fp}`)
export const storageKey = (fp: string) => NOTES_STORAGE_PREFIX + fp

export async function seal(lock: NotesLock, notes: readonly Note[]): Promise<string> {
  const so = await initAssignmentCrypto()
  const nonce = so.randombytes_buf(so.crypto_aead_xchacha20poly1305_ietf_NPUBBYTES)
  const ct = so.crypto_aead_xchacha20poly1305_ietf_encrypt(enc.encode(JSON.stringify(notes)), aad(lock.fp), null, nonce, lock.key)
  const out = new Uint8Array(1 + nonce.length + ct.length)
  out[0] = VERSION
  out.set(nonce, 1)
  out.set(ct, 1 + nonce.length)
  return so.to_base64(out, URLSAFE())
}

/** The notebook, or null for any failure: wrong key, wrong relationship, tampered or not ours. */
export async function open(lock: NotesLock, blob: string): Promise<Note[] | null> {
  const so = await initAssignmentCrypto()
  try {
    const bytes = so.from_base64(blob, URLSAFE())
    const n = so.crypto_aead_xchacha20poly1305_ietf_NPUBBYTES
    if (bytes.length < 1 + n + so.crypto_aead_xchacha20poly1305_ietf_ABYTES || bytes[0] !== VERSION) return null
    const plain = so.crypto_aead_xchacha20poly1305_ietf_decrypt(null, bytes.subarray(1 + n), aad(lock.fp), bytes.subarray(1, 1 + n), lock.key)
    const parsed: unknown = JSON.parse(dec.decode(plain))
    return isNotebook(parsed) ? parsed : null
  } catch {
    return null
  }
}

export type Loaded =
  | { state: 'ready'; notes: Note[] }
  /** Notes are stored for this key but did not open. Nothing may be written over them. */
  | { state: 'unreadable' }
  /** This browser will not keep anything. Notes can be written, but not kept. */
  | { state: 'no-storage' }

export async function load(storage: NotesStorage | null, lock: NotesLock): Promise<Loaded> {
  if (!storage) return { state: 'no-storage' }
  const blob = storage.getItem(storageKey(lock.fp))
  if (blob === null) return { state: 'ready', notes: [] }
  const notes = await open(lock, blob)
  return notes ? { state: 'ready', notes } : { state: 'unreadable' }
}

export class NotesNotKept extends Error {}

/** Locks and keeps the whole notebook. Refuses rather than overwrites a notebook that did not open. */
export async function save(storage: NotesStorage | null, lock: NotesLock, loaded: Loaded['state'], notes: readonly Note[]): Promise<void> {
  if (loaded === 'unreadable') throw new NotesNotKept('The notes already kept here did not open, so nothing was written over them.')
  if (!storage) throw new NotesNotKept('This browser is not keeping anything, so the note was not kept.')
  try {
    storage.setItem(storageKey(lock.fp), await seal(lock, notes))
  } catch {
    throw new NotesNotKept('This browser refused to keep the note, often because its storage is full.')
  }
}

export function forgetLock(lock: NotesLock | null): void {
  lock?.key.fill(0)
}
