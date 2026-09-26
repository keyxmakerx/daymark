/*
 * THE LANE ON THE OWNER'S SERVER (#345): written one version at a time, each carrying everything
 * the phone has not taken in; read back from every browser's lane; and never sent under a key the
 * server no longer serves.
 *
 * The server is a stand-in here that keeps the sync API's rules for lineages (append-only, 409 for
 * a version that exists, the newest MAX versions kept and the rest deleted) and serves one key
 * document with an ETag, and records every request, so "nothing was sent" is a count over what was
 * actually asked, with a planted request as its control. integration.test.ts holds the same code to
 * the real server.
 */
import { describe, it, expect, beforeAll } from 'vitest'
import { SyncError, type KeyDocument, type SnapshotMeta } from '../sync/client'
import { encryptLaneVersion, encryptSnapshot, initCrypto } from '../sync/crypto'
import { parseBackup } from '../backup'
import { LANE_FAULT_TEXT } from './copy'
import { addToLane, ownerLane, readLanes, LaneWriteError, type LaneKey, type LanePort } from './lane'
import { LANE_STORAGE_KEY, laneForThisBrowser, newLaneLineage, type LaneStorage } from './laneId'
import { LANE_LINEAGE, isLaneLineage } from './lineage'
import {
  assignmentDecisionRecord,
  encodeLaneVersion,
  instrumentResultRecord,
  readRecord,
  takenInIds,
  taskResultRecord,
  type LaneRecord,
} from './record'

const enc = new TextEncoder()
const SIGNED = { payloadJson: '{"context":"daymark.assignment.v1","assignment":{"lineageId":"as-7","version":0}}', sigB64: 'c2lnbmVkLWJ5LXRoZS1jbGluaWNpYW4' }
const RESULT = {
  instrumentId: 'wellbeing-check',
  instrumentVersion: '1.0.0',
  takenAt: 1_790_000_000_000,
  scales: [{ scaleId: 'total', score: 9, bandLabel: 'Some days', tone: 'neutral' as const }],
}
const TASK = {
  taskId: 'steady-attention',
  taskVersion: '1.0.0',
  takenAt: 1_790_000_100_000,
  timing: { flag: 'ok' as const, frameJitterMs: 1.2, droppedFrames: 0, refreshMs: 16.7 },
  metrics: { hits: 30, omissions: 0 },
}

const noWait = async () => {}

/** A Storage stand-in: Node has no localStorage. */
function memoryStorage(): LaneStorage & { map: Map<string, string> } {
  const map = new Map<string, string>()
  return { map, getItem: (k) => map.get(k) ?? null, setItem: (k, v) => void map.set(k, v) }
}

/**
 * The sync API's rules for lineages and the one key document, in memory. `before` runs ahead of
 * every request, so a test can make another tab, or another device, act between two of the lane's.
 */
function memoryServer(maxVersions = 200) {
  const lineages = new Map<string, Map<number, { bytes: Uint8Array; createdAt: number }>>()
  const calls: string[] = []
  let clock = 0
  const state = {
    keyDocument: { kind: 'wrapped', wrapped: { v: 1, slots: [] }, version: 1, etag: '"the-key-this-console-opened"' } as KeyDocument,
    before: null as ((call: string) => void) | null,
  }
  const ask = (call: string) => {
    calls.push(call)
    state.before?.(call)
  }
  const meta = (lineage: string, version: number, v: { bytes: Uint8Array; createdAt: number }): SnapshotMeta => ({
    version,
    size: v.bytes.length,
    contentHash: `${lineage}/${version}`,
    createdAt: v.createdAt,
  })
  /** Store as the server does: append-only, then keep the newest `maxVersions`. */
  function store(lineage: string, version: number, bytes: Uint8Array): SnapshotMeta {
    const versions = lineages.get(lineage) ?? new Map()
    if (versions.has(version)) throw new SyncError('version already exists (append-only)', 409)
    if ([...versions.keys()].filter((v) => v > version).length >= maxVersions) throw new SyncError('version below retention window', 409)
    const stored = { bytes, createdAt: ++clock }
    versions.set(version, stored)
    for (const old of [...versions.keys()].sort((a, b) => b - a).slice(maxVersions)) versions.delete(old)
    lineages.set(lineage, versions)
    return meta(lineage, version, stored)
  }
  const port: LanePort = {
    async listLineages() {
      ask('GET /v1/snapshots')
      return [...lineages.keys()].sort()
    },
    async listVersions(lineage) {
      ask(`GET /v1/snapshots/${lineage}`)
      return [...(lineages.get(lineage) ?? new Map()).entries()].sort(([a], [b]) => a - b).map(([v, s]) => meta(lineage, v, s))
    },
    async getBlob(lineage, version) {
      ask(`GET /v1/snapshots/${lineage}/${version}`)
      const got = lineages.get(lineage)?.get(version)
      if (!got) throw new SyncError('blob fetch failed', 404)
      return got.bytes
    },
    async putBlob(lineage, version, bytes) {
      ask(`PUT /v1/snapshots/${lineage}/${version}`)
      return store(lineage, version, bytes)
    },
    async getKeyDocument() {
      ask('GET /v1/keydoc')
      return state.keyDocument
    },
  }
  return { port, calls, state, lineages, store, versionsOf: (lineage: string) => [...(lineages.get(lineage)?.keys() ?? [])].sort((a, b) => a - b) }
}

