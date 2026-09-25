import { describe, it, expect, beforeAll } from 'vitest'
import { verifyGrantBlob, hasCapability, applyModeOf, GrantError, GrantAddressError } from './grant'
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
    const grant = verifyGrantBlob(bytes, owner.publicKey, therapistFp)
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
    expect(() => verifyGrantBlob(bytes, owner.publicKey, therapistFp)).toThrow(GrantError)
  })

  it('rejects a grant whose envelope fingerprint does not match the pinned owner (key substitution)', () => {
    // A different pinned key than the one that actually signed.
    const other = newSignKeyPair()
    const bytes = signedBlob((g) => setCapability(g, 'read.share', true, 'propose'))
    expect(fingerprint(other.publicKey)).not.toBe(fingerprint(owner.publicKey))
    expect(() => verifyGrantBlob(bytes, other.publicKey, therapistFp)).toThrow(GrantError)
  })

  it('reflects auto vs propose apply mode', () => {
    const bytes = signedBlob((g) => setCapability(g, 'assign.goal', true, 'auto'))
    const grant = verifyGrantBlob(bytes, owner.publicKey, therapistFp)
    expect(applyModeOf(grant, 'assign.goal')).toBe('auto')
  })
})

describe('a grant is trusted only by the clinician it names', () => {
  let owner: ReturnType<typeof newSignKeyPair>
  const mine = 'ther-fp-mine'
  const theirs = 'ther-fp-theirs'

  beforeAll(async () => {
    await initAssignmentCrypto()
    owner = newSignKeyPair()
  })

  it('refuses a grant the same owner signed for another clinician', () => {
    // The owner signs every clinician's grant with one key, so a server holding another
    // clinician's grant holds a valid signature. Only the name inside says who it is for.
    expect(theirs).not.toBe(mine)
    const bytes = encodeSignedGrant(signGrant(setCapability(emptyGrant(theirs), 'assign.task', true, 'auto'), owner))
    expect(() => verifyGrantBlob(bytes, owner.publicKey, mine)).toThrow(GrantAddressError)
  })

  it('the same bytes verify for the clinician they name, so the refusal is only the name (positive control)', () => {
    const bytes = encodeSignedGrant(signGrant(setCapability(emptyGrant(theirs), 'assign.task', true, 'auto'), owner))
    expect(hasCapability(verifyGrantBlob(bytes, owner.publicKey, theirs), 'assign.task')).toBe(true)
  })

  it('is a GrantError, so every caller that refuses on GrantError refuses this too', () => {
    expect(new GrantAddressError('x')).toBeInstanceOf(GrantError)
  })
})
