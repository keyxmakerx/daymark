/*
 * Owner console session model — held only in memory for the console lifetime and dropped on
 * lock. The owner is the sole root of trust: opening sealed assignments needs the owner X25519
 * box keypair; signing grants/shares needs the owner Ed25519 sign keypair. PinnedTherapist keys
 * are OOB-verified (the pairing slice) and are the ONLY authors the console will trust.
 *
 * Key GENERATION / at-rest custody (WebAuthn-PRF wrapping) is the pairing/auth slice — this
 * console CONSUMES already-unlocked keys. See the slice's "unverifiableHere" note.
 */
import type { BoxKeyPair, SignKeyPair } from '../../assignments/crypto'
import type { Grant } from '../../assignments/types'
import type { PinnedTherapist as InboxTherapist } from '../../assignments/inbox'

/** A pinned therapist for the owner console: OOB-verified keys + SAS words + the current grant. */
export interface PinnedTherapist extends InboxTherapist {
  /** Opaque per-relationship inbox token (delivered OOB at pairing). Routes blobs; never a fp. */
  inboxToken: string
  /** SAS words the owner confirms out-of-band before sealing/granting. Display only. */
  fingerprintWords: string
  pinnedAt: number
}

export interface OwnerSession {
  ownerBox: BoxKeyPair // X25519 — opens sealed assignments
  ownerSign: SignKeyPair // Ed25519 — signs grants + shares
  pinned: PinnedTherapist[]
}

/** Update a therapist's grant within the session (returns a new session for reactive updates). */
export function withGrant(session: OwnerSession, therapistId: string, grant: Grant): OwnerSession {
  return {
    ...session,
    pinned: session.pinned.map((t) => (t.id === therapistId ? { ...t, grant } : t)),
  }
}
