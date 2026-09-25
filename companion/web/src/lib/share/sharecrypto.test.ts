import { describe, it, expect, beforeAll } from 'vitest'
import {
  transcript,
  signedMessage,
  serializeBundle,
  deserializeBundle,
  buildShare,
  openShare,
  ShareOpenError,
  ShareExpiredError,
  ShareFormatError,
  ShareUnpinnedError,
  SHARE_CONTEXT,
  type SealedShare,
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
import { pad, paddedLength } from '../padding'
import _sodium from 'libsodium-wrappers-sumo'

function makeBundle(shareId: string, ownerFp: string, note = 'ok day'): ShareBundle {
  return {
    schema: 1,
    shareId,
    scope: { from: 100, to: 200, recordTypes: ['checkIns', 'moods'] },
    ownerFp,
    checkIns: [{ instrumentId: 'wellbeing-selfcheck', at: 150, score: 12, band: 'moderate' }],
    moods: [{ at: 120, level: 3, note }],
  }
}

const AEAD = () => ({
  nonce: _sodium.crypto_aead_xchacha20poly1305_ietf_NPUBBYTES,
  tag: _sodium.crypto_aead_xchacha20poly1305_ietf_ABYTES,
  key: _sodium.crypto_aead_xchacha20poly1305_ietf_KEYBYTES,
})

/**
 * Encrypt `bundle` under a fresh CEK sealed to the therapist, exactly as buildShare does, and
 * return the body and wrapped key. What anyone holding the therapist's PUBLIC key can make.
 */
function encryptAs(bundle: ShareBundle, aad: Uint8Array, therapistX25519Pub: Uint8Array) {
  const cek = _sodium.randombytes_buf(AEAD().key)
  const nonce = _sodium.randombytes_buf(AEAD().nonce)
  const ct = _sodium.crypto_aead_xchacha20poly1305_ietf_encrypt(pad(serializeBundle(bundle)), aad, null, nonce, cek)
  const body = new Uint8Array(nonce.length + ct.length)
  body.set(nonce, 0)
  body.set(ct, nonce.length)
  return { cek, body, wrappedCEK: _sodium.crypto_box_seal(cek, therapistX25519Pub) }
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
      context: SHARE_CONTEXT,
      shareId: 'share-1',
      version: 1,
      recipientFp: therX25519Fp,
      expiry: 10_000,
      ownerSigningFp,
    }
  })

  function sealed(): SealedShare {
    return buildShare(makeBundle('share-1', ownerSigningFp), meta, ther.x25519.publicKey, owner.ed25519, therEd25519Fp, pins)
  }
  const open = (s: SealedShare, now = 5_000) => openShare(s, ther.x25519, owner.ed25519.publicKey, ownerSigningFp, now)

  it('1. ROUND-TRIP: pinned therapist opens to the original bundle', () => {
    const out = open(sealed())
    expect(out).toEqual(makeBundle('share-1', ownerSigningFp))
    // self-harm / item-9 slot is structurally absent
    expect(Object.keys(out)).not.toContain('selfHarm')
    expect(JSON.stringify(out)).not.toMatch(/item9|itemNine|selfHarm/i)
  })

  it('2. WRONG-RECIPIENT: a different therapist keypair cannot open it', () => {
    const stranger = newIdentity()
    expect(() => openShare(sealed(), stranger.x25519, owner.ed25519.publicKey, ownerSigningFp, 5_000)).toThrow(ShareOpenError)
  })

  it('3a. FORGED-SIGNER: an attacker signature over the envelope is rejected', () => {
    const s = sealed()
    const attacker = newIdentity()
    s.ownerSig = _sodium.crypto_sign_detached(signedMessage(transcript(meta), s.body, s.wrappedCEK), attacker.ed25519.privateKey)
    expect(() => open(s)).toThrow(/signature/)
  })

  it('3b. KEY-SUBSTITUTION: valid attacker sig + attacker fp/pub is rejected by the pin gate', () => {
    const attacker = newIdentity()
    const attackerFp = fingerprint(attacker.ed25519.publicKey)
    const s = sealed()
    // Attacker re-points the envelope at their own owner identity and signs it.
    s.ownerSigningFp = attackerFp
    const attackerMeta: ShareMeta = { ...meta, ownerSigningFp: attackerFp }
    s.ownerSig = _sodium.crypto_sign_detached(signedMessage(transcript(attackerMeta), s.body, s.wrappedCEK), attacker.ed25519.privateKey)
    // Therapist verifies against the PINNED (real) owner fp → mismatch → reject.
    expect(() => openShare(s, ther.x25519, attacker.ed25519.publicKey, ownerSigningFp, 5_000)).toThrow(ShareOpenError)
  })

  describe('4. THE CONTENT SWAP: the owner signature covers the contents, not only the transcript', () => {
    /*
     * The forgery a transcript-only signature allowed: keep the owner's transcript and signature,
     * seal a CEK of your own to the therapist's public key (crypto_box_seal is anonymous, so
     * anyone can), and put new content under it with the same associated data.
     */
    function forge() {
      const s = sealed()
      const words = makeBundle('share-1', ownerSigningFp, 'FORGED: not written by the owner')
      const f = encryptAs(words, transcript(meta), ther.x25519.publicKey)
      return { s, forged: { ...s, body: f.body, wrappedCEK: f.wrappedCEK }, f }
    }

    it('the forged envelope is otherwise well-formed: it decrypts to the forged words (positive control)', () => {
      const { s, forged, f } = forge()
      expect(Buffer.from(forged.body).equals(Buffer.from(s.body))).toBe(false)
      const nb = AEAD().nonce
      const padded = _sodium.crypto_aead_xchacha20poly1305_ietf_decrypt(null, forged.body.subarray(nb), transcript(meta), forged.body.subarray(0, nb), f.cek)
      expect(new TextDecoder().decode(padded)).toContain('FORGED')
    })

    it('is refused, at the signature, before anything is decrypted', () => {
      const { forged } = forge()
      expect(() => open(forged)).toThrow(/signature/)
    })

    it('swapping only the body, or only the wrapped key, is refused at the signature too', () => {
      const { s, forged } = forge()
      expect(() => open({ ...s, body: forged.body })).toThrow(/signature/)
      expect(() => open({ ...s, wrappedCEK: forged.wrappedCEK })).toThrow(/signature/)
    })

    it('flipping one ciphertext byte is caught by the signature, not first by the AEAD', () => {
      const s = sealed()
      s.body[s.body.length - 1] ^= 0x01
      expect(() => open(s)).toThrow(/signature/)
    })
  })

  it('5. TAMPER: mutating a bound meta field without re-signing is refused', () => {
    const s = sealed()
    s.version = 99 // was 1
    expect(() => open(s)).toThrow(ShareOpenError)
    const s2 = sealed()
    s2.expiry = 20_000
    expect(() => open(s2)).toThrow(ShareOpenError)
  })

  it('6. EXPIRY: past expiry throws; future expiry opens', () => {
    const s = sealed()
    expect(() => open(s, 10_001)).toThrow(ShareExpiredError)
    expect(() => open(s, 9_999)).not.toThrow()
  })

  it('7. FORMAT 1 is refused as a format, not opened', () => {
    const s = sealed() as unknown as Record<string, unknown>
    expect(() => open({ ...s, fmt: 1 } as unknown as SealedShare)).toThrow(ShareFormatError)
    expect(() => open({ ...s, fmt: 3 } as unknown as SealedShare)).toThrow(ShareFormatError)
  })

  it('8. MALFORMED fields are refused before any key is used', () => {
    const cases: Array<Partial<SealedShare>> = [
      { shareId: 'share|1' },
      { shareId: '' },
      { version: -1 },
      { version: 1.5 },
      { expiry: Number.MAX_SAFE_INTEGER + 2 },
      { recipientFp: 'has space' },
      { ownerSig: new Uint8Array(63) },
      { wrappedCEK: new Uint8Array(79) },
      { body: new Uint8Array(10) },
    ]
    for (const c of cases) expect(() => open({ ...sealed(), ...c })).toThrow(/malformed/)
  })

  it('9. PADDING: the body says only which size bucket the share is in', () => {
    const overhead = AEAD().nonce + AEAD().tag
    const small = buildShare(makeBundle('share-1', ownerSigningFp, 'a'), meta, ther.x25519.publicKey, owner.ed25519, therEd25519Fp, pins)
    const larger = buildShare(makeBundle('share-1', ownerSigningFp, 'a'.repeat(3000)), meta, ther.x25519.publicKey, owner.ed25519, therEd25519Fp, pins)
    const plainLen = serializeBundle(makeBundle('share-1', ownerSigningFp, 'a')).length
    // Positive control: unpadded, this share would not be a bucket size at all.
    expect(paddedLength(plainLen)).not.toBe(plainLen)
    expect(small.body.length - overhead).toBe(paddedLength(4 + plainLen))
    expect(larger.body.length).toBe(small.body.length)
    expect(open(larger).moods?.[0].note).toBe('a'.repeat(3000))
  })

  it('10. the contents must name the share and owner of their envelope', () => {
    // Only the owner can make this (the signature is theirs); the check is defence in depth.
    const aad = transcript(meta)
    const wrong = encryptAs(makeBundle('another-share', ownerSigningFp), aad, ther.x25519.publicKey)
    const ownerSig = _sodium.crypto_sign_detached(signedMessage(aad, wrong.body, wrong.wrappedCEK), owner.ed25519.privateKey)
    const s: SealedShare = { ...sealed(), body: wrong.body, wrappedCEK: wrong.wrappedCEK, ownerSig }
    expect(() => open(s)).toThrow(/different share or owner/)
    // and buildShare will not make one
    expect(() =>
      buildShare(makeBundle('another-share', ownerSigningFp), meta, ther.x25519.publicKey, owner.ed25519, therEd25519Fp, pins),
    ).toThrow(/different share or owner/)
  })

  it('buildShare refuses an unpinned therapist', () => {
    const emptyPins = new PinStore()
    expect(() =>
      buildShare(makeBundle('share-1', ownerSigningFp), meta, ther.x25519.publicKey, owner.ed25519, therEd25519Fp, emptyPins),
    ).toThrow(ShareUnpinnedError)
  })

  it('buildShare refuses when meta.recipientFp mismatches the therapist X25519 key', () => {
    const badMeta: ShareMeta = { ...meta, recipientFp: 'not-the-real-fp' }
    expect(() =>
      buildShare(makeBundle('share-1', ownerSigningFp), badMeta, ther.x25519.publicKey, owner.ed25519, therEd25519Fp, pins),
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
    const m: ShareMeta = { context: SHARE_CONTEXT, shareId: 'abc', version: 2, recipientFp: 'RFP', expiry: 42, ownerSigningFp: 'OFP' }
    expect(new TextDecoder().decode(transcript(m))).toBe('daymark.share.v2|abc|2|RFP|42|OFP')
  })

  it('the signed message is a fixed wire contract: domain, zero byte, three BLAKE2b-256 hashes', () => {
    const aad = new TextEncoder().encode('t')
    const body = new Uint8Array([1, 2, 3])
    const key = new Uint8Array([4])
    const m = signedMessage(aad, body, key)
    const domain = new TextEncoder().encode('daymark.share.v2/owner-signature')
    expect(m.length).toBe(domain.length + 1 + 96)
    expect(Buffer.from(m.subarray(0, domain.length)).equals(Buffer.from(domain))).toBe(true)
    expect(m[domain.length]).toBe(0)
    expect(Buffer.from(m.subarray(domain.length + 1 + 32, domain.length + 1 + 64)).equals(Buffer.from(_sodium.crypto_generichash(32, body)))).toBe(true)
    // each part moves the message
    expect(Buffer.from(signedMessage(aad, new Uint8Array([1, 2, 4]), key)).equals(Buffer.from(m))).toBe(false)
    expect(Buffer.from(signedMessage(aad, body, new Uint8Array([5]))).equals(Buffer.from(m))).toBe(false)
  })
})
