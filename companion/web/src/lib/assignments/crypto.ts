/*
 * Assignment / game-plan write-back crypto (therapist → owner). One primitive:
 *   payload ──Ed25519.sign(therapist)──▶ signed ──crypto_box_seal(owner X25519 pub)──▶ blob
 * The server stores an opaque blob. The owner opens the sealed box with their X25519 keypair,
 * then verifies the signature against the therapist's OUT-OF-BAND-PINNED Ed25519 public key
 * (NOT a key taken from the blob). Confidentiality + owner-only readability + authenticated
 * authorship; matches docs/COMPANION_SECURITY.md §4 and docs/COMPANION_ASSIGNMENTS.md §2.
 *
 * PADDING INSIDE THE SEALED BOX (#315). What is sealed is the signed envelope, the UTF-8 JSON
 * {"payloadJson":…,"sigB64":…}. Every writer seals it padded by ../padding.ts, so the stored size
 * says which size bucket an item falls in and not how long it is. Padding hides how much, never
 * when. Game plans (../therapist/gamePlan.ts) are sealed the same way, through the same two
 * functions, padEnvelope and openedEnvelope.
 *
 * How a reader tells the two forms apart: by the first byte the sealed box opens to. An envelope
 * sealed unpadded (every item stored before #315) is a JSON object and so begins with '{' (0x7B).
 * A padded one begins with the top byte of its u32 length prefix, which is 0x7B only for an
 * envelope of at least 0x7B000000 bytes; padEnvelope refuses one that large, so the rule holds by
 * construction and not by the size of today's items. Anything else must unpad strictly.
 *
 * Why that marker is enough: it is inside the sealed box, so it is covered by the encryption —
 * changing any byte of a sealed item makes it fail to open, and nobody who cannot open a box can
 * move its contents from one form to the other. Anyone can seal a NEW box to the owner (sealed
 * boxes are anonymous), and choose either form, but the signature must still verify against the
 * pinned therapist key.
 *
 * Why the marker is not in the signed payload: the signature covers payloadJson, and padding wraps
 * the envelope that carries it, so a padded and an unpadded item verify against exactly the same
 * signed bytes. Unpadding comes before the signature check only to recover that envelope, byte for
 * byte. The form carries no authority, so it needs none of the signature's; a marker in the
 * payload would make the two forms sign different bytes and change the signed format that the
 * owner's pinned-key check has always read.
 */
import _sodium from 'libsodium-wrappers-sumo'
import type { Assignment } from './types'
import { pad, unpad, PaddingError } from '../padding'

type Sodium = typeof _sodium
let sodium: Sodium | null = null
export async function initAssignmentCrypto(): Promise<Sodium> {
  if (sodium) return sodium
  await _sodium.ready
  sodium = _sodium
  return sodium
}
function s(): Sodium {
  if (!sodium) throw new Error('assignment crypto not initialized — await initAssignmentCrypto()')
  return sodium
}
const B = () => s().base64_variants.URLSAFE_NO_PADDING

export interface SignKeyPair {
  publicKey: Uint8Array // Ed25519
  privateKey: Uint8Array
}
export interface BoxKeyPair {
  publicKey: Uint8Array // X25519
  privateKey: Uint8Array
}

export function newSignKeyPair(): SignKeyPair {
  const kp = s().crypto_sign_keypair()
  return { publicKey: kp.publicKey, privateKey: kp.privateKey }
}
export function newBoxKeyPair(): BoxKeyPair {
  const kp = s().crypto_box_keypair()
  return { publicKey: kp.publicKey, privateKey: kp.privateKey }
}

/** Short, human-comparable fingerprint of a public key (for OOB pinning / display). */
export function fingerprint(publicKey: Uint8Array): string {
  return s().to_base64(s().crypto_generichash(16, publicKey), B())
}

/** The first byte of an envelope sealed unpadded: the '{' that opens a JSON object. */
const UNPADDED_FIRST_BYTE = 0x7b

/**
 * What a writer seals to the owner: the signed envelope, padded (#315, see the header). Refuses an
 * envelope so large that its length prefix would begin with '{', since openedEnvelope would then
 * read it as unpadded.
 */
