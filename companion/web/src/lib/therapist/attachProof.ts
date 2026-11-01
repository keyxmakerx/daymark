/*
 * ATTACHING A RELATIONSHIP BY KEY PROOF (#314).
 *
 * A clinician whose relationships were each made with their own sign-in, or whose lost account an
 * administrator closed, attaches each relationship to the account they are signed in with by
 * proving the clinician key that relationship was paired with: the Ed25519 key its owner pinned,
 * opened here from this browser's key record with the reading passphrase. Nobody else can do it for
 * them, because the key is in their browser alone.
 *
 * The server hands out a challenge, good once for ten minutes, for this account and this
 * relationship. The browser signs `attachMessage` over it and sends the signature. The message is
 * the same bytes companion/server .../routes/StaffAccountRoutes.kt builds, one field per line, and
 * attachProof.test.ts holds the vector both sides are checked against.
 *
 * Nothing secret leaves the browser: only the signature. The keys are the caller's to zeroize.
 */
import { initAssignmentCrypto } from '../assignments/crypto'
import type { TherapistKeys } from './keyStore'
import type { AttachChallenge, PortalClient, SessionInfo } from './session'

/** The first line of every attach message; nothing else a clinician signs begins with it. */
export const ATTACH_CONTEXT = 'daymark-staff-attach-v1'

/** The bytes signed: the context, the account, the relationship, the server's address and the challenge. */
export function attachMessage(accountId: string, relRef: string, address: string, challenge: string): Uint8Array {
  return new TextEncoder().encode(`${ATTACH_CONTEXT}\n${accountId}\n${relRef}\n${address}\n${challenge}`)
}

/** What the clinician is told when the server refused a proof: the same words whichever check failed. */
export const ATTACH_REFUSED_COPY =
  'This relationship was not added to your sign-in. The key this browser holds for it is not the one ' +
  'it was paired with, or it can no longer be added. Nothing was changed.'

export type AttachOutcome = 'attached' | 'refused'

/**
 * Ask for a challenge, sign it with [keys] (the relationship's own, opened by the caller), and send
 * it. Refuses to sign a challenge that names another relationship than the one asked about, so a
 * server cannot get this browser's signature for a relationship it was not asked to attach.
 */
export async function attachByKeyProof(
  client: PortalClient,
  session: SessionInfo,
  relRef: string,
  keys: TherapistKeys,
): Promise<AttachOutcome> {
  const issued: AttachChallenge = await client.attachChallenge(session, relRef)
  if (issued.relRef !== relRef) return 'refused'
  const so = await initAssignmentCrypto()
  const signature = so.crypto_sign_detached(
    attachMessage(issued.accountId, issued.relRef, issued.address, issued.challenge),
    keys.sign.privateKey,
  )
  const signatureB64 = so.to_base64(signature, so.base64_variants.URLSAFE_NO_PADDING)
  return (await client.attachByKeyProof(session, issued.challengeId, relRef, signatureB64)) ? 'attached' : 'refused'
}
