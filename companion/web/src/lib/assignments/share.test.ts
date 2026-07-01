import { describe, it, expect, beforeAll } from 'vitest'
import { buildShareBundle, previewCounts, emptySelection, buildShare, ShareUnpinnedError, type ShareBundleMeta } from './share'
import type { BackupData } from '../backup'
import { initShareCrypto, newIdentity, publicOf, fingerprints, PinStore } from '../share/pairing'
import { openShare, type ShareMeta } from '../share/sharecrypto'
import { newSignKeyPair, fingerprint } from '../assignments/crypto'

function sampleData(): BackupData {
  return {
    version: 1,
    exportedAt: 1000,
    entries: [
      { id: 1, dateTime: 500, moodLevel: 3, note: 'secret note' },
      { id: 2, dateTime: 2000, moodLevel: 4, note: 'out of range' },
    ],
    activities: [],
    refs: [],
    journal: [{ id: 10, dateTime: 500, title: 'T', body: 'private body' }],
    sleepLogs: [{ id: 20, night: 500, bedTime: 0, wakeTime: 8, sleepLatencyMin: 5, awakeMin: 0, quality: 4, note: 'z' }],
    assessments: [
      { id: 30, key: 'wellbeing-selfcheck', dateTime: 500, score: 12, bandLabel: 'moderate' },
      { id: 31, key: 'wellbeing-selfcheck', dateTime: 500, score: 15, bandLabel: 'high' },
    ],
  }
}

const meta: ShareBundleMeta = { shareId: 's1', version: 1, createdAt: 1000, ownerFp: 'ownerfp', expiry: 9999 }

describe('buildShareBundle — curation & redaction', () => {
  it('emits ONLY opted-in record types', () => {
    const sel = { ...emptySelection(), types: { checkIns: true, moods: false, journal: false, sleep: false } }
    const b = buildShareBundle(sampleData(), sel, meta)
    expect(b.checkIns.length).toBe(2)
    expect(b.moods).toBeUndefined()
    expect(b.journal).toBeUndefined()
    expect(b.sleep).toBeUndefined()
  })

  it('check-ins carry scores/bands ONLY — no raw answers or self-harm slot', () => {
    const sel = { ...emptySelection(), types: { checkIns: true, moods: false, journal: false, sleep: false } }
    const b = buildShareBundle(sampleData(), sel, meta)
    for (const c of b.checkIns) {
      expect(Object.keys(c).sort()).toEqual(['at', 'band', 'instrumentId', 'score'])
    }
    // Structural guarantee: nothing anywhere in the serialized bundle names item-9 / self-harm.
    expect(JSON.stringify(b).toLowerCase()).not.toMatch(/item9|item-9|selfharm|self-harm|suicid/)
  })

  it('strips notes when stripNotes is set', () => {
    const sel = { ...emptySelection(), stripNotes: true, types: { checkIns: false, moods: true, journal: true, sleep: false } }
    const b = buildShareBundle(sampleData(), sel, meta)
    expect(b.moods?.every((m) => m.note === undefined)).toBe(true)
    expect(b.journal?.every((j) => j.text === '')).toBe(true)
  })

  it('keeps notes when stripNotes is false', () => {
    const sel = { ...emptySelection(), stripNotes: false, types: { checkIns: false, moods: true, journal: true, sleep: false } }
    const b = buildShareBundle(sampleData(), sel, meta)
    expect(b.moods?.[0].note).toBe('secret note')
    expect(b.journal?.[0].text).toBe('private body')
  })

  it('respects the date range and explicit exclusions', () => {
    const sel = { ...emptySelection(), from: 0, to: 1000, excludeIds: [31], types: { checkIns: true, moods: true, journal: false, sleep: false } }
    const b = buildShareBundle(sampleData(), sel, meta)
    expect(b.moods?.length).toBe(1) // entry id 2 (dateTime 2000) is out of range
    expect(b.checkIns.length).toBe(1) // assessment id 31 excluded
    expect(b.checkIns[0].score).toBe(12)
  })

  it('previewCounts reflects the bundle', () => {
    const sel = { ...emptySelection(), types: { checkIns: true, moods: true, journal: false, sleep: false } }
    const b = buildShareBundle(sampleData(), sel, meta)
    const c = previewCounts(b)
    expect(c.checkIns).toBe(2)
    expect(c.moods).toBe(2)
    expect(c.journal).toBe(0)
  })
})

describe('buildShare — seal to pinned therapist, therapist opens', () => {
  beforeAll(async () => {
    await initShareCrypto()
  })

  it('round-trips: owner seals a curated bundle, pinned therapist opens it', () => {
    const therapist = newIdentity()
    const ownerSign = newSignKeyPair()
    const ownerFp = fingerprint(ownerSign.publicKey)
    const tFps = fingerprints(publicOf(therapist))

    const pins = new PinStore()
    pins.pin(publicOf(therapist))

    const sel = { ...emptySelection(), types: { checkIns: true, moods: false, journal: false, sleep: false } }
    const bundle = buildShareBundle(sampleData(), sel, { shareId: 's1', version: 1, createdAt: 1000, ownerFp, expiry: 9_000_000_000_000 })

    const shareMeta: ShareMeta = {
      context: 'daymark.share.v1', shareId: 's1', version: 1,
      recipientFp: tFps.x25519Fp, expiry: 9_000_000_000_000, ownerSigningFp: ownerFp,
    }
    const sealed = buildShare(bundle, shareMeta, therapist.x25519.publicKey, ownerSign, tFps.ed25519Fp, pins)

    const opened = openShare(sealed, therapist.x25519, ownerSign.publicKey, ownerFp, Date.now())
    expect(opened.checkIns.length).toBe(2)
    expect(opened.shareId).toBe('s1')
  })

  it('refuses to seal to an UNPINNED therapist', () => {
    const therapist = newIdentity()
    const ownerSign = newSignKeyPair()
    const ownerFp = fingerprint(ownerSign.publicKey)
    const tFps = fingerprints(publicOf(therapist))
    const pins = new PinStore() // not pinned

    const bundle = buildShareBundle(sampleData(), { ...emptySelection(), types: { checkIns: true, moods: false, journal: false, sleep: false } }, meta)
    const shareMeta: ShareMeta = {
      context: 'daymark.share.v1', shareId: 's1', version: 1,
      recipientFp: tFps.x25519Fp, expiry: 9_000_000_000_000, ownerSigningFp: ownerFp,
    }
    expect(() => buildShare(bundle, shareMeta, therapist.x25519.publicKey, ownerSign, tFps.ed25519Fp, pins)).toThrow(ShareUnpinnedError)
  })
})
