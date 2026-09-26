/*
 * THE INBOX'S DECISIONS SURVIVE A REFRESH (#234, #345).
 *
 * An accept or a decline is written to the owner's lane as a record carrying the clinician's signed
 * assignment verbatim, and a Refresh reads the lanes back and puts each decision on the item it
 * answers. These tests run the inbox's own steps (inboxLane.ts, which AssignmentInbox.svelte calls
 * and holds no rule of its own beside) over real sealed assignments and a real lane, against a
 * stand-in for the sync API that keeps its lineage rules.
 */
import { describe, it, expect, beforeAll } from 'vitest'
import { SyncError, type KeyDocument, type SnapshotMeta } from '../sync/client'
import { initCrypto } from '../sync/crypto'
import { buildInbox, evaluateBlob, type InboxItem, type PinnedTherapist, type RawAssignmentBlob } from './inbox'
import { applyDecisions, decisionRecordFor } from './inboxLane'
import { initAssignmentCrypto, newBoxKeyPair, newSignKeyPair, sealAssignment, fingerprint, type BoxKeyPair, type SignKeyPair } from './crypto'
import { emptyGrant, setCapability } from './grant'
import type { Assignment } from './types'
import { ownerLane, type LaneKey, type LanePort } from '../lane/lane'
import { assignmentDecisionRecord, readRecord, type LaneRecord } from '../lane/record'
import type { LaneStorage } from '../lane/laneId'

let owner: BoxKeyPair
let therapistSign: SignKeyPair
let therapistFp: string
let key: LaneKey

beforeAll(async () => {
  await initAssignmentCrypto()
  const so = await initCrypto()
  owner = newBoxKeyPair()
  therapistSign = newSignKeyPair()
  therapistFp = fingerprint(therapistSign.publicKey)
  key = { syncKey: so.randombytes_buf(32), keyDocumentEtag: '"the-key"' }
})

const noWait = async () => {}

function therapist(granted = true): PinnedTherapist {
  const grant = granted ? setCapability(emptyGrant(therapistFp), 'assign.questionnaire', true, 'propose') : emptyGrant(therapistFp)
  return { id: 't1', displayName: 'Dr. Example', signPub: therapistSign.publicKey, boxPub: newBoxKeyPair().publicKey, grant }
}

function assignment(lineageId: string, version = 0): Assignment {
  return {
    assignmentId: `a-${lineageId}`,
    lineageId,
    version,
    type: 'questionnaire',
    capability: 'assign.questionnaire',
    payload: { instrumentId: 'wellbeing-selfcheck' },
    issuedAt: 100 + version,
    authorFingerprint: therapistFp,
  }
}

/** What the server hands the inbox: each assignment sealed to the owner, filed under its lineage. */
function served(...as: Assignment[]): RawAssignmentBlob[] {
  return as.map((a) => ({ therapistId: 't1', lineage: a.lineageId, version: a.version, bytes: sealAssignment(a, therapistSign, owner.publicKey) }))
}

/** The sync API's lineage rules and one key document, in memory. */
function memoryServer(): LanePort & { lineages: Map<string, Map<number, Uint8Array>> } {
  const lineages = new Map<string, Map<number, Uint8Array>>()
  const meta = (version: number, bytes: Uint8Array): SnapshotMeta => ({ version, size: bytes.length, contentHash: 'h', createdAt: version })
  return {
    lineages,
    listLineages: async () => [...lineages.keys()],
    listVersions: async (l) => [...(lineages.get(l) ?? new Map<number, Uint8Array>()).entries()].map(([v, b]) => meta(v, b)),
    getBlob: async (l, v) => {
      const got = lineages.get(l)?.get(v)
      if (!got) throw new SyncError('blob fetch failed', 404)
      return got
    },
    putBlob: async (l, v, bytes) => {
      const versions = lineages.get(l) ?? new Map<number, Uint8Array>()
      if (versions.has(v)) throw new SyncError('version already exists (append-only)', 409)
      versions.set(v, bytes)
      lineages.set(l, versions)
      return meta(v, bytes)
    },
    getKeyDocument: async (): Promise<KeyDocument> => ({ kind: 'wrapped', wrapped: { v: 1, slots: [] }, version: 1, etag: '"the-key"' }),
  }
}

function storage(): LaneStorage {
  const map = new Map<string, string>()
  return { getItem: (k) => map.get(k) ?? null, setItem: (k, v) => void map.set(k, v) }
}

/** The inbox as a Refresh builds it: fetched, opened and checked, then the lanes' decisions applied. */
async function refreshed(blobs: RawAssignmentBlob[], lane: ReturnType<typeof ownerLane>, t: PinnedTherapist = therapist()): Promise<InboxItem[]> {
  return applyDecisions(buildInbox(blobs, [t], owner), (await lane.read()).records)
}

