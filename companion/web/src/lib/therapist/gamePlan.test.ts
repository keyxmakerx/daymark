import { describe, it, expect, beforeAll } from 'vitest'
import _sodium from 'libsodium-wrappers-sumo'
import {
  newGamePlan,
  sealGamePlan,
  openGamePlan,
  supersede,
  withdraw,
  canonicalize,
  GamePlanOpenError,
  GAMEPLAN_CONTEXT,
  type GamePlanItem,
  type GamePlanPayload,
} from './gamePlan'
import { initAssignmentCrypto, newBoxKeyPair, newSignKeyPair, fingerprint, openedEnvelope } from '../assignments/crypto'
import { MIN_PADDED, pad, paddedLength } from '../padding'

const MiB = 1 << 20
/** What crypto_box_seal adds to what it seals: an ephemeral public key (32) and a MAC (16). */
const SEALBYTES = 48
const enc = new TextEncoder()
const dec = new TextDecoder()
const B64 = () => _sodium.base64_variants.URLSAFE_NO_PADDING

/** A length the padding could have produced for some input: the buckets, and nothing else. */
function isBucket(len: number): boolean {
  return len >= MIN_PADDED && paddedLength(len) === len
}

/** Byte-for-byte equality, cheap on large arrays (see padding.test.ts). */
function sameBytes(a: Uint8Array, b: Uint8Array): boolean {
  return Buffer.from(a.buffer, a.byteOffset, a.byteLength).equals(Buffer.from(b.buffer, b.byteOffset, b.byteLength))
}

/** The signed envelope sealGamePlan seals, built here by hand: canonical payload, then signature. */
function signedEnvelope(plan: GamePlanPayload, signer: { privateKey: Uint8Array }): Uint8Array {
  const payloadJson = dec.decode(canonicalize(plan))
  const sig = _sodium.crypto_sign_detached(enc.encode(payloadJson), signer.privateKey)
  return enc.encode(JSON.stringify({ payloadJson, sigB64: _sodium.to_base64(sig, B64()) }))
}

/** How long that envelope is for this plan. A signature is 64 bytes, 86 characters of base64. */
function envelopeLength(plan: GamePlanPayload): number {
  const payloadJson = dec.decode(canonicalize(plan))
  return enc.encode(JSON.stringify({ payloadJson, sigB64: 'A'.repeat(86) })).length
}

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

