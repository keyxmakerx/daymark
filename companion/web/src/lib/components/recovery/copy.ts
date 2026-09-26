/*
 * THE RECOVERY CODE SURFACE'S STANDING STATEMENTS — every fixed sentence these screens say, in one
 * place, so that a test can read them.
 *
 * ─── WHY THE WORDS LIVE IN A MODULE RATHER THAN IN THE MARKUP ────────────────────────────────────
 *
 * Same arrangement as lib/practice/copy.ts and lib/onboarding/audience.ts, for the same reason: the
 * sentences below are not decoration. They are the product's claims about what this feature does,
 * what it costs, and — the part that matters most on this particular surface — what has not been
 * built. A claim needs a test watching it, the test environment is node with no component
 * renderer, and markup cannot be asserted over in node. A module can.
 *
 * ─── THE ONE SENTENCE THIS SURFACE EXISTS TO GET RIGHT ───────────────────────────────────────────
 *
 * [IF_BOTH_ARE_LOST]. Everything else here is furniture around it. A recovery code is a piece of
 * paper whose entire value is realised months or years after it was written, by somebody who will
 * have had no reminder in between; the only thing that makes a person copy thirty characters
 * carefully and then put the paper somewhere they will find it is understanding, at the moment they
 * are doing it, that nobody anywhere can do this for them afterwards. Soften that sentence and the
 * paper goes in a drawer half-copied, and the feature has failed in a way that will not be
 * discovered until it is far too late to fix.
 *
 * It is deliberately not hedged, not apologetic, and not reassuring. docs/COMPANION_SECURITY.md §4
 * states it flatly ("the data is gone ... That is what makes it safe and what makes it
 * unforgiving"), and docs/COMPANION_ARCHITECTURE.md §1 says explicitly that the sentence belongs
 * "where the passphrase is chosen, not in a footnote".
 *
 * ─── WHAT THE PAPER IS ATTACHED TO ───────────────────────────────────────────────────────────────
 *
 * The key is kept on the owner's server, locked under the passphrase and again under the code
 * (docs/SYNC_PROTOCOL.md §1.2), and both flows read and write it there with the owner's access
 * token (recovery/serverKey.ts). A person deciding how seriously to take a piece of paper needs to
 * know what it is attached to, so [WHERE_THE_KEY_IS] is said at the top of the panel, before either
 * flow. What is still not built — replacing a code, the phone, a split — is said as a placeholder,
 * never implied.
 *
 * ─── ON PLACEHOLDERS ─────────────────────────────────────────────────────────────────────────────
 *
 * Several parts of this feature are specified and unbuilt. They are rendered as marked placeholders
 * rather than omitted, because an omission is indistinguishable from a feature that was quietly
 * dropped, and rather than faked, because invented data is indistinguishable from a bug to somebody
 * trying to decide whether the thing works. Every entry in [PLACEHOLDERS] carries the word
 * "Placeholder" into the interface, says what would be there instead, and says where the real thing
 * is specified so a reader can go and check rather than take this module's word for it.
 *
 * ─── REGISTER ────────────────────────────────────────────────────────────────────────────────────
 *
 * Flat, plain and adult throughout. No cheer, no exclamation, no emoji, no verdict, no figure. A
 * person reading the second flow has lost their passphrase and is finding out whether years of
 * their own writing still exists; they may be reading it at two in the morning. Nothing here
 * congratulates anybody for finishing a step, because finishing a step is not an achievement, and
 * a screen that celebrates is a screen that is not listening.
 */
import type { SetUpFault } from '../../recovery/serverKey'

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   1. What the surface is.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

export const PANEL_TITLE = 'Recovery code'

/** The one-line subject, under the title. Says what the code is for before anything else. */
export const PANEL_LEDE =
  'A recovery code is a second way into your own data. It opens the same key your passphrase ' +
  'opens, so either one is enough on its own, and neither can be reconstructed from the other.'

/**
 * Where the key is, said first, because everything below reads differently once you know it: a
 * code made here is attached to the key the server keeps, and the flows reach it with the address
 * and token the sync card above holds.
 */
export const WHERE_THE_KEY_IS =
  'Your key is kept on your server, locked twice: once under your passphrase and once under your ' +
  'recovery code. The server can open neither lock. The two tabs below read the locks from it, and ' +
  'write them, with the address and access token in the card above.'

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   2. The sentence the paper depends on, and the three around it.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

/**
 * The cost of losing both secrets. Said where the code is shown, and again where it is confirmed.
 *
 * Three parties are named rather than covered by "nobody", because "nobody can help you" is read as
 * a formality by people who have watched a support agent reset a password. Naming the server, the
 * operator and the authors is what makes it land as a description of a mechanism rather than as a
 * policy somebody could be talked out of.
 */
