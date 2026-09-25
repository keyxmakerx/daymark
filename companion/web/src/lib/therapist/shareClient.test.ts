import { describe, it, expect, beforeAll } from 'vitest'
import { decodeSealed, fetchShare, bundleToBackupData, ShareOpenError, ShareFormatError, ShareOlderError } from './shareClient'
import { newestOpened, type SeenStorage } from './shareSeen'
import type { PortalClient, SessionInfo } from './session'
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
    fmt: s.fmt, shareId: s.shareId, version: s.version, createdAt: s.createdAt, expiry: s.expiry,
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
    const meta: ShareMeta = { context: 'daymark.share.v2', shareId: 's1', version: 0, recipientFp, createdAt: 1_000, expiry: 9e15, ownerSigningFp }
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
    expect(() => decodeSealed(new TextEncoder().encode('not json'))).toThrow(ShareOpenError)
    const o = JSON.parse(new TextDecoder().decode(encodeSealed(sealed)))
    expect(() => decodeSealed(new TextEncoder().encode(JSON.stringify({ ...o, body: '***' })))).toThrow(ShareOpenError)
    expect(() => decodeSealed(new TextEncoder().encode(JSON.stringify({ ...o, version: '0' })))).toThrow(ShareOpenError)
  })

  it('refuses a format-1 envelope as a format, so the screen can say so', () => {
    const o = JSON.parse(new TextDecoder().decode(encodeSealed(sealed)))
    expect(o.fmt).toBe(2) // so setting 1 really is a change
    expect(() => decodeSealed(new TextEncoder().encode(JSON.stringify({ ...o, fmt: 1 })))).toThrow(ShareFormatError)
  })

  describe('the signed version must be the version the server serves it under', () => {
    const client = (version: number) =>
      ({ getCurrent: async () => ({ version, bytes: encodeSealed(sealed) }) }) as unknown as PortalClient
    const session = {} as SessionInfo

    it('opens when they match (positive control)', async () => {
      expect(sealed.version).toBe(0)
      const opened = await fetchShare(client(0), session, ther, owner.publicKey, ownerSigningFp, Date.now())
      expect(opened?.checkIns).toEqual(bundle.checkIns)
    })

    it('refuses when the server serves it as another version', async () => {
      await expect(fetchShare(client(3), session, ther, owner.publicKey, ownerSigningFp, Date.now())).rejects.toThrow(/different version/)
    })
  })

  describe('a share sealed before one this browser already opened stays closed', () => {
    const session = { relRef: 'rel-1' } as SessionInfo
    const memory = (): SeenStorage => {
      const data = new Map<string, string>()
      return { getItem: (k) => data.get(k) ?? null, setItem: (k, v) => void data.set(k, v) }
    }
    /** The same bundle sealed at `createdAt` as `version`, served as that version. */
    function served(createdAt: number, version: number): PortalClient {
      const therSign = newSignKeyPair()
      const pins = new PinStore()
      pins.pin({ x25519Pub: ther.publicKey, ed25519Pub: therSign.publicKey })
      const meta: ShareMeta = {
        context: 'daymark.share.v2', shareId: 's1', version, recipientFp: fingerprint(ther.publicKey),
        createdAt, expiry: 9e15, ownerSigningFp,
      }
      const s = buildShare(bundle, meta, ther.publicKey, owner, fingerprint(therSign.publicKey), pins)
      return { getCurrent: async () => ({ version, bytes: encodeSealed(s) }) } as unknown as PortalClient
    }
    const fetchWith = (c: PortalClient, seen: SeenStorage) =>
      fetchShare(c, session, ther, owner.publicKey, ownerSigningFp, Date.now(), seen)

    it('refuses the older copy once a newer one has opened, and the refusal moves nothing', async () => {
      const seen = memory()
      expect((await fetchWith(served(2_000, 1), seen))?.shareId).toBe('s1')
      await expect(fetchWith(served(1_000, 0), seen)).rejects.toThrow(ShareOlderError)
      expect(newestOpened('rel-1', ther, seen)).toBe(2_000)
    })

    it('opens the same share again and a later one, and an older one in a browser that never saw the newer (positive control)', async () => {
      const seen = memory()
      await fetchWith(served(2_000, 1), seen)
      expect((await fetchWith(served(2_000, 1), seen))?.shareId).toBe('s1')
      expect((await fetchWith(served(3_000, 2), seen))?.shareId).toBe('s1')
      expect(newestOpened('rel-1', ther, seen)).toBe(3_000)
      expect((await fetchWith(served(1_000, 0), memory()))?.shareId).toBe('s1')
    })

    it('a share refused for any reason never moves the mark, so a forged future time cannot lock out real shares', async () => {
      const seen = memory()
      await fetchWith(served(2_000, 1), seen)
      const genuine = served(3_000, 2)
      const current = await genuine.getCurrent(session, 'shares', 'share')
      const o = JSON.parse(new TextDecoder().decode(current!.bytes))
      const forged = { ...o, createdAt: o.createdAt === 9e12 ? 9e12 + 1 : 9e12 }
      expect(forged.createdAt).not.toBe(o.createdAt)
      const client = { getCurrent: async () => ({ version: 2, bytes: new TextEncoder().encode(JSON.stringify(forged)) }) } as unknown as PortalClient
      await expect(fetchWith(client, seen)).rejects.toThrow(/signature/)
      expect(newestOpened('rel-1', ther, seen)).toBe(2_000)
      expect((await fetchWith(genuine, seen))?.shareId).toBe('s1') // the genuine newer share still opens
    })

    it('a share sealed on a device whose clock ran ahead cannot lock out the shares after it', async () => {
      const seen = memory()
      const now = 1_800_000_000_000
      const yearAhead = now + 365 * 24 * 60 * 60 * 1000
      const openAt = (c: PortalClient, at: number) => fetchShare(c, session, ther, owner.publicKey, ownerSigningFp, at, seen)
      expect((await openAt(served(yearAhead, 1), now))?.shareId).toBe('s1')
      expect(newestOpened('rel-1', ther, seen)).toBe(now) // the mark stops at this browser's clock
      // Sealed after the owner's clock was put right: later than the mark, so it opens.
      expect((await openAt(served(now + 5_000, 2), now + 10_000))?.shareId).toBe('s1')
      // The guard itself still holds (positive control).
      await expect(openAt(served(now - 1, 3), now + 20_000)).rejects.toThrow(ShareOlderError)
    })

    it('goes by when it was sealed, not by version number, which a restored server could reuse', async () => {
      const seen = memory()
      await fetchWith(served(2_000, 5), seen)
      expect((await fetchWith(served(3_000, 1), seen))?.shareId).toBe('s1') // lower number, sealed later
    })
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
