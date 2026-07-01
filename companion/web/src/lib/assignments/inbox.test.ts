import { describe, it, expect, beforeAll } from 'vitest'
import { evaluateBlob, buildInbox, canApply, type PinnedTherapist, type RawAssignmentBlob } from './inbox'
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