export const IF_BOTH_ARE_LOST =
  'If you lose both your passphrase and this code, nothing opens your data again. Not the server, ' +
  'which holds ciphertext and has never held the key. Not whoever runs it. Not the people who ' +
  'wrote this software. Your entries stay on the disk, unreadable, permanently.'

/** Why that is the design rather than an oversight. */
export const WHY_NOBODY_CAN_HELP =
  'That is the same property that keeps the server ignorant. A route that could return your key to ' +
  'you could return it to somebody else, so there is no such route, and adding one would change ' +
  'what this product is rather than making it more convenient.'

/** Said at the top of the second flow. A recovery is something you start, not something you request. */
export const NOT_A_PASSWORD_RESET =
  'This is not a password reset. Nobody can begin a recovery on your behalf — not support, not a ' +
  'clinic administrator, not the person running the server. Recovery happens on this device, with ' +
  'the code in your hand, or it does not happen.'

/** The code is shown once because nothing keeps it. Stated as a fact about the software. */
export const SHOWN_ONCE =
  'The code is shown once. Nothing here writes it down for you: it is not saved, not sent, and not ' +
  'put on the clipboard. When this page closes, the only copy is the one you made.'

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   3. Getting a code.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

/**
 * The heading on the printed sheet, and only on the printed sheet.
 *
 * On screen the panel around the code says all of this. On paper the box may be the only thing
 * anybody sees in five years, cut out of a page whose context is long gone, by somebody who did not
 * print it. It has to name the product and say what the characters are for, or it is thirty
 * characters in a drawer that nobody dares throw away and nobody knows how to use.
 */
export const WHAT_THIS_OPENS =
  'Daymark recovery code. These characters open the encryption key for one person’s Daymark data. ' +
  'Besides that person’s passphrase, nothing else can.'

/**
 * What the code can do besides open the data, said wherever the code is shown and on the printed
 * sheet. The code opens the master, and the master is what the owner's signing identity is derived
 * from (owner/identity.ts), so the paper is not read-only. Written without naming a console or a
 * clinician, because the sheet is shown on servers that offer neither.
 */
export const CODE_CAN_ACT_AS_YOU =
  'Whoever holds this code can open your data and act as you, exactly as your passphrase can. Keep ' +
  'the paper where only you can reach it.'

export const WRITE_IT_ON_PAPER =
  'Copy it onto paper, in the six groups shown. Paper does not sync to anyone else’s machine, does ' +
  'not sit in a clipboard, and does not end up in a photo library. Then put the paper somewhere you ' +
  'would still find it after losing the device you are reading this on.'

/**
 * Why the confirmation asks for two groups instead of offering a checkbox.
 *
 * The checkbox version of this step measures nothing at all: it is ticked by a person who wrote
 * nothing down, in the same motion and the same second as by a person who wrote it down correctly.
 * Typing two groups back, with the code hidden, is the cheapest test that distinguishes them, and
 * the only failure it can produce is the one worth catching.
 */
export const WHY_TYPE_IT_BACK =
  'The code is hidden before you are asked for two of its groups, because a box you tick while ' +
  'looking at the code proves nothing. Reading two groups off your paper is the cheapest check that ' +
  'the paper exists, that it is legible, and that it says what the screen said.'

/** Shown beside the confirmation when a group does not match. No echo of what was shown or typed. */
export const CONFIRMATION_MISMATCH =
  'That does not match what was shown. Nothing is wrong with your copy yet — read it again, and if ' +
  'the paper is not right, show the code once more and rewrite it.'

/** The offer to see it again. Deliberately unpunished and unremarked. */
export const SHOWING_AGAIN_IS_FINE =
  'You can show the code again as many times as you need while this page is open. It is not a test.'

/** Printing and downloading, and the honest cost of each. */
export const PRINTING =
  'Print sends this page to your printer, code included. A printer with a queue on a shared machine ' +
  'keeps a copy of what it printed, so this is worth doing on a printer you own.'

export const DOWNLOAD_IS_A_PLAINTEXT_COPY =
  'A downloaded file is a plain, readable copy of the code sitting on this device, and it will be ' +
  'swept up by whatever backs this device up. Paper is the intended home. Download it if the ' +
  'alternative is not recording it at all.'

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   4. Using a code.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

export const HOW_ENTRY_WORKS =
  'Type the code one group to a box; a full group moves you on to the next. Case does not matter ' +
  'and neither do the hyphens. A recovery code never contains the characters 0, O, 1, I or L, so ' +
  'if your paper looks like it has one, it is a digit from 2 to 9 or one of the other letters.'

