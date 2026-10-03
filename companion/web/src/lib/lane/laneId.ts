/*
 * THIS BROWSER'S LANE, FOUND AGAIN AFTER A REFRESH (#345).
 *
 * Each browser writes its own lane, so no two browsers ever write one lineage and nothing is
 * merged. The first time a browser adds a record it makes its lane's name (lane/lineage.ts) and
 * keeps it here, in its own storage, under LANE_STORAGE_KEY; after a refresh, or on the next visit,
 * it writes to the same lane. Reading needs no name: every console reads every lane (lane.ts).
 *
 * WHAT IS KEPT. The lane's name and nothing else: 27 characters the server already holds as a
 * lineage name. No key, no token, no record. Someone looking through the device learns that this
 * browser added something to the owner's lane, which the server knows too.
 *
 * UNREADABLE IS ABSENT. With no storage (a private window, cleared site data, a browser that will
 * not hand it over) or a stored value that is not a lane's name, a new lane is made. The old one
 * stays on the server, holding every record in it the phone has not taken in, and every console
 * still reads it; nothing is lost, there is only one more lane to read.
 */
import { LANE_LINEAGE, LANE_PREFIX } from './lineage'
import { newRecordId } from './record'

/** Versioned so a later format can be migrated rather than silently mis-read. */
export const LANE_STORAGE_KEY = 'daymark.lane.v1'

/** The slice of the Storage API this needs. Lets tests pass a plain object; Node has no DOM. */
export interface LaneStorage {
  getItem(key: string): string | null
  setItem(key: string, value: string): void
}

/** localStorage, or null where it does not exist or the browser refuses to hand it over. */
export function defaultLaneStorage(): LaneStorage | null {
  try {
    return globalThis.localStorage ?? null
  } catch {
    return null
  }
}

/** A new lane's name: the prefix and 16 random bytes, as a record id is made. libsodium must be ready. */
export function newLaneLineage(): string {
  return LANE_PREFIX + newRecordId()
}

/** The lane this browser writes: the name it kept, or a new one, kept from now on where it can be. */
export function laneForThisBrowser(storage: LaneStorage | null = defaultLaneStorage()): string {
  try {
    const kept = storage?.getItem(LANE_STORAGE_KEY)
    if (kept && LANE_LINEAGE.test(kept)) return kept
  } catch {
    /* unreadable is absent */
  }
  const made = newLaneLineage()
  try {
    storage?.setItem(LANE_STORAGE_KEY, made)
  } catch {
    /* kept for this session only, by the caller */
  }
  return made
}
