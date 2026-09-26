/*
 * THE LANE'S ENVELOPE (#345): a format-2 snapshot's bytes under the lane's own associated data.
 *
 * What the web console adds to the person's record travels in lineages of its own on the owner's
 * sync API (lane/lane.ts). Each version is sealed exactly as a snapshot is, magic, format byte,
 * nonce and padded body under the sync key, and is told apart from a snapshot by its associated
 * data alone: "daymark.lane.v1|lineage|version". These tests hold the separation both ways, each
 * with the positive control that the same bytes open as what they are, and show the padding and
 * the binding to lineage and version that every snapshot has.
 */
import { describe, it, expect, beforeAll } from 'vitest'
import {
  initCrypto,
  deriveKeys,
  newSalt,
  encryptSnapshot,
  decryptSnapshot,
  encryptLaneVersion,
  decryptLaneVersion,
  snapshotBlobLength,
  LANE_CONTEXT,
  FMT_PADDED,
  MAGIC,
  type KdfParams,
} from './crypto'
import { MIN_PADDED } from '../padding'

const FAST: KdfParams = { alg: 'argon2id', memMiB: 8, ops: 2 }
const enc = new TextEncoder()
const dec = new TextDecoder()
const LANE = 'lane_AAECAwQFBgcICQoLDA0ODw'
const PLAIN = enc.encode('{"v":1,"records":[]}')

let key: Uint8Array

beforeAll(async () => {
  await initCrypto()
  key = deriveKeys('a lane passphrase', newSalt(), FAST).syncKey
})

describe('a lane version is sealed under the lane context, never a snapshot one', () => {
  it('round-trips under its lineage and version', () => {
    const blob = encryptLaneVersion(PLAIN, key, LANE, 4)
    expect(dec.decode(decryptLaneVersion(blob, key, LANE, 4))).toBe('{"v":1,"records":[]}')
  })

  it('is a snapshot envelope in its layout: the magic, the padded format byte, a nonce, the padded body', () => {
    const blob = encryptLaneVersion(PLAIN, key, LANE, 4)
    expect([...blob.subarray(0, 4)]).toEqual([...MAGIC])
    expect(blob[4]).toBe(FMT_PADDED)
    expect(blob.length).toBe(snapshotBlobLength(PLAIN.length))
    expect(LANE_CONTEXT).toBe('daymark.lane.v1')
  })

  it('a lane version and a snapshot never open as each other, both ways', () => {
    // The same key, lineage and version, and the same layout: only the associated data differs.
    const lane = encryptLaneVersion(PLAIN, key, LANE, 4)
    const snapshot = encryptSnapshot(PLAIN, key, LANE, 4)
    // Positive controls: each opens as itself...
    expect(dec.decode(decryptLaneVersion(lane, key, LANE, 4))).toBe('{"v":1,"records":[]}')
    expect(dec.decode(decryptSnapshot(snapshot, key, LANE, 4))).toBe('{"v":1,"records":[]}')
    // ...and neither opens as the other.
    expect(() => decryptSnapshot(lane, key, LANE, 4)).toThrow()
    expect(() => decryptLaneVersion(snapshot, key, LANE, 4)).toThrow()
  })

  it('is refused under another lineage, another version or another key', () => {
    const blob = encryptLaneVersion(PLAIN, key, LANE, 4)
    expect(() => decryptLaneVersion(blob, key, 'lane_BBECAwQFBgcICQoLDA0ODw', 4)).toThrow()
    expect(() => decryptLaneVersion(blob, key, LANE, 5)).toThrow()
    const other = deriveKeys('another passphrase', newSalt(), FAST).syncKey
    expect(() => decryptLaneVersion(blob, other, LANE, 4)).toThrow()
    // Control: the right three open it.
    expect(decryptLaneVersion(blob, key, LANE, 4).length).toBe(PLAIN.length)
  })

  it('refuses the unpadded format outright, as a lane was never written in it', () => {
    const blob = encryptLaneVersion(PLAIN, key, LANE, 4)
    const relabelled = blob.slice()
    relabelled[4] = 0x01
    expect(relabelled[4]).not.toBe(blob[4])
    expect(() => decryptLaneVersion(relabelled, key, LANE, 4)).toThrow(/unsupported lane envelope format 1/)
  })
})

describe('a lane version is padded like every encrypted item (#214)', () => {
  it('two lengths in one bucket are stored at one size, and the smallest bucket is 4 KiB', () => {
    const short = encryptLaneVersion(enc.encode('{"v":1,"records":[]}'), key, LANE, 0)
    const longer = encryptLaneVersion(enc.encode(JSON.stringify({ v: 1, records: [], pad: 'x'.repeat(3000) })), key, LANE, 1)
    expect(short.length).toBe(4 + 1 + 24 + MIN_PADDED + 16)
    expect(longer.length).toBe(short.length)
  })

  it('a longer version moves to the next bucket, and nothing between', () => {
    const big = enc.encode('y'.repeat(5000))
    const blob = encryptLaneVersion(big, key, LANE, 2)
    expect(blob.length).toBe(4 + 1 + 24 + 8192 + 16)
    expect(dec.decode(decryptLaneVersion(blob, key, LANE, 2))).toBe('y'.repeat(5000))
  })

  it('the stored bytes carry none of the plaintext', () => {
    const marker = 'UNIQUE-LANE-MARKER-6c1e'
    const blob = encryptLaneVersion(enc.encode(JSON.stringify({ v: 1, records: [], note: marker })), key, LANE, 3)
    expect(Buffer.from(blob).toString('latin1').includes(marker)).toBe(false)
    // Control: the marker is in what was sealed.
    expect(dec.decode(decryptLaneVersion(blob, key, LANE, 3)).includes(marker)).toBe(true)
  })
})