describe('game plans are padded inside the sealed box (#315)', () => {
  let owner: ReturnType<typeof newBoxKeyPair>
  let ther: ReturnType<typeof newSignKeyPair>
  let ownerFp: string
  let therFp: string

  beforeAll(async () => {
    await initAssignmentCrypto()
    owner = newBoxKeyPair()
    ther = newSignKeyPair()
    ownerFp = fingerprint(owner.publicKey)
    therFp = fingerprint(ther.publicKey)
  })

  /** A plan whose signed envelope is exactly `target` bytes: one item's detail made that long. */
  function planOfEnvelopeLength(target: number): GamePlanPayload {
    const base: GamePlanPayload = {
      ...newGamePlan(ownerFp, therFp, 1_758_800_000_000),
      items: [{ itemRef: 'g1', kind: 'goal', title: 'Short walk', detail: '' }],
    }
    const filler = target - envelopeLength(base) // 'a' needs no escaping: one character, one byte
    expect(filler).toBeGreaterThanOrEqual(0)
    const plan = { ...base, items: [{ ...base.items[0]!, detail: 'a'.repeat(filler) }] }
    expect(envelopeLength(plan)).toBe(target)
    return plan
  }

  it('every sealed game plan is a bucket size, whatever its length', () => {
    for (const target of [600, 4091, 4092, 4093, 8187, 8188, 8189, 70_000, MiB - 5, MiB - 4, MiB - 3]) {
      const blob = sealGamePlan(planOfEnvelopeLength(target), ther, owner.publicKey)
      const inBox = blob.length - SEALBYTES
      expect(isBucket(inBox), `envelope of ${target}`).toBe(true)
      expect(inBox, `envelope of ${target}`).toBe(paddedLength(4 + target))
      // What the box holds is the envelope padded: its length prefix is the envelope's length.
      const opened = _sodium.crypto_box_seal_open(blob, owner.publicKey, owner.privateKey)
      expect(new DataView(opened.buffer, opened.byteOffset, 4).getUint32(0, false)).toBe(target)
    }
  })

  it('the same check fails on a game plan sealed unpadded (positive control)', () => {
    const plan = planOfEnvelopeLength(5000)
    const unpadded = _sodium.crypto_box_seal(signedEnvelope(plan, ther), owner.publicKey)
    const inBox = unpadded.length - SEALBYTES
    expect(inBox).toBe(5000)
    expect(Number.isInteger(Math.log2(inBox))).toBe(false) // so its length is not already a bucket size
    expect(isBucket(inBox)).toBe(false)
  })

  it('round-trips one byte under, exactly on and one byte over each boundary, including 1 MiB', () => {
    // The 4-byte length prefix counts toward the bucket, so each boundary sits 4 bytes below it.
    for (let boundary = MIN_PADDED; boundary <= MiB; boundary *= 2) {
      for (const target of [boundary - 5, boundary - 4, boundary - 3]) {
        const plan = planOfEnvelopeLength(target)
        const opened = openGamePlan(sealGamePlan(plan, ther, owner.publicKey), owner, ther.publicKey)
        // The long field compared as a string: a failing toEqual would print a megabyte of it.
        expect(opened.items[0]!.detail === plan.items[0]!.detail, `envelope of ${target}`).toBe(true)
        expect({ ...opened, items: [] }).toEqual({ ...plan, items: [] })
      }
    }
  })

  it('a padded and an unpadded game plan verify against the same signed bytes', () => {
    const plan = { ...newGamePlan(ownerFp, therFp), items: [{ itemRef: 'g1', kind: 'goal' as const, title: 'Short walk' }] }
    const envelope = signedEnvelope(plan, ther)
    const unpadded = _sodium.crypto_box_seal(envelope, owner.publicKey)
    const padded = _sodium.crypto_box_seal(pad(envelope), owner.publicKey)
    const inUnpadded = _sodium.crypto_box_seal_open(unpadded, owner.publicKey, owner.privateKey)
    const inPadded = _sodium.crypto_box_seal_open(padded, owner.publicKey, owner.privateKey)
    // The two boxes hold different bytes, around one envelope, whose signed payload is the same.
    expect(sameBytes(inUnpadded, inPadded)).toBe(false)
    expect(sameBytes(openedEnvelope(inPadded), inUnpadded)).toBe(true)
    expect(sameBytes(openedEnvelope(inUnpadded), envelope)).toBe(true)
    expect(openGamePlan(padded, owner, ther.publicKey)).toEqual(plan)
    expect(openGamePlan(unpadded, owner, ther.publicKey)).toEqual(plan)
  })

  it('refuses a padded game plan whose padding pad() could not have produced', () => {
    const plan = { ...newGamePlan(ownerFp, therFp), items: [{ itemRef: 'g1', kind: 'goal' as const, title: 'Short walk' }] }
    const good = pad(signedEnvelope(plan, ther))
    expect(openGamePlan(_sodium.crypto_box_seal(good, owner.publicKey), owner, ther.publicKey)).toEqual(plan)
    const bad = good.slice()
    const last = bad.length - 1
    bad[last] = good[last] === 0 ? 1 : 0
    expect(bad[last]).not.toBe(good[last])
    expect(() => openGamePlan(_sodium.crypto_box_seal(bad, owner.publicKey), owner, ther.publicKey)).toThrow(/malformed/)
  })
})

