/*
 * THE WEB CONSOLE'S RECORDS (#345): a random id, a kind, a time and a payload; add only; scores and
 * bands only. record.ts says what each rule is for. Every absence here is paired with a planted
 * example the check is shown to see.
 */
import { describe, it, expect, beforeAll } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { initCrypto } from '../sync/crypto'
import * as recordModule from './record'
import {
  RECORD_ID,
  RECORD_KINDS,
  LaneFormatError,
  assignmentDecisionRecord,
  gamePlanDecisionRecord,
  instrumentResultRecord,
  taskResultRecord,
  decodeLaneVersion,
  encodeLaneVersion,
  nextRecords,
  newRecordId,
  readRecord,
  takenInIds,
  type KnownRecord,
  type LaneRecord,
  type RecordKind,
} from './record'
import type { InstrumentResult, TaskResult } from '../instruments/types'

const enc = new TextEncoder()
const dec = new TextDecoder()

const SIGNED = { payloadJson: '{"context":"daymark.assignment.v1","assignment":{"lineageId":"as-1","version":0}}', sigB64: 'c2lnbmF0dXJlLW9mLXRoZS1jbGluaWNpYW4' }
const PLAN = { payloadJson: '{"context":"daymark.gameplan.v1","lineageId":"gp-1","version":2}', sigB64: 'cGxhbi1zaWduYXR1cmU' }

/** A self-check result as the runner makes one: answers, and a tone on every scale. */
const TAKEN: InstrumentResult = {
  kind: 'instrument',
  instrumentId: 'wellbeing-check',
  instrumentVersion: '1.2.0',
  takenAt: 1_790_000_000_000,
  scales: [
    { scaleId: 'total', score: 12.5, bandLabel: 'Some days', tone: 'attention' },
    { scaleId: 'sleep', score: 3, bandLabel: '—', tone: 'neutral' },
  ],
  answers: { q1: 'opt-2', q2: 4, q9: 'PLANTED-ANSWER-TEXT-3b7e' },
}

const RUN: TaskResult = {
  kind: 'task',
  taskId: 'steady-attention',
  taskVersion: '1.0.0',
  takenAt: 1_790_000_100_000,
  timing: { flag: 'lower-precision', frameJitterMs: 5.2, droppedFrames: 4, refreshMs: 16.7 },
  metrics: { targets: 30, nonTargets: 10, hits: 28, accuracyPct: 92.5, rtMeanMs: 412 },
}

beforeAll(async () => {
  await initCrypto()
})

const everyKind = (): KnownRecord[] => [
  assignmentDecisionRecord(SIGNED, 'accepted', 1_790_000_200_000),
  gamePlanDecisionRecord(PLAN, 'declined', 1_790_000_300_000),
  instrumentResultRecord(TAKEN, 1_790_000_400_000),
  taskResultRecord(RUN, 1_790_000_500_000),
]

