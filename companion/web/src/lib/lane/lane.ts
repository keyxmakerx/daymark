/*
 * THE WEB CONSOLE'S LANE ON THE OWNER'S SERVER (#345): where the console keeps what it adds, and
 * how every console, and the phone, reads it all back.
 *
 * Decided in #200: the phone is the journal's only writer, and the browser never uploads a copy of
 * the journal. What the owner's console adds (record.ts) goes into a lane: a lineage of the owner's
 * own sync API (docs/SYNC_PROTOCOL.md §2), append-only, one per browser (laneId.ts), named so that
 * `GET /v1/snapshots` lists it beside the snapshots and its prefix tells it apart (lineage.ts). Each
 * version is sealed under the owner's sync key with the lane's own associated data, padded
 * (sync/crypto.ts encryptLaneVersion). The server needs no change and learns nothing it did not
 * learn from a snapshot: that a version of some size arrived, and when.
 *
 * ─── WHY EVERY VERSION CARRIES EVERYTHING NOT YET TAKEN IN ────────────────────────────────────────
 *
 * The server keeps only the newest DAYMARK_MAX_VERSIONS versions of a lineage (200 by default) and
 * deletes the rest. If each record were a version of its own, a record the phone had not yet taken
 * in could be deleted before it did. So a record is added by writing the next version of this
 * browser's lane with every record of the version before still in it (record.ts nextRecords), and
 * only the newest version of each lane is ever read. A record leaves a lane only when a version is
 * written after the phone's copy lists its id as taken in (record.ts takenInIds): nothing else drops
 * one, so pruning loses nothing.
 *
 * ─── WHAT THE WRITER WILL NOT DO (the rules the snapshot writer keeps, sync/client.ts) ────────────
 *
 * The console holds the sync key it opened at unlock from the owner's wrapped key, and the ETag of
 * the key document it opened (LaneKey). Right before every upload the key document is read again,
 * and nothing is sent unless the server still serves that wrapped key byte for byte: after a new
 * passphrase or recovery code on another device the console holds no secret to prove its key is
 * still the served one, so it adds nothing until it is unlocked again. A version the server already
 * holds (409) was written by another tab of this browser, which shares its lane: the writer reads
 * the newest version again and writes after it. A request that got no answer may have landed: the
 * next read says, and a record already in the lane is never written twice, because its id is
 * there.
 *
 * ─── WHAT A READER TRUSTS ────────────────────────────────────────────────────────────────────────
 *
 * A lane version that opens was sealed with the owner's sync key, so it was written by something
 * that held the owner's key: one of the owner's consoles. That is all it says. The console is
 * served by the server (COMPANION_SECURITY.md §3 T3), so the phone trusts a record no further than
 * "the owner's console added this" and checks every decision's signature, recipient and grant again
 * itself (#346). A lane whose newest version does not open is named as not opened, never guessed at.
 */
import { SyncError, type KeyDocument, type SnapshotMeta } from '../sync/client'
import { decryptLaneVersion, encryptLaneVersion, initCrypto } from '../sync/crypto'
import { paced, pause, type Wait } from '../sync/paced'
import { LANE_FAULT_TEXT, type LaneFault } from './copy'
import { laneForThisBrowser, type LaneStorage, defaultLaneStorage } from './laneId'
import { isLaneLineage } from './lineage'
import { decodeLaneVersion, encodeLaneVersion, nextRecords, type LaneRecord } from './record'

/** The owner's sync key as the console opened it, and the key document it was opened from. */
export interface LaneKey {
  /** Subkey 1 of the owner's master (docs/SYNC_PROTOCOL.md §1). Wiped when the console locks. */
  readonly syncKey: Uint8Array
  /** The ETag of the wrapped key the master was opened from, exactly as the server sent it. */
  readonly keyDocumentEtag: string
}

/** What the lane asks of the owner's sync API. SyncClient is one. */
export interface LanePort {
  listLineages(): Promise<string[]>
  listVersions(lineage: string): Promise<SnapshotMeta[]>
  getBlob(lineage: string, version: number): Promise<Uint8Array>
  putBlob(lineage: string, version: number, bytes: Uint8Array): Promise<SnapshotMeta>
  getKeyDocument(): Promise<KeyDocument>
}

/** An addition that was not made, or not confirmed, in the fixed words of copy.ts. */
export class LaneWriteError extends Error {
  constructor(readonly fault: LaneFault) {
    super(LANE_FAULT_TEXT[fault])
  }
}

/** A lane's newest version that does not open under this key, or is not a lane version this console reads. */
class LaneUnopened extends Error {}

/** One lane's newest version, opened. */
export interface LaneHead {
  readonly lineage: string
  readonly version: number
  readonly records: readonly LaneRecord[]
}

/**
 * The newest version of one lane, opened and read, or null when it has none. Throws LaneUnopened for
 * a version that does not open or does not read, and lets a failed request's own error through.
 */
async function readHead(port: LanePort, key: LaneKey, lineage: string, wait: Wait): Promise<LaneHead | null> {
  const versions = await paced(() => port.listVersions(lineage), wait)
  if (versions.length === 0) return null
  const version = versions.reduce((a, b) => (b.version > a.version ? b : a)).version
  const sealed = await paced(() => port.getBlob(lineage, version), wait)
  try {
    return { lineage, version, records: decodeLaneVersion(decryptLaneVersion(sealed, key.syncKey, lineage, version)) }
  } catch {
    throw new LaneUnopened(lineage)
  }
}

