/*
 * Owner console session model — held only in memory for the console lifetime and dropped on
 * lock. The owner is the sole root of trust: opening sealed assignments needs the owner X25519
 * box keypair; signing grants/shares needs the owner Ed25519 sign keypair. PinnedTherapist keys
 * are OOB-verified (the pairing slice) and are the ONLY authors the console will trust.
 *
 * Key GENERATION and at-rest custody live in owner/unlock.ts and owner/identity.ts — this
 * console CONSUMES already-unlocked keys. A passkey (WebAuthn-PRF) unlock is not built: #205.
 */
import type { BoxKeyPair, SignKeyPair } from '../../assignments/crypto'
import type { Grant } from '../../assignments/types'
import type { PinnedTherapist as InboxTherapist } from '../../assignments/inbox'
import type { LaneKey } from '../../lane/lane'

/** A pinned therapist for the owner console: OOB-verified keys + SAS words + the current grant. */
export interface PinnedTherapist extends InboxTherapist {
  /** Opaque per-relationship inbox token (delivered OOB at pairing). Routes blobs; never a fp. */
  inboxToken: string
  /** SAS words the owner confirms out-of-band before sealing/granting. Display only. */
  fingerprintWords: string
  pinnedAt: number
  /**
   * True while this entry has a name and an inbox token but NO keys — the state every relationship
   * now starts in, because the clinician's keys do not exist until they accept the invitation.
   *
   * This flag is what finally connects the two halves of the pairing. Without it the console
   * demanded both public keys at pin time, which meant the owner could only reach the screen that
   * FETCHES the clinician's published keys by first pasting those same keys by hand — a circle the
   * intake screen's own header documented ("this screen pins; it does not change which keys this
   * console seals to") without being able to break. While true, `signPub`/`boxPub` are empty and
   * nothing may seal to or verify against this entry; the seal paths already refuse independently
   * (libsodium rejects an empty key, and buildShare checks the pin store), so the flag gates the
   * INTERFACE and the crypto gates the crypto.
   */
  keysPending?: boolean
}

export interface OwnerSession {
  ownerBox: BoxKeyPair // X25519 — opens sealed assignments
  ownerSign: SignKeyPair // Ed25519 — signs grants + shares
  pinned: PinnedTherapist[]
  /**
   * The sync key of the same master, with the ETag of the key document it was opened from: what the
   * console reads and adds to the owner's lane with (lane/lane.ts, #345). Wiped when it locks.
   */
  lane: LaneKey
}

/** Update a therapist's grant within the session (returns a new session for reactive updates). */
export function withGrant(session: OwnerSession, therapistId: string, grant: Grant): OwnerSession {
  return {
    ...session,
    pinned: session.pinned.map((t) => (t.id === therapistId ? { ...t, grant } : t)),
  }
}