let key: LaneKey

beforeAll(async () => {
  const so = await initCrypto()
  key = { syncKey: so.randombytes_buf(32), keyDocumentEtag: '"the-key-this-console-opened"' }
})

const ids = (records: readonly LaneRecord[]) => records.map((r) => r.id)
const puts = (calls: string[]) => calls.filter((c) => c.startsWith('PUT '))

describe('the round trip', () => {
  it('keeps each record, and its id, through the lane and back', async () => {
    const server = memoryServer()
    const lane = ownerLane(server.port, key, { storage: memoryStorage(), wait: noWait })
    const made = [assignmentDecisionRecord(SIGNED, 'accepted'), instrumentResultRecord(RESULT), taskResultRecord(TASK)]
    for (const r of made) await lane.add(r)
    const read = await lane.read()
    expect(ids(read.records)).toEqual(ids(made))
    expect(read.records).toEqual(made)
    expect(read.records.map((r) => readRecord(r)?.kind)).toEqual(['assignmentDecision', 'instrumentResult', 'taskResult'])
    expect(read.unopened).toEqual([])
  })

  it('writes one lane, named as a lane, at versions 0, 1, 2, and nothing in the clear', async () => {
    const server = memoryServer()
    const marker = 'UNIQUE-LANE-MARKER-41d9'
    const lane = ownerLane(server.port, key, { storage: memoryStorage(), wait: noWait })
    await lane.add(assignmentDecisionRecord({ ...SIGNED, payloadJson: `{"note":"${marker}"}` }, 'declined'))
    await lane.add(instrumentResultRecord(RESULT))
    await lane.add(taskResultRecord(TASK))
    const names = [...server.lineages.keys()]
    expect(names).toHaveLength(1)
    expect(names[0]).toMatch(LANE_LINEAGE)
    expect(server.versionsOf(names[0]!)).toEqual([0, 1, 2])
    for (const { bytes } of server.lineages.get(names[0]!)!.values()) expect(Buffer.from(bytes).toString('latin1')).not.toContain(marker)
    // Control: the marker is in what was sealed.
    expect(JSON.stringify((await lane.read()).records)).toContain(marker)
  })
})

