/*
 * Therapist-side GRANT verification. The owner publishes an Ed25519-signed Grant on the `grants`
 * channel (opaque blob = the {payloadJson, ownerSigB64, ownerSigningFp} envelope from
 * ../assignments/grant.ts). The therapist fetches it and VERIFIES the signature against the
 * OOB-PINNED owner Ed25519 key before trusting it or rendering any granted UI.
 *
 * SECURITY BOUNDARY: the portal treats the server's grant blob as UNTRUSTED until verifyGrant()
 * succeeds against the pinned owner key AND the grant names this clinician's own signing key. The
 * local grant is a UX pre-flight only — the owner's device is the authoritative gate. A grant that
 * fails either check (forged / substituted owner key / unpinned / written for someone else) yields
 * NO granted capabilities.
 *
 * WHY THE NAME IS CHECKED. The owner signs the grant for every clinician with the same key, so
 * the signature says only that the owner wrote it, not for whom. A server could serve one
 * clinician the grant written for another; the fingerprint inside the signed payload is the only
 * thing that says who a grant is for.
 */
import type { Capability, Grant, ApplyMode } from '../assignments/types'
import { verifyGrant, decodeSignedGrant, GrantError, type SignedGrant } from '../assignments/grant'

export { GrantError }
export type { SignedGrant }

/** The grant verified, but it names a different clinician key than the one this portal holds. */
export class GrantAddressError extends GrantError {}

/**
 * Decode + verify a grant blob against the pinned owner Ed25519 public key, for the clinician whose
 * signing-key fingerprint is `therapistFp`. Throws GrantError on a malformed envelope, an
 * owner-fingerprint mismatch or a bad signature, and GrantAddressError on a grant written for
 * another clinician.
 */
export function verifyGrantBlob(bytes: Uint8Array, pinnedOwnerSignPub: Uint8Array, therapistFp: string): Grant {
  const signed = decodeSignedGrant(bytes)
  const grant = verifyGrant(signed, pinnedOwnerSignPub)
  if (grant.therapistFingerprint !== therapistFp) {
    throw new GrantAddressError('the grant names a different clinician key')
  }
  return grant
}

export function hasCapability(grant: Grant, cap: Capability): boolean {
  return grant.capabilities[cap]?.granted === true
}

export function applyModeOf(grant: Grant, cap: Capability): ApplyMode | null {
  const g = grant.capabilities[cap]
  return g?.granted ? g.apply : null
}
