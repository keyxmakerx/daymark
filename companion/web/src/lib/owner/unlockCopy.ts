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
