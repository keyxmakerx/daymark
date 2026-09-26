import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { auditActionLabel, auditActorLabel } from './auditLabels'
import { orgAuditActionLabel } from '../../practice/audit'
import { REVOKE_CAVEAT } from '../../pairing/copy'

/*
 * EVERY ACTION THE SERVER CAN WRITE HAS A LABEL (#277).
 *
 * The server's closed list is AuditAction in AuditStore.kt (COMPANION_SECURITY.md §9), and this file
 * reads it from there, as text, rather than keeping a second list by hand: a hand list is how
 * `share.revoke` reached the owner's access log as a raw code. The `org.*` actions are written to a
 * practice's own log, a separate database the owner console never reads, so they are held to the
 * practice console's labels instead. Either way, a line somebody reads comes with words.
 */

const AUDIT_STORE = fileURLToPath(
  new URL('../../../../../server/src/main/kotlin/com/daymark/companion/storage/AuditStore.kt', import.meta.url),
)

/** Kotlin with comments removed, so a wire value quoted in a comment is not an action. */
const kotlinCode = (src: string) => src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/(?<!:)\/\/[^\n]*/g, '')

/** The wire values of AuditAction, in declaration order. Empty when the enum is not there. */
function serverActions(kotlin: string): string[] {
  const code = kotlinCode(kotlin)
  const start = code.indexOf('enum class AuditAction(')
  if (start < 0) return []
  const end = code.indexOf('\n}', start)
  const body = code.slice(start, end < 0 ? undefined : end)
  return [...body.matchAll(/^\s*[A-Z][A-Z0-9_]*\("([^"]+)"\)/gm)].map((m) => m[1])
}

/** Written to a practice's log, which only the practice console reads (practice/audit.ts). */
const onPracticeLog = (action: string) => action.startsWith('org.')

/** What a reader sees for an action, in the console that reads the log it is written to. */
const labelFor = (action: string) => (onPracticeLog(action) ? orgAuditActionLabel(action) : auditActionLabel(action))

/** The actions a reader would see as raw codes. Null when nothing was read: that is no verdict. */
function unlabelled(kotlin: string): string[] | null {
  const actions = serverActions(kotlin)
  return actions.length === 0 ? null : actions.filter((a) => labelFor(a) === a)
}

const KOTLIN = readFileSync(AUDIT_STORE, 'utf8')
const ACTIONS = serverActions(KOTLIN)

describe('every action the server can write has a label (#277)', () => {
  it('reads the whole list from the server, and only the actions', () => {
    // Anchors from both ends of the enum and from both logs: a parse that stopped early, or read
    // the actor enum above it, misses one of these.
    for (const known of ['auth.success', 'share.revoke', 'relationship.ended', 'owner_key.fetched', 'org.created', 'org.action_denied']) {
      expect(ACTIONS).toContain(known)
    }
    expect(ACTIONS).not.toContain('owner')
  })

  it('labels each one in the console that reads its log', () => {
    expect(unlabelled(KOTLIN)).toEqual([])
  })

  it('fails for a planted action with no label on either log, and for an empty read (positive control)', () => {
    const header = 'enum class AuditAction(val wire: String) {'
    const planted = KOTLIN.replace(header, `${header}\n    SHARE_FORWARDED("share.forwarded"),\n    ORG_RENAMED("org.renamed"),`)
    expect(planted).not.toBe(KOTLIN)
    expect(unlabelled(planted)).toEqual(expect.arrayContaining(['share.forwarded', 'org.renamed']))
    // Nothing read is no verdict, never a pass.
    expect(unlabelled('')).toBeNull()
    const renamed = KOTLIN.replace('enum class AuditAction(', 'enum class Renamed(')
    expect(renamed).not.toBe(KOTLIN)
    expect(unlabelled(renamed)).toBeNull()
  })
})

describe('audit label mapping', () => {
  it('calls an opened share what it is, not a report (#337)', () => {
    // A share is access; a report is a copy handed over (#305). The line matches its neighbour,
    // "Ended their access to what you share".
    expect(auditActionLabel('share.open')).toBe('Opened what you share')
    expect(auditActionLabel('share.open')).not.toMatch(/report/i)
    // Control: the retired label is seen by the same pattern.
    expect('Opened a shared report').toMatch(/report/i)
  })

  it('falls back to the raw code for an unrecognized action rather than hiding it', () => {
    expect(auditActionLabel('some.future.event')).toBe('some.future.event')
  })

  it('names the clinician’s own ending without narrating why', () => {
    // The server cannot know whether somebody retired, moved practice, or fell out with the person,
    // and the label must not imply it does. It also must not read as the owner's own revoke.
    const label = auditActionLabel('relationship.ended')
    expect(label).toContain('Ended their access')
    expect(label).not.toMatch(/revoked|left the|quit|resigned|removed/i)
    // Control: the same search fires on labels that do narrate or borrow the owner's word.
    expect('Revoked their access').toMatch(/revoked|left the|quit|resigned|removed/i)
    expect('Your therapist left the practice').toMatch(/revoked|left the|quit|resigned|removed/i)
  })

  it('calls the owner’s revoke the owner’s act, never the clinician’s ending (#277)', () => {
    // The word on the button the owner pressed. The two lines sit in one log with different actors,
    // and each must say which of them did what.
    const revoke = auditActionLabel('share.revoke')
    expect(revoke).toBe('Revoked sharing')
    expect(revoke).not.toBe(auditActionLabel('relationship.ended'))
    expect(revoke).not.toMatch(/ended their access/i)
    // Control: the pattern sees the clinician's line.
    expect(auditActionLabel('relationship.ended')).toMatch(/ended their access/i)
  })

  it('no label carries the revoke caveat: that sentence belongs at the click', () => {
    expect(ACTIONS.length).toBeGreaterThan(0)
    const CAVEAT = /un-?send/i
    for (const action of ACTIONS) {
      expect(labelFor(action)).not.toContain(REVOKE_CAVEAT)
      expect(labelFor(action)).not.toMatch(CAVEAT)
    }
    // Control: a label that carried it is seen by both checks.
    const carried = `${auditActionLabel('share.revoke')}. ${REVOKE_CAVEAT}`
    expect(carried).toContain(REVOKE_CAVEAT)
    expect(carried).toMatch(CAVEAT)
  })

  it('labels actor roles, calling the professional a clinician (#158)', () => {
    expect(auditActorLabel('owner')).toBe('You')
    // The actor VALUE stays 'therapist': it is what the server stores and sends. Only the word the
    // owner reads changed.
    expect(auditActorLabel('therapist')).toBe('Your clinician')
    expect(auditActorLabel('therapist')).not.toMatch(/therapist/i)
    // Control: the word planted back into the real label is seen.
    expect(auditActorLabel('therapist').replace('clinician', 'therapist')).toMatch(/therapist/i)
  })
})