describe('a refresh reads the decision back', () => {
  it('a new console in the same browser reads what the last one added, and writes to the same lane', async () => {
    const server = memoryServer()
    const storage = memoryStorage()
    const decision = assignmentDecisionRecord(SIGNED, 'accepted')
    await ownerLane(server.port, key, { storage, wait: noWait }).add(decision)

    // The page is reloaded: nothing survives but the server and this browser's storage.
    const again = ownerLane(server.port, key, { storage, wait: noWait })
    const read = await again.read()
    expect(ids(read.records)).toEqual([decision.id])
    expect(readRecord(read.records[0]!)).toEqual(decision)
    await again.add(instrumentResultRecord(RESULT))
    expect(server.lineages.size).toBe(1)
    expect(storage.map.get(LANE_STORAGE_KEY)).toBe([...server.lineages.keys()][0])
  })

  it('every browser\'s lane is read, and a snapshot is not', async () => {
    const server = memoryServer()
    const here = assignmentDecisionRecord(SIGNED, 'accepted', 1)
    const there = assignmentDecisionRecord(SIGNED, 'declined', 2)
    await ownerLane(server.port, key, { storage: memoryStorage(), wait: noWait }).add(here)
    await ownerLane(server.port, key, { storage: memoryStorage(), wait: noWait }).add(there)
    server.store('laptop', 0, encryptSnapshot(enc.encode('{"entries":[]}'), key.syncKey, 'laptop', 0))
    server.calls.length = 0
    const read = await readLanes(server.port, key, noWait)
    expect(new Set(ids(read.records))).toEqual(new Set([here.id, there.id]))
    expect(server.lineages.size).toBe(3)
    // The snapshot's lineage is listed and never asked about.
    expect(server.calls.filter((c) => c.includes('laptop'))).toEqual([])
    expect(server.calls.filter((c) => c.includes('lane_')).length).toBe(4)
  })
})

describe('each version carries every record the phone has not taken in', () => {
  it('so the server keeping only its newest versions loses nothing', async () => {
    // A server that keeps two versions of a lineage: after five additions, versions 0 to 2 are gone.
    const server = memoryServer(2)
    const lane = ownerLane(server.port, key, { storage: memoryStorage(), wait: noWait })
    const made: LaneRecord[] = []
    for (let i = 0; i < 5; i++) {
      const r = assignmentDecisionRecord({ ...SIGNED, payloadJson: `{"item":${i}}` }, i % 2 ? 'declined' : 'accepted')
      made.push(r)
      await lane.add(r)
    }
    expect(server.versionsOf([...server.lineages.keys()][0]!)).toEqual([3, 4])
    expect(ids((await lane.read()).records)).toEqual(ids(made))
  })

  it('a record leaves only once the phone\'s copy lists its id, and only that record', async () => {
    const server = memoryServer()
    let phoneCopy: { laneRecordsTakenIn?: string[] } | null = null
    const lane = ownerLane(server.port, key, { storage: memoryStorage(), wait: noWait, takenIn: () => takenInIds(phoneCopy) })
    const [a, b, c] = [assignmentDecisionRecord(SIGNED, 'accepted'), instrumentResultRecord(RESULT), taskResultRecord(TASK)]
    await lane.add(a)
    await lane.add(b)
    expect(ids((await lane.read()).records)).toEqual([a.id, b.id])
    // The phone's copy now lists `a` as taken in: the next version carries `b` and adds `c`.
    phoneCopy = { laneRecordsTakenIn: [a.id, 'an-id-no-lane-holds'] }
    await lane.add(c)
    expect(ids((await lane.read()).records)).toEqual([b.id, c.id])
  })

  it('reads the phone\'s list from its snapshot, as the console opens one', () => {
    const backup = parseBackup(JSON.stringify({ entries: [], activities: [], laneRecordsTakenIn: ['AAECAwQFBgcICQoLDA0ODw', 3] }))
    expect(backup.laneRecordsTakenIn).toEqual(['AAECAwQFBgcICQoLDA0ODw'])
    expect([...takenInIds(backup)]).toEqual(['AAECAwQFBgcICQoLDA0ODw'])
    // A backup from before the phone takes anything in lists nothing, and gains no member it lacked.
    const before = parseBackup('{"entries":[],"activities":[]}')
    expect(takenInIds(before).size).toBe(0)
    expect('laneRecordsTakenIn' in before).toBe(false)
    expect('laneRecordsTakenIn' in backup).toBe(true)
  })
})