describe('a record has a random id, a kind, a time and a payload, and keeps its id through the lane', () => {
  it('each id is 16 random bytes, in the canonical URL-safe base64 without padding', () => {
    const ids = Array.from({ length: 500 }, () => newRecordId())
    for (const id of ids) expect(id).toMatch(RECORD_ID)
    expect(new Set(ids).size).toBe(ids.length)
    // The form refuses what is not 16 bytes' canonical encoding: 15 or 17 bytes, a padded one.
    expect(RECORD_ID.test('AAECAwQFBgcICQoLDA0ODw')).toBe(true)
    expect(RECORD_ID.test('AAECAwQFBgcICQoLDA0ODx')).toBe(false)
    expect(RECORD_ID.test('AAECAwQFBgcICQoLDA0O')).toBe(false)
    expect(RECORD_ID.test('AAECAwQFBgcICQoLDA0ODw==')).toBe(false)
  })

  it('round-trips every kind: the same ids, kinds, times and payloads, in the same order', () => {
    const records = everyKind()
    const back = decodeLaneVersion(encodeLaneVersion(records))
    expect(back.map((r) => r.id)).toEqual(records.map((r) => r.id))
    expect(back).toEqual(records)
    expect(back.map(readRecord)).toEqual(records)
  })

  it('is written as the format says: {"v":1,"records":[…]}, members in order, no spaces', () => {
    const r = assignmentDecisionRecord(SIGNED, 'accepted', 1_790_000_200_000)
    const text = dec.decode(encodeLaneVersion([r]))
    expect(text).toBe(
      `{"v":1,"records":[{"id":"${r.id}","kind":"assignmentDecision","createdAt":1790000200000,` +
        `"payload":{"decision":"accepted","payloadJson":${JSON.stringify(SIGNED.payloadJson)},"sigB64":"${SIGNED.sigB64}"}}]}`,
    )
  })

  it('a decision carries the clinician\'s signed item verbatim', () => {
    const r = readRecord(gamePlanDecisionRecord(PLAN, 'accepted', 1))!
    expect(r.kind).toBe('gamePlanDecision')
    if (r.kind === 'gamePlanDecision') {
      expect(r.payload.payloadJson).toBe(PLAN.payloadJson)
      expect(r.payload.sigB64).toBe(PLAN.sigB64)
    }
  })
})

describe('a record only adds: the type has no edit or delete form', () => {
  const EDITING = /edit|delet|remov|updat|replac|revok|retract|undo|amend|withdr|supersed|cancel|overwrit|erase|clear|patch|modif|mutat/i

  it('the detector sees an editing name', () => {
    for (const planted of ['assignmentDecisionEdit', 'deleteRecord', 'removeFromLane', 'recordUpdate', 'supersedeRecord', 'withdrawDecision']) {
      expect(EDITING.test(planted), planted).toBe(true)
    }
    expect(RECORD_KINDS.some((k) => EDITING.test(k))).toBe(false)
  })

  it('the kinds are exactly four, and each one adds', () => {
    expect([...RECORD_KINDS]).toEqual(['assignmentDecision', 'gamePlanDecision', 'instrumentResult', 'taskResult'])
    // @ts-expect-error — a kind that edits is not a record kind
    const edit: RecordKind = 'assignmentDecisionEdit'
    expect(RECORD_KINDS as readonly string[]).not.toContain(edit)
  })

  it('the module offers no function that edits, replaces or removes a record', () => {
    const names = Object.keys(recordModule)
    expect(names.length).toBeGreaterThan(10)
    expect(names.filter((n) => EDITING.test(n))).toEqual([])
    // Control: a planted export would be seen.
    expect([...names, 'editRecord'].filter((n) => EDITING.test(n))).toEqual(['editRecord'])
  })

  it('a decision is "accepted" or "declined" and nothing else: no retraction, no undo', () => {
    const source = readFileSync(fileURLToPath(new URL('./record.ts', import.meta.url)), 'utf8')
    expect(source).toContain("export type Decision = 'accepted' | 'declined'\n")
    // @ts-expect-error — there is no decision that takes one back
    expect(() => assignmentDecisionRecord(SIGNED, 'withdrawn', 1)).toThrow(RangeError)
  })

  it('a record, and everything in it, is frozen: nothing changes one after it is made or read', () => {
    const made = assignmentDecisionRecord(SIGNED, 'accepted', 1)
    const read = decodeLaneVersion(encodeLaneVersion([made]))[0]!
    for (const r of [made, read] as LaneRecord[]) {
      expect(Object.isFrozen(r)).toBe(true)
      expect(Object.isFrozen(r.payload)).toBe(true)
      expect(() => {
        // @ts-expect-error — a record's members are read-only
        r.kind = 'assignmentDecisionEdit'
      }).toThrow(TypeError)
      expect(() => {
        // @ts-expect-error — and so is its payload
        r.payload.decision = 'declined'
      }).toThrow(TypeError)
      expect(r.kind).toBe('assignmentDecision')
      expect(r.payload.decision).toBe('accepted')
    }
    // Control: an unfrozen copy of the same record does change, so the throws above are the freeze.
    const loose = JSON.parse(JSON.stringify(made)) as { kind: string; payload: Record<string, unknown> }
    loose.kind = 'assignmentDecisionEdit'
    loose.payload.decision = 'declined'
    expect([loose.kind, loose.payload.decision]).toEqual(['assignmentDecisionEdit', 'declined'])
  })
})

