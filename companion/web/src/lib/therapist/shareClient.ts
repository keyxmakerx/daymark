/*
 * Therapist SHARE reader. Fetches the current curated share blob from the `shares` channel, decodes
 * the opaque envelope the owner published (see owner ShareBuilder.encodeSealed), then opens it with
 * openShare(): verify the owner's Ed25519 signature over the whole envelope against the PINNED owner
 * key → unseal CEK → AEAD decrypt → unpad. Verify-then-decrypt, REFUSE-TO-RENDER on any failure
 * (never hand a bundle to the dashboard on a forged / spliced / wrong-owner / tampered / expired
 * share, or on one sealed in format 1, whose contents cannot be checked).
 *
 * The signed version must be the version the server serves the share under, so a server cannot
 * hand one version of a lineage out as another. And a share sealed before the newest one this
 * browser has already opened stays closed (shareSeen.ts): the signed creation time decides, before
 * anything is decrypted.
 *
 * The decrypted ShareBundle is curated (scores/bands/aggregates only, self-harm slot structurally
 * absent). bundleToBackupData() adapts it into the BackupData shape the existing Dashboard renders.
 * In-memory only; re-fetched per session (thin viewer).
 */
import _sodium from 'libsodium-wrappers-sumo'
import {
  openShare, ShareOpenError, ShareExpiredError, ShareFormatError, ShareOlderError, SHARE_FORMAT,
  type SealedShare, type ShareBundle,
} from '../share/sharecrypto'
import { newestOpened, rememberOpened, defaultSeenStorage, type SeenStorage } from './shareSeen'
import type { BoxKeyPair } from '../assignments/crypto'
import type { BackupData } from '../backup'
import type { PortalClient, SessionInfo } from './session'

const URLSAFE = () => _sodium.base64_variants.URLSAFE_NO_PADDING
const dec = new TextDecoder()

export { ShareOpenError, ShareExpiredError, ShareFormatError, ShareOlderError }
export type { ShareBundle }

/**
 * Decode the owner's opaque share envelope (base64url fields) into a SealedShare. Throws
 * ShareFormatError for any format but 2, and ShareOpenError for anything that is not a
 * well-formed envelope; openShare checks the fields' contents.
 */
export function decodeSealed(bytes: Uint8Array): SealedShare {
  let o: Record<string, unknown>
  try {
    o = JSON.parse(dec.decode(bytes)) as Record<string, unknown>
  } catch {
    throw new ShareOpenError('malformed sealed-share envelope')
  }
  if (o === null || typeof o !== 'object') throw new ShareOpenError('malformed sealed-share envelope')
  if (o.fmt !== SHARE_FORMAT) throw new ShareFormatError('share is not in format 2')
  const strings = ['shareId', 'recipientFp', 'ownerSigningFp', 'body', 'wrappedCEK', 'ownerSig'] as const
  if (strings.some((k) => typeof o[k] !== 'string') || typeof o.version !== 'number' ||
    typeof o.createdAt !== 'number' || typeof o.expiry !== 'number') {
    throw new ShareOpenError('malformed sealed-share envelope')
  }
  try {
    return {
      fmt: SHARE_FORMAT,
      shareId: o.shareId as string,
      version: o.version as number,
      createdAt: o.createdAt as number,
      expiry: o.expiry as number,
      recipientFp: o.recipientFp as string,
      ownerSigningFp: o.ownerSigningFp as string,
      body: _sodium.from_base64(o.body as string, URLSAFE()),
      wrappedCEK: _sodium.from_base64(o.wrappedCEK as string, URLSAFE()),
      ownerSig: _sodium.from_base64(o.ownerSig as string, URLSAFE()),
    }
  } catch {
    throw new ShareOpenError('malformed sealed-share envelope')
  }
}

/**
 * Fetch + open the current share for this session. Returns the verified ShareBundle. THROWS
 * (ShareOpenError and its kinds: expired, older format, older than one already opened) on any
 * failure — the caller must not render. Returns null when there is simply no share yet.
 */
export async function fetchShare(
  client: PortalClient,
  session: SessionInfo,
  therapistBox: BoxKeyPair,
  pinnedOwnerSignPub: Uint8Array,
  pinnedOwnerSigningFp: string,
  now: number = Date.now(),
  seen: SeenStorage | null = defaultSeenStorage(),
): Promise<ShareBundle | null> {
  const current = await client.getCurrent(session, 'shares', 'share')
  if (!current) return null
  const sealed = decodeSealed(current.bytes)
  if (sealed.version !== current.version) {
    throw new ShareOpenError('the share names a different version than the one it was served as')
  }
  // openShare throws on any failure; we deliberately do NOT catch here so the UI shows a
  // refuse-to-render error rather than a (possibly forged) bundle.
  const notBefore = newestOpened(session.relRef, therapistBox, seen) ?? undefined
  const bundle = openShare(sealed, therapistBox, pinnedOwnerSignPub, pinnedOwnerSigningFp, now, notBefore)
  // Remembered only once the share has opened: a refused share never moves the mark. And never
  // later than this browser's own clock: a share sealed on a device whose clock ran ahead would
  // otherwise refuse every later share until that date came round.
  rememberOpened(session.relRef, Math.min(sealed.createdAt, now), therapistBox, seen)
  return bundle
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
