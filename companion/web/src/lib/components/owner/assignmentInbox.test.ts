/*
 * THE INBOX SCREEN KEEPS ITS DECISIONS IN THE OWNER'S LANE, ASSERTED OVER ITS SOURCE (#234, #345).
 *
 * The rules live in assignments/inboxLane.ts and lane/lane.ts, where they are node tests; web tests
 * have no renderer, so what is held here is that the screen calls them, in the order that makes a
 * decision last: written to the lane before the card shows it, and read back on every Refresh.
 */
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { DECISIONS_UNREAD, LANE_FAULT_TEXT, SOME_DECISIONS_UNOPENED } from '../../lane/copy'

const codeOf = (rel: string) =>
  readFileSync(fileURLToPath(new URL(rel, import.meta.url)), 'utf8')
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/(?<!:)\/\/[^\n]*/g, '')

const inbox = codeOf('./AssignmentInbox.svelte')
const consoleCode = codeOf('./OwnerConsole.svelte')
const card = codeOf('./AssignmentCard.svelte')

const between = (code: string, from: string, to: string) => {
  const start = code.indexOf(from)
  expect(start, from).toBeGreaterThan(-1)
  return code.slice(start, code.indexOf(to, start + from.length))
}

describe('a Refresh reads the decisions back', () => {
  it('reads every lane and puts each decision on its item before the items are shown', () => {
    const refresh = between(inbox, 'async function refresh()', 'async function decide(')
    const read = refresh.indexOf('await lane.read()')
    const applied = refresh.indexOf('built = applyDecisions(built, reading.records)')
    const shown = refresh.indexOf('items = built')
    expect(read).toBeGreaterThan(refresh.indexOf('buildInbox('))
    expect(applied).toBeGreaterThan(read)
    expect(shown).toBeGreaterThan(applied)
  })

  it('says, in fixed words, when decisions could not be read or opened, and still shows the items', () => {
    const refresh = between(inbox, 'async function refresh()', 'async function decide(')
    expect(refresh).toContain("laneNote = { text: SOME_DECISIONS_UNOPENED, tone: 'warn' }")
    expect(refresh).toContain("laneNote = { text: DECISIONS_UNREAD, tone: 'warn' }")
    // The failed read is caught inside, so the items are still shown.
    expect(refresh.indexOf('laneNote = { text: DECISIONS_UNREAD')).toBeLessThan(refresh.indexOf('items = built'))
    expect([DECISIONS_UNREAD, SOME_DECISIONS_UNOPENED].every((t) => t.length > 0)).toBe(true)
  })
})

describe('a decision is shown only once the lane has it', () => {
  const decide = between(inbox, 'async function decide(', '</script>')

  it('writes the record first, and marks the item decided only after the write returned', () => {
    const made = decide.indexOf('const record = decisionRecordFor(item, decision)')
    const written = decide.indexOf('await lane.add(record)')
    const marked = decide.indexOf('items = items.map((it) => (keyOf(it) === keyOf(item) ? { ...it, decision } : it))')
    expect(made).toBeGreaterThan(-1)
    expect(written).toBeGreaterThan(made)
    expect(marked).toBeGreaterThan(written)
  })

  it('a write that failed returns before the item is marked, with the lane\'s own words', () => {
    const failed = between(decide, '} catch (e) {', '} finally {')
    expect(failed).toContain('LANE_FAULT_TEXT[fault]')
    expect(failed).toContain('return')
    expect(failed).not.toContain('decision }')
    // Only the unconfirmed save is a warning; "nothing was saved" is drawn as a refusal.
    expect(failed).toContain("tone: fault === 'unconfirmed' ? 'warn' : 'critical'")
    expect(LANE_FAULT_TEXT.unconfirmed.startsWith('Nothing was saved')).toBe(false)
    for (const f of ['keyChanged', 'refused', 'ownLaneUnreadable', 'notConnected'] as const) {
      expect(LANE_FAULT_TEXT[f].startsWith('Nothing was saved'), f).toBe(true)
    }
  })

  it('with no lane, nothing is marked either', () => {
    const noLane = between(decide, 'if (!lane) {', 'saving = keyOf(item)')
    expect(noLane).toContain('LANE_FAULT_TEXT.notConnected')
    expect(noLane).toContain('return')
  })

  it('the card\'s buttons, and Refresh, wait while a decision is being saved', () => {
    expect(inbox).toContain('saving={saving === keyOf(item)}')
    expect(inbox).toContain("disabled={busy || saving !== null}")
    expect(card).toContain('disabled={!applyable || saving}')
    expect(card).toContain('onclick={ondecline} disabled={saving}')
  })

  it('every message it shows is a fixed one: none is built from what the server said', () => {
    const notes = inbox.match(/laneNote = \{ text: [^,]+/g) ?? []
    expect(notes.length).toBeGreaterThanOrEqual(4)
    for (const n of notes) expect(n).not.toMatch(/`|\+|e\.message/)
    // Control: the detector sees an interpolated message.
    expect('laneNote = { text: `Failed: ${e.message}`'.match(/laneNote = \{ text: [^,]+/)![0]).toMatch(/`|\+|e\.message/)
  })
})

describe('the console hands the inbox its lane', () => {
  it('builds the lane on the same connection, with the session\'s key, and what the open snapshot says was taken in', () => {
    expect(consoleCode).toContain('<AssignmentInbox {session} {client} {lane} />')
    expect(consoleCode).toContain('ownerLane(new SyncClient(url, tok), s.lane, { takenIn: () => takenInIds(data) })')
    expect(consoleCode).toContain('lane = session ? await laneOn(serverUrl, token, session).catch(() => null) : null')
    const lock = between(consoleCode, 'function lock()', 'async function connect()')
    expect(lock).toContain('lane = null')
  })
})