describe('a game plan sealed unpadded, before #315, still opens', () => {
  /*
   * Sealed by sealGamePlan as it was before padding, with these fixed keys, and kept as bytes. A
   * sealed box is randomised, so the blob cannot be rebuilt; it is the stored item itself. The
   * therapist seed is the one sync-crypto's SyncCryptoTest.kt signs with, so the phone (#316)
   * already derives this key.
   */
  const OWNER_SEED = Uint8Array.from({ length: 32 }, (_, i) => (i * 7 + 3) & 0xff)
  const THERAPIST_SEED = Uint8Array.from({ length: 32 }, (_, i) => (i * 3 + 1) & 0xff)
  const OWNER_PUB = 'viuLdbNpuPRZuLFTeZvFqwei-P66BMEcyEPRn-Va4lw'
  const THERAPIST_PUB = 'XI7lgPfB0I-AHkchZYiWJfFtmPI3s6vfqFjFCJMUjh4'
  const PLAN: GamePlanPayload = {
    context: GAMEPLAN_CONTEXT,
    lineageId: 'gp-fixture',
    version: 0,
    supersedes: null,
    recipientOwnerFp: 'tzZ6t2NE9YF_t8JrkfzoYA',
    status: 'active',
    items: [{ itemRef: 'g1', kind: 'goal', title: 'Short walk', detail: 'after lunch' }],
    authorFingerprint: 'UEkiWzxtRDm-bbeZmqdreQ',
    issuedAt: 1_758_800_000_000,
  }
  const BLOB =
    'AGAADreL954jea6_oMv9k7kxagtvivU8pNHzC1yIrmYgeix07wBsXHn41zILXNkH0R4rrMVf1ei0yLqaeLLfTO8T5GiLjYzD1r6b6bDSd0L6hvuCVvO5JafVSj73fZHzPt4GFIBFq4FFEF5RiP4e35-qMzdGhsCCmj0OPqu-0Bm3ehT9bBNabE8JAE7ttzdwzTZatB7049uiWVL_cvBjc14p-mImFxdZopmA1zjz9ZhjZoN5TMYyBgMk4JS3LHlFJatw17v17unxSpRuRSOPfXiKgmbGnJJMbgck0GV-KAbdc8Ul1ZZwQyxKn6vr_rjRPspYKBYgT1WxsniKhUD7dxvzZPxF2Ydw5LXO8UgX6d_W-PR_nxl9wkRNF4d8gwYJLnGrYgHN9Yb9a4bDuF5oB5QxEBdFQPShk3T1r73P1wxQWmGMyZ-4d0qZPSLL55atSfGI7pVvhpWDkpCVqMDOtCZJAcjN22OQ1XpPBxlDXWQM3iW5BXGRBfwDAvBjgD3kh2lT1-EKnEEMDFAaChXT18YMioHXHnhh-K1s6KrcHpBvuRh9HXBCV52MAVJVMCAjaFtony1rmygaCJSGJciIYD9bZ0fe8iWmi5_0nzZZHLP29CiMNakR7SyxmkaaPJ5_eMiZYxRkcxy8roDpBdS6xY4fA_IvRdegXnCQmXaIkwjy'

  beforeAll(async () => {
    await initAssignmentCrypto()
  })

  it('opens to the plan it was sealed from', () => {
    const owner = _sodium.crypto_box_seed_keypair(OWNER_SEED)
    const ther = _sodium.crypto_sign_seed_keypair(THERAPIST_SEED)
    expect(_sodium.to_base64(owner.publicKey, B64())).toBe(OWNER_PUB)
    expect(_sodium.to_base64(ther.publicKey, B64())).toBe(THERAPIST_PUB)
    const blob = _sodium.from_base64(BLOB, B64())
    // It really is the unpadded form: the box holds JSON, and its length is no bucket size.
    const inBox = _sodium.crypto_box_seal_open(blob, owner.publicKey, owner.privateKey)
    expect(inBox[0]).toBe(0x7b)
    expect(isBucket(inBox.length)).toBe(false)
    expect(openGamePlan(blob, owner, ther.publicKey)).toEqual(PLAN)
  })
})
