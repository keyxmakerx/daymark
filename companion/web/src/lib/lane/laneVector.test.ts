/*
 * THE LANE VECTOR THE PHONE IS HELD TO (#345, #346).
 *
 * One lane version, fixed in every input: the sync key of the snapshot vectors (crypto.test.ts: the
 * passphrase "conformance-vector", salt 0x00..0x0f, 8 MiB and 2 passes), the lane
 * "lane_" + base64url(0x00..0x0f), version 3, the nonce 0x01..0x18, and two records whose ids are
 * base64url(0x10..0x1f) and base64url(0x20..0x2f). The plaintext is stated as text, the envelope is
 * built from its documented parts rather than by the module, and both are pinned byte for byte. The
 * phone's reader must open these bytes to these two records, and must refuse them as a snapshot.
 * A change to any constant below is a change to the wire format, never a refactor.
 */
import { describe, it, expect, beforeAll } from 'vitest'
import { createHash } from 'node:crypto'
import _sodium from 'libsodium-wrappers-sumo'
import { initCrypto, deriveKeys, decryptLaneVersion, decryptSnapshot, encryptLaneVersion, MAGIC, type KdfParams } from '../sync/crypto'
import { pad } from '../padding'
import { decodeLaneVersion, encodeLaneVersion, readRecord, type LaneRecord } from './record'

const FAST: KdfParams = { alg: 'argon2id', memMiB: 8, ops: 2 }
const enc = new TextEncoder()
const dec = new TextDecoder()
const hex = (b: Uint8Array): string => Buffer.from(b.buffer, b.byteOffset, b.byteLength).toString('hex')

const SYNC_KEY = '3d0a8d769e09df76fcb676a3e0eb792f3a5f2106c9d81759622eadd73a51fd65'
const LANE = 'lane_AAECAwQFBgcICQoLDA0ODw'
const VERSION = 3
const NONCE_1_TO_24 = Uint8Array.from({ length: 24 }, (_, i) => i + 1)
const ID_1 = 'EBESExQVFhcYGRobHB0eHw'
const ID_2 = 'ICEiIyQlJicoKSorLC0uLw'
const PLAINTEXT =
  '{"v":1,"records":[' +
  '{"id":"EBESExQVFhcYGRobHB0eHw","kind":"assignmentDecision","createdAt":1790000000000,' +
  '"payload":{"decision":"accepted","payloadJson":"{\\"context\\":\\"daymark.assignment.v1\\"}","sigB64":"c2lnbmF0dXJl"}},' +
  '{"id":"ICEiIyQlJicoKSorLC0uLw","kind":"instrumentResult","createdAt":1790000060000,' +
  '"payload":{"instrumentId":"wellbeing-check","instrumentVersion":"1.0.0","takenAt":1790000055000,' +
  '"scales":[{"scaleId":"total","score":12.5,"bandLabel":"Some days"}]}}]}'
const PLAINTEXT_BYTES = 468
const AAD = 'daymark.lane.v1|lane_AAECAwQFBgcICQoLDA0ODw|3'
const LENGTH = 4141 // 29-byte header, the 4096-byte padded body, the 16-byte tag
const HEADER = '444d5331' + '02' + '0102030405060708090a0b0c0d0e0f101112131415161718'
const CIPHERTEXT_HEAD = '7947e1a3a129d034edcbebbc9158b195' // the first 16 bytes after the header
const TAG = 'ea23c4192ac10dac17e86d9d1666e5f9'
const SHA256 = '3f7910c147963fda8b80cf77fe99b1b853c0398446238b18ef4155d355e6aeea'

/** The two records, as a lane carries them. */
const RECORDS: LaneRecord[] = [
  {
    id: ID_1,
    kind: 'assignmentDecision',
    createdAt: 1_790_000_000_000,
    payload: { decision: 'accepted', payloadJson: '{"context":"daymark.assignment.v1"}', sigB64: 'c2lnbmF0dXJl' },
  },
  {
    id: ID_2,
    kind: 'instrumentResult',
    createdAt: 1_790_000_060_000,
    payload: {
      instrumentId: 'wellbeing-check',
      instrumentVersion: '1.0.0',
      takenAt: 1_790_000_055_000,
      scales: [{ scaleId: 'total', score: 12.5, bandLabel: 'Some days' }],
    },
  },
]

