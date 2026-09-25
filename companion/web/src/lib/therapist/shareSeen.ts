/*
 * THE NEWEST SHARE THIS BROWSER HAS OPENED, per relationship, so an older copy stays closed.
 *
 * What this stops: a share the owner sealed BEFORE one this browser has already opened. An honest
 * server never serves one (a newer share ends the ones before it, #332), but a server restored
 * from a backup can, and so can anything able to change what a server stores. The order is the
 * owner's own and it is signed: `createdAt` is in the share's transcript (share/sharecrypto.ts),
 * so it cannot be changed without breaking the owner's signature. Version numbers are not used,
 * because a restored server could hand the same numbers out again and lock out a genuine share.
 *
 * What this does not stop, said plainly:
 *  - the first share a browser opens, which has nothing to be compared with;
 *  - a server that changes the page itself: the check runs in code that server serves
 *    (COMPANION_SECURITY.md R5). A clinician client the server cannot change is #319;
 *  - a withdrawn share that a dishonest server kept, which is not older than itself (R3).
 *
 * WHAT GOES INTO STORAGE. For each relationship, the creation time of the newest share opened,
 * sealed to that relationship's own X25519 key (crypto_box_seal), under the relRef that the key
 * record already keeps in the clear (therapist/inviteAccept.ts). Someone looking through the
 * device learns that a share was opened in this browser, never when it was sealed, and the portal
 * reads the time back only while the keys are unlocked.
 *
 * UNREADABLE IS ABSENT: bad JSON, a record sealed to keys this browser no longer holds (after
 * re-pairing), or storage the browser will not hand over. The next share opened writes a fresh
 * record. Clearing site data is therefore a way round the check, as it already is for the keys
 * themselves; the check is there to catch a server, not the person at the keyboard.
 */
import _sodium from 'libsodium-wrappers-sumo'
import type { BoxKeyPair } from '../assignments/crypto'

/** Versioned so a later format can be migrated rather than silently mis-read. */
export const SHARE_SEEN_STORAGE_KEY = 'daymark.therapist.share-seen.v1'

/** The slice of the Storage API this needs. Lets tests pass a plain object; Node has no DOM. */
export interface SeenStorage {
  getItem(key: string): string | null
  setItem(key: string, value: string): void
}

/** localStorage, or null where it does not exist or the browser refuses to hand it over. */
export function defaultSeenStorage(): SeenStorage | null {
  try {
    return globalThis.localStorage ?? null
  } catch {
    return null
  }
}

const URLSAFE = () => _sodium.base64_variants.URLSAFE_NO_PADDING

function readAll(storage: SeenStorage): Record<string, unknown> {
  try {
    const raw = storage.getItem(SHARE_SEEN_STORAGE_KEY)
    if (raw === null) return {}
    const parsed: unknown = JSON.parse(raw)
    return parsed !== null && typeof parsed === 'object' && !Array.isArray(parsed) ? (parsed as Record<string, unknown>) : {}
  } catch {
    return {}
  }
}

/** When the newest share this browser opened for `relRef` was sealed, or null if it knows of none. */
export function newestOpened(
  relRef: string,
  box: BoxKeyPair,
  storage: SeenStorage | null = defaultSeenStorage(),
): number | null {
  if (!storage) return null
  const sealed = readAll(storage)[relRef]
  if (typeof sealed !== 'string') return null
  try {
    const plain = _sodium.crypto_box_seal_open(_sodium.from_base64(sealed, URLSAFE()), box.publicKey, box.privateKey)
    const text = new TextDecoder().decode(plain)
    const n = Number(text)
    return /^\d+$/.test(text) && Number.isSafeInteger(n) ? n : null
  } catch {
    return null
  }
}

/** Remember that a share sealed at `createdAt` was opened, keeping the later of it and what is held. */
export function rememberOpened(
  relRef: string,
  createdAt: number,
  box: BoxKeyPair,
  storage: SeenStorage | null = defaultSeenStorage(),
): void {
  if (!storage) return
  const held = newestOpened(relRef, box, storage)
  if (held !== null && held >= createdAt) return
  const all = readAll(storage)
  all[relRef] = _sodium.to_base64(_sodium.crypto_box_seal(new TextEncoder().encode(String(createdAt)), box.publicKey), URLSAFE())
  try {
    storage.setItem(SHARE_SEEN_STORAGE_KEY, JSON.stringify(all))
  } catch {
    // A browser that refuses the write leaves the check where it was. The share itself opened.
  }
}