/** What every lane on the server holds, read from the newest version of each. */
export interface LaneReading {
  /** Every record in every lane, each once, lane by lane in the server's order and in each lane's order. */
  readonly records: readonly LaneRecord[]
  /** The lanes whose newest version did not open, or was not a lane version this console reads. */
  readonly unopened: readonly string[]
}

/**
 * Read every lane: list the owner's lineages, keep those that name a lane, and open the newest
 * version of each. A request that fails, after being asked again, fails the whole read, because it
 * is not a fact about any lane; a lane that does not open is one, and is named.
 */
export async function readLanes(port: LanePort, key: LaneKey, wait: Wait = pause): Promise<LaneReading> {
  await initCrypto()
  const byId = new Map<string, LaneRecord>()
  const unopened: string[] = []
  for (const lineage of (await paced(() => port.listLineages(), wait)).filter(isLaneLineage)) {
    let head: LaneHead | null
    try {
      head = await readHead(port, key, lineage, wait)
    } catch (e) {
      if (!(e instanceof LaneUnopened)) throw e
      unopened.push(lineage)
      continue
    }
    for (const r of head?.records ?? []) if (!byId.has(r.id)) byId.set(r.id, r)
  }
  return { records: [...byId.values()], unopened }
}

/** How many times one addition reads the lane and tries to write after it. */
const WRITE_ATTEMPTS = 4

/**
 * Add one record to one lane: the next version, carrying every record of the newest one that the
 * phone has not taken in (`takenIn`), then this one. See the header for the rules it keeps. Throws
 * LaneWriteError, and has sent nothing unless the fault is 'unconfirmed'.
 */
export async function addToLane(
  port: LanePort,
  key: LaneKey,
  lineage: string,
  record: LaneRecord,
  takenIn: ReadonlySet<string>,
  wait: Wait = pause,
): Promise<{ version: number }> {
  await initCrypto()
  // Whether an upload may have landed without its answer reaching this console.
  let maybeLanded = false
  const fault = (): LaneWriteError => new LaneWriteError(maybeLanded ? 'unconfirmed' : 'refused')

  for (let attempt = 1; attempt <= WRITE_ATTEMPTS; attempt++) {
    let head: LaneHead | null
    try {
      head = await readHead(port, key, lineage, wait)
    } catch (e) {
      if (e instanceof LaneUnopened) throw new LaneWriteError('ownLaneUnreadable')
      throw fault()
    }
    // An earlier upload landed and its answer was lost: the record is in the lane already.
    if (head?.records.some((r) => r.id === record.id)) return { version: head.version }

    const version = head ? head.version + 1 : 0
    const sealed = encryptLaneVersion(encodeLaneVersion(nextRecords(head?.records ?? [], takenIn, record)), key.syncKey, lineage, version)

    // The key document again, right before the upload: nothing goes under a key the server has
    // stopped serving.
    let served: KeyDocument
    try {
      served = await paced(() => port.getKeyDocument(), wait)
    } catch {
      throw fault()
    }
    if (served.kind !== 'wrapped' || served.etag !== key.keyDocumentEtag) throw new LaneWriteError('keyChanged')

    try {
      await port.putBlob(lineage, version, sealed)
      return { version }
    } catch (e) {
      // Another tab of this browser wrote that version first: read again, and write after it.
      if (e instanceof SyncError && e.status === 409) continue
      const upload = uploadFailure(e)
      if (upload === 'refused') throw fault()
      if (upload === 'maybeLanded') maybeLanded = true
      await wait(300 * attempt)
    }
  }
  throw fault()
}

/**
 * What a failed upload says about the version it carried. The server's 429 and 503 were answered
 * before anything was stored ('notTaken'), and are asked again. A 500, a proxy's 502 or 504, or no
 * answer at all may follow a version that was stored ('maybeLanded'): the next read of the lane
 * says. Anything else, a 413 or a 507 among them, is the server's no ('refused').
 */
function uploadFailure(e: unknown): 'notTaken' | 'maybeLanded' | 'refused' {
  if (e instanceof SyncError) {
    if (e.status === 429 || e.status === 503) return 'notTaken'
    if (e.status === 500 || e.status === 502 || e.status === 504) return 'maybeLanded'
    return 'refused'
  }
  return e instanceof TypeError ? 'maybeLanded' : 'refused'
}

/** What the owner's console hands its screens: read every lane, and add to this browser's. */
export interface OwnerLane {
  read(): Promise<LaneReading>
  add(record: LaneRecord): Promise<void>
}

export interface OwnerLaneOptions {
  /** The ids the phone's copy the console has open lists as taken in, read at each addition. */
  takenIn?: () => ReadonlySet<string>
  /** Where this browser keeps its lane's name. */
  storage?: LaneStorage | null
  wait?: Wait
}

/**
 * The lane for one unlocked console. This browser's lane is found, or made, at the first addition
 * and kept for the life of the console, whether or not the browser could store its name.
 */
export function ownerLane(port: LanePort, key: LaneKey, options: OwnerLaneOptions = {}): OwnerLane {
  const wait = options.wait ?? pause
  const storage = options.storage === undefined ? defaultLaneStorage() : options.storage
  let lineage: string | null = null
  return {
    read: () => readLanes(port, key, wait),
    async add(record) {
      await initCrypto()
      lineage ??= laneForThisBrowser(storage)
      await addToLane(port, key, lineage, record, options.takenIn?.() ?? new Set(), wait)
    },
  }
}
