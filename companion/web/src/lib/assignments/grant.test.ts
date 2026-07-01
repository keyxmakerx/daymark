import { describe, it, expect, beforeAll } from 'vitest'
import {
  emptyGrant, setCapability, revoke, signGrant, verifyGrant, serializeGrant,
  encodeSignedGrant, decodeSignedGrant, GrantError,
} from './grant'
import { initAssignmentCrypto, newSignKeyPair } from './crypto'
import { ALL_CAPABILITIES } from './types'

describe('grant editing', () => {
  it('emptyGrant has every capability present and OFF', () => {
    const g = emptyGrant('fp')
    for (const cap of ALL_CAPABILITIES) {
      expect(g.capabilities[cap]?.granted).toBe(false)
    }
  })

  it('setCapability grants a capability with the chosen apply mode', () => {
    const g = setCapability(emptyGrant('fp'), 'assign.task', true, 'auto')
    expect(g.capabilities['assign.task']).toEqual({ granted: true, apply: 'auto' })
  })

  it('coerces suggest.setting to propose even when auto is requested', () => {
    const g = setCapability(emptyGrant('fp'), 'suggest.setting', true, 'auto')
    expect(g.capabilities['suggest.setting']).toEqual({ granted: true, apply: 'propose' })
  })

  it('revoke sets granted:false', () => {
    const g = revoke(setCapability(emptyGrant('fp'), 'assign.goal', true, 'propose'), 'assign.goal')
    expect(g.capabilities['assign.goal']?.granted).toBe(false)
  })

  it('serializeGrant is deterministic (stable capability order)', () => {
    const g1 = setCapability(setCapability(emptyGrant('fp'), 'assign.task', true, 'auto'), 'assign.goal', true, 'propose')
    const g2 = setCapability(setCapability(emptyGrant('fp'), 'assign.goal', true, 'propose'), 'assign.task', true, 'auto')
    expect(serializeGrant(g1)).toBe(serializeGrant(g2))
  })
})

describe('grant signing (owner-signed, therapist-readable)', () => {
  beforeAll(async () => {
    await initAssignmentCrypto()
  })

  it('signs and verifies under the owner key', () => {
    const owner = newSignKeyPair()
    const g = setCapability(emptyGrant('therapist-fp'), 'read.share', true, 'propose')
    const signed = signGrant(g, owner)
    const back = verifyGrant(signed, owner.publicKey)
    expect(back.capabilities['read.share']?.granted).toBe(true)
    expect(back.therapistFingerprint).toBe('therapist-fp')
  })

  it('rejects a grant verified under a DIFFERENT (wrong) owner key', () => {
    const owner = newSignKeyPair()
    const wrong = newSignKeyPair()
    const signed = signGrant(emptyGrant('fp'), owner)
    expect(() => verifyGrant(signed, wrong.publicKey)).toThrow(GrantError)
  })

  it('rejects a tampered payload (signature no longer matches)', () => {
    const owner = newSignKeyPair()
    const signed = signGrant(setCapability(emptyGrant('fp'), 'assign.goal', false, 'propose'), owner)
    // Forge: flip the grant to granted:true without re-signing.
    const forged = { ...signed, payloadJson: signed.payloadJson.replace('"granted":false', '"granted":true') }
    expect(() => verifyGrant(forged, owner.publicKey)).toThrow(GrantError)
  })

  it('round-trips through opaque blob encode/decode', () => {
    const owner = newSignKeyPair()
    const signed = signGrant(emptyGrant('fp'), owner)
    const bytes = encodeSignedGrant(signed)
    const back = decodeSignedGrant(bytes)
    expect(verifyGrant(back, owner.publicKey).therapistFingerprint).toBe('fp')
  })
})