describe('a result carries scores and bands only', () => {
  it('a self-check result carries its scales and nothing of its answers or tones', () => {
    const r = instrumentResultRecord(TAKEN, 1)
    const text = JSON.stringify(r)
    // Control: the result it was made from carries the planted answer and the tones.
    expect(JSON.stringify(TAKEN)).toContain('PLANTED-ANSWER-TEXT-3b7e')
    expect(JSON.stringify(TAKEN)).toContain('"tone"')
    expect(text).not.toContain('PLANTED-ANSWER-TEXT-3b7e')
    expect(text).not.toContain('answers')
    expect(text).not.toContain('"tone"')
    expect(r.payload).toEqual({
      instrumentId: 'wellbeing-check',
      instrumentVersion: '1.2.0',
      takenAt: 1_790_000_000_000,
      scales: [
        { scaleId: 'total', score: 12.5, bandLabel: 'Some days' },
        { scaleId: 'sleep', score: 3, bandLabel: '—' },
      ],
    })
  })

  it('a result that carries an answer, or anything else, is not read', () => {
    const clean = instrumentResultRecord(TAKEN, 1)
    const withAnswers: LaneRecord = { ...clean, payload: { ...clean.payload, answers: { q9: 'x' } } }
    const withTone: LaneRecord = {
      ...clean,
      payload: { ...clean.payload, scales: [{ scaleId: 'total', score: 12.5, bandLabel: 'Some days', tone: 'attention' }] },
    }
    expect(readRecord(clean)).not.toBeNull()
    expect(readRecord(withAnswers)).toBeNull()
    expect(readRecord(withTone)).toBeNull()
  })

  it('a task result carries its numbers and its timing caveat, and nothing of the trials', () => {
    const r = taskResultRecord({ ...RUN, trials: [{ isTarget: true, responded: true, rtMs: 300 }] } as TaskResult, 1)
    expect(Object.keys(r.payload)).toEqual(['taskId', 'taskVersion', 'takenAt', 'timing', 'metrics'])
    expect(JSON.stringify(r)).not.toContain('trials')
    expect(r.payload).toMatchObject({ timing: { flag: 'lower-precision' }, metrics: { hits: 28 } })
  })

  it('refuses to make a result its own reader would not read', () => {
    expect(() => instrumentResultRecord({ ...TAKEN, scales: [{ scaleId: 'total', score: Number.NaN, bandLabel: 'x', tone: 'neutral' }] }, 1)).toThrow(
      RangeError,
    )
    expect(() => taskResultRecord({ ...RUN, metrics: { hits: Number.POSITIVE_INFINITY } }, 1)).toThrow(RangeError)
    expect(() => assignmentDecisionRecord(SIGNED, 'accepted', -1)).toThrow(RangeError)
  })
})

