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
 * are two objects. The set-up form's words — for a server holding no key yet, or only key
 * parameters — are that module's too, because the Recovery code screen shows the same form.
 */

/** What the console does with the key, and why it is asking. */
export const UNLOCK_LEDE =
  'This console signs what you send to a clinician, and it signs with an identity derived from your ' +
  'key. To act as you it needs that key, for this session only.'

/**
 * That nothing survives the tab.
 *
 * Said at the door rather than in a footnote, because the alternative is someone discovering it by
 * being asked again and reading that as a fault.
 */
export const NOTHING_IS_KEPT =
  'Nothing here is kept between visits: not your key, and not the identity it gives. When this tab ' +
  'closes they are gone, and the console asks again next time.'

/* ── Where the key is (#258) ──────────────────────────────────────────────────────────────── */

/**
 * Where the key comes from, said before the token is asked for. The server keeps the master locked
 * under each secret and can open neither (docs/SYNC_PROTOCOL.md §1.2), which is the fact that makes
 * handing the locks to this page with a token acceptable.
 */
export const KEY_IS_ON_THE_SERVER =
  'This console reads your key from your server, where it is kept locked twice: once under your ' +
  'passphrase and once under your recovery code. Either one opens it. The server can open neither ' +
  'lock. The key is opened here, in this tab, and only for this session.'

/** The verb for reading what the server holds. The console's own connection panel uses the same. */
export const CONNECT_ACTION = 'Connect'
export const CONNECT_BUSY = 'Reading what this server holds'

/**
 * What a server holding a locked key holds, once read. A fact about the server, no verdict. The
 * other two answers lead to the set-up form, whose words are components/recovery/copy.ts's.
 */
export const HOLDS_A_LOCKED_KEY = 'This server holds your key, locked. Open it with your passphrase or your recovery code.'

/** Refusals at the connect step. The token is never repeated in any of them. */
export const CONNECT_NO_TOKEN = 'Enter your owner access token. Nothing was sent.'
export const CONNECT_REFUSED = 'This server did not accept that access token, so nothing was read.'
export const CONNECT_FAILED = 'This console could not read what this server holds, so nothing has been unlocked.'

/**
 * Under the new code, once the server has taken the key and it was read back and opened. Under it,
 * not above: the one line above a code is CodeSheet's (ONLY_TIME_SHOWN). The same sentence as the
 * Recovery code screen's KEY_STORED_HERE, pointing at the code on screen instead of the paper.
 */
export const KEY_STORED_WITH_THIS_CODE =
  'Your key is on your server now, locked under your passphrase and under the recovery code above.'

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
 * whole user-visible content of this fix, and it is what makes reading it aloud worth doing. Both
 * secrets open the same master, so both give this fingerprint (owner/identity.test.ts, three doors).
 */
export const FINGERPRINT_IS_STABLE =
  'It is the same every time, whether your passphrase or your recovery code opened the key. A ' +
  'clinician who wrote it down can check it against what they see.'

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
