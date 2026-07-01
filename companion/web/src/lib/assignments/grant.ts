/*
 * Owner-signed capability GRANTS (owner → therapist).
 *
 * A Grant is the owner's per-therapist permission policy (see types.ts). It is NOT secret — the
 * therapist is allowed to read it — so it is signed, not sealed: the owner Ed25519-signs the
 * canonical grant JSON and publishes {payloadJson, ownerSigB64, ownerSigningFp}. The therapist
 * verifies the signature against the OOB-PINNED owner key before trusting it; nobody but the
 * owner can forge or edit a grant.
 *
 * Revoke = set granted:false and re-sign as a new append-only version. NB (honest limit):
 * revocation only stops FUTURE server-mediated delivery; already-delivered material is not
 * clawed back — a true cutoff is a re-key (out of scope here). Copy in the UI states this.
 */
import _sodium from 'libsodium-wrappers-sumo'
import type { Capability, CapabilityGrant, Grant, ApplyMode } from './types'
import { ALL_CAPABILITIES } from './types'
import { fingerprint, type SignKeyPair } from './crypto'

const URLSAFE = () => _sodium.base64_variants.URLSAFE_NO_PADDING
const enc = new TextEncoder()

/** The published, owner-signed grant envelope. */
export interface SignedGrant {
  payloadJson: string // canonical Grant JSON (the exact bytes that were signed)
  ownerSigB64: string // Ed25519 detached signature over payloadJson
  ownerSigningFp: string // owner Ed25519 fingerprint (the therapist pins this)
}

export class GrantError extends Error {}

/** An empty grant for a therapist: every capability present but ungranted (default-OFF). */
export function emptyGrant(therapistFingerprint: string): Grant {
  const capabilities: Partial<Record<Capability, CapabilityGrant>> = {}
  for (const cap of ALL_CAPABILITIES) {
    capabilities[cap] = { granted: false, apply: 'propose' }
  }
  return { therapistFingerprint, capabilities }
}

/**
 * Set a capability's grant. `suggest.setting` can never be `auto` — it is always propose/accept —
 * so we coerce it here as a structural guarantee (defense in depth alongside shouldAutoApply).
 */
export function setCapability(grant: Grant, cap: Capability, granted: boolean, apply: ApplyMode): Grant {
  const safeApply: ApplyMode = cap === 'suggest.setting' ? 'propose' : apply
  return {
    ...grant,
    capabilities: { ...grant.capabilities, [cap]: { granted, apply: safeApply } },
  }
}

/** Revoke a capability (granted:false), preserving the apply mode for display. */
export function revoke(grant: Grant, cap: Capability): Grant {
  const current = grant.capabilities[cap]
  return setCapability(grant, cap, false, current?.apply ?? 'propose')
}

/**
 * Canonical grant bytes — stable key order via explicit construction so the signed bytes are
 * deterministic (capabilities emitted in ALL_CAPABILITIES order, only granted-present keys).
 */
export function serializeGrant(grant: Grant): string {
  const capabilities: Record<string, CapabilityGrant> = {}
  for (const cap of ALL_CAPABILITIES) {
    const g = grant.capabilities[cap]
    if (g) capabilities[cap] = { granted: g.granted, apply: g.apply }
  }
  return JSON.stringify({ therapistFingerprint: grant.therapistFingerprint, capabilities })
}

/** Owner signs a grant, producing the publishable envelope. */
export function signGrant(grant: Grant, ownerSign: SignKeyPair): SignedGrant {
  const payloadJson = serializeGrant(grant)
  const sig = _sodium.crypto_sign_detached(enc.encode(payloadJson), ownerSign.privateKey)
  return {
    payloadJson,
    ownerSigB64: _sodium.to_base64(sig, URLSAFE()),
    ownerSigningFp: fingerprint(ownerSign.publicKey),
  }
}

/**
 * Verify + parse a signed grant against a PINNED owner key. Throws GrantError if the envelope's
 * ownerSigningFp is not the pinned one, or the signature does not verify. (This is the check the
 * therapist client also runs; used here so the owner UI can round-trip / self-verify.)
 */
export function verifyGrant(signed: SignedGrant, pinnedOwnerSignPub: Uint8Array): Grant {
  const pubFp = fingerprint(pinnedOwnerSignPub)
  if (signed.ownerSigningFp !== pubFp) {
    throw new GrantError('grant owner fingerprint does not match the pinned owner key')
  }
  const ok = _sodium.crypto_sign_verify_detached(
    _sodium.from_base64(signed.ownerSigB64, URLSAFE()),
    enc.encode(signed.payloadJson),
    pinnedOwnerSignPub,
  )
  if (!ok) throw new GrantError('grant signature does not verify against the pinned owner key')
  return JSON.parse(signed.payloadJson) as Grant
}

/** Serialize a signed grant to opaque bytes for the (zero-knowledge) blob store. */
export function encodeSignedGrant(signed: SignedGrant): Uint8Array {
  return enc.encode(JSON.stringify(signed))
}

export function decodeSignedGrant(bytes: Uint8Array): SignedGrant {
  const obj = JSON.parse(new TextDecoder().decode(bytes)) as SignedGrant
  if (typeof obj.payloadJson !== 'string' || typeof obj.ownerSigB64 !== 'string' || typeof obj.ownerSigningFp !== 'string') {
    throw new GrantError('malformed signed grant envelope')
  }
  return obj
}