describe('a lane version is read strictly', () => {
  const good = () => JSON.parse(dec.decode(encodeLaneVersion(everyKind()))) as { v: unknown; records: Record<string, unknown>[] }
  const decodes = (doc: unknown) => decodeLaneVersion(enc.encode(JSON.stringify(doc)))

  it('control: the unmutated version decodes', () => {
    expect(decodes(good())).toHaveLength(4)
  })

  const MUTATIONS: Array<[string, (d: ReturnType<typeof good>) => void]> = [
    ['another format', (d) => { d.v = 2 }],
    ['no records member', (d) => { delete (d as Partial<typeof d>).records }],
    ['a record that is not an object', (d) => { d.records[1] = [] as unknown as Record<string, unknown> }],
    ['a record with no id', (d) => { delete d.records[0]!.id }],
    ['an id that is not 16 bytes', (d) => { d.records[0]!.id = String(d.records[0]!.id).slice(0, 20) }],
    ['an empty kind', (d) => { d.records[2]!.kind = '' }],
    ['a time that is not whole', (d) => { d.records[3]!.createdAt = Number(d.records[3]!.createdAt) + 0.5 }],
    ['a time before 1970', (d) => { d.records[3]!.createdAt = -1 }],
    ['a payload that is not an object', (d) => { d.records[0]!.payload = 'accepted' }],
    ['one record twice', (d) => { d.records[1]!.id = d.records[0]!.id }],
  ]
  for (const [name, mutate] of MUTATIONS) {
    it(`refuses ${name}`, () => {
      const d = good()
      const before = JSON.stringify(d)
      mutate(d)
      expect(JSON.stringify(d)).not.toBe(before)
      expect(() => decodes(d)).toThrow(LaneFormatError)
    })
  }

  it('refuses bytes that are not UTF-8 JSON', () => {
    expect(() => decodeLaneVersion(new Uint8Array([0xff, 0xfe, 0x7b]))).toThrow(LaneFormatError)
    expect(() => decodeLaneVersion(enc.encode('{"v":1,"records":['))).toThrow(LaneFormatError)
  })

  it('carries a kind it does not know, and members it does not know, and reads neither', () => {
    const d = good()
    d.records.push({ id: newRecordId(), kind: 'passkeyNote', createdAt: 5, payload: { anything: true }, addedLater: 1 })
    d.records[0]!.addedLater = 'kept'
    const read = decodes(d)
    expect(read).toHaveLength(5)
    expect(readRecord(read[4]!)).toBeNull()
    // Carried exactly as read, unknown members included.
    const next = nextRecords(read, new Set(), assignmentDecisionRecord(SIGNED, 'declined', 6))
    const written = JSON.parse(dec.decode(encodeLaneVersion(next))) as typeof d
    expect(written.records.slice(0, 5)).toEqual(d.records)
  })
})

describe('the next version carries every record the phone has not taken in', () => {
  it('keeps every record not listed as taken in, in order, and adds the new one after them', () => {
    const [a, b, c, d] = everyKind()
    const added = assignmentDecisionRecord(SIGNED, 'declined', 9)
    expect(nextRecords([a!, b!, c!, d!], new Set(), added).map((r) => r.id)).toEqual([a!.id, b!.id, c!.id, d!.id, added.id])
  })

  it('drops exactly the records the phone lists as taken in, and no other', () => {
    const [a, b, c, d] = everyKind()
    const added = taskResultRecord(RUN, 9)
    const next = nextRecords([a!, b!, c!, d!], new Set([b!.id, d!.id, 'an-id-in-no-lane']), added)
    expect(next.map((r) => r.id)).toEqual([a!.id, c!.id, added.id])
  })

  it('adds a record once: one whose id is already carried is not added again', () => {
    const [a, b] = everyKind()
    expect(nextRecords([a!, b!], new Set(), b!).map((r) => r.id)).toEqual([a!.id, b!.id])
  })
})

describe('the phone lists what it has taken in', () => {
  it('reads laneRecordsTakenIn from the phone\'s snapshot, and nothing else', () => {
    expect([...takenInIds({ laneRecordsTakenIn: ['AAECAwQFBgcICQoLDA0ODw', 7, null, 'EBESExQVFhcYGRobHB0eHw'] })]).toEqual([
      'AAECAwQFBgcICQoLDA0ODw',
      'EBESExQVFhcYGRobHB0eHw',
    ])
    expect(takenInIds({}).size).toBe(0)
    expect(takenInIds(null).size).toBe(0)
    expect(takenInIds({ laneRecordsTakenIn: 'AAECAwQFBgcICQoLDA0ODw' } as never).size).toBe(0)
  })
})
