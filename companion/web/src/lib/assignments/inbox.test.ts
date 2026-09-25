import { describe, it, expect, beforeAll } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import {
  evaluateBlob, buildInbox, canApply, fetchInbox, goneItemLine,
  type PinnedTherapist, type RawAssignmentBlob, type InboxSource,
} from './inbox'
import { PortalError, type RelMeta } from '../sync/portal'
import {
  initAssignmentCrypto, newSignKeyPair, newBoxKeyPair, sealAssignment, fingerprint,
  type BoxKeyPair, type SignKeyPair,
} from './crypto'
import { emptyGrant, setCapability } from './grant'
import type { Assignment, Grant } from './types'

let owner: BoxKeyPair
let therapistSign: SignKeyPair
let therapistFp: string

beforeAll(async () => {
  await initAssignmentCrypto()
  owner = newBoxKeyPair()
  therapistSign = newSignKeyPair()
  therapistFp = fingerprint(therapistSign.publicKey)
})

function pinned(grant: Grant): PinnedTherapist {
  return {
    id: 't1', displayName: 'Dr. Example',
    signPub: therapistSign.publicKey, boxPub: newBoxKeyPair().publicKey, grant,
  }
}

function assignment(over: Partial<Assignment> = {}): Assignment {
  return {
    assignmentId: 'a1', lineageId: 'l', version: 0, type: 'questionnaire',
    capability: 'assign.questionnaire', payload: { instrumentId: 'wellbeing-selfcheck' },
    issuedAt: 100, authorFingerprint: therapistFp, ...over,
  }
}

function blob(a: Assignment, signer: SignKeyPair = therapistSign, recipient: BoxKeyPair = owner): RawAssignmentBlob {
  return { therapistId: 't1', lineage: 'l', version: a.version, bytes: sealAssignment(a, signer, recipient.publicKey) }
}

describe('inbox — verified path', () => {
  it('VERIFIED for a granted questionnaire from the pinned therapist', () => {
    const g = setCapability(emptyGrant(therapistFp), 'assign.questionnaire', true, 'propose')
    const item = evaluateBlob(blob(assignment()), pinned(g), owner)
    expect(item.verdict).toBe('VERIFIED')
    expect(item.requiresAccept).toBe(true) // propose ⇒ requires accept
    expect(canApply(item)).toBe(true)
    expect(item.preview).toContain('self-check')
  })

  it('a granted AUTO non-setting capability does not require accept', () => {
    const g = setCapability(emptyGrant(therapistFp), 'assign.task', true, 'auto')
    const a = assignment({ type: 'task', capability: 'assign.task', payload: { taskId: 'steady-attention' } })
    const item = evaluateBlob(blob(a), pinned(g), owner)
    expect(item.verdict).toBe('VERIFIED')
    expect(item.requiresAccept).toBe(false)
  })

  it('suggest.setting ALWAYS requires accept even when the grant marks it auto-adjacent', () => {
    // Grant stores propose (coerced), but assert requiresAccept regardless of mode.
    const g = setCapability(emptyGrant(therapistFp), 'suggest.setting', true, 'auto')
    const a = assignment({ type: 'setting', capability: 'suggest.setting', payload: { key: 'theme', value: 'dark' } })
    const item = evaluateBlob(blob(a), pinned(g), owner)
    expect(item.verdict).toBe('VERIFIED')
    expect(item.requiresAccept).toBe(true)
  })
})

