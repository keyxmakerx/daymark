import { describe, it, expect, beforeAll } from 'vitest'
import { validateAssignment, shouldAutoApply } from './validate'
import _sodium from 'libsodium-wrappers-sumo'
import {
  initAssignmentCrypto,
  newSignKeyPair,
  newBoxKeyPair,
  sealAssignment,
  openAssignment,
  openedEnvelope,
  padEnvelope,
  fingerprint,
  ASSIGNMENT_CONTEXT,
  AssignmentOpenError,
} from './crypto'
import type { Assignment, Grant } from './types'
import { MIN_PADDED, pad, paddedLength, PaddingError } from '../padding'

const MiB = 1 << 20
/** What crypto_box_seal adds to what it seals: an ephemeral public key (32) and a MAC (16). */
const SEALBYTES = 48

/** A length the padding could have produced for some input: the buckets, and nothing else. */
function isBucket(len: number): boolean {
  return len >= MIN_PADDED && paddedLength(len) === len
}

/** Byte-for-byte equality, cheap on large arrays (see padding.test.ts). */
function sameBytes(a: Uint8Array, b: Uint8Array): boolean {
  return Buffer.from(a.buffer, a.byteOffset, a.byteLength).equals(Buffer.from(b.buffer, b.byteOffset, b.byteLength))
}

/** The signed payload sealAssignment signs, built here by hand. */
function payloadJsonFor(a: Assignment, ownerBoxPub: Uint8Array): string {
  return JSON.stringify({ context: ASSIGNMENT_CONTEXT, recipientOwnerFp: fingerprint(ownerBoxPub), assignment: a })
}

/** The signed envelope sealAssignment seals, built here by hand: payload, then signature. */
function signedEnvelope(a: Assignment, signer: { privateKey: Uint8Array }, ownerBoxPub: Uint8Array): Uint8Array {
  const so = _sodium
  const payloadJson = payloadJsonFor(a, ownerBoxPub)
  const sig = so.crypto_sign_detached(so.from_string(payloadJson), signer.privateKey)
  return so.from_string(JSON.stringify({ payloadJson, sigB64: so.to_base64(sig, so.base64_variants.URLSAFE_NO_PADDING) }))
}

/** How long that envelope is. A signature is 64 bytes, 86 characters of base64. */
function envelopeLength(a: Assignment, ownerBoxPub: Uint8Array): number {
  const payloadJson = payloadJsonFor(a, ownerBoxPub)
  return new TextEncoder().encode(JSON.stringify({ payloadJson, sigB64: 'A'.repeat(86) })).length
}

function grantFor(fp: string): Grant {
  return {
    therapistFingerprint: fp,
    capabilities: {
      'assign.questionnaire': { granted: true, apply: 'propose' },
      'assign.task': { granted: true, apply: 'auto' },
      'suggest.setting': { granted: true, apply: 'auto' }, // auto here must still be overridden for settings
    },
  }
}

function baseAssignment(fp: string, over: Partial<Assignment> = {}): Assignment {
  return {
    assignmentId: 'a1', lineageId: 'laptop', version: 0, type: 'questionnaire',
    capability: 'assign.questionnaire', payload: { instrumentId: 'wellbeing-selfcheck' },
    issuedAt: 1, authorFingerprint: fp, ...over,
  }
}

