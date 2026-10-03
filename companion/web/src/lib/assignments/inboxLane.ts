/*
 * THE INBOX'S DECISIONS, KEPT IN THE OWNER'S LANE (#234, #345).
 *
 * An accept or a decline in the owner console's assignment inbox is written to the owner's lane
 * (lane/lane.ts) as an assignmentDecision record carrying the clinician's signed assignment
 * verbatim, and a Refresh reads every lane back and puts each decision on the item it answers. So
 * a decision survives a Refresh, and reaches the journal when the phone takes it in (#346).
 *
 * WHAT IS KEPT, AND WHAT IS NOT:
 *   - an accept or a decline of a VERIFIED item: kept. It is signed by the pinned clinician,
 *     addressed to this owner and within the grant, which is what the phone checks again before it
 *     takes the decision in;
 *   - a snooze: not kept. It is "not now", in this console, and nothing the journal records;
 *   - a decline of an item that did not verify, or that the grant refuses: not kept. There is no
 *     assignment the phone could take a decision about in, so it is a dismissal for this session.
 *
 * WHICH ITEM A DECISION ANSWERS. The one whose signed assignment is exactly the record's: the same
 * payloadJson and the same signature. The lineage and version the server files an item under are
 * not signed, so they are never what matches. When more than one record answers an item (a second
 * browser, or a later answer in this one), the latest wins, as the phone keeps the later answer
 * (#346); two made in the same millisecond are ordered by id, so every console shows the same one.
 *
 * Ports and transitions only, so the order the inbox keeps is a node test (inboxLane.test.ts):
 * the screen calls these and holds no rule of its own.
 */
import type { Decision as InboxDecision, InboxItem } from './inbox'
import { assignmentDecisionRecord, readRecord, type KnownRecord, type LaneRecord } from '../lane/record'

/**
 * The record an accept or a decline of this item adds to the lane, or null for a decision that is
 * not kept (see the header): a snooze, or any decision about an item that is not VERIFIED.
 */
export function decisionRecordFor(item: InboxItem, decision: InboxDecision, createdAt: number = Date.now()): KnownRecord | null {
  if (decision === 'snoozed' || item.verdict !== 'VERIFIED' || !item.signed) return null
  return assignmentDecisionRecord(item.signed, decision, createdAt)
}

/** Later first: by the time the console made it, then by id, so every console orders them alike. */
function later(a: LaneRecord, b: LaneRecord): boolean {
  return a.createdAt !== b.createdAt ? a.createdAt > b.createdAt : a.id > b.id
}

/**
 * The items, each with the latest decision the lane holds for its signed assignment. An item no
 * record answers keeps the decision it has, which after a Refresh is none.
 */
export function applyDecisions(items: readonly InboxItem[], records: readonly LaneRecord[]): InboxItem[] {
  const latest = new Map<string, LaneRecord>()
  for (const r of records) {
    const read = readRecord(r)
    if (read?.kind !== 'assignmentDecision') continue
    const answers = `${read.payload.sigB64}\n${read.payload.payloadJson}`
    const held = latest.get(answers)
    if (!held || later(r, held)) latest.set(answers, r)
  }
  return items.map((item) => {
    if (!item.signed) return item
    const found = latest.get(`${item.signed.sigB64}\n${item.signed.payloadJson}`)
    const read = found && readRecord(found)
    return read?.kind === 'assignmentDecision' ? { ...item, decision: read.payload.decision } : item
  })
}
