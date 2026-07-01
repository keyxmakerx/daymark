import { describe, it, expect, beforeAll } from 'vitest'
import { verifyGrantBlob, hasCapability, applyModeOf, GrantError } from './grant'
import { emptyGrant, setCapability, signGrant, encodeSignedGrant } from '../assignments/grant'
import { initAssignmentCrypto, newSignKeyPair, fingerprint } from '../assignments/crypto'

describe('therapist grant verification', () => {
  let owner: ReturnType<typeof newSignKeyPair>
  let therapistFp: string

  beforeAll(async () => {
    await initAssignmentCrypto()
    owner = newSignKeyPair()
    therapistFp = 'ther-fp-123'
  })

  function signedBlob(mut: (g: ReturnType<typeof emptyGrant>) => ReturnType<typeof emptyGrant>) {
    const grant = mut(emptyGrant(therapistFp))
    return encodeSignedGrant(signGrant(grant, owner))
  }

  it('accepts an owner-signed grant against the pinned owner key', () => {
    const bytes = signedBlob((g) => setCapability(g, 'read.share', true, 'propose'))
    const grant = verifyGrantBlob(bytes, owner.publicKey)
    expect(hasCapability(grant, 'read.share')).toBe(true)
    expect(applyModeOf(grant, 'read.share')).toBe('propose')
    expect(hasCapability(grant, 'assign.goal')).toBe(false)
    expect(applyModeOf(grant, 'assign.goal')).toBeNull()
  })

  it('rejects a grant signed by a different (forged owner) key', () => {
    const attacker = newSignKeyPair()
    const grant = setCapability(emptyGrant(therapistFp), 'read.share', true, 'propose')
    const bytes = encodeSignedGrant(signGrant(grant, attacker))
    // Verified against the REAL pinned owner key → fingerprint mismatch / bad signature.
    expect(() => verifyGrantBlob(bytes, owner.publicKey)).toThrow(GrantError)
  })

  it('rejects a grant whose envelope fingerprint does not match the pinned owner (key substitution)', () => {
    // A different pinned key than the one that actually signed.
    const other = newSignKeyPair()
    const bytes = signedBlob((g) => setCapability(g, 'read.share', true, 'propose'))
    expect(fingerprint(other.publicKey)).not.toBe(fingerprint(owner.publicKey))
    expect(() => verifyGrantBlob(bytes, other.publicKey)).toThrow(GrantError)
  })

  it('reflects auto vs propose apply mode', () => {
    const bytes = signedBlob((g) => setCapability(g, 'assign.goal', true, 'auto'))
    const grant = verifyGrantBlob(bytes, owner.publicKey)
    expect(applyModeOf(grant, 'assign.goal')).toBe('auto')
  })
})
