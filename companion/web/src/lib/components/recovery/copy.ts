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
 * and docs/PLAN_2026-08-COMPANION-NEXT.md §3.11.1 both state it flatly ("forget it and the data is
 * gone... This is what makes it safe and what makes it unforgiving"), and the plan says explicitly
 * that it must be said "where the passphrase is chosen, not in a footnote".
 *
 * ─── THE OTHER THING THIS SURFACE MUST NOT DO ────────────────────────────────────────────────────
 *
 * There is no transport and no storage for a wrapped key: no wire format, no endpoint, no client
 * call. src/lib/recovery/migration.ts says so in its own words and names them as deliberately
 * unimplemented. So this interface can generate a real code and can really open a real wrapped key,
 * and it cannot yet recover anything, because nothing keeps the wrapped key between one visit and
 * the next.
 *
 * A screen that implied otherwise would be the worst thing on this surface by a distance. Somebody
 * would write a code onto paper, file it, and find out at the moment they needed it that there had
 * never been anything for it to open. [STORAGE_IS_NOT_BUILT] is therefore said at the top of the
 * panel, before either flow, rather than as a caveat under one of them.
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

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   1. What the surface is.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

export const PANEL_TITLE = 'Recovery code'

/** The one-line subject, under the title. Says what the code is for before anything else. */
export const PANEL_LEDE =
  'A recovery code is a second way into your own data. It opens the same key your passphrase ' +
  'opens, so either one is enough on its own, and neither can be reconstructed from the other.'

/**
 * The build state, said first, because everything below reads differently once you know it.
 *
 * The crypto is finished and tested; the interface is a first pass; the storage between them does
 * not exist. Saying which half you are looking at is not modesty — unmarked scaffolding is the most
 * expensive kind of confusion to unpick later.
 */
export const PANEL_BUILD_STATE =
  'First interface over a finished crypto module. The code generation, the check character and the ' +
  'wrapped key are real and tested. What is missing is everything between them and a server, so ' +
  'the two flows below hand a wrapped key to each other inside this page rather than storing one.'

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
 * The sentence that stops a sheet printed today from being trusted in five years.
 *
 * Printed only, because it is about the build the paper came out of rather than about the feature.
 * A screen is re-read; a sheet of paper is filed once and believed later, so the paper is the one
 * that has to carry its own date-stamp of honesty.
 */
export const PRINT_SHEET_CAVEAT =
  'This sheet was printed from a build with no storage for the wrapped key, so this code opens ' +
  'nothing outside the page it was made in. Keep it as a rehearsal rather than as a recovery.'

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

/**
 * The key wrapped in this flow is new, and is not the key an existing archive is encrypted under.
 *
 * This is the difference between "generate a recovery code" and "enrol the archive I already have",
 * and it is invisible from the screen unless it is said. migration.ts implements the second one
 * cryptographically and cannot run it, because reproducing an existing master needs the published
 * key parameters, and reading those needs the transport that does not exist.
 */
export const NEW_KEY_NOT_YOUR_ARCHIVE =
  'The key wrapped here is generated on this device, now. It is not the key your existing snapshots ' +
  'are encrypted under. Enrolling an archive you already have means wrapping the key your passphrase ' +
  'already derives, which needs the stored key document this build does not have.'

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

/** Shown when the code is well-formed but does not open this wrapped key. */
export const CODE_DOES_NOT_OPEN_THIS =
  'That code is well-formed and does not open this wrapped key. Either it belongs to a different ' +
  'one, or a character is wrong in a way the check character could not catch.'

/** Setting the new passphrase, once the code has opened the key. */
export const NEW_PASSPHRASE_LEDE =
  'The data key is open. Setting a passphrase wraps that same key a second way; it does not change ' +
  'the key, so nothing that was encrypted under it needs re-encrypting.'

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
 * The migration hazard, said where a passphrase is changed rather than left in a source comment.
 *
 * migration.ts spells it out: after enrolment a migrated owner has two routes to the same master —
 * the new wrapped slot, and the key parameters still published from before — and re-wrapping the
 * slot does not revoke the second one. Somebody who changes their passphrase reasonably believes
 * the old one stopped working. For a migrated owner it did not. The dishonest option is to show a
 * reassuring sentence and say nothing; this is the sentence instead.
 */
export const PASSPHRASE_CHANGE_IS_NOT_A_REVOCATION =
  'For an archive that was enrolled from an older setup, the previous passphrase can still derive ' +
  'the key through the key parameters published at that time, and changing it here would not remove ' +
  'those. A passphrase change becomes complete only once that older record is gone.'

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   5. What stands in for storage, and is not storage.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

/**
 * The top-of-panel statement. Everything on the surface reads differently once this is known, so it
 * is said before either flow rather than under one of them.
 */
export const STORAGE_IS_NOT_BUILT =
  'Nothing stores the wrapped key yet. There is no wire format for it, no endpoint that accepts one, ' +
  'and no client call that would send one. Everything on this screen runs on this device and reaches ' +
  'no server at all, so a code made here opens nothing outside this page. This is not yet a working ' +
  'recovery, and a code from this build is not yet worth filing.'

export const HANDOFF_IS_A_STAND_IN =
  'The wrapped key is held in this page’s memory so the second flow has something real to open. ' +
  'Reload the tab and it is gone. This stands in for storage; it is not storage.'

export const FILE_IS_A_STAND_IN =
  'Saving the wrapped key to a file is the same stand-in written to disk, so both flows can be tried ' +
  'across a reload. It is not a wire format, and no server would accept it. The file holds two ' +
  'locked boxes and no secret: neither your passphrase nor your code is in it or derivable from it.'

export const NOTHING_TO_OPEN =
  'There is no wrapped key in this page to open. Nothing fetches one, because no endpoint serves ' +
  'one. Make one in the other flow, or load a file you saved there.'

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
    id: 'storage',
    title: 'Storing the wrapped key',
    body:
      'The two locked boxes have to live somewhere a new device can fetch them, next to the key ' +
      'parameters the server already publishes. That means a document format, a route that reads ' +
      'and writes it, and the client calls either side. None of the three exists, which is why this ' +
      'panel hands the wrapped key between its own two flows instead.',
    specifiedAt: 'src/lib/recovery/migration.ts, “WHAT IS AND IS NOT IMPLEMENTED HERE”',
  },
  {
    id: 'enrolment',
    title: 'Enrolling an archive that already exists',
    body:
      'Somebody with snapshots on a server keeps their existing key and wraps it, so not one ' +
      'snapshot is re-encrypted and the manifest signing identity does not change. The cryptographic ' +
      'half of that is written and tested; running it needs the published key parameters, which ' +
      'needs the transport above.',
    specifiedAt: 'src/lib/recovery/migration.ts enrolExistingOwner(), and its test',
  },
  {
    id: 'rotation',
    title: 'Replacing a code you no longer trust',
    body:
      'A code that has been photographed, emailed to yourself or left in a moving box is a live key ' +
      'to everything, and the answer is a new one that drops the old. The function exists and takes ' +
      'a wrapped key and an open data key; publishing the result needs somewhere to publish it to.',
    specifiedAt: 'src/lib/recovery/dataKey.ts rotateRecoveryCode()',
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
