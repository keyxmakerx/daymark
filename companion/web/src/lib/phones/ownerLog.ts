/*
 * THE OWNER'S OWN LOG (#434 item 4): `GET /v1/owner/audit`, which no screen read until now. It holds
 * what is the owner's and no relationship's: phones paired and disconnected, and the network addresses
 * paused after wrong tries at the owner's own credentials (DeviceRoutes.kt). Metadata only, as every
 * audit row is (COMPANION_SECURITY.md §9).
 *
 * The answer is read strictly: a row that is not one is no page at all, never a shorter list, because a
 * log that quietly drops lines reads as a quieter history than the one recorded. Each line's words are
 * auditLabels.ts's `ownerLogActionLabel`, which reads the row's meta as well as its action.
 */
import type { AuditEvent } from '../sync/portal'
import { ownerLogActionLabel } from '../components/owner/auditLabels'

export type OwnerLogFault = 'unreachable' | 'refused' | 'unsupported'

export class OwnerLogError extends Error {
  constructor(readonly fault: OwnerLogFault) {
    super(fault)
  }
}

export interface OwnerLogPage {
  events: AuditEvent[]
  nextCursor: number | null
}

export interface OwnerLogLine {
  seq: number
  what: string
  /** Seconds since the epoch, as the server wrote it. */
  ts: number
}

export const OWNER_LOG_COPY = {
  heading: 'Your own log',
  lede:
    'Phones paired and disconnected, and network addresses paused after wrong tries at your access ' +
    'token, a phone’s key or a pairing code. Your clinicians’ access is in each one’s access log.',
  show: 'Show your log',
  refresh: 'Refresh',
  loading: 'Loading…',
  more: 'Load more',
  empty: 'Nothing is recorded in your log yet.',
  faults: {
    unreachable: 'The server did not answer. Try again when you are ready.',
    refused: 'The server refused the request, so your log cannot be shown here.',
    unsupported: 'This server keeps no log of its own for you.',
  } satisfies Record<OwnerLogFault, string>,
} as const

type FetchLike = typeof fetch

const isRecord = (x: unknown): x is Record<string, unknown> => typeof x === 'object' && x !== null && !Array.isArray(x)
const isWhole = (x: unknown): x is number => typeof x === 'number' && Number.isSafeInteger(x) && x >= 0

function asEvent(x: unknown): AuditEvent | null {
  if (!isRecord(x) || !isWhole(x.seq) || !isWhole(x.ts) || typeof x.action !== 'string' || x.action === '') return null
  if (typeof x.actor !== 'string' || typeof x.entryHash !== 'string') return null
  let meta: Record<string, string> | null = null
  if (x.meta !== undefined && x.meta !== null) {
    if (!isRecord(x.meta) || Object.values(x.meta).some((v) => typeof v !== 'string')) return null
    meta = x.meta as Record<string, string>
  }
  return {
    seq: x.seq,
    ts: x.ts,
    actor: x.actor as AuditEvent['actor'],
    action: x.action,
    objectRef: typeof x.objectRef === 'string' ? x.objectRef : null,
    meta,
    entryHash: x.entryHash,
  }
}

/** One page of the owner's log, newest first; [before] continues from a previous page's cursor. */
export async function fetchOwnerLog(
  serverUrl: string,
  token: string,
  before: number | null = null,
  doFetch: FetchLike = fetch.bind(globalThis),
): Promise<OwnerLogPage> {
  const base = serverUrl.trim().replace(/\/+$/, '')
  const params = new URLSearchParams({ limit: '50' })
  if (before !== null) params.set('before', String(before))
  let res: Response
  try {
    res = await doFetch(`${base}/v1/owner/audit?${params}`, {
      headers: { Authorization: `Bearer ${token}` },
      cache: 'no-store',
    })
  } catch {
    throw new OwnerLogError('unreachable')
  }
  if (res.status === 404) throw new OwnerLogError('unsupported')
  if (res.status === 401 || res.status === 403) throw new OwnerLogError('refused')
  if (res.status !== 200) throw new OwnerLogError('unreachable')
  let body: unknown
  try {
    body = await res.json()
  } catch {
    throw new OwnerLogError('unreachable')
  }
  if (!isRecord(body) || !Array.isArray(body.events)) throw new OwnerLogError('unreachable')
  const events = body.events.map(asEvent)
  if (events.some((e) => e === null)) throw new OwnerLogError('unreachable')
  const cursor = body.nextCursor
  if (cursor !== undefined && cursor !== null && !isWhole(cursor)) throw new OwnerLogError('unreachable')
  return { events: events as AuditEvent[], nextCursor: isWhole(cursor) ? cursor : null }
}

/** The lines to draw, in the order the server gave them. */
export function ownerLogLines(events: readonly AuditEvent[]): OwnerLogLine[] {
  return events.map((e) => ({ seq: e.seq, what: ownerLogActionLabel(e.action, e.meta), ts: e.ts }))
}