describe('inbox — security: rejected / untrusted never applyable', () => {
  it('UNTRUSTED_KEY when signed by a DIFFERENT key than the pinned one', () => {
    const attacker = newSignKeyPair()
    const g = setCapability(emptyGrant(therapistFp), 'assign.questionnaire', true, 'propose')
    // Signed by attacker, but sealed to the real owner and claiming the real author fp.
    const item = evaluateBlob(blob(assignment(), attacker), pinned(g), owner)
    expect(item.verdict).toBe('UNTRUSTED_KEY')
    expect(canApply(item)).toBe(false)
    expect(item.requiresAccept).toBe(false)
  })

  it('OPEN_FAILED when the blob is not addressed to this owner', () => {
    const otherOwner = newBoxKeyPair()
    const g = setCapability(emptyGrant(therapistFp), 'assign.questionnaire', true, 'propose')
    const item = evaluateBlob(blob(assignment(), therapistSign, otherOwner), pinned(g), owner)
    expect(item.verdict).toBe('OPEN_FAILED')
    expect(canApply(item)).toBe(false)
  })

  it('OPEN_FAILED when the sealed blob is tampered', () => {
    const g = setCapability(emptyGrant(therapistFp), 'assign.questionnaire', true, 'propose')
    const b = blob(assignment())
    b.bytes[b.bytes.length - 2] ^= 0x01
    const item = evaluateBlob(b, pinned(g), owner)
    expect(item.verdict).toBe('OPEN_FAILED')
    expect(canApply(item)).toBe(false)
  })

  it('OPEN_FAILED when a signed assignment is served under another lineage or version', () => {
    const g = setCapability(emptyGrant(therapistFp), 'assign.questionnaire', true, 'propose')
    const b = blob(assignment())
    // Positive control: under the label it was signed with, the same bytes are VERIFIED.
    expect(evaluateBlob(b, pinned(g), owner).verdict).toBe('VERIFIED')
    const lineage = b.lineage === 'l2' ? 'l3' : 'l2'
    expect(lineage).not.toBe(b.lineage)
    const relabelled = evaluateBlob({ ...b, lineage }, pinned(g), owner)
    expect(relabelled.verdict).toBe('OPEN_FAILED')
    expect(canApply(relabelled)).toBe(false)
    const renumbered = evaluateBlob({ ...b, version: b.version + 1 }, pinned(g), owner)
    expect(renumbered.verdict).toBe('OPEN_FAILED')
    expect(canApply(renumbered)).toBe(false)
  })

  it('REJECTED when the capability is not currently granted', () => {
    const g = emptyGrant(therapistFp) // nothing granted
    const item = evaluateBlob(blob(assignment()), pinned(g), owner)
    expect(item.verdict).toBe('REJECTED')
    expect(canApply(item)).toBe(false)
    expect(item.errors.join(' ')).toMatch(/not granted/)
  })

  it('REJECTED when a setting key is OFF the allowlist (security keys can never apply)', () => {
    const g = setCapability(emptyGrant(therapistFp), 'suggest.setting', true, 'propose')
    const a = assignment({ type: 'setting', capability: 'suggest.setting', payload: { key: 'pin', value: '1234' } })
    const item = evaluateBlob(blob(a), pinned(g), owner)
    expect(item.verdict).toBe('REJECTED')
    expect(canApply(item)).toBe(false)
    expect(item.errors.join(' ')).toMatch(/allowlist/)
  })

  it('REJECTED on a type/capability mismatch', () => {
    const g = setCapability(emptyGrant(therapistFp), 'assign.questionnaire', true, 'propose')
    const a = assignment({ type: 'task', capability: 'assign.questionnaire', payload: { taskId: 'steady-attention' } })
    const item = evaluateBlob(blob(a), pinned(g), owner)
    expect(item.verdict).toBe('REJECTED')
    expect(canApply(item)).toBe(false)
  })
})

describe('buildInbox', () => {
  it('folds many blobs newest-first and skips unpinned authors', () => {
    const g = setCapability(emptyGrant(therapistFp), 'assign.questionnaire', true, 'propose')
    const t = pinned(g)
    const older = blob(assignment({ assignmentId: 'old', issuedAt: 100 }))
    const newer = blob(assignment({ assignmentId: 'new', issuedAt: 200, version: 1 }))
    const orphan: RawAssignmentBlob = { therapistId: 'unknown', lineage: 'x', version: 0, bytes: newer.bytes }
    const items = buildInbox([older, newer, orphan], [t], owner)
    expect(items.length).toBe(2) // orphan skipped
    expect(items[0].assignment?.assignmentId).toBe('new')
  })
})

