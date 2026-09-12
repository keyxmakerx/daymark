/*
 * SENDING THE OWNER'S KEY TO A CLINICIAN — the words for an act that cannot be undone.
 *
 * `owner_keys` is insert-only by primary key on the server (AuthStore.registerOwnerKeys is
 * INSERT OR IGNORE, with no update path and no delete). The first key published for a relationship
 * is that relationship's key permanently. That is deliberate and worth keeping: a table that could
 * be updated would make a hostile server substituting its own key indistinguishable, from the
 * clinician's side, from the owner rotating theirs — which is the one thing the pinning design
 * exists to catch.
 *
 * The cost of keeping it lands on a person, so it is said before the button rather than after: if
 * they lose both their passphrase and their recovery code, they lose the identity, and the
 * connection is over rather than repairable.
 */

/**
 * The sentence at the moment of first publish.
 *
 * Names the consequence, not a cause. Does not say the data is gone, because it is not — the
 * journal is a separate loss with its own copy, and conflating them would make this screen carry a
 * warning that belongs elsewhere. Does not congratulate, and does not promise recovery.
 */
export const CANNOT_BE_REPLACED =
  'Once this key is sent to a clinician it cannot be replaced: if you ever lose both your ' +
  'passphrase and your recovery code, the connection with them ends and you would need to invite ' +
  'them again.'

/** What the act is, in the words of what it lets the other person do. */
export const PUBLISH_LEDE =
  'Your clinician checks everything you send against this key. Until it is here, their console has ' +
  'nothing to check against.'

export const PUBLISH_ACTION = 'Send my key to this server'
export const PUBLISH_BUSY = 'Sending'

/** Already there, and it is the key this console just derived. */
export const ALREADY_YOURS =
  'This connection already carries your key. Sending it again changes nothing, and there is nothing ' +
  'further to do here.'

/**
 * Already there, and it is NOT the key this console derived.
 *
 * The serious one. It means the relationship is pinned to a key the owner cannot produce — the
 * shape of someone who published, then lost their key file and made a new one. Nothing here can
 * repair it, so the copy says what is true and what the way out actually is, rather than offering
 * a button that would answer 409 again.
 */
export const DIFFERENT_KEY_PUBLISHED =
  'A different key is already published for this connection, and it cannot be replaced. Anything ' +
  'you sign with the key you unlocked today will not match it, so this clinician cannot verify ' +
  'you. Starting again with them means adding them as a new connection, with a new inbox token.'

/** The read-back failed. Says what is not known, and does not guess. */
export const COULD_NOT_CHECK =
  'This console could not read what is published for this connection, so it cannot say whether your ' +
  'key is already there. Nothing has been sent.'

/* ── What is actually published, compared against what this console holds ─────────────────── */

/** Public key pair as it travels: two base64url strings, never bytes. */
export interface PublicKeyPairB64 {
  signPubB64: string
  boxPubB64: string
}

/**
 * What the server holds for this relationship, relative to the identity this console derived.
 *
 * `absent` is an ordinary state, not a fault — it is what every relationship looks like before the
 * owner has sent their key.
 */
export type PublishState = 'absent' | 'mine' | 'different'

/**
 * Compared on BOTH halves, and that is the whole content of this function.
 *
 * A record matching the signing key but not the encryption key is not a near-miss to be waved
 * through. It is a record this console did not write, and calling it `mine` would tell an owner
 * their connection is fine while half of what a clinician pins belongs to somebody else. The same
 * reasoning the server's own route header gives for refusing a request whose relRef disagrees with
 * its session: a disagreement is not a confused client.
 */
export function classifyPublishedKey(published: PublicKeyPairB64 | null, mine: PublicKeyPairB64): PublishState {
  if (!published) return 'absent'
  const same = published.signPubB64 === mine.signPubB64 && published.boxPubB64 === mine.boxPubB64
  return same ? 'mine' : 'different'
}
