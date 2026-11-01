import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { attachByKeyProof, attachMessage, ATTACH_CONTEXT } from './attachProof'
import { initAssignmentCrypto } from '../assignments/crypto'
import type { AttachChallenge, PortalClient, SessionInfo } from './session'
import type { TherapistKeys } from './keyStore'

const session: SessionInfo = { relRef: '', credentialKind: 'totp', csrf: 'c', absoluteExpiresAt: 1, idleExpiresAt: 1 }

async function keys(): Promise<TherapistKeys> {
  const so = await initAssignmentCrypto()
  const sign = so.crypto_sign_keypair()
  const box = so.crypto_box_keypair()
  return { sign: { publicKey: sign.publicKey, privateKey: sign.privateKey }, box: { publicKey: box.publicKey, privateKey: box.privateKey } }
}

function fakeClient(challenge: AttachChallenge, sent: { sig?: string; relRef?: string }) {
  return {
    attachChallenge: async () => challenge,
    attachByKeyProof: async (_s: SessionInfo, _id: string, relRef: string, sig: string) => {
      sent.sig = sig
      sent.relRef = relRef
      return true
    },
  } as unknown as PortalClient
}

describe('the attach message', () => {
  it('is the same bytes the server builds, one field per line', () => {
    const text = new TextDecoder().decode(attachMessage('acct', 'rel', 'https://daymark.test', 'chal'))
    expect(text).toBe('daymark-staff-attach-v1\nacct\nrel\nhttps://daymark.test\nchal')
    // The server's own constant and its own test hold the same vector.
    const server = readFileSync(
      fileURLToPath(new URL('../../../../server/src/main/kotlin/com/daymark/companion/routes/StaffAccountRoutes.kt', import.meta.url)),
      'utf8',
    )
    expect(server).toContain(`const val ATTACH_CONTEXT = "${ATTACH_CONTEXT}"`)
    const serverTest = readFileSync(
      fileURLToPath(new URL('../../../../server/src/test/kotlin/com/daymark/companion/StaffAccountTest.kt', import.meta.url)),
      'utf8',
    )
    expect(serverTest).toContain(JSON.stringify(text))
  })
})

describe('attaching by key proof', () => {
  it('signs the challenge with the relationship key, and the signature verifies', async () => {
    const so = await initAssignmentCrypto()
    const k = await keys()
    const challenge: AttachChallenge = { challengeId: 'ac_1', challenge: 'xyz', accountId: 'clin', relRef: 'relB', address: 'https://d.test', expiresAt: 0 }
    const sent: { sig?: string; relRef?: string } = {}
    expect(await attachByKeyProof(fakeClient(challenge, sent), session, 'relB', k)).toBe('attached')
    const sig = so.from_base64(sent.sig!, so.base64_variants.URLSAFE_NO_PADDING)
    expect(so.crypto_sign_verify_detached(sig, attachMessage('clin', 'relB', 'https://d.test', 'xyz'), k.sign.publicKey)).toBe(true)
    // Control: the same signature does not verify for another relationship.
    expect(so.crypto_sign_verify_detached(sig, attachMessage('clin', 'relC', 'https://d.test', 'xyz'), k.sign.publicKey)).toBe(false)
  })

  it('will not sign a challenge naming a relationship it was not asked about', async () => {
    const k = await keys()
    const challenge: AttachChallenge = { challengeId: 'ac_1', challenge: 'xyz', accountId: 'clin', relRef: 'someone-else', address: 'https://d.test', expiresAt: 0 }
    const sent: { sig?: string } = {}
    expect(await attachByKeyProof(fakeClient(challenge, sent), session, 'relB', k)).toBe('refused')
    expect(sent.sig).toBeUndefined()
    // Control: the matching relationship is signed.
    const ok: { sig?: string } = {}
    expect(await attachByKeyProof(fakeClient({ ...challenge, relRef: 'relB' }, ok), session, 'relB', k)).toBe('attached')
    expect(ok.sig).toBeDefined()
  })
})