describe('a clinician who re-paired with new keys', () => {
  it('is refused under a grant still naming the old key, and verified once it is re-bound', () => {
    // What keysArrived in OwnerConsole does on re-pairing: keep the grant, re-bind it to the key.
    const oldGrant = setCapability(emptyGrant('old-key-fp'), 'assign.questionnaire', true, 'propose')
    expect(oldGrant.therapistFingerprint).not.toBe(therapistFp)
    expect(evaluateBlob(blob(assignment()), pinned(oldGrant), owner).verdict).toBe('REJECTED')
    const rebound = { ...oldGrant, therapistFingerprint: therapistFp }
    expect(evaluateBlob(blob(assignment()), pinned(rebound), owner).verdict).toBe('VERIFIED')
  })

  it('keeps their grant in the owner console, re-bound to the new signing key', () => {
    const src = readFileSync(resolve(process.cwd(), 'src/lib/components/owner/OwnerConsole.svelte'), 'utf8')
    expect(src).toContain('{ ...cur.grant, therapistFingerprint: id }')
  })
})

describe('fetching the inbox item by item (#339)', () => {
  const sender = { id: 't1', displayName: 'Dr. Example', inboxToken: 'tok' }
  const meta = (version: number, createdAt: number): RelMeta => ({ version, size: 1, contentHash: 'h', createdAt })

  /** A server with these lineages; `status` makes a lineage's item answer that instead of bytes. */
  function source(lineages: Record<string, { createdAt: number; status?: number }>): InboxSource {
    return {
      listLineages: async () => Object.keys(lineages),
      listVersions: async (_t, _c, lineage) => [meta(0, lineages[lineage].createdAt)],
      getBlob: async (_t, _c, lineage) => {
        const status = lineages[lineage].status
        if (status !== undefined) throw new PortalError('blob fetch failed', status)
        return new Uint8Array([lineage.charCodeAt(0)])
      },
    }
  }

  it('lists the live item, and one line for an item the server no longer keeps', async () => {
    const { blobs, gone } = await fetchInbox(source({ a: { createdAt: 1000 }, b: { createdAt: 2000, status: 410 } }), [sender])
    expect(blobs.map((b) => b.lineage)).toEqual(['a'])
    expect(gone).toEqual([{ therapistName: 'Dr. Example', sentAt: 2000, lineage: 'b', version: 0 }])
  })

  it('lists both when both are live (positive control)', async () => {
    const { blobs, gone } = await fetchInbox(source({ a: { createdAt: 1000 }, b: { createdAt: 2000 } }), [sender])
    expect(blobs.map((b) => b.lineage)).toEqual(['a', 'b'])
    expect(gone).toEqual([])
  })

  it('still fails the load on any other refusal, which says nothing about the item', async () => {
    await expect(fetchInbox(source({ a: { createdAt: 1000, status: 500 } }), [sender])).rejects.toThrow(PortalError)
  })

  it('the line says who and when, and nothing about what or why', () => {
    expect(goneItemLine('Dr. Example', '9 October 2026')).toBe('Sent by Dr. Example on 9 October 2026. The server keeps items for 90 days.')
  })

  it('the inbox screen fetches through it and draws the line in ink, not the alarm hue', () => {
    const src = readFileSync(resolve(process.cwd(), 'src/lib/components/owner/AssignmentInbox.svelte'), 'utf8')
    expect(src).toContain('fetchInbox(client, session.pinned)')
    expect(src).toContain('goneItemLine(')
    const goneRule = src.match(/\.gone \{[^}]*\}/)?.[0] ?? ''
    expect(goneRule).toContain('--ink-text')
    expect(goneRule).not.toContain('--clay')
  })
})

