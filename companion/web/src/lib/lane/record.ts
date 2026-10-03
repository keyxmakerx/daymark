/*
 * THE WEB CONSOLE'S RECORDS (#345): what the owner's console adds to the person's record, and the
 * only shape in which it can add it.
 *
 * Decided in #200: the phone is the journal's only writer. What the web console makes, an accept or
 * decline of a clinician's assignment (#234) or game plan (#231), a self-check or task result
 * (#237), reaches the phone as a new record in the console's own lane (lane.ts), never as a copy of
 * the journal. A record only adds. It has a random id, a kind, the time it was made and a payload,
 * and nothing here edits or removes one: no kind means "change" or "undo", no function takes a
 * record and gives back a changed one, and every record is frozen. The phone takes each record in
 * once, by its id (#346), so nothing in a lane can reach a row already in the journal.
 *
 * ─── THE FORMAT (docs/SYNC_PROTOCOL.md, the lane section; the phone reads it, #346) ──────────────
 *
 * One lane version's plaintext is UTF-8 JSON, written with no spaces:
 *
 *   {"v":1,"records":[{"id":…,"kind":…,"createdAt":…,"payload":{…}},…]}
 *
 *   id         the URL-safe base64, without padding, of 16 random bytes: 22 characters, the last
 *              one of A, Q, g or w
 *   kind       one of the four below; a record of any other kind is carried and not read
 *   createdAt  when the console made the record, in epoch milliseconds: a whole number, 0 or more
 *   payload    an object, of the kind's shape
 *
 * A reader refuses the whole version for anything else: a `v` other than 1, a record that is not an
 * object or lacks one of the four members in its form, or two records with one id. It ignores
 * members it does not know, and a writer carries every record forward exactly as it read it, unknown
 * members and unknown kinds included.
 *
 * ─── THE KINDS ───────────────────────────────────────────────────────────────────────────────────
 *
 *   assignmentDecision, gamePlanDecision   {"decision","payloadJson","sigB64"}
 *     "accepted" or "declined", and the clinician's signed item verbatim: the two strings of the
 *     envelope they sealed (assignments/crypto.ts, therapist/gamePlan.ts), exactly as the signature
 *     covers them. Verbatim because the server keeps an item for 90 days (#228, #332), and the phone
 *     checks the signature, the recipient and the grant again itself (#346): the console is served
 *     by the server (COMPANION_SECURITY.md §3 T3), so a record says no more than "the owner's
 *     console added this".
 *   instrumentResult   {"instrumentId","instrumentVersion","takenAt","scales":[{"scaleId","score","bandLabel"}]}
 *   taskResult         {"taskId","taskVersion","takenAt","timing":{"flag","frameJitterMs","droppedFrames","refreshMs"},"metrics":{…}}
 *     Scores and bands only, as the phone's instrument_results and task_results keep them. No
 *     member can hold an answer, so no item's answer, the self-harm item's included, can travel in
 *     a record; a result with any member besides these is not read.
 *
 * A payload of a known kind is read only with exactly its members, of their types. One that is not
 * is still carried, since nothing leaves a lane until the phone has taken it in (lane.ts).
 */
import _sodium from 'libsodium-wrappers-sumo'
import type { InstrumentResult, TaskResult } from '../instruments/types'

/** The `v` of every lane version written; a reader refuses any other. */
export const LANE_FORMAT = 1

/** The kinds a record can be. Each one adds; none edits, replaces or removes. */
export const RECORD_KINDS = ['assignmentDecision', 'gamePlanDecision', 'instrumentResult', 'taskResult'] as const
export type RecordKind = (typeof RECORD_KINDS)[number]

export type Decision = 'accepted' | 'declined'

/*
 * The payloads are object types rather than interfaces, so that each is also a LaneRecord's payload:
 * an object whose members this format does not otherwise constrain.
 */

/** A clinician's signed item: the payload exactly as signed, and the signature over it. */
export type SignedEnvelope = {
  readonly payloadJson: string
  readonly sigB64: string
}

export type DecisionPayload = {
  readonly decision: Decision
  readonly payloadJson: string
  readonly sigB64: string
}

export type ScaleScore = {
  readonly scaleId: string
  readonly score: number
  readonly bandLabel: string
}

export type InstrumentResultPayload = {
  readonly instrumentId: string
  readonly instrumentVersion: string
  readonly takenAt: number
  readonly scales: readonly ScaleScore[]
}

export type TaskTiming = {
  readonly flag: 'ok' | 'lower-precision'
  readonly frameJitterMs: number
  readonly droppedFrames: number
  readonly refreshMs: number
}

export type TaskResultPayload = {
  readonly taskId: string
  readonly taskVersion: string
  readonly takenAt: number
  readonly timing: TaskTiming
  readonly metrics: { readonly [metric: string]: number }
}

