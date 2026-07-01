/*
 * Shared unlocked-portal context type. Produced by LoginGate on a successful TOTP verify + key
 * unwrap, consumed by TherapistPortal and its tabs. Held in memory only; dropped (and keys
 * zeroized) on logout/idle.
 */
import type { PortalClient, SessionInfo } from './session'
import type { TherapistKeys } from './keyStore'

export interface UnlockedContext {
  client: PortalClient
  session: SessionInfo
  keys: TherapistKeys
  /** Owner Ed25519 signing key (pinned OOB) — verifies grants + shares. */
  pinnedOwnerSignPub: Uint8Array
  pinnedOwnerSigningFp: string
  /** Owner X25519 box key (pinned OOB) — seal assignments + game plans TO the owner. */
  ownerBoxPub: Uint8Array
  therapistFp: string
}
