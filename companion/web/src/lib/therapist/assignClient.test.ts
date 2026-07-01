import { describe, it, expect, beforeAll } from 'vitest'
import { buildAssignment, preflight, publishAssignment } from './assignClient'
import { emptyGrant, setCapability } from '../assignments/grant'
import { openAssignment, initAssignmentCrypto, newBoxKeyPair, newSignKeyPair, fingerprint } from '../assignments/crypto'
import { CATALOG } from '../instruments'
import type { PortalClient, SessionInfo, RelMeta } from './session'

/** A minimal fake PortalClient that records the last PUT so we can open+verify the sealed blob. */
class FakeClient {
  lastPut?: { channel: string; lineage: string; version: number; bytes: Uint8Array }
  async listVersions(): Promise<RelMeta[]> {
    return []
  }
  async putBlob(_s: SessionInfo, channel: string, lineage: string, version: number, bytes: Uint8Array): Promise<RelMeta> {
    this.lastPut = { channel, lineage, version, bytes }
    return { version, size: bytes.length, contentHash: 'x', createdAt: 0 }
  }
}

describe('therapist assign client', () => {
  let ther: ReturnType<typeof newSignKeyPair>
  let owner: ReturnType<typeof newBoxKeyPair>
  let therFp: string
  const session = { relRef: 'r', csrf: 'c', credentialKind: 'totp', absoluteExpiresAt: 9e15, idleExpiresAt: 9e15 } as SessionInfo

  beforeAll(async () => {
    await initAssignmentCrypto()
    ther = newSignKeyPair()
    owner = newBoxKeyPair()
    therFp = fingerprint(ther.publicKey)
  })

  function grantWith(cap: Parameters<typeof setCapability>[1]) {
    return setCapability(emptyGrant(therFp), cap, true, 'propose')
  }

  it('builds + passes pre-flight for a granted catalog questionnaire, and round-trips sealed', async () => {
    const grant = grantWith('assign.questionnaire')
    const a = buildAssignment({ type: 'questionnaire', payload: { instrumentId: CATALOG[0].instrumentId } }, therFp)
    expect(preflight(a, grant).ok).toBe(true)

    const client = new FakeClient() as unknown as PortalClient
    const res = await publishAssignment(a, grant, { sign: ther }, owner.publicKey, client, session)
    expect(res.ok).toBe(true)
    // The published blob opens + verifies against the therapist key.
    const put = (client as unknown as FakeClient).lastPut!
    const opened = openAssignment(put.bytes, owner, ther.publicKey)
    expect(opened.capability).toBe('assign.questionnaire')
    expect(opened.version).toBe(0)
  })

  it('refuses to publish an ungranted capability', async () => {
    const grant = grantWith('assign.goal') // goal granted, questionnaire not
    const a = buildAssignment({ type: 'questionnaire', payload: { instrumentId: CATALOG[0].instrumentId } }, therFp)
    expect(preflight(a, grant).ok).toBe(false)
    const client = new FakeClient() as unknown as PortalClient
    const res = await publishAssignment(a, grant, { sign: ther }, owner.publicKey, client, session)
    expect(res.ok).toBe(false)
    expect((client as unknown as FakeClient).lastPut).toBeUndefined()
  })

  it('refuses a non-catalog instrument (same gate the owner enforces)', () => {
    const grant = grantWith('assign.questionnaire')
    const a = buildAssignment({ type: 'questionnaire', payload: { instrumentId: 'phq-9-fabricated' } }, therFp)
    expect(preflight(a, grant).ok).toBe(false)
  })

  it('refuses a non-allowlisted setting key', () => {
    const grant = grantWith('suggest.setting')
    const a = buildAssignment({ type: 'setting', payload: { key: 'pin', value: 1234 } }, therFp)
    expect(preflight(a, grant).ok).toBe(false)
  })
})