type Payloads = {
  assignmentDecision: DecisionPayload
  gamePlanDecision: DecisionPayload
  instrumentResult: InstrumentResultPayload
  taskResult: TaskResultPayload
}

/** A record as a lane carries it: of any kind, its payload not read. Frozen; nothing changes one. */
export interface LaneRecord {
  readonly id: string
  readonly kind: string
  readonly createdAt: number
  readonly payload: { readonly [member: string]: unknown }
}

/** A record of a kind this console reads, its payload read as that kind. */
export type KnownRecord = {
  [K in RecordKind]: { readonly id: string; readonly kind: K; readonly createdAt: number; readonly payload: Payloads[K] }
}[RecordKind]

/** Anything in a lane version that no writer of this format produces. */
export class LaneFormatError extends Error {}

const ID_BYTES = 16
/** The canonical URL-safe base64 of 16 bytes: 21 characters, then one of the four that end 16 bytes. */
export const RECORD_ID = /^[A-Za-z0-9_-]{21}[AQgw]$/

/** A fresh record id. libsodium must be ready, as it is wherever the console has unlocked. */
export function newRecordId(): string {
  return _sodium.to_base64(_sodium.randombytes_buf(ID_BYTES), _sodium.base64_variants.URLSAFE_NO_PADDING)
}

const isObject = (x: unknown): x is Record<string, unknown> => typeof x === 'object' && x !== null && !Array.isArray(x)
const isTime = (x: unknown): x is number => Number.isSafeInteger(x) && (x as number) >= 0
const isNumber = (x: unknown): x is number => typeof x === 'number' && Number.isFinite(x)
const isName = (x: unknown): x is string => typeof x === 'string' && x.length > 0

/** Exactly these members and no other: a payload with one more is a payload of another shape. */
function hasExactly(o: Record<string, unknown>, members: readonly string[]): boolean {
  const keys = Object.keys(o)
  return keys.length === members.length && members.every((m) => Object.hasOwn(o, m))
}

function isDecision(p: Record<string, unknown>): boolean {
  return (
    hasExactly(p, ['decision', 'payloadJson', 'sigB64']) &&
    (p.decision === 'accepted' || p.decision === 'declined') &&
    isName(p.payloadJson) &&
    isName(p.sigB64)
  )
}

function isInstrumentResult(p: Record<string, unknown>): boolean {
  return (
    hasExactly(p, ['instrumentId', 'instrumentVersion', 'takenAt', 'scales']) &&
    isName(p.instrumentId) &&
    isName(p.instrumentVersion) &&
    isTime(p.takenAt) &&
    Array.isArray(p.scales) &&
    p.scales.every(
      (s) =>
        isObject(s) &&
        hasExactly(s, ['scaleId', 'score', 'bandLabel']) &&
        isName(s.scaleId) &&
        isNumber(s.score) &&
        typeof s.bandLabel === 'string',
    )
  )
}

function isTaskResult(p: Record<string, unknown>): boolean {
  const t = p.timing
  const m = p.metrics
  return (
    hasExactly(p, ['taskId', 'taskVersion', 'takenAt', 'timing', 'metrics']) &&
    isName(p.taskId) &&
    isName(p.taskVersion) &&
    isTime(p.takenAt) &&
    isObject(t) &&
    hasExactly(t, ['flag', 'frameJitterMs', 'droppedFrames', 'refreshMs']) &&
    (t.flag === 'ok' || t.flag === 'lower-precision') &&
    isNumber(t.frameJitterMs) &&
    isNumber(t.droppedFrames) &&
    isNumber(t.refreshMs) &&
    isObject(m) &&
    Object.values(m).every(isNumber)
  )
}

const READS: Readonly<Record<RecordKind, (p: Record<string, unknown>) => boolean>> = {
  assignmentDecision: isDecision,
  gamePlanDecision: isDecision,
  instrumentResult: isInstrumentResult,
  taskResult: isTaskResult,
}

/** The record as the kind it names, or null: a kind this console does not know, or a payload not of its kind's shape. */
export function readRecord(record: LaneRecord): KnownRecord | null {
  if (!(RECORD_KINDS as readonly string[]).includes(record.kind)) return null
  const read = READS[record.kind as RecordKind]
  return read(record.payload as Record<string, unknown>) ? (record as KnownRecord) : null
}

function deepFreeze<T>(value: T): T {
  if (typeof value === 'object' && value !== null && !Object.isFrozen(value)) {
    for (const member of Object.values(value)) deepFreeze(member)
    Object.freeze(value)
  }
  return value
}

/** A new record of a known kind, frozen. Refuses a payload that its own reader would not read. */
function make<K extends RecordKind>(kind: K, payload: Payloads[K], createdAt: number): KnownRecord {
  if (!isTime(createdAt)) throw new RangeError('a record is made at a time: whole epoch milliseconds, 0 or more')
  if (!READS[kind](payload as unknown as Record<string, unknown>)) throw new RangeError(`not a ${kind} this format carries`)
  return deepFreeze({ id: newRecordId(), kind, createdAt, payload }) as KnownRecord
}

