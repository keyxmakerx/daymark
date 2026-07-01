/*
 * Therapist-side GRANT verification. The owner publishes an Ed25519-signed Grant on the `grants`
 * channel (opaque blob = the {payloadJson, ownerSigB64, ownerSigningFp} envelope from
 * ../assignments/grant.ts). The therapist fetches it and VERIFIES the signature against the
 * OOB-PINNED owner Ed25519 key before trusting it or rendering any granted UI.
 *
 * SECURITY BOUNDARY: the portal treats the server's grant blob as UNTRUSTED until verifyGrant()
 * succeeds against the pinned owner key. The local grant is a UX pre-flight only — the owner's
 * device is the authoritative gate. A grant that fails verification (forged / substituted owner
 * key / unpinned) yields NO granted capabilities.
 */
import type { Capability, Grant, ApplyMode } from '../assignments/types'
import { verifyGrant, decodeSignedGrant, GrantError, type SignedGrant } from '../assignments/grant'

export { GrantError }
export type { SignedGrant }

/**
 * Decode + verify a grant blob against the pinned owner Ed25519 public key. Throws GrantError on a
 * malformed envelope, an owner-fingerprint mismatch, or a bad signature.
 */
export function verifyGrantBlob(bytes: Uint8Array, pinnedOwnerSignPub: Uint8Array): Grant {
  const signed = decodeSignedGrant(bytes)
  return verifyGrant(signed, pinnedOwnerSignPub)
}

export function hasCapability(grant: Grant, cap: Capability): boolean {
  return grant.capabilities[cap]?.granted === true
}

export function applyModeOf(grant: Grant, cap: Capability): ApplyMode | null {
  const g = grant.capabilities[cap]
  return g?.granted ? g.apply : null
}
