/*
 * THE WORDS ON THE OWNER CONSOLE'S FRONT DOOR.
 *
 * A module rather than strings in the component, for the reason lib/pairing/copy.ts is one: these
 * sentences are asserted by tests, they are the kind of sentence that gets softened by accident in
 * a hurry, and the softening is exactly what would be dangerous here.
 *
 * The register is set by components/recovery/copy.ts, which already says most of this product's
 * hard things about keys, and constants are imported from there rather than restated wherever they
 * fit. Two screens describing the same object in two voices is how a person ends up believing they
 * are two objects.
 */

/** What the console does with the key, and why it is asking. */
export const UNLOCK_LEDE =
  'This console signs what you send to a clinician, and it signs with an identity derived from the ' +
  'key in your key file. To act as you it needs that key, for this session only.'

/**
 * That nothing survives the tab.
 *
 * Said at the door rather than in a footnote, because the alternative is someone discovering it by
 * being asked for the file again and reading that as a fault.
 */
export const NOTHING_IS_KEPT =
  'Nothing here is kept between visits. Not the file, not the key, not the identity. When this tab ' +
  'closes they are gone, and you will be asked for them again next time.'

/** The file input's label and the description under it. */
export const KEY_FILE_LABEL = 'Your key file'

export const KEY_FILE_HINT =
  'The file saved from the Recovery code screen, on the Sync panel. It holds two locked boxes and ' +
  'no secret: neither your passphrase nor your code is in it or derivable from it.'

/**
 * For the owner who has no file.
 *
 * Plain text under the input rather than an empty state or a callout: not having made one yet is
 * not a fault and must not be drawn as one. It says the consequence of making a new one, because
 * that consequence is the entire defect this screen was rebuilt to remove — a new key is a new
 * identity, and a clinician holding the old fingerprint will not match it.
 */
export const NO_KEY_FILE_YET =
  'If you have not made one, the Recovery code screen makes one. A key made there is a new key with ' +
  'a new identity, so anything a clinician has already written down will not match it.'

/** Offering the recovery code at the door. */
export const USE_CODE_INSTEAD = 'Use my recovery code instead'
export const USE_PASSPHRASE_INSTEAD = 'Use my passphrase instead'

/**
 * What using the code here does, and does not do.
 *
 * UseCodeFlow's code entry ends by re-wrapping the passphrase slot. This one does not, and someone
 * who has used that screen will reasonably expect it to.
 */
export const CODE_OPENS_THIS_SESSION =
  'Using the code here opens the key for this session and changes nothing else. To set a new ' +
  'passphrase, use the Recovery code screen.'

/** The verb. Not "generate", and not "sign in" — nothing is created and no account is entered. */
export const UNLOCK_ACTION = 'Unlock'

/** Argon2id at the floor takes seconds, and a button that looks stuck reads as a broken button. */
export const UNLOCK_BUSY = 'Opening your key — this takes a few seconds'

/**
 * The fingerprint line, after unlocking.
 *
 * The old screen showed a fingerprint that was true for one tab. Saying it is now stable is the
 * whole user-visible content of this fix, and it is what makes reading it aloud worth doing.
 */
export const FINGERPRINT_IS_STABLE =
  'With this key file it is the same every time. A clinician who wrote it down can check it against ' +
  'what they see.'

/* ── Adding a clinician, and the token that comes with it ─────────────────────────────────── */

/**
 * What adding a clinician does now, said before the button rather than after it.
 *
 * The field it replaces asked for an "Inbox token (OOB)" and accepted anything at all, so the
 * secret guarding a person's journal was whatever the owner typed (issue #126). There is no field
 * now, so the sentence has to say where the value comes from instead — otherwise the first time
 * anybody meets the token is as forty-three characters that appeared on their screen unannounced.
 */
export const ADDING_MINTS_A_TOKEN =
  'Only a name is needed to start. Adding them makes the token that routes their requests to this ' +
  'one relationship, and the token is shown once, here.'

/** The token's own label. "Inbox token" is what both consoles and the server call it. */
export const INBOX_TOKEN_LABEL = 'Their inbox token'

/**
 * The two-channel rule, at the point it is broken or kept.
 *
 * Word for word the job pairing/copy.ts's `twoChannels` does for the pairing code, and it has to be
 * said again here because this is a different secret on a different screen. The second sentence is
 * the correction the audit asked for: onboarding/fieldHelp.ts told clinicians this value was in the
 * invitation, and it never has been — the mail message has no field for it and the mint API is
 * given the digest, so the server has never held the value to put in one.
 */
export const INBOX_TOKEN_TWO_CHANNELS =
  'Give it to them some way other than the invitation — say it out loud, send it by text, hand it ' +
  'over in the room. It is not in the invitation and cannot be put there.'

/**
 * That this is the only sighting.
 *
 * Not a warning and not an instruction to write it down somewhere: it says what is true and leaves
 * the owner to decide. The last clause is the consequence, because "shown once" without a way
 * forward reads as a threat.
 */
export const INBOX_TOKEN_SHOWN_ONCE =
  'This console shows it here and nowhere else, and nothing on this machine writes it down. If it ' +
  'goes astray, adding this clinician again makes a new token and a new relationship — what was ' +
  'already shared under the old one stays as it is.'