describe('assignment validation (capability gate)', () => {
  const FP = 'therapist-fp'

  it('accepts a granted questionnaire assignment for a catalog instrument', () => {
    const r = validateAssignment(baseAssignment(FP), grantFor(FP))
    expect(r.ok).toBe(true)
    expect(r.applyMode).toBe('propose')
  })

  it('rejects an assignment whose capability is not granted', () => {
    const g = grantFor(FP)
    delete g.capabilities['assign.questionnaire']
    expect(validateAssignment(baseAssignment(FP), g).ok).toBe(false)
  })

  it('rejects a type/capability mismatch', () => {
    const a = baseAssignment(FP, { type: 'task', capability: 'assign.questionnaire' })
    expect(validateAssignment(a, grantFor(FP)).ok).toBe(false)
  })

  it('rejects a non-catalog instrument', () => {
    const a = baseAssignment(FP, { payload: { instrumentId: 'made-up' } })
    expect(validateAssignment(a, grantFor(FP)).ok).toBe(false)
  })

  it('rejects a setting key not on the allowlist (e.g. security keys)', () => {
    const a = baseAssignment(FP, { type: 'setting', capability: 'suggest.setting', payload: { key: 'pin', value: '1234' } })
    expect(validateAssignment(a, grantFor(FP)).ok).toBe(false)
  })

  it('accepts an allowlisted setting but NEVER auto-applies it', () => {
    const a = baseAssignment(FP, { type: 'setting', capability: 'suggest.setting', payload: { key: 'theme', value: 'dark' } })
    const r = validateAssignment(a, grantFor(FP))
    expect(r.ok).toBe(true)
    expect(shouldAutoApply(a, r.applyMode!)).toBe(false) // settings are always propose→accept
  })

  it('honours auto for a low-risk capability', () => {
    const a = baseAssignment(FP, { type: 'task', capability: 'assign.task', payload: { taskId: 'steady-attention' } })
    const r = validateAssignment(a, grantFor(FP))
    expect(r.ok).toBe(true)
    expect(shouldAutoApply(a, r.applyMode!)).toBe(true)
  })

  it('rejects an author that does not match the granted therapist', () => {
    expect(validateAssignment(baseAssignment('someone-else'), grantFor(FP)).ok).toBe(false)
  })
})

describe('assignment crypto (sign + seal → owner)', () => {
  beforeAll(async () => {
    await initAssignmentCrypto()
  })

  it('round-trips: therapist seals, owner opens + verifies', () => {
    const therapist = newSignKeyPair()
    const owner = newBoxKeyPair()
    const a = baseAssignment(fingerprint(therapist.publicKey))
    const blob = sealAssignment(a, therapist, owner.publicKey)
    const got = openAssignment(blob, owner, therapist.publicKey)
    expect(got.assignmentId).toBe('a1')
    expect(got.payload).toEqual({ instrumentId: 'wellbeing-selfcheck' })
  })

  it('a different owner cannot open the sealed box', () => {
    const therapist = newSignKeyPair()
    const owner = newBoxKeyPair()
    const other = newBoxKeyPair()
    const blob = sealAssignment(baseAssignment('fp'), therapist, owner.publicKey)
    expect(() => openAssignment(blob, other, therapist.publicKey)).toThrow(AssignmentOpenError)
  })

  it('rejects a signature from an unpinned (forged) therapist key', () => {
    const therapist = newSignKeyPair()
    const attacker = newSignKeyPair()
    const owner = newBoxKeyPair()
    const blob = sealAssignment(baseAssignment('fp'), attacker, owner.publicKey) // signed by attacker
    expect(() => openAssignment(blob, owner, therapist.publicKey)).toThrow(/signature/) // verified against pinned therapist
  })

  it('detects tampering of the sealed blob', () => {
    const therapist = newSignKeyPair()
    const owner = newBoxKeyPair()
    const blob = sealAssignment(baseAssignment('fp'), therapist, owner.publicKey)
    blob[blob.length - 2] ^= 0x01
    expect(() => openAssignment(blob, owner, therapist.publicKey)).toThrow(AssignmentOpenError)
  })

  it('rejects a signed assignment whose recipientOwnerFp is a DIFFERENT owner (splice)', () => {
    // Splice: the therapist signs a payload addressed to owner A (recipientOwnerFp = A), but the
    // envelope is sealed to owner B's box. B CAN open the sealed box and the signature is valid,
    // but the bound recipientOwnerFp does not match B — open must reject (mis-addressed / spliced).
    const so = _sodium
    const therapist = newSignKeyPair()
    const ownerA = newBoxKeyPair()
    const ownerB = newBoxKeyPair()
    const B64 = so.base64_variants.URLSAFE_NO_PADDING
    const signed = {
      context: ASSIGNMENT_CONTEXT,
      recipientOwnerFp: fingerprint(ownerA.publicKey), // addressed to A
      assignment: baseAssignment(fingerprint(therapist.publicKey)),
    }
    const payloadJson = JSON.stringify(signed)
    const sig = so.crypto_sign_detached(so.from_string(payloadJson), therapist.privateKey)
    const envelope = so.from_string(JSON.stringify({ payloadJson, sigB64: so.to_base64(sig, B64) }))
    const blobToB = so.crypto_box_seal(envelope, ownerB.publicKey) // but sealed to B
    // B opens the box + verifies the sig fine, yet the recipientOwnerFp binding rejects it.
    expect(() => openAssignment(blobToB, ownerB, therapist.publicKey)).toThrow(/different owner/)
  })

  it('rejects a wrong-context payload (message-type confusion)', () => {
    const so = _sodium
    const therapist = newSignKeyPair()
    const owner = newBoxKeyPair()
    const B64 = so.base64_variants.URLSAFE_NO_PADDING
    const signed = {
      context: 'daymark.gameplan.v1', // wrong context bound + validly signed + correct recipient
      recipientOwnerFp: fingerprint(owner.publicKey),
      assignment: baseAssignment(fingerprint(therapist.publicKey)),
    }
    const payloadJson = JSON.stringify(signed)
    const sig = so.crypto_sign_detached(so.from_string(payloadJson), therapist.privateKey)
    const envelope = so.from_string(JSON.stringify({ payloadJson, sigB64: so.to_base64(sig, B64) }))
    const blob = so.crypto_box_seal(envelope, owner.publicKey)
    expect(() => openAssignment(blob, owner, therapist.publicKey)).toThrow(/context/)
  })
})