describe('nothing is sent under a key the server no longer serves', () => {
  it('a key document that changed after this console opened its key: refused, and nothing is sent', async () => {
    const server = memoryServer()
    const lineage = newLaneLineage()
    await addToLane(server.port, key, lineage, assignmentDecisionRecord(SIGNED, 'accepted'), new Set(), noWait)
    // A new passphrase on another device: the next version of the wrapped key.
    server.state.keyDocument = { kind: 'wrapped', wrapped: { v: 1, slots: [] }, version: 2, etag: '"a-later-version-of-the-key"' }
    server.calls.length = 0
    const refused = await addToLane(server.port, key, lineage, instrumentResultRecord(RESULT), new Set(), noWait).catch((e: unknown) => e)
    expect(refused).toBeInstanceOf(LaneWriteError)
    expect((refused as LaneWriteError).fault).toBe('keyChanged')
    expect((refused as Error).message).toBe(LANE_FAULT_TEXT.keyChanged)
    // The key document was read, and nothing was put.
    expect(server.calls).toContain('GET /v1/keydoc')
    expect(puts(server.calls)).toEqual([])
    expect(server.versionsOf(lineage)).toEqual([0])
  })

  it('the key document is read again right before the upload, after the version is sealed', async () => {
    const server = memoryServer()
    const lineage = newLaneLineage()
    // The key changes between the lane's read and its upload.
    server.state.before = (call) => {
      if (call === 'GET /v1/keydoc') server.state.keyDocument = { kind: 'wrapped', wrapped: { v: 1, slots: [] }, version: 2, etag: '"moved"' }
    }
    const refused = await addToLane(server.port, key, lineage, assignmentDecisionRecord(SIGNED, 'accepted'), new Set(), noWait).catch((e: unknown) => e)
    expect((refused as LaneWriteError).fault).toBe('keyChanged')
    expect(server.calls).toEqual([`GET /v1/snapshots/${lineage}`, 'GET /v1/keydoc'])
  })

  it('positive control: the same key document, byte for byte, and the version goes', async () => {
    const server = memoryServer()
    const lineage = newLaneLineage()
    await addToLane(server.port, key, lineage, assignmentDecisionRecord(SIGNED, 'accepted'), new Set(), noWait)
    expect(server.calls).toEqual([`GET /v1/snapshots/${lineage}`, 'GET /v1/keydoc', `PUT /v1/snapshots/${lineage}/0`])
  })

  it('a server that serves no wrapped key at all gets nothing either', async () => {
    const server = memoryServer()
    server.state.keyDocument = { kind: 'none' }
    const refused = await addToLane(server.port, key, newLaneLineage(), taskResultRecord(TASK), new Set(), noWait).catch((e: unknown) => e)
    expect((refused as LaneWriteError).fault).toBe('keyChanged')
    expect(puts(server.calls)).toEqual([])
  })
})

