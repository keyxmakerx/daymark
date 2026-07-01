/*
 * Therapist SHARE reader. Fetches the current curated share blob from the `shares` channel, decodes
 * the opaque envelope the owner published (see owner ShareBuilder.encodeSealed), then opens it with
 * openShare(): unseal CEK → verify owner Ed25519 signature against the PINNED owner key → AEAD
 * decrypt. Verify-then-decrypt, REFUSE-TO-RENDER on any failure (never hand a bundle to the
 * dashboard on a forged / spliced / wrong-owner / tampered / expired share).
 *
 * The decrypted ShareBundle is curated (scores/bands/aggregates only, self-harm slot structurally
 * absent). bundleToBackupData() adapts it into the BackupData shape the existing Dashboard renders.
 * In-memory only; re-fetched per session (thin viewer).
 */
import _sodium from 'libsodium-wrappers-sumo'
import { openShare, ShareOpenError, ShareExpiredError, type SealedShare, type ShareBundle } from '../share/sharecrypto'
import type { BoxKeyPair } from '../assignments/crypto'
import type { BackupData } from '../backup'
import type { PortalClient, SessionInfo } from './session'

const URLSAFE = () => _sodium.base64_variants.URLSAFE_NO_PADDING
const dec = new TextDecoder()

export { ShareOpenError, ShareExpiredError }
export type { ShareBundle }

/** Decode the owner's opaque share envelope (base64url fields) into a SealedShare. */
export function decodeSealed(bytes: Uint8Array): SealedShare {
  const o = JSON.parse(dec.decode(bytes)) as {
    fmt: number
    shareId: string
    version: number
    expiry: number
    recipientFp: string
    ownerSigningFp: string
    body: string
    wrappedCEK: string
    ownerSig: string
  }
  if (o.fmt !== 1 || typeof o.body !== 'string' || typeof o.wrappedCEK !== 'string' || typeof o.ownerSig !== 'string') {
    throw new ShareOpenError('malformed sealed-share envelope')
  }
  return {
    fmt: 1,
    shareId: o.shareId,
    version: o.version,
    expiry: o.expiry,
    recipientFp: o.recipientFp,
    ownerSigningFp: o.ownerSigningFp,
    body: _sodium.from_base64(o.body, URLSAFE()),
    wrappedCEK: _sodium.from_base64(o.wrappedCEK, URLSAFE()),
    ownerSig: _sodium.from_base64(o.ownerSig, URLSAFE()),
  }
}

/**
 * Fetch + open the current share for this session. Returns the verified ShareBundle. THROWS
 * (ShareOpenError / ShareExpiredError) on any verification failure — the caller must not render.
 * Returns null when there is simply no share yet.
 */
export async function fetchShare(
  client: PortalClient,
  session: SessionInfo,
  therapistBox: BoxKeyPair,
  pinnedOwnerSignPub: Uint8Array,
  pinnedOwnerSigningFp: string,
  now: number = Date.now(),
): Promise<ShareBundle | null> {
  const current = await client.getCurrent(session, 'shares', 'share')
  if (!current) return null
  const sealed = decodeSealed(current.bytes)
  // openShare throws on any failure; we deliberately do NOT catch here so the UI shows a
  // refuse-to-render error rather than a (possibly forged) bundle.
  return openShare(sealed, therapistBox, pinnedOwnerSignPub, pinnedOwnerSigningFp, now)
}

/**
 * Adapt a curated ShareBundle into the BackupData shape the Dashboard renders. Only the curated,
 * non-diagnostic fields are materialized; there is no path for raw item responses. Journal/mood
 * notes are already redacted upstream in the owner's curation.
 */
export function bundleToBackupData(bundle: ShareBundle): BackupData {
  const entries = (bundle.moods ?? []).map((m, i) => ({
    id: i + 1,
    dateTime: m.at,
    moodLevel: Math.min(5, Math.max(1, Math.round(m.level))),
    note: m.note ?? '',
    photoPath: null,
  }))
  const journal = (bundle.journal ?? []).map((j, i) => ({
    id: i + 1,
    dateTime: j.at,
    title: '',
    body: j.text,
  }))
  const sleepLogs = (bundle.sleep ?? []).map((s, i) => ({
    id: i + 1,
    night: s.at,
    bedTime: s.bedTime,
    wakeTime: s.wakeTime,
    sleepLatencyMin: 0,
    awakeMin: 0,
    quality: s.quality,
    note: '',
  }))
  const assessments = bundle.checkIns.map((c, i) => ({
    id: i + 1,
    key: c.instrumentId,
    dateTime: c.at,
    score: c.score,
    bandLabel: c.band,
  }))
  return {
    version: 0,
    exportedAt: bundle.scope.to,
    entries,
    activities: [],
    refs: [],
    journal,
    goals: [],
    sleepLogs,
    assessments,
    moodLabels: {},
    moodColors: {},
  }
}