/**
 * Why a failed check character does not name a group, when a bad character does.
 *
 * This is the one place where the obvious helpful thing is the dishonest thing. The check character
 * proves that something in a well-formed thirty-symbol code is wrong; it cannot say what. The
 * arithmetic is a weighted sum mod 31, and its syndrome can be solved for a correction at EVERY
 * position at once — each position has some value that would explain the mismatch — so picking one
 * and pointing at it would be a guess rendered as a finding. recoveryCode.ts refuses to guess for
 * exactly this reason ("NO CORRECTION, ONLY DETECTION"), and a screen that pointed at group 3
 * anyway would send somebody to rewrite a character that was right.
 *
 * A character that is not in the alphabet is the opposite case and is positioned exactly, which is
 * what the alphabet gave up two symbols to buy.
 */
export const CHECKSUM_CANNOT_POINT =
  'The last character is a check on the other twenty-nine. It has told us that one of them is wrong ' +
  'and it cannot tell us which: every position has some value that would explain the mismatch, so ' +
  'naming one would be a guess dressed up as an answer. Read the whole code back against your paper.'

/** Shown when the code is well-formed but does not open the key the server holds. */
export const CODE_DOES_NOT_OPEN_THIS =
  'That code is well-formed and does not open the key this server holds. Either it belongs to a ' +
  'different key, or a character is wrong in a way the check character could not catch.'

/** Setting the new passphrase, once the code has opened the key. */
export const NEW_PASSPHRASE_LEDE =
  'The key is open. Setting a passphrase locks this same key again, under the new passphrase, and ' +
  'stores that lock on the server in place of the old one. The key does not change, so nothing ' +
  'encrypted under it needs encrypting again.'

export const PASSPHRASE_ADVICE =
  'A long passphrase of ordinary words is easier to remember and harder to guess than a short one ' +
  'with substitutions in it. There is no rule here about its shape and no meter scoring it.'

/**
 * The recovery code is not reissued by a passphrase change, and that is a decision rather than an
 * omission — dataKey.ts replacePassphrase() leaves every other slot alone on purpose.
 */
export const OLD_CODE_STILL_WORKS =
  'Your recovery code is unchanged and still opens this key. A new passphrase does not invalidate ' +
  'the paper in your filing cabinet. Replacing the code is a separate act, so that it is one you ' +
  'take knowingly.'

/**
 * What a passphrase change does not do, said where it is done (#258).
 *
 * The master does not move, so a changed passphrase is retired against the live server and not
 * against copies someone kept: the server hands out only the newest version and no longer serves the
 * key parameters, but older versions stay on its disk and in its backups, and they still open with
 * the old passphrase (docs/SYNC_PROTOCOL.md §1.2). Saying "your old passphrase no longer works"
 * would be the reassuring sentence that is not true. Retiring the key itself is key rotation, which
 * is not built: #297.
 */
export const PASSPHRASE_CHANGE_IS_NOT_A_REVOCATION =
  'The key itself did not change, so everything encrypted under it still opens with it. This server ' +
  'now hands out only the copy locked under the new passphrase, but a copy of the server made before ' +
  'now, such as a backup, still opens with the old one. Replacing the key itself is not built yet.'

/** After the new passphrase's lock was stored, read back and opened to the same key. */
export const PASSPHRASE_REPLACED =
  'The passphrase copy of your key on the server was replaced. The key itself did not change, so ' +
  'nothing encrypted under it needs encrypting again.'

/** A new version another device stored first (409): nothing from here was stored. */
export const REPLACE_MOVED =
  'The key on the server changed while it was open here, so the new passphrase was not stored. ' +
  'Open it again with your code.'

/**
 * The server took the new version, and what it handed back did not open to the same key. It was
 * sent, so this does not say it was not stored.
 */
export const REPLACE_UNCHECKED =
  'The server took the new passphrase copy, and what it handed back did not open to the same key. ' +
  'Read what the server holds again before relying on the new passphrase.'