describe('assignments are padded inside the sealed box (#315)', () => {
  let therapist: ReturnType<typeof newSignKeyPair>
  let owner: ReturnType<typeof newBoxKeyPair>

  beforeAll(async () => {
    await initAssignmentCrypto()
    therapist = newSignKeyPair()
    owner = newBoxKeyPair()
  })

  /** An assignment whose signed envelope is exactly `target` bytes: its note made that long. */
  function assignmentOfEnvelopeLength(target: number): Assignment {
    const base = baseAssignment(fingerprint(therapist.publicKey), { note: '' })
    const filler = target - envelopeLength(base, owner.publicKey) // 'a' needs no escaping: one byte each
    expect(filler).toBeGreaterThanOrEqual(0)
    const a = { ...base, note: 'a'.repeat(filler) }
    expect(envelopeLength(a, owner.publicKey)).toBe(target)
    return a
  }

  it('every sealed assignment is a bucket size, whatever its length', () => {
    for (const target of [600, 4091, 4092, 4093, 8187, 8188, 8189, 70_000, MiB - 5, MiB - 4, MiB - 3]) {
      const blob = sealAssignment(assignmentOfEnvelopeLength(target), therapist, owner.publicKey)
      const inBox = blob.length - SEALBYTES
      expect(isBucket(inBox), `envelope of ${target}`).toBe(true)
      expect(inBox, `envelope of ${target}`).toBe(paddedLength(4 + target))
      // What the box holds is the envelope padded: its length prefix is the envelope's length.
      const opened = _sodium.crypto_box_seal_open(blob, owner.publicKey, owner.privateKey)
      expect(new DataView(opened.buffer, opened.byteOffset, 4).getUint32(0, false)).toBe(target)
    }
  })

  it('the same check fails on an assignment sealed unpadded (positive control)', () => {
    const a = assignmentOfEnvelopeLength(5000)
    const unpadded = _sodium.crypto_box_seal(signedEnvelope(a, therapist, owner.publicKey), owner.publicKey)
    const inBox = unpadded.length - SEALBYTES
    expect(inBox).toBe(5000)
    expect(Number.isInteger(Math.log2(inBox))).toBe(false) // so its length is not already a bucket size
    expect(isBucket(inBox)).toBe(false)
  })

  it('round-trips one byte under, exactly on and one byte over each boundary, including 1 MiB', () => {
    // The 4-byte length prefix counts toward the bucket, so each boundary sits 4 bytes below it.
    for (let boundary = MIN_PADDED; boundary <= MiB; boundary *= 2) {
      for (const target of [boundary - 5, boundary - 4, boundary - 3]) {
        const a = assignmentOfEnvelopeLength(target)
        const got = openAssignment(sealAssignment(a, therapist, owner.publicKey), owner, therapist.publicKey)
        // The long field compared as a string: a failing toEqual would print a megabyte of it.
        expect(got.note === a.note, `envelope of ${target}`).toBe(true)
        expect({ ...got, note: '' }).toEqual({ ...a, note: '' })
      }
    }
  })

  it('a padded and an unpadded assignment verify against the same signed bytes', () => {
    const a = baseAssignment(fingerprint(therapist.publicKey))
    const envelope = signedEnvelope(a, therapist, owner.publicKey)
    const unpadded = _sodium.crypto_box_seal(envelope, owner.publicKey)
    const padded = _sodium.crypto_box_seal(pad(envelope), owner.publicKey)
    const inUnpadded = _sodium.crypto_box_seal_open(unpadded, owner.publicKey, owner.privateKey)
    const inPadded = _sodium.crypto_box_seal_open(padded, owner.publicKey, owner.privateKey)
    // The two boxes hold different bytes, around one envelope, whose signed payload is the same.
    expect(sameBytes(inUnpadded, inPadded)).toBe(false)
    expect(sameBytes(openedEnvelope(inPadded), inUnpadded)).toBe(true)
    expect(sameBytes(openedEnvelope(inUnpadded), envelope)).toBe(true)
    expect(openAssignment(padded, owner, therapist.publicKey)).toEqual(a)
    expect(openAssignment(unpadded, owner, therapist.publicKey)).toEqual(a)
  })

  it('refuses a padded assignment whose padding pad() could not have produced', () => {
    const a = baseAssignment(fingerprint(therapist.publicKey))
    const good = pad(signedEnvelope(a, therapist, owner.publicKey))
    expect(openAssignment(_sodium.crypto_box_seal(good, owner.publicKey), owner, therapist.publicKey)).toEqual(a)
    const bad = good.slice()
    const last = bad.length - 1
    bad[last] = good[last] === 0 ? 1 : 0
    expect(bad[last]).not.toBe(good[last])
    expect(() => openAssignment(_sodium.crypto_box_seal(bad, owner.publicKey), owner, therapist.publicKey)).toThrow(/malformed/)
  })

  it('the first byte in the box tells the forms apart, and a padded envelope never begins with "{"', () => {
    const envelope = signedEnvelope(baseAssignment('fp'), therapist, owner.publicKey)
    expect(envelope[0]).toBe(0x7b) // an unpadded envelope is a JSON object
    expect(padEnvelope(envelope)[0]).not.toBe(0x7b) // a padded one starts with its length prefix
    expect(sameBytes(padEnvelope(envelope), pad(envelope))).toBe(true)
    // The top byte of the u32 prefix is '{' from 0x7B000000 bytes on, so padEnvelope refuses an
    // envelope that long.
    const prefix = new DataView(new ArrayBuffer(4))
    prefix.setUint32(0, 0x7b000000 - 1, false)
    expect(prefix.getUint8(0)).toBe(0x7a)
    prefix.setUint32(0, 0x7b000000, false)
    expect(prefix.getUint8(0)).toBe(0x7b)
    // A stand-in with only a length, so nothing that size is built. Reading any of its bytes
    // throws a plain Error, which is what would happen next if the refusal were missing.
    const tooLong = new Proxy(
      { length: 0x7b000000 },
      {
        get: (target, key) => {
          if (key === 'length') return target.length
          throw new Error(`read ${String(key)}`)
        },
      },
    ) as unknown as Uint8Array
    expect(() => padEnvelope(tooLong)).toThrow(PaddingError)
  })
})

