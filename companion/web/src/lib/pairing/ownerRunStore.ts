/*
 * The owner's half of an open pairing run, kept across a reload.
 *
 * WHY IT HAS TO EXIST. relay.ts holds the CPace scalar in memory only, on purpose, and says the
 * caller must keep it "on the device, under its own protection" if the run is to survive the
 * store-and-forward gap (COMPANION_PAIRING.md §4, "What each side keeps across the gap") — the
 * owner opens a run now and the therapist answers whenever they open the link. Without this, a
 * page reload between those two moments makes the run unfinishable by anyone, and the only remedy
 * spends one of the invitation's eight exchanges.
 *
 * WHAT IS IN IT, AND WHAT IS NOT. The scalar, the opening message, the session id, and the ids that
 * name the run — everything cpaceFinish() needs. NOT the code: the code is never persisted anywhere,
 * and cpaceFinish() does not need it (cpace.ts), which is what makes that a structural property
 * rather than a promise. NOT the derived key either: it is re-derivable from the scalar and a read
 * of the reply, so storing it would widen exposure for nothing. A test serialises a run made from a
 * real code and greps the stored value for the code in every encoding relay.test.ts checks.
 *
 * WHERE. sessionStorage, not localStorage: per tab, gone when the tab closes. The scalar is a
 * secret for the life of one run, and a run outliving the tab that opened it is the case the owner's
 * screen handles by offering a new code rather than the case this file tries to survive.
 *
 * FAILS OPEN. A browser that refuses storage yields a null port, not an error: the ceremony still
 * runs in memory, and the screen tells the owner the run will not survive a reload.
 */
import type { OwnerPairingState } from './relay'

export const OWNER_RUN_STORAGE_KEY = 'daymark.pairing.owner-run.v1'

export interface OwnerRunRecord {
  v: 1
  relRef: string
  inviteId: string
  exchangeId: string
  sidB64: string
  /** The CPace scalar. Secret; the reason this lives in sessionStorage and nowhere longer-lived. */
  yaB64: string
  msgAB64: string
  createdAt: number
  /**
   * The reply this run pinned, once one was read. Restored so that ONE RUN, ONE KEY holds across a
   * reload as well as within a tab: a later read showing a different reply is refused, not re-keyed.
   */
  pinnedMsgBB64?: string
}

/** The slice of the Storage API this needs. Tests pass a plain object; Node has no DOM. */
export interface OwnerRunStorage {
  getItem(key: string): string | null
  setItem(key: string, value: string): void
  removeItem?(key: string): void
}

/** sessionStorage, or null where it does not exist or the browser refuses to hand it over. */
export function defaultOwnerRunStorage(): OwnerRunStorage | null {
  try {
    return globalThis.sessionStorage ?? null
  } catch {
    return null
  }
}

const b64 = {
  encode(bytes: Uint8Array): string {
    let s = ''
    for (const b of bytes) s += String.fromCharCode(b)
    return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
  },
  decode(text: string): Uint8Array {
    if (!/^[A-Za-z0-9_-]*$/.test(text)) throw new Error('not base64url')
    const std = text.replace(/-/g, '+').replace(/_/g, '/')
    const raw = atob(std)
    const out = new Uint8Array(raw.length)
    for (let i = 0; i < raw.length; i++) out[i] = raw.charCodeAt(i)
    return out
  },
}

/** What to persist for a run relay.ts just opened. Never takes the code; there is no field for it. */
export function ownerRunFromState(relRef: string, state: OwnerPairingState, now: number): OwnerRunRecord {
  const record: OwnerRunRecord = {
    v: 1,
    relRef,
    inviteId: state.inviteId,
    exchangeId: state.exchangeId,
    sidB64: state.sidB64,
    yaB64: b64.encode(state.start.ya),
    msgAB64: b64.encode(state.start.msgA),
    createdAt: now,
  }
  if (state.pinnedMsgBB64 !== undefined) record.pinnedMsgBB64 = state.pinnedMsgBB64
  return record
}

/** The in-memory state relay.ts continues from. `finished` is left unset: the key is re-derived. */
export function ownerStateFromRun(record: OwnerRunRecord): OwnerPairingState {
  const state: OwnerPairingState = {
    exchangeId: record.exchangeId,
    inviteId: record.inviteId,
    sidB64: record.sidB64,
    start: { ya: b64.decode(record.yaB64), msgA: b64.decode(record.msgAB64) },
  }
  if (record.pinnedMsgBB64 !== undefined) state.pinnedMsgBB64 = record.pinnedMsgBB64
  return state
}

export function saveOwnerRun(storage: OwnerRunStorage | null, record: OwnerRunRecord): void {
  if (!storage) return
  try {
    storage.setItem(OWNER_RUN_STORAGE_KEY, JSON.stringify(record))
  } catch {
    // A refusing browser is a null port, not an error. The screen says the run will not survive a reload.
  }
}

/**
 * The stored run, or null for anything that is not exactly a v1 record. A record this code cannot
 * vouch for is treated as absent rather than repaired: the harmless direction is "open a new run".
 */
export function loadOwnerRun(storage: OwnerRunStorage | null): OwnerRunRecord | null {
  if (!storage) return null
  let raw: string | null
  try {
    raw = storage.getItem(OWNER_RUN_STORAGE_KEY)
  } catch {
    return null
  }
  if (!raw) return null
  let parsed: unknown
  try {
    parsed = JSON.parse(raw)
  } catch {
    return null
  }
  if (typeof parsed !== 'object' || parsed === null) return null
  const r = parsed as Record<string, unknown>
  if (r.v !== 1) return null
  for (const key of ['relRef', 'inviteId', 'exchangeId', 'sidB64', 'yaB64', 'msgAB64'] as const) {
    if (typeof r[key] !== 'string' || (r[key] as string).length === 0) return null
  }
  if (typeof r.createdAt !== 'number' || !Number.isFinite(r.createdAt)) return null
  if (r.pinnedMsgBB64 !== undefined && typeof r.pinnedMsgBB64 !== 'string') return null
  try {
    // A scalar that does not decode is not a run this code can continue.
    if (b64.decode(r.yaB64 as string).length !== 32) return null
    b64.decode(r.msgAB64 as string)
    b64.decode(r.sidB64 as string)
  } catch {
    return null
  }
  const record: OwnerRunRecord = {
    v: 1,
    relRef: r.relRef as string,
    inviteId: r.inviteId as string,
    exchangeId: r.exchangeId as string,
    sidB64: r.sidB64 as string,
    yaB64: r.yaB64 as string,
    msgAB64: r.msgAB64 as string,
    createdAt: r.createdAt,
  }
  if (typeof r.pinnedMsgBB64 === 'string') record.pinnedMsgBB64 = r.pinnedMsgBB64
  return record
}

export function forgetOwnerRun(storage: OwnerRunStorage | null): void {
  if (!storage) return
  try {
    if (storage.removeItem) storage.removeItem(OWNER_RUN_STORAGE_KEY)
    else storage.setItem(OWNER_RUN_STORAGE_KEY, '')
  } catch {
    // Nothing to do: a port that cannot forget also could not have remembered.
  }
}
