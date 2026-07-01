import { describe, it, expect, beforeAll } from 'vitest'
import { decodeSealed, bundleToBackupData, ShareOpenError } from './shareClient'
import { openShare } from '../share/sharecrypto'
import { buildShare, type ShareBundle, type ShareMeta, type SealedShare } from '../share/sharecrypto'
import { PinStore } from '../share/pairing'
import { initAssignmentCrypto, newBoxKeyPair, newSignKeyPair, fingerprint } from '../assignments/crypto'
import _sodium from 'libsodium-wrappers-sumo'

const URLSAFE = () => _sodium.base64_variants.URLSAFE_NO_PADDING

/** Mirror the owner ShareBuilder.encodeSealed wire format so the therapist decode is exercised. */
function encodeSealed(s: SealedShare): Uint8Array {
  const toB = (b: Uint8Array) => _sodium.to_base64(b, URLSAFE())
  const obj = {
    fmt: s.fmt, shareId: s.shareId, version: s.version, expiry: s.expiry,
    recipientFp: s.recipientFp, ownerSigningFp: s.ownerSigningFp,
    body: toB(s.body), wrappedCEK: toB(s.wrappedCEK), ownerSig: toB(s.ownerSig),
  }
  return new TextEncoder().encode(JSON.stringify(obj))
}

describe('therapist share reader', () => {
  let owner: ReturnType<typeof newSignKeyPair>
  let ther: ReturnType<typeof newBoxKeyPair>
  let sealed: SealedShare
  let ownerSigningFp: string
  let bundle: ShareBundle

  beforeAll(async () => {
    await initAssignmentCrypto()
    owner = newSignKeyPair()
    ther = newBoxKeyPair()
    ownerSigningFp = fingerprint(owner.publicKey)
    const recipientFp = fingerprint(ther.publicKey)
    bundle = {
      schema: 1,
      shareId: 's1',
      scope: { from: 100, to: 200, recordTypes: ['checkIns', 'moods'] },
      ownerFp: ownerSigningFp,
      checkIns: [{ instrumentId: 'wellbeing-selfcheck', at: 150, score: 12, band: 'moderate' }],
      moods: [{ at: 120, level: 3 }],
    }
    const meta: ShareMeta = { context: 'daymark.share.v1', shareId: 's1', version: 0, recipientFp, expiry: 9e15, ownerSigningFp }
    const pins = new PinStore()
    pins.pin({ x25519Pub: ther.publicKey, ed25519Pub: newSignKeyPair().publicKey })
    // pin gate needs the recipient's ed25519 fp; buildShare checks the therapist ed key is pinned.
    const therSign = newSignKeyPair()
    const pins2 = new PinStore()
    pins2.pin({ x25519Pub: ther.publicKey, ed25519Pub: therSign.publicKey })
    sealed = buildShare(bundle, meta, ther.publicKey, owner, fingerprint(therSign.publicKey), pins2)
  })

  it('decodes the owner envelope and opens against the pinned owner key', () => {
    const bytes = encodeSealed(sealed)
    const decoded = decodeSealed(bytes)
    const opened = openShare(decoded, ther, owner.publicKey, ownerSigningFp, Date.now())
    expect(opened.checkIns).toEqual(bundle.checkIns)
  })

  it('throws (refuse-to-render) on a tampered ciphertext', () => {
    const tampered: SealedShare = { ...sealed, body: sealed.body.slice() }
    tampered.body[tampered.body.length - 1] ^= 0xff
    const bytes = encodeSealed(tampered)
    expect(() => openShare(decodeSealed(bytes), ther, owner.publicKey, ownerSigningFp, Date.now())).toThrow(ShareOpenError)
  })

  it('throws when the owner signing key is not the pinned one', () => {
    const attacker = newSignKeyPair()
    const bytes = encodeSealed(sealed)
    expect(() =>
      openShare(decodeSealed(bytes), ther, attacker.publicKey, fingerprint(attacker.publicKey), Date.now()),
    ).toThrow(ShareOpenError)
  })

  it('rejects a malformed envelope', () => {
    const bad = new TextEncoder().encode(JSON.stringify({ fmt: 9 }))
    expect(() => decodeSealed(bad)).toThrow(ShareOpenError)
  })

  it('bundleToBackupData materializes only curated fields (scores/bands, no raw items)', () => {
    const data = bundleToBackupData(bundle)
    expect(data.assessments?.length).toBe(1)
    expect(data.assessments?.[0]).toMatchObject({ key: 'wellbeing-selfcheck', score: 12, bandLabel: 'moderate' })
    expect(data.entries.length).toBe(1)
    // No raw item-response slot exists anywhere in the produced shape.
    expect(JSON.stringify(data)).not.toContain('item9')
    expect(JSON.stringify(data)).not.toContain('selfHarm')
  })
})