/** MAGIC | 0x02 | nonce | the AEAD of `body` under `aadText`: the envelope from its documented parts. */
function envelopeFromParts(body: Uint8Array, aadText: string, nonce: Uint8Array, key: Uint8Array): Uint8Array {
  const ct = _sodium.crypto_aead_xchacha20poly1305_ietf_encrypt(body, enc.encode(aadText), null, nonce, key)
  const out = new Uint8Array(29 + ct.length)
  out.set(MAGIC, 0)
  out[4] = 0x02
  out.set(nonce, 5)
  out.set(ct, 29)
  return out
}

let key: Uint8Array

beforeAll(async () => {
  await initCrypto()
  key = deriveKeys('conformance-vector', Uint8Array.from({ length: 16 }, (_, i) => i), FAST).syncKey
})

describe('the lane vector the phone is held to (#346)', () => {
  it('the key is the snapshot vectors\' key, and the ids and the lane are base64url of their bytes', () => {
    expect(hex(key)).toBe(SYNC_KEY)
    const b64 = (from: number) => _sodium.to_base64(Uint8Array.from({ length: 16 }, (_, i) => from + i), _sodium.base64_variants.URLSAFE_NO_PADDING)
    expect([`lane_${b64(0x00)}`, b64(0x10), b64(0x20)]).toEqual([LANE, ID_1, ID_2])
  })

  it('the writer writes the two records as exactly this plaintext', () => {
    expect(dec.decode(encodeLaneVersion(RECORDS))).toBe(PLAINTEXT)
    expect(enc.encode(PLAINTEXT).length).toBe(PLAINTEXT_BYTES)
  })

  it('sealed under the lane associated data with this nonce, it is exactly these bytes', () => {
    const envelope = envelopeFromParts(pad(enc.encode(PLAINTEXT)), AAD, NONCE_1_TO_24, key)
    expect(envelope.length).toBe(LENGTH)
    expect(hex(envelope.subarray(0, 29))).toBe(HEADER)
    expect(hex(envelope.subarray(29, 45))).toBe(CIPHERTEXT_HEAD)
    expect(hex(envelope.subarray(LENGTH - 16))).toBe(TAG)
    expect(createHash('sha256').update(envelope).digest('hex')).toBe(SHA256)
  })

  it('opens to the two records, each read as its kind, with its id', () => {
    const envelope = envelopeFromParts(pad(enc.encode(PLAINTEXT)), AAD, NONCE_1_TO_24, key)
    const records = decodeLaneVersion(decryptLaneVersion(envelope, key, LANE, VERSION))
    expect(records.map((r) => r.id)).toEqual([ID_1, ID_2])
    expect(records).toEqual(RECORDS)
    expect(records.map((r) => readRecord(r)?.kind)).toEqual(['assignmentDecision', 'instrumentResult'])
  })

  it('is refused as a snapshot of the same lineage and version', () => {
    const envelope = envelopeFromParts(pad(enc.encode(PLAINTEXT)), AAD, NONCE_1_TO_24, key)
    expect(() => decryptSnapshot(envelope, key, LANE, VERSION)).toThrow()
    // And the same body under the snapshot's associated data has another tag: the AAD is pinned too.
    const asSnapshot = envelopeFromParts(pad(enc.encode(PLAINTEXT)), `daymark.snapshot.v2|${LANE}|${VERSION}`, NONCE_1_TO_24, key)
    expect(hex(asSnapshot.subarray(29, 45))).toBe(CIPHERTEXT_HEAD)
    expect(hex(asSnapshot.subarray(LENGTH - 16))).not.toBe(TAG)
    expect(() => decryptLaneVersion(asSnapshot, key, LANE, VERSION)).toThrow()
  })

  it('the writer makes exactly that construction, for whatever nonce it draws', () => {
    const blob = encryptLaneVersion(enc.encode(PLAINTEXT), key, LANE, VERSION)
    const rebuilt = envelopeFromParts(pad(enc.encode(PLAINTEXT)), AAD, blob.slice(5, 29), key)
    expect(hex(blob)).toBe(hex(rebuilt))
  })
})
