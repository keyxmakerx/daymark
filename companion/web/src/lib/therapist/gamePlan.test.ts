import { describe, it, expect, beforeAll } from 'vitest'
import { newGamePlan, sealGamePlan, openGamePlan, supersede, withdraw, GamePlanOpenError, type GamePlanItem } from './gamePlan'
import { initAssignmentCrypto, newBoxKeyPair, newSignKeyPair, fingerprint } from '../assignments/crypto'

describe('therapist game plan sign-then-seal', () => {
  let owner: ReturnType<typeof newBoxKeyPair>
  let ther: ReturnType<typeof newSignKeyPair>
  let therFp: string
  let ownerFp: string
  const items: GamePlanItem[] = [{ itemRef: 'g1', kind: 'goal', title: 'Short walk', detail: 'after lunch' }]

  beforeAll(async () => {
    await initAssignmentCrypto()
    owner = newBoxKeyPair()
    ther = newSignKeyPair()
    therFp = fingerprint(ther.publicKey)
    ownerFp = fingerprint(owner.publicKey)
  })

  it('round-trips: owner opens + verifies against the pinned therapist key', () => {
    const plan = { ...newGamePlan(ownerFp, therFp), items }
    const blob = sealGamePlan(plan, ther, owner.publicKey)
    const opened = openGamePlan(blob, owner, ther.publicKey)
    expect(opened.items).toEqual(items)
    expect(opened.recipientOwnerFp).toBe(ownerFp)
    expect(opened.authorFingerprint).toBe(therFp)
  })

  it('a plan sealed to a DIFFERENT owner fails the recipientOwnerFp check on open', () => {
    const otherOwner = newBoxKeyPair()
    // Plan claims otherOwner's fp but is sealed to `owner` — opening as `owner` sees the wrong fp.
    const plan = { ...newGamePlan(fingerprint(otherOwner.publicKey), therFp), items }
    const blob = sealGamePlan(plan, ther, owner.publicKey)
    expect(() => openGamePlan(blob, owner, ther.publicKey)).toThrow(GamePlanOpenError)
  })

  it('a plan signed by a non-pinned key is rejected', () => {
    const attacker = newSignKeyPair()
    const plan = { ...newGamePlan(ownerFp, fingerprint(attacker.publicKey)), items }
    const blob = sealGamePlan(plan, attacker, owner.publicKey)
    // Verify against the PINNED therapist key, not the attacker's.
    expect(() => openGamePlan(blob, owner, ther.publicKey)).toThrow(GamePlanOpenError)
  })

  it('supersede increments version + records supersedes', () => {
    const v0 = { ...newGamePlan(ownerFp, therFp), items }
    const v1 = supersede(v0, [...items, { itemRef: 'g2', kind: 'note', title: 'keep it up' }])
    expect(v1.version).toBe(v0.version + 1)
    expect(v1.supersedes).toBe(v0.version)
    expect(v1.lineageId).toBe(v0.lineageId)
    expect(v1.status).toBe('active')
  })

  it('withdraw builds a signed "withdrawn" tombstone on the same lineage', () => {
    const v0 = { ...newGamePlan(ownerFp, therFp), items }
    const tomb = withdraw(v0)
    expect(tomb.status).toBe('withdrawn')
    expect(tomb.items).toEqual([])
    expect(tomb.lineageId).toBe(v0.lineageId)
    const blob = sealGamePlan(tomb, ther, owner.publicKey)
    const opened = openGamePlan(blob, owner, ther.publicKey)
    expect(opened.status).toBe('withdrawn')
  })
})
