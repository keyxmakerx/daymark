import { describe, it, expect, beforeAll } from 'vitest'
import { validateAssignment, shouldAutoApply } from './validate'
import {
  initAssignmentCrypto,
  newSignKeyPair,
  newBoxKeyPair,
  sealAssignment,
  openAssignment,
  fingerprint,
  AssignmentOpenError,
} from './crypto'
import type { Assignment, Grant } from './types'

function grantFor(fp: string): Grant {
  return {
    therapistFingerprint: fp,
    capabilities: {
      'assign.questionnaire': { granted: true, apply: 'propose' },
      'assign.task': { granted: true, apply: 'auto' },
      'suggest.setting': { granted: true, apply: 'auto' }, // auto here must still be overridden for settings
    },
  }
}

function baseAssignment(fp: string, over: Partial<Assignment> = {}): Assignment {
  return {
    assignmentId: 'a1', lineageId: 'laptop', version: 0, type: 'questionnaire',
    capability: 'assign.questionnaire', payload: { instrumentId: 'wellbeing-selfcheck' },
    issuedAt: 1, authorFingerprint: fp, ...over,
  }
}

describe('assignment validation (capability gate)', () => {
  const FP = 'therapist-fp'

  it('accepts a granted questionnaire assignment for a catalog instrument', () => {
    const r = validateAssignment(baseAssignment(FP), grantFor(FP))
    expect(r.ok).toBe(true)
    expect(r.applyMode).toBe('propose')
  })

  it('rejects an assignment whose capability is not granted', () => {
    const g = grantFor(FP)
    delete g.capabilities['assign.questionnaire']
    expect(validateAssignment(baseAssignment(FP), g).ok).toBe(false)
  })

  it('rejects a type/capability mismatch', () => {
    const a = baseAssignment(FP, { type: 'task', capability: 'assign.questionnaire' })
    expect(validateAssignment(a, grantFor(FP)).ok).toBe(false)
  })

  it('rejects a non-catalog instrument', () => {
    const a = baseAssignment(FP, { payload: { instrumentId: 'made-up' } })
    expect(validateAssignment(a, grantFor(FP)).ok).toBe(false)
  })

  it('rejects a setting key not on the allowlist (e.g. security keys)', () => {
    const a = baseAssignment(FP, { type: 'setting', capability: 'suggest.setting', payload: { key: 'pin', value: '1234' } })
    expect(validateAssignment(a, grantFor(FP)).ok).toBe(false)
  })

  it('accepts an allowlisted setting but NEVER auto-applies it', () => {
    const a = baseAssignment(FP, { type: 'setting', capability: 'suggest.setting', payload: { key: 'theme', value: 'dark' } })
    const r = validateAssignment(a, grantFor(FP))
    expect(r.ok).toBe(true)
    expect(shouldAutoApply(a, r.applyMode!)).toBe(false) // settings are always propose→accept
  })

  it('honours auto for a low-risk capability', () => {
    const a = baseAssignment(FP, { type: 'task', capability: 'assign.task', payload: { taskId: 'steady-attention' } })
    const r = validateAssignment(a, grantFor(FP))
    expect(r.ok).toBe(true)
    expect(shouldAutoApply(a, r.applyMode!)).toBe(true)
  })

  it('rejects an author that does not match the granted therapist', () => {
    expect(validateAssignment(baseAssignment('someone-else'), grantFor(FP)).ok).toBe(false)
  })
})

describe('assignment crypto (sign + seal → owner)', () => {
  beforeAll(async () => {
    await initAssignmentCrypto()
  })

  it('round-trips: therapist seals, owner opens + verifies', () => {
    const therapist = newSignKeyPair()
    const owner = newBoxKeyPair()
    const a = baseAssignment(fingerprint(therapist.publicKey))
    const blob = sealAssignment(a, therapist, owner.publicKey)
    const got = openAssignment(blob, owner, therapist.publicKey)
    expect(got.assignmentId).toBe('a1')
    expect(got.payload).toEqual({ instrumentId: 'wellbeing-selfcheck' })
  })

  it('a different owner cannot open the sealed box', () => {
    const therapist = newSignKeyPair()
    const owner = newBoxKeyPair()
    const other = newBoxKeyPair()
    const blob = sealAssignment(baseAssignment('fp'), therapist, owner.publicKey)
    expect(() => openAssignment(blob, other, therapist.publicKey)).toThrow(AssignmentOpenError)
  })

  it('rejects a signature from an unpinned (forged) therapist key', () => {
    const therapist = newSignKeyPair()
    const attacker = newSignKeyPair()
    const owner = newBoxKeyPair()
    const blob = sealAssignment(baseAssignment('fp'), attacker, owner.publicKey) // signed by attacker
    expect(() => openAssignment(blob, owner, therapist.publicKey)).toThrow(/signature/) // verified against pinned therapist
  })

  it('detects tampering of the sealed blob', () => {
    const therapist = newSignKeyPair()
    const owner = newBoxKeyPair()
    const blob = sealAssignment(baseAssignment('fp'), therapist, owner.publicKey)
    blob[blob.length - 2] ^= 0x01
    expect(() => openAssignment(blob, owner, therapist.publicKey)).toThrow(AssignmentOpenError)
  })
})