describe('a decision written to the lane is read back after a refresh', () => {
  it('an accept, a decline and an undecided item come back as they were left, from a new console', async () => {
    const server = memoryServer()
    const browser = storage()
    const blobs = served(assignment('walk'), assignment('sleep'), assignment('journal'))
    const first = ownerLane(server, key, { storage: browser, wait: noWait })
    const inbox = await refreshed(blobs, first)
    expect(inbox.map((i) => i.decision)).toEqual([undefined, undefined, undefined])

    const byLineage = (items: InboxItem[], l: string) => items.find((i) => i.raw.lineage === l)!
    await first.add(decisionRecordFor(byLineage(inbox, 'walk'), 'accepted')!)
    await first.add(decisionRecordFor(byLineage(inbox, 'sleep'), 'declined')!)

    // The page is reloaded: a new lane client, the same browser, the same server.
    const after = await refreshed(blobs, ownerLane(server, key, { storage: browser, wait: noWait }))
    expect(byLineage(after, 'walk').decision).toBe('accepted')
    expect(byLineage(after, 'sleep').decision).toBe('declined')
    expect(byLineage(after, 'journal').decision).toBeUndefined()
  })

  it('from another browser too: every lane is read', async () => {
    const server = memoryServer()
    const blobs = served(assignment('walk'))
    const elsewhere = ownerLane(server, key, { storage: storage(), wait: noWait })
    await elsewhere.add(decisionRecordFor((await refreshed(blobs, elsewhere))[0]!, 'accepted')!)
    const here = await refreshed(blobs, ownerLane(server, key, { storage: storage(), wait: noWait }))
    expect(here[0]!.decision).toBe('accepted')
    expect(server.lineages.size).toBe(1)
  })

  it('the record carries the signed assignment verbatim, and its id', async () => {
    const [item] = buildInbox(served(assignment('walk')), [therapist()], owner)
    const record = decisionRecordFor(item!, 'accepted', 5)!
    const read = readRecord(record)!
    expect(read.kind).toBe('assignmentDecision')
    if (read.kind === 'assignmentDecision') {
      expect(read.payload.payloadJson).toBe(item!.signed!.payloadJson)
      expect(read.payload.sigB64).toBe(item!.signed!.sigB64)
      expect(JSON.parse(read.payload.payloadJson).assignment.lineageId).toBe('walk')
    }
    expect(record.id).toMatch(/^[A-Za-z0-9_-]{21}[AQgw]$/)
  })
})

describe('what is kept, and what is not', () => {
  it('a snooze is not kept', () => {
    const [item] = buildInbox(served(assignment('walk')), [therapist()], owner)
    expect(decisionRecordFor(item!, 'snoozed')).toBeNull()
    // Control: the same item's accept is.
    expect(decisionRecordFor(item!, 'accepted')).not.toBeNull()
  })

  it('no decision is kept about an item that did not verify, or that the grant refuses', () => {
    const forged = evaluateBlob(served(assignment('walk'))[0]!, { ...therapist(), signPub: newSignKeyPair().publicKey }, owner)
    expect(forged.verdict).toBe('UNTRUSTED_KEY')
    expect(forged.signed).toBeUndefined()
    const refused = evaluateBlob(served(assignment('walk'))[0]!, therapist(false), owner)
    expect(refused.verdict).toBe('REJECTED')
    expect(refused.signed).toBeDefined()
    for (const item of [forged, refused]) {
      expect(decisionRecordFor(item, 'declined'), item.verdict).toBeNull()
      expect(decisionRecordFor(item, 'accepted'), item.verdict).toBeNull()
    }
  })
})

describe('which item a decision answers', () => {
  const recordFor = (item: InboxItem, decision: 'accepted' | 'declined', at: number): LaneRecord =>
    assignmentDecisionRecord(item.signed!, decision, at)

  it('the one with exactly its signed assignment, never one filed under the same lineage and version', () => {
    const [v0] = buildInbox(served(assignment('walk', 0)), [therapist()], owner)
    // Another assignment the server files under the same place (a re-issue signed afresh).
    const other = { ...v0!, signed: { payloadJson: v0!.signed!.payloadJson.replace('wellbeing-selfcheck', 'another-selfcheck'), sigB64: v0!.signed!.sigB64 } }
    expect(other.signed.payloadJson).not.toBe(v0!.signed!.payloadJson)
    const applied = applyDecisions([v0!, other], [recordFor(v0!, 'accepted', 1)])
    expect(applied.map((i) => i.decision)).toEqual(['accepted', undefined])
  })

  it('the latest answer wins, whichever lane it is in; the same millisecond is ordered by id', () => {
    const [item] = buildInbox(served(assignment('walk')), [therapist()], owner)
    const early = recordFor(item!, 'accepted', 10)
    const late = recordFor(item!, 'declined', 20)
    expect(applyDecisions([item!], [late, early])[0]!.decision).toBe('declined')
    expect(applyDecisions([item!], [early, late])[0]!.decision).toBe('declined')
    const a = recordFor(item!, 'accepted', 30)
    const b = recordFor(item!, 'declined', 30)
    const winner = a.id > b.id ? 'accepted' : 'declined'
    expect(applyDecisions([item!], [a, b])[0]!.decision).toBe(winner)
    expect(applyDecisions([item!], [b, a])[0]!.decision).toBe(winner)
  })

  it('reads only assignment decisions: a game plan\'s, a result or a stranger answers no assignment', () => {
    const [item] = buildInbox(served(assignment('walk')), [therapist()], owner)
    const asPlan: LaneRecord = { ...recordFor(item!, 'accepted', 1), kind: 'gamePlanDecision' }
    const stranger: LaneRecord = { ...recordFor(item!, 'accepted', 2), kind: 'somethingLater' }
    expect(applyDecisions([item!], [asPlan, stranger])[0]!.decision).toBeUndefined()
    // Control: the same payload as an assignment decision answers it.
    expect(applyDecisions([item!], [recordFor(item!, 'accepted', 3)])[0]!.decision).toBe('accepted')
  })
})