export function padEnvelope(envelope: Uint8Array): Uint8Array {
  if (envelope.length >= UNPADDED_FIRST_BYTE * 2 ** 24) throw new PaddingError('envelope too large to seal')
  return pad(envelope)
}

/**
 * The signed envelope inside an opened sealed box, from either form: as it is when it begins with
 * '{' (sealed unpadded, before #315), otherwise unpadded strictly. Throws PaddingError for a padded
 * form that pad() could not have produced.
 */
export function openedEnvelope(opened: Uint8Array): Uint8Array {
  if (opened[0] === UNPADDED_FIRST_BYTE) return opened
  return unpad(opened)
}

/**
 * Assignment binding context. Bound into the SIGNED payload (like game plans' GAMEPLAN_CONTEXT
 * and shares' 'daymark.share.v2') so a signed assignment cannot be replayed as a different
 * message type, and — together with recipientOwnerFp — cannot be re-pointed at a different owner
 * and still verify.
 */
export const ASSIGNMENT_CONTEXT = 'daymark.assignment.v1' as const

/**
 * The signed payload = the assignment plus its binding fields. recipientOwnerFp is the fingerprint
 * of the owner X25519 key the blob is sealed TO; openAssignment recomputes it from the owner's own
 * key and rejects any mismatch (COMPANION_ASSIGNMENTS.md §2/§4 — "reuse the game-plan shape exactly").
 */
interface SignedAssignment {
  context: typeof ASSIGNMENT_CONTEXT
  recipientOwnerFp: string
  assignment: Assignment
}

/** Therapist signs the assignment (bound to context + recipient owner), then seals it to the owner's X25519 key. */
export function sealAssignment(a: Assignment, therapistSign: SignKeyPair, ownerBoxPub: Uint8Array): Uint8Array {
  const so = s()
  const signed: SignedAssignment = {
    context: ASSIGNMENT_CONTEXT,
    recipientOwnerFp: fingerprint(ownerBoxPub),
    assignment: a,
  }
  const payloadJson = JSON.stringify(signed)
  const sig = so.crypto_sign_detached(so.from_string(payloadJson), therapistSign.privateKey)
  const envelope = so.from_string(JSON.stringify({ payloadJson, sigB64: so.to_base64(sig, B()) }))
  return so.crypto_box_seal(padEnvelope(envelope), ownerBoxPub)
}

export class AssignmentOpenError extends Error {}

/**
 * Owner opens + verifies. Throws if the seal can't be opened (not for us / tampered), the
 * signature doesn't match the PINNED therapist key (wrong/forged author), the context is wrong
 * (message-type confusion), or recipientOwnerFp is not this owner (mis-addressed / spliced).
 */
export function openAssignment(blob: Uint8Array, ownerBox: BoxKeyPair, pinnedTherapistSignPub: Uint8Array): Assignment {
  const so = s()
  let openedBytes: Uint8Array
  try {
    openedBytes = so.crypto_box_seal_open(blob, ownerBox.publicKey, ownerBox.privateKey)
  } catch {
    throw new AssignmentOpenError('sealed box could not be opened (not addressed to this owner, or tampered)')
  }
  let env: { payloadJson?: string; sigB64?: string }
  try {
    env = JSON.parse(so.to_string(openedEnvelope(openedBytes)))
  } catch {
    throw new AssignmentOpenError('malformed assignment envelope')
  }
  if (typeof env.payloadJson !== 'string' || typeof env.sigB64 !== 'string') {
    throw new AssignmentOpenError('assignment envelope missing fields')
  }
  const ok = so.crypto_sign_verify_detached(so.from_base64(env.sigB64, B()), so.from_string(env.payloadJson), pinnedTherapistSignPub)
  if (!ok) throw new AssignmentOpenError('assignment signature does not match the pinned therapist key')
  const signed = JSON.parse(env.payloadJson) as SignedAssignment
  if (signed.context !== ASSIGNMENT_CONTEXT) {
    throw new AssignmentOpenError('unexpected assignment context')
  }
  if (signed.recipientOwnerFp !== fingerprint(ownerBox.publicKey)) {
    throw new AssignmentOpenError('assignment is addressed to a different owner')
  }
  return signed.assignment
}