/** The owner's accept or decline of a clinician's assignment, carrying the signed assignment verbatim. */
export function assignmentDecisionRecord(signed: SignedEnvelope, decision: Decision, createdAt: number = Date.now()): KnownRecord {
  return make('assignmentDecision', { decision, payloadJson: signed.payloadJson, sigB64: signed.sigB64 }, createdAt)
}

/** The owner's accept or decline of a clinician's game plan, carrying the signed plan verbatim. */
export function gamePlanDecisionRecord(signed: SignedEnvelope, decision: Decision, createdAt: number = Date.now()): KnownRecord {
  return make('gamePlanDecision', { decision, payloadJson: signed.payloadJson, sigB64: signed.sigB64 }, createdAt)
}

/**
 * A self-check's result: the scores and bands and nothing else. Built member by member from the
 * result, so its answers, and each band's tone, are never read, let alone carried.
 */
export function instrumentResultRecord(
  result: Pick<InstrumentResult, 'instrumentId' | 'instrumentVersion' | 'takenAt' | 'scales'>,
  createdAt: number = Date.now(),
): KnownRecord {
  return make(
    'instrumentResult',
    {
      instrumentId: result.instrumentId,
      instrumentVersion: result.instrumentVersion,
      takenAt: result.takenAt,
      scales: result.scales.map((s) => ({ scaleId: s.scaleId, score: s.score, bandLabel: s.bandLabel })),
    },
    createdAt,
  )
}

/** A timed task's result: its numbers and the run's own timing caveat, as the task computed them. */
export function taskResultRecord(
  result: Pick<TaskResult, 'taskId' | 'taskVersion' | 'takenAt' | 'timing' | 'metrics'>,
  createdAt: number = Date.now(),
): KnownRecord {
  const { flag, frameJitterMs, droppedFrames, refreshMs } = result.timing
  return make(
    'taskResult',
    {
      taskId: result.taskId,
      taskVersion: result.taskVersion,
      takenAt: result.takenAt,
      timing: { flag, frameJitterMs, droppedFrames, refreshMs },
      metrics: Object.fromEntries(Object.entries(result.metrics)),
    },
    createdAt,
  )
}

/** One lane version's plaintext: the format, then the records in the order given. */
export function encodeLaneVersion(records: readonly LaneRecord[]): Uint8Array {
  const ids = new Set(records.map((r) => r.id))
  if (ids.size !== records.length) throw new LaneFormatError('a lane version holds each record once')
  return new TextEncoder().encode(JSON.stringify({ v: LANE_FORMAT, records }))
}

/**
 * The records of one lane version, as they were written, in order, frozen. Refuses the whole version
 * for anything no writer of this format produces (see the header).
 */
export function decodeLaneVersion(bytes: Uint8Array): LaneRecord[] {
  let doc: unknown
  try {
    doc = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes))
  } catch {
    throw new LaneFormatError('a lane version is UTF-8 JSON')
  }
  if (!isObject(doc) || doc.v !== LANE_FORMAT || !Array.isArray(doc.records)) {
    throw new LaneFormatError('not a lane version of this format')
  }
  const seen = new Set<string>()
  for (const r of doc.records) {
    if (!isObject(r) || typeof r.id !== 'string' || !RECORD_ID.test(r.id) || !isName(r.kind) || !isTime(r.createdAt) || !isObject(r.payload)) {
      throw new LaneFormatError('a record of a lane version is not in its form')
    }
    if (seen.has(r.id)) throw new LaneFormatError('a lane version holds a record twice')
    seen.add(r.id)
  }
  return deepFreeze(doc.records as LaneRecord[])
}

/**
 * The records the next version of a lane holds: every record of the version before whose id the
 * phone's copy does not list as taken in, exactly as it was read and in its order, then `added`,
 * unless a record with its id is already there. So each version carries everything the phone has
 * not taken in, and the server pruning older versions loses nothing (lane.ts).
 */
export function nextRecords(head: readonly LaneRecord[], takenIn: ReadonlySet<string>, added: LaneRecord): LaneRecord[] {
  const carried = head.filter((r) => !takenIn.has(r.id))
  return carried.some((r) => r.id === added.id) ? carried : [...carried, added]
}

/**
 * The ids of the lane records the phone has taken in, from its snapshot: the `laneRecordsTakenIn`
 * member of its BackupData JSON, an array of record ids that the phone writes (#346). Absent is
 * empty, and anything in it that is not a string names no record.
 */
export function takenInIds(snapshot: { readonly laneRecordsTakenIn?: readonly unknown[] } | null | undefined): ReadonlySet<string> {
  const listed = snapshot?.laneRecordsTakenIn
  if (!Array.isArray(listed)) return new Set()
  return new Set(listed.filter((id): id is string => typeof id === 'string'))
}
