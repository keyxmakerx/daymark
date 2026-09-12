/*
 * MINTING A RELATIONSHIP'S INBOX TOKEN.
 *
 * WHAT IT IS. The raw secret every request for relationship content carries in `X-Rel-Token`. The
 * server stores only BLAKE2b-256 of it and derives the relationship reference from that digest, so
 * the token names the relationship AND authenticates the caller's right to route to it. It is one
 * of two factors — a request still needs an owner bearer token or a therapist session — which is
 * why a weak one is not directly a read. It is what makes a stolen database useless, and that is
 * the property a weak one quietly removes.
 *
 * WHY THIS MODULE EXISTS AT ALL. Nothing in this repository generated one (issue #126). The only
 * way a token entered the system was a text box on the owner console whose entire validation was
 * "not empty", so `"a"` produced a perfectly valid relationship reference, while
 * docs/COMPANION_SECURITY.md described the value as a 256-bit CSPRNG. The documentation was not
 * describing the code; it was describing an intention nobody had implemented.
 *
 * THE TYPED PATH IS DELETED, NOT VALIDATED. A length floor and a charset would have stopped `"a"`
 * and left the shape of the defect in place: a secret whose strength is whatever the person at the
 * keyboard thought of. There is no argument for a hand-chosen token here — it is never remembered
 * by a human and never spoken from memory, it is read off a screen either way — so the generate
 * path replaces the field rather than guarding it. The same reasoning as OwnerUnlock's deleted
 * "Generate owner keys" button, pointing the other way.
 *
 * WHY COLLISIONS NEED NO CHECK. Two clinicians holding one token share a relationship reference and
 * can each read the other's material, and the old console could not have noticed: its pending id
 * was `pending:${token.slice(0,8)}:${index}`, so the list index made two identical tokens render as
 * two different-looking rows. With 256 bits from a CSPRNG a collision is not something to defend
 * against, it is arithmetic — see inboxToken.test.ts, which asserts the property rather than adding
 * a guard that could only ever fire on a defect in libsodium.
 */
import { initAssignmentCrypto } from '../assignments/crypto'
import { toBase64 } from '../share/sharecrypto'

/** 32 bytes. The number docs/COMPANION_SECURITY.md has always claimed for this value. */
export const INBOX_TOKEN_BYTES = 32

/** 32 bytes of base64url with no padding. Fixed, so a truncated paste is a different length. */
export const INBOX_TOKEN_CHARS = 43

/** The alphabet base64url-unpadded draws from, and nothing else — no prefix, no separators. */
export const INBOX_TOKEN_SHAPE = /^[A-Za-z0-9_-]{43}$/

/**
 * A fresh token, from libsodium's CSPRNG.
 *
 * ASYNC BECAUSE SODIUM IS. The caller on the owner console has already initialised it — the unlock
 * that produced the owner identity did — so this resolves immediately there. Awaiting rather than
 * assuming is what keeps the module usable from a test that has not.
 */
export async function mintInboxToken(): Promise<string> {
  const sodium = await initAssignmentCrypto()
  return toBase64(sodium.randombytes_buf(INBOX_TOKEN_BYTES))
}
