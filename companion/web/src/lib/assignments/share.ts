/*
 * Owner-side SHARE curation. Turns a full BackupData into a curated, redacted ShareBundle
 * (the wire type from ../share/sharecrypto whose self-harm / raw-item slot is STRUCTURALLY
 * ABSENT), applying the owner's selection: date range, per-record-type opt-ins, note stripping,
 * and explicit exclusions. Check-ins carry scores + bands ONLY — never raw item responses.
 *
 * Sealing is delegated to ../share/sharecrypto (buildShare): fresh CEK, XChaCha20-Poly1305 under
 * an AAD-bound transcript, CEK sealed to the therapist's pinned X25519 key, owner Ed25519
 * signature over the transcript. Refuses to seal to an unpinned therapist.
 */
import type { BackupData } from '../backup'
import type { ShareBundle } from '../share/sharecrypto'

/** The owner's curation choices. Everything defaults OFF/empty — the owner opts data IN. */
export interface ShareSelection {
  from?: number // epoch ms inclusive (undefined = no lower bound)
  to?: number // epoch ms inclusive (undefined = now)
  types: {
    checkIns: boolean
    moods: boolean
    journal: boolean
    sleep: boolean
  }
  stripNotes: boolean
  excludeIds: number[] // entry/journal/sleep ids the owner explicitly removed
}

export function emptySelection(): ShareSelection {
  return { types: { checkIns: false, moods: false, journal: false, sleep: false }, stripNotes: true, excludeIds: [] }
}

function inRange(at: number, sel: ShareSelection): boolean {
  if (sel.from !== undefined && at < sel.from) return false
  if (sel.to !== undefined && at > sel.to) return false
  return true
}

export interface ShareBundleMeta {
  shareId: string
  version: number
  createdAt: number
  ownerFp: string // owner signing fingerprint (pinned by therapist)
  expiry: number
}

/**
 * Materialize a curated bundle. Only opted-in record types are emitted, only in-range and
 * non-excluded, and — critically — check-ins are reduced to {instrumentId, at, score, band}.
 * There is NO code path that copies a raw item answer, a PHQ item-9, or a self-harm field: the
 * ShareBundle type has no slot for them.
 */
export function buildShareBundle(data: BackupData, sel: ShareSelection, meta: ShareBundleMeta): ShareBundle {
  const excluded = new Set(sel.excludeIds)
  const recordTypes: string[] = []

  const checkIns = sel.types.checkIns
    ? (data.assessments ?? [])
        .filter((a) => inRange(a.dateTime, sel) && !excluded.has(a.id))
        .map((a) => ({ instrumentId: a.key, at: a.dateTime, score: a.score, band: a.bandLabel }))
    : []
  if (sel.types.checkIns) recordTypes.push('checkIns')

  const moods = sel.types.moods
    ? (data.entries ?? [])
        .filter((e) => inRange(e.dateTime, sel) && !excluded.has(e.id))
        .map((e) => {
          const base: { at: number; level: number; note?: string } = { at: e.dateTime, level: e.moodLevel }
          if (!sel.stripNotes && e.note) base.note = e.note
          return base
        })
    : undefined
  if (moods) recordTypes.push('moods')

  const journal = sel.types.journal
    ? (data.journal ?? [])
        .filter((j) => inRange(j.dateTime, sel) && !excluded.has(j.id))
        .map((j) => ({ at: j.dateTime, text: sel.stripNotes ? '' : j.body }))
    : undefined
  if (journal) recordTypes.push('journal')

  const sleep = sel.types.sleep
    ? (data.sleepLogs ?? [])
        .filter((sLog) => inRange(sLog.night, sel) && !excluded.has(sLog.id))
        .map((sLog) => ({ at: sLog.night, bedTime: sLog.bedTime, wakeTime: sLog.wakeTime, quality: sLog.quality }))
    : undefined
  if (sleep) recordTypes.push('sleep')

  const from = sel.from ?? 0
  const to = sel.to ?? meta.createdAt
  return {
    schema: 1,
    shareId: meta.shareId,
    scope: { from, to, recordTypes },
    ownerFp: meta.ownerFp,
    checkIns,
    ...(moods ? { moods } : {}),
    ...(journal ? { journal } : {}),
    ...(sleep ? { sleep } : {}),
  }
}

/** Counts for the pre-seal preview UI (so the owner sees exactly what they're about to share). */
export function previewCounts(bundle: ShareBundle): { checkIns: number; moods: number; journal: number; sleep: number } {
  return {
    checkIns: bundle.checkIns.length,
    moods: bundle.moods?.length ?? 0,
    journal: bundle.journal?.length ?? 0,
    sleep: bundle.sleep?.length ?? 0,
  }
}

// Re-export the seal primitive so callers use one import surface for owner share-building.
export { buildShare, type SealedShare, type ShareMeta, ShareUnpinnedError } from '../share/sharecrypto'