describe('two tabs, lost answers and lanes that do not open', () => {
  it('another tab of this browser wrote the version first (409): read again, write after it, and keep both', async () => {
    const server = memoryServer()
    const lineage = newLaneLineage()
    const theirs = assignmentDecisionRecord(SIGNED, 'declined', 1)
    const mine = instrumentResultRecord(RESULT, 2)
    let raced = false
    // The other tab's write lands first at version 0; this one is refused 409, reads it, and writes 1.
    server.state.before = (call) => {
      if (!raced && call === `PUT /v1/snapshots/${lineage}/0`) {
        raced = true
        server.store(lineage, 0, sealed([theirs], lineage, 0))
      }
    }
    const done = await addToLane(server.port, key, lineage, mine, new Set(), noWait)
    expect(done.version).toBe(1)
    expect(ids((await readLanes(server.port, key, noWait)).records)).toEqual([theirs.id, mine.id])
  })

  it('an upload whose answer was lost is not written again: the next read finds the record there', async () => {
    const server = memoryServer()
    const lineage = newLaneLineage()
    const r = assignmentDecisionRecord(SIGNED, 'accepted')
    const port: LanePort = {
      ...server.port,
      async putBlob(l, v, bytes) {
        await server.port.putBlob(l, v, bytes)
        throw new TypeError('the connection closed before the answer')
      },
    }
    const done = await addToLane(port, key, lineage, r, new Set(), noWait)
    expect(done.version).toBe(0)
    expect(puts(server.calls)).toEqual([`PUT /v1/snapshots/${lineage}/0`])
    expect(ids((await readLanes(server.port, key, noWait)).records)).toEqual([r.id])
  })

  it('an upload that never gets an answer is reported as unconfirmed, never as saved or refused', async () => {
    const server = memoryServer()
    const port: LanePort = { ...server.port, putBlob: async () => { throw new TypeError('offline') } }
    const out = await addToLane(port, key, newLaneLineage(), taskResultRecord(TASK), new Set(), noWait).catch((e: unknown) => e)
    expect((out as LaneWriteError).fault).toBe('unconfirmed')
  })

  it('an upload the server refuses is reported as not saved', async () => {
    const server = memoryServer()
    const port: LanePort = { ...server.port, putBlob: async () => { throw new SyncError('blob store failed', 507) } }
    const out = await addToLane(port, key, newLaneLineage(), taskResultRecord(TASK), new Set(), noWait).catch((e: unknown) => e)
    expect((out as LaneWriteError).fault).toBe('refused')
    expect((out as Error).message).toBe(LANE_FAULT_TEXT.refused)
  })

  it('a lane whose newest version does not open is named, and the others are still read', async () => {
    const server = memoryServer()
    const good = assignmentDecisionRecord(SIGNED, 'accepted')
    await ownerLane(server.port, key, { storage: memoryStorage(), wait: noWait }).add(good)
    const broken = newLaneLineage()
    server.store(broken, 0, encryptSnapshot(enc.encode('{"v":1,"records":[]}'), key.syncKey, broken, 0))
    const read = await readLanes(server.port, key, noWait)
    expect(ids(read.records)).toEqual([good.id])
    expect(read.unopened).toEqual([broken])
  })

  it('the writer adds nothing after a version of its own lane that does not open', async () => {
    const server = memoryServer()
    const lineage = newLaneLineage()
    server.store(lineage, 0, encryptSnapshot(enc.encode('{"v":1,"records":[]}'), key.syncKey, lineage, 0))
    server.calls.length = 0
    const out = await addToLane(server.port, key, lineage, taskResultRecord(TASK), new Set(), noWait).catch((e: unknown) => e)
    expect((out as LaneWriteError).fault).toBe('ownLaneUnreadable')
    expect(puts(server.calls)).toEqual([])
  })

  it('a read that fails for a transient reason is asked again; one that keeps failing fails the whole read', async () => {
    const server = memoryServer()
    await ownerLane(server.port, key, { storage: memoryStorage(), wait: noWait }).add(taskResultRecord(TASK))
    let busy = 1
    const once: LanePort = {
      ...server.port,
      async listLineages() {
        if (busy-- > 0) throw new SyncError('list lineages failed', 429)
        return server.port.listLineages()
      },
    }
    expect((await readLanes(once, key, noWait)).records).toHaveLength(1)
    const never: LanePort = { ...server.port, listLineages: async () => { throw new SyncError('list lineages failed', 503) } }
    await expect(readLanes(never, key, noWait)).rejects.toBeInstanceOf(SyncError)
  })
})

describe('this browser\'s lane', () => {
  it('is made once and kept: the same name after a refresh', async () => {
    const storage = memoryStorage()
    const first = laneForThisBrowser(storage)
    expect(first).toMatch(LANE_LINEAGE)
    expect(isLaneLineage(first)).toBe(true)
    expect(laneForThisBrowser(storage)).toBe(first)
    expect(storage.map.get(LANE_STORAGE_KEY)).toBe(first)
  })

  it('a stored value that is not a lane\'s name is replaced, and no storage at all still gives a lane', () => {
    const storage = memoryStorage()
    storage.map.set(LANE_STORAGE_KEY, 'laptop')
    const made = laneForThisBrowser(storage)
    expect(made).toMatch(LANE_LINEAGE)
    expect(storage.map.get(LANE_STORAGE_KEY)).toBe(made)
    const refusing: LaneStorage = {
      getItem: () => {
        throw new Error('storage is off')
      },
      setItem: () => {
        throw new Error('storage is off')
      },
    }
    expect(laneForThisBrowser(refusing)).toMatch(LANE_LINEAGE)
    expect(laneForThisBrowser(null)).toMatch(LANE_LINEAGE)
  })

  it('without storage, one console still writes one lane', async () => {
    const server = memoryServer()
    const lane = ownerLane(server.port, key, { storage: null, wait: noWait })
    await lane.add(taskResultRecord(TASK))
    await lane.add(instrumentResultRecord(RESULT))
    expect(server.lineages.size).toBe(1)
  })
})

/** One lane version, sealed as the writer seals it, for the stand-in to store directly. */
function sealed(records: LaneRecord[], lineage: string, version: number): Uint8Array {
  return encryptLaneVersion(encodeLaneVersion(records), key.syncKey, lineage, version)
}
