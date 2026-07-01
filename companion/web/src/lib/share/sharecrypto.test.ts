import { describe, it, expect, beforeAll } from 'vitest'
import {
  transcript,
  serializeBundle,
  deserializeBundle,
  buildShare,
  openShare,
  ShareOpenError,
  ShareExpiredError,
  ShareUnpinnedError,
  type ShareBundle,
  type ShareMeta,
} from './sharecrypto'
import {
  initShareCrypto,
  newIdentity,
  publicOf,
  fingerprints,
  fingerprint,
  PinStore,
} from './pairing'
import _sodium from 'libsodium-wrappers-sumo'

function makeBundle(shareId: string, ownerFp: string): ShareBundle {
  return {
    schema: 1,
    shareId,
    scope: { from: 100, to: 200, recordTypes: ['checkIns', 'moods'] },
    ownerFp,
    checkIns: [{ instrumentId: 'wellbeing-selfcheck', at: 150, score: 12, band: 'moderate' }],
    moods: [{ at: 120, level: 3, note: 'ok day' }],
  }
}

describe('share crypto — build/seal/open + negative cases', () => {
  let owner: ReturnType<typeof newIdentity>
  let ther: ReturnType<typeof newIdentity>
  let pins: PinStore
  let meta: ShareMeta
  let ownerSigningFp: string
  let therX25519Fp: string
  let therEd25519Fp: string

  beforeAll(async () => {
    await initShareCrypto()
    owner = newIdentity()
    ther = newIdentity()
    const of = fingerprints(publicOf(owner))
    const tf = fingerprints(publicOf(ther))
    ownerSigningFp = of.ed25519Fp
    therX25519Fp = tf.x25519Fp
    therEd25519Fp = tf.ed25519Fp
    pins = new PinStore()
    pins.pin(publicOf(ther))
    meta = {
      context: 'daymark.share.v1',
      shareId: 'share-1',
      version: 1,
      recipientFp: therX25519Fp,
      expiry: 10_000,
      ownerSigningFp,
    }
  })

  function sealed() {
    return buildShare(makeBundle('share-1', fingerprint(owner.ed25519.publicKey)), meta, ther.x25519.publicKey, owner.ed25519, therEd25519Fp, pins)
  }

  it('1. ROUND-TRIP: pinned therapist opens to the original bundle', () => {
    const s = sealed()
    const out = openShare(s, ther.x25519, owner.ed25519.publicKey, ownerSigningFp, 5_000)
    expect(out).toEqual(makeBundle('share-1', fingerprint(owner.ed25519.publicKey)))
    // self-harm / item-9 slot is structurally absent
    expect(Object.keys(out)).not.toContain('selfHarm')
    expect(JSON.stringify(out)).not.toMatch(/item9|itemNine|selfHarm/i)
  })

  it('2. WRONG-RECIPIENT: a different therapist keypair cannot unseal', () => {
    const s = sealed()
    const stranger = newIdentity()
    expect(() => openShare(s, stranger.x25519, owner.ed25519.publicKey, ownerSigningFp, 5_000)).toThrow(ShareOpenError)
  })

  it('3a. FORGED-SIGNER: attacker signature over the transcript is rejected', () => {
    const s = sealed()
    const attacker = newIdentity()
    s.ownerSig = _sodium.crypto_sign_detached(transcript(meta), attacker.ed25519.privateKey)
    expect(() => openShare(s, ther.x25519, owner.ed25519.publicKey, ownerSigningFp, 5_000)).toThrow(ShareOpenError)
  })

  it('3b. KEY-SUBSTITUTION: valid attacker sig + attacker fp/pub is rejected by the pin gate', () => {
    const attacker = newIdentity()
    const attackerFp = fingerprint(attacker.ed25519.publicKey)
    const s = sealed()
    // Attacker re-points the envelope at their own owner identity and signs it.
    s.ownerSigningFp = attackerFp
    const attackerMeta: ShareMeta = { ...meta, ownerSigningFp: attackerFp }
    s.ownerSig = _sodium.crypto_sign_detached(transcript(attackerMeta), attacker.ed25519.privateKey)
    // Therapist verifies against the PINNED (real) owner fp → mismatch → reject.
    expect(() => openShare(s, ther.x25519, attacker.ed25519.publicKey, ownerSigningFp, 5_000)).toThrow(ShareOpenError)
  })

  it('4a. TAMPER: flipping a ciphertext byte fails AEAD', () => {
    const s = sealed()
    s.body[s.body.length - 1] ^= 0x01
    expect(() => openShare(s, ther.x25519, owner.ed25519.publicKey, ownerSigningFp, 5_000)).toThrow(ShareOpenError)
  })

  it('4b. TAMPER: mutating a bound meta field without re-signing breaks the transcript', () => {
    const s = sealed()
    s.version = 99 // was 1 — AAD/transcript now differs from what was signed/encrypted
    expect(() => openShare(s, ther.x25519, owner.ed25519.publicKey, ownerSigningFp, 5_000)).toThrow(ShareOpenError)
    const s2 = sealed()
    s2.expiry = 20_000
    expect(() => openShare(s2, ther.x25519, owner.ed25519.publicKey, ownerSigningFp, 5_000)).toThrow(ShareOpenError)
  })

  it('5. EXPIRY: past expiry throws; future expiry opens', () => {
    const s = sealed()
    expect(() => openShare(s, ther.x25519, owner.ed25519.publicKey, ownerSigningFp, 10_001)).toThrow(ShareExpiredError)
    expect(() => openShare(s, ther.x25519, owner.ed25519.publicKey, ownerSigningFp, 9_999)).not.toThrow()
  })

  it('buildShare refuses an unpinned therapist', () => {
    const emptyPins = new PinStore()
    expect(() =>
      buildShare(makeBundle('share-1', fingerprint(owner.ed25519.publicKey)), meta, ther.x25519.publicKey, owner.ed25519, therEd25519Fp, emptyPins),
    ).toThrow(ShareUnpinnedError)
  })

  it('buildShare refuses when meta.recipientFp mismatches the therapist X25519 key', () => {
    const badMeta: ShareMeta = { ...meta, recipientFp: 'not-the-real-fp' }
    expect(() =>
      buildShare(makeBundle('share-1', fingerprint(owner.ed25519.publicKey)), badMeta, ther.x25519.publicKey, owner.ed25519, therEd25519Fp, pins),
    ).toThrow(ShareUnpinnedError)
  })

  it('serializeBundle/deserializeBundle is canonical + stable', () => {
    const b = makeBundle('s', 'ownerfp')
    const bytes1 = serializeBundle(b)
    const bytes2 = serializeBundle(b)
    expect(Buffer.from(bytes1)).toEqual(Buffer.from(bytes2))
    expect(deserializeBundle(bytes1)).toEqual(b)
  })

  it('transcript is a fixed wire contract (conformance vector)', () => {
    const m: ShareMeta = { context: 'daymark.share.v1', shareId: 'abc', version: 2, recipientFp: 'RFP', expiry: 42, ownerSigningFp: 'OFP' }
    expect(new TextDecoder().decode(transcript(m))).toBe('daymark.share.v1|abc|2|RFP|42|OFP')
  })
})
