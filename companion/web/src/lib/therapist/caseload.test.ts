import { describe, it, expect } from 'vitest'
import { attachable, unlockCaseload } from './caseload'
import type { KeyRecord } from './inviteAccept'
import type { TherapistKeys, WrappedKeyBlob } from './keyStore'

const record = (relRef: string, pass: string): KeyRecord => ({
  v: 1,
  relRef,
  credentialId: `cred-${relRef}`,
  wrapped: { v: 1, kdf: { alg: 'argon2id', memMiB: 256, ops: 3 }, saltB64: pass, nonceB64: 'n', ctB64: relRef },
  createdAt: 1,
})

const keysFor = (relRef: string): TherapistKeys => ({
  box: { publicKey: new Uint8Array([1]), privateKey: new TextEncoder().encode(relRef) },
  sign: { publicKey: new Uint8Array([2]), privateKey: new Uint8Array([3]) },
})

/** A stand-in unwrap: the "salt" holds the passphrase the record opens under. */
const unwrapKeys = async (blob: WrappedKeyBlob, passphrase: string) => {
  if (blob.saltB64 !== passphrase) throw new Error('wrong passphrase')
  return keysFor(blob.ctB64)
}

describe('one reading passphrase for the caseload', () => {
  it('opens every record the account reaches with the one passphrase', async () => {
    const records = [record('a', 'p1'), record('b', 'p1'), record('c', 'p1')]
    const caseload = await unlockCaseload(['a', 'b', 'c'], records, 'p1', unwrapKeys)
    expect([...caseload.opened.keys()]).toEqual(['a', 'b', 'c'])
    expect(caseload.otherPassphrase).toEqual([])
  })

  it('opens nothing the account does not reach, even under the right passphrase', async () => {
    const records = [record('a', 'p1'), record('ended', 'p1')]
    const caseload = await unlockCaseload(['a'], records, 'p1', unwrapKeys)
    expect(caseload.opened.has('ended')).toBe(false)
    // Control: the same record opens when it is reached.
    expect((await unlockCaseload(['ended'], records, 'p1', unwrapKeys)).opened.has('ended')).toBe(true)
  })

  it('names the records under another passphrase and the relationships with no record here', async () => {
    const records = [record('a', 'p1'), record('old', 'p0')]
    const caseload = await unlockCaseload(['a', 'old', 'elsewhere'], records, 'p1', unwrapKeys)
    expect([...caseload.opened.keys()]).toEqual(['a'])
    expect(caseload.otherPassphrase).toEqual(['old'])
    expect(caseload.notInThisBrowser).toEqual(['elsewhere'])
  })

  it('offers the records the account does not reach for a key proof', () => {
    const records = [record('a', 'p'), record('b', 'p')]
    expect(attachable(['a'], records).map((r) => r.relRef)).toEqual(['b'])
    expect(attachable(['a', 'b'], records)).toEqual([])
  })
})