/** A failure none of the above covers, which may have come after the server took the new version. */
export const REPLACE_FAILED =
  'This screen could not finish storing the new passphrase. Read what the server holds again to ' +
  'see which passphrase opens it.'

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   5. Reading the key from the server (#258).
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

/** The verb for reading what the server holds, and what it says while it does. */
export const READ_ACTION = 'Read what this server holds'
export const READ_BUSY = 'Reading what the server holds'

/** Refusals of a read. None repeats the token, and none guesses why a read failed. */
export const READ_NEEDS_TOKEN = 'Enter your access token in the card above. Nothing was sent.'
export const TOKEN_NOT_ACCEPTED = 'The server did not accept that access token, so nothing was read.'
export const READ_FAILED = 'What the server holds could not be read, so nothing has changed.'

/** "Get a code" on a server that already holds a locked key: there is no new code to make there. */
export const ALREADY_LOCKED_HERE =
  'This server already holds your key, locked under a passphrase and a recovery code, so there is no ' +
  'new code to make here. Using the code you have is the other tab.'

/** After the code is confirmed written down: where the key now is. Not a verdict, not a tick. */
export const KEY_STORED_HERE =
  'Your key is on your server now, locked under your passphrase and under the code you wrote down.'

/** "Use a code" on a server that holds no locked key: there is no code that opens anything there. */
export const NOTHING_TO_OPEN =
  'This server holds no locked key, so there is no recovery code to use with it. Getting a code, in ' +
  'the other tab, makes one.'

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   6. The placeholder catalogue.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

/** The word itself, in one place, so no placeholder can be added without the marker. */
export const PLACEHOLDER_WORD = 'Placeholder'

export interface PlaceholderNote {
  id: string
  title: string
  /** What would be there instead. A placeholder that does not say this is a gap with a label on it. */
  body: string
  /** Where the real thing is specified, so a reader can check rather than take this on faith. */
  specifiedAt: string
}

/**
 * Everything this surface would show if the parts underneath it existed.
 *
 * Each entry names a thing that is specified somewhere and unbuilt here. None of them promises a
 * date or a version: a date is a claim about a future nobody in this repository controls, and it
 * ages into a lie without anybody editing it.
 */
export const PLACEHOLDERS: PlaceholderNote[] = [
  {
    id: 'rotation',
    title: 'Replacing a code you no longer trust',
    body:
      'A code that has been photographed, emailed to yourself or left in a moving box is a live key ' +
      'to everything, and the answer is a new one that drops the old. The server takes a new version ' +
      'of the locked key, and the function that makes one exists; nothing on this screen makes one yet.',
    specifiedAt: 'src/lib/recovery/dataKey.ts rotateRecoveryCode(); docs/SYNC_PROTOCOL.md §2, PUT /v1/keydoc/{version}',
  },
  {
    id: 'devices',
    title: 'Doing any of this from the phone',
    body:
      'The phone is the trusted, secret-handling path in this product, and a browser served by the ' +
      'server it talks to is the convenience path. A recovery code belongs on the first one. Nothing ' +
      'here is wired to it.',
    specifiedAt: 'docs/COMPANION_SECURITY.md §4; the lower-assurance banner above',
  },
  {
    id: 'split',
    title: 'Splitting recovery across people or devices',
    body:
      'The access-control document also names a recovery split among people or devices you choose, ' +
      'so that no single piece of paper is the whole key. The wrapped-key format already takes more ' +
      'than two locked boxes; nothing here splits a secret.',
    specifiedAt: 'docs/COMPANION_ACCESS_CONTROL.md § Key recovery',
  },
]

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   7. Composed sentences.

   Two positioned messages that take a number. They are functions rather than strings because the
   number comes from the parse result, and a template assembled at the call site is a template that
   drifts between the three places that assemble it.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

/** The ordinals a group position needs. A code is five symbols per group, so five names suffice. */
const ORDINAL = ['first', 'second', 'third', 'fourth', 'fifth']

/**
 * "There is a mistake in group 3, at the second character."
 *
 * Only ever called for a fault that carries a definite position — a character outside the alphabet,
 * or one of the five characters the alphabet excludes so that seeing one is a diagnosis rather than
 * an ambiguity. The check character's failure does not come through here; see CHECKSUM_CANNOT_POINT.
 */
export function mistakeInGroup(group: number, positionInGroup: number): string {
  const ordinal = ORDINAL[positionInGroup - 1]
  const where = ordinal ? `, at the ${ordinal} character` : ''
  return `There is a mistake in group ${group}${where}.`
}

/**
 * "Group 4 does not match what was shown."
 *
 * Names the group and stops. It does not print the expected value, which is the secret being
 * checked — a "did you mean" here would turn the confirmation into a second way of reading the code
 * off the screen, which is the one thing the hidden-code design exists to prevent.
 */
export function groupDoesNotMatch(group: number): string {
  return `Group ${group} does not match what was shown.`
}

/**
 * "Group 4 has three characters. Each group has five."
 *
 * The length complaint is positioned too, and it is the common one: a group misread off a page, or
 * a line that wrapped in a way that dropped a character. Saying which group is the wrong length
 * saves reading all thirty back. One sentence covers both directions — short and long — because
 * the reader's next action is identical either way, which is to look at that group.
 */
export function groupLengthIsWrong(group: number, typed: number): string {
  return `Group ${group} has ${typed} ${typed === 1 ? 'character' : 'characters'}. Each group has five.`
}

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   8. Setting the key up on the server (#258).

   The set-up form (KeySetup.svelte) is the same wherever a server holds no locked key yet — the
   owner console's door, and "Get a code" on this screen — so its words are here, once. What each
   step does is recovery/serverKey.ts's; these say it to a person.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

/** What the server holds, once read: one sentence per answer that needs a set-up. No verdicts. */
export const HOLDS_KEY_PARAMETERS =
  'This server holds the key parameters your snapshots were written with, and no recovery code yet. ' +
  'Adding a recovery code locks the key your passphrase already opens, once under the passphrase and ' +
  'once under a new code, and stores the two locks here. Nothing already stored is encrypted again.'

export const HOLDS_NOTHING =
  'This server holds no key yet. Choosing a passphrase here makes your key in this tab, locks it under ' +
  'the passphrase and under a new recovery code, and stores only the two locks on the server.'

/**
 * How an enrolment proves the passphrase before it stores anything (serverKey.ts, the anchor). One
 * of the two, whichever this server allows, said before the passphrase is typed.
 */
export const ENROL_TRIES_NEWEST_SNAPSHOT =
  'Your passphrase is tried first on the newest snapshot this server stores. If it does not open it, ' +
  'nothing is stored.'

export const ENROL_ASKS_TWICE =
  'This server stores no snapshot to try your passphrase on, so it is asked for twice instead.'

/** The field labels of a set-up. */
export const NEW_PASSPHRASE_LABEL = 'Passphrase for this key'
export const SYNC_PASSPHRASE_LABEL = 'The passphrase you sync with'
export const SAME_AGAIN_LABEL = 'The same passphrase again'

/** The verbs of a set-up, and what each says while Argon2id runs several times over. */
export const FIRST_RUN_ACTION = 'Make my key'
export const FIRST_RUN_BUSY = 'Making and locking your key — this takes several seconds'
export const ENROL_ACTION = 'Add a recovery code'
export const ENROL_BUSY = 'Checking and locking your key — this takes several seconds'

/**
 * Why a set-up stored nothing. Every one is decided before anything is sent (serverKey.ts
 * SetUpFault), so each can say that nothing was stored and be true. None names a cause the screen
 * cannot know: a passphrase that does not open the newest snapshot is said to not open it, not to
 * be wrong.
 */
export const SETUP_FAULT_TEXT: Readonly<Record<SetUpFault, string>> = {
  noPassphrase: 'Enter a passphrase. Nothing has been stored.',
  typeItTwice: 'Enter the passphrase a second time. Nothing has been stored.',
  passphrasesDiffer: 'The two passphrases are different. Nothing has been stored.',
  doesNotOpenNewest: 'That passphrase does not open the newest snapshot on this server, so nothing has been stored.',
  snapshotsWithoutKey:
    'This server stores snapshots and no key parameters for them. A new key would not open them, so none was made, and nothing has been stored.',
  selfCheckFailed: 'The locked key made in this tab did not open again to the same key, so nothing has been stored.',
  alreadyLocked: 'This server already holds a locked key, so nothing has been stored.',
}

/** After a 412: the state moved between the read and the create. What changed, not who changed it. */
export const KEY_CHANGED_ON_SERVER =
  'What the server holds changed after it was read here, so nothing from here was stored. What it ' +
  'holds now is below.'

/**
 * After a create the server took, when what it handed back did not open to the same key. The key
 * was sent, so this does not say nothing was stored; the code for it is not shown, because it may
 * open nothing the server hands out.
 */
export const READ_BACK_DID_NOT_MATCH =
  'The server took the new key, and what it handed back did not open to the same key, so no recovery ' +
  'code is shown. Read what the server holds again to see where things stand.'

/**
 * After a create the server took (201), when the read-back could not be read however many times it
 * was asked. The code IS shown with this, because the lock it opens is on the server; the sentence
 * says what is not known and what to do, in that order, and names the button that sits under it.
 */
export const READ_BACK_FAILED =
  'The server accepted your key, but it could not be read back to check just now. Write your ' +
  'recovery code down, then use Read what this server holds to check it.'

/**
 * A set-up that failed in a way none of the above covers. It does not say nothing was stored: the
 * failure may have come after the server took the key, and the next read says which.
 */
export const SETUP_FAILED = 'The key could not be set up. Read what the server holds again to see where things stand.'