describe('an assignment sealed unpadded, before #315, still opens', () => {
  /*
   * Sealed by sealAssignment as it was before padding, with these fixed keys, and kept as bytes. A
   * sealed box is randomised, so the blob cannot be rebuilt; it is the stored item itself. The
   * therapist seed is the one sync-crypto's SyncCryptoTest.kt signs with, so the phone (#316)
   * already derives this key.
   */
  const OWNER_SEED = Uint8Array.from({ length: 32 }, (_, i) => (i * 7 + 3) & 0xff)
  const THERAPIST_SEED = Uint8Array.from({ length: 32 }, (_, i) => (i * 3 + 1) & 0xff)
  const OWNER_PUB = 'viuLdbNpuPRZuLFTeZvFqwei-P66BMEcyEPRn-Va4lw'
  const THERAPIST_PUB = 'XI7lgPfB0I-AHkchZYiWJfFtmPI3s6vfqFjFCJMUjh4'
  const ASSIGNMENT: Assignment = {
    assignmentId: 'a-fixture',
    lineageId: 'fixture',
    version: 0,
    type: 'questionnaire',
    capability: 'assign.questionnaire',
    payload: { instrumentId: 'wellbeing-selfcheck' },
    issuedAt: 1_758_800_000_000,
    authorFingerprint: 'UEkiWzxtRDm-bbeZmqdreQ',
  }
  const BLOB =
    'dAva4oxgQhLu1GWpReI4EiabRHQjWNM0B2ZI03LfFU95uz_me4-c9l2UXaUBITsQZ2F6emgW8IEiBKpQf0xkqa4DA77CxVGW2msIdO-oR-N8VAZ6xNFyDYmafj7ow-Ydz1gdVcBnJZj0s9UG4gGFonkkEpSMLcRynF9BhNRKYYgJ8QARASvtzGrCIZxsnYGegYFi3_mV5nF7RJWi36lJiaEZYBuFPmeQJRbYb_1qRqXwLdqkjEuufv6SPk_w7kE7lHT5bz9r3LKP5IBA8rZBdUTUolnOCEnItbPdvug0tPXoXTLa9R5273P_u4H5nT5VvsO1an_FoqRtKDJzq5G4c6QsNGTwe7BBKePLiHacXQNGBTzclebzKxi0rhcinMkm8zZLIIhnJ45LLJx9c8s6DsrsBTYwiKeNTmxz6cxnDui2QHy3-IM2ZbbQToR2wDFuKBeR6eMMzu7B8NJ_NE2cCCQnaPNttLhGStXlLsnLhJaXKlT_5n2b-DZMUY5TFTOPuBYWqn9jvysPHzPyoX6fMiQUU21H_1u0eFuUeLhWcYLkhnuUirGyECiy_aMqPUE6E9ITJMcJpoaOLu6qHuveS9h3cTe5rYOvPxf8QpZrjfw0NG1-KAJY9QRZr-X8LS_pYGjqq4AZdS54bGu3RKhStsgc61w7URsM5EMBrvt4pqAP_-W0F0LYtvFy8ZVvvpqxkNmSLz-fb7RQ'

  beforeAll(async () => {
    await initAssignmentCrypto()
  })

  it('opens to the assignment it was sealed from', () => {
    const so = _sodium
    const B64 = so.base64_variants.URLSAFE_NO_PADDING
    const owner = so.crypto_box_seed_keypair(OWNER_SEED)
    const therapist = so.crypto_sign_seed_keypair(THERAPIST_SEED)
    expect(so.to_base64(owner.publicKey, B64)).toBe(OWNER_PUB)
    expect(so.to_base64(therapist.publicKey, B64)).toBe(THERAPIST_PUB)
    const blob = so.from_base64(BLOB, B64)
    // It really is the unpadded form: the box holds JSON, and its length is no bucket size.
    const inBox = so.crypto_box_seal_open(blob, owner.publicKey, owner.privateKey)
    expect(inBox[0]).toBe(0x7b)
    expect(isBucket(inBox.length)).toBe(false)
    expect(openAssignment(blob, owner, therapist.publicKey)).toEqual(ASSIGNMENT)
  })
})
