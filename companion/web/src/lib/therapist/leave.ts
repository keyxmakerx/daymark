/*
 * A CLINICIAN PUTTING DOWN THEIR OWN ACCESS (issue #91, Slice E).
 *
 * An ordinary professional act — leaving a practice, retiring, no longer needing this — and the
 * copy and the colours treat it as one. What it must never be is a route to touching anything of
 * the owner's.
 *
 * ─── WHAT IT ACTUALLY DOES: EXACTLY THREE OPERATIONS, IN THIS ORDER ─────────────────────────────
 *
 * All three are named here so the claim "this cannot destroy anything belonging to the owner" is
 * checkable rather than asserted:
 *
 *   1. Ask the server to end the relationship. One insert-only row; the server then closes this
 *      clinician's sign-in for it and cuts every session the credential holds.
 *   2. Remove this relationship's KeyRecord from THIS BROWSER's localStorage, and verify it went.
 *   3. End the current session from this browser's side.
 *
 * Nothing else is called. No share is revoked, no blob is written or deleted, no grant is touched,
 * no owner-side table is reached. The owner's entries, the material they shared, and their record
 * of having shared it are all exactly as they were.
 *
 * ─── IT WAS TWO OPERATIONS, AND WHY THAT WAS NOT ENOUGH ─────────────────────────────────────────
 *
 * The first version of this module forgot the record and signed out, and its own header said
 * plainly that this was NOT an off switch: the server's credential table is insert-only, so the
 * clinician's TOTP row survived and only their sessions died. A saved copy of the key record — which
 * the acceptance screen offers as text to keep somewhere, on purpose — plus the passphrase plus the
 * authenticator still opened the door from another browser. A clinician who needed to be CERTAIN
 * they were out could not get there from a screen.
 *
 * The server route that closes the credential now exists, so it does. The closure is a row in a
 * SEPARATE insert-only table that the sign-in path consults; the `totp` table is still never
 * deleted from and never updated. See companion/server .../routes/RelationshipEndingRoutes.kt.
 *
 * ─── WHY THE ORDER IS SERVER, THEN FORGET, THEN LOG OUT ─────────────────────────────────────────
 *
 * SERVER FIRST, because it is the only step whose failure can be reported honestly. Nothing local
 * has happened yet, so a refusal here is a leave that did not happen: the keys are still in the
 * browser, the session is still live, nothing was said to anybody, and a retry is a real offer
 * rather than a retry of something that cannot be retried. Doing it last would mean forgetting the
 * keys and then failing to close the door — the worst state available, because the clinician can no
 * longer read anything and is also not out.
 *
 * FORGET BEFORE LOGOUT, unchanged from the first version. The other way round leaves somebody
 * looking at a signed-out screen with their keys still on the device, which is the state most
 * likely to be read as "done".
 *
 * A FAILED LOGOUT IS STILL A COMPLETED LEAVE. By then the server has closed the sign-in and cut
 * every session, so this last call is housekeeping; reporting the whole thing as failed would
 * invite a retry of something already finished. It is reported separately so the screen can say the
 * one true thing rather than glossing it.
 *
 * ─── WHY THE LOCAL WRITE IS VERIFIED RATHER THAN TRUSTED ────────────────────────────────────────
 *
 * forgetKeyRecord() is best-effort by construction: its one other caller is the rollback after a
 * refused enrolment, a path that is already failing, where turning a failed enrolment into a second
 * different error would bury the message the person needs. Its header says so.
 *
 * This is the opposite case. Here the clinician has ASKED for exactly that outcome, and a swallowed
 * write means telling somebody their keys are gone while the keys are still in the browser they are
 * looking at. So the record is re-read afterwards and the result says which actually happened.
 */
import { findKeyRecord, forgetKeyRecord, type KeyRecordStorage } from './inviteAccept'

/** Everything this needs, as ports, so the order and the failure paths are node tests. */
export interface LeavePorts {
  /**
   * Ends the relationship server-side: the ending row, and the closure of this clinician's sign-in
   * for it. Resolves when the server has it; rejects when it does not, and a rejection is a leave
   * that did not happen.
   */
  readonly serverLeave: () => Promise<void>
  /** localStorage, or whatever stands in for it. */
  readonly storage: KeyRecordStorage | null
  /** Ends the session from this side. Failure here is reported, never fatal — see the header. */
  readonly logout: () => Promise<void>
}

export type LeaveOutcome =
  /** The relationship is ended and the record is gone. Whether the sign-out landed is separate. */
  | { ok: true; signedOut: boolean }
  /** Nothing happened. Nothing was claimed to the person, and a retry is honest. */
  | { ok: false; reason: 'serverRefused' | 'recordRemains' | 'noRecord' }

/**
 * relRef -> the relationship ended on the server, the record forgotten and verified, the session
 * closed.
 *
 * The three failure answers are three different sentences on the screen, which is why they are
 * three values rather than a boolean: `serverRefused` happened before anything irreversible and
 * offers a retry, `recordRemains` means the browser would not let go so nothing is claimed, and
 * `noRecord` means there was nothing here to leave.
 */
export async function leaveRelationship(relRef: string, ports: LeavePorts): Promise<LeaveOutcome> {
  /*
   * NO STORAGE IS `noRecord`, AND IT IS CHECKED HERE RATHER THAN LEFT TO findKeyRecord, WHICH
   * THROWS. That throw is right where it is — a browser refusing storage mid-enrolment means there
   * would be nothing to unwrap next time, which the person must be stopped and told about. Here it
   * would be an unhandled exception on a click, which is the worst of both: no keys were forgotten
   * and no sentence was shown. A browser with no storage holds no record, so there is nothing to
   * forget, and that is the honest answer.
   *
   * Both checks run BEFORE the server call, deliberately. A browser that holds no record for this
   * relationship is not the browser this clinician accepted the invitation in, and ending somebody's
   * relationship from a screen that was never theirs is not an act this module should be able to
   * perform on a stray click.
   */
  if (!ports.storage) return { ok: false, reason: 'noRecord' }
  if (!findKeyRecord(relRef, ports.storage)) return { ok: false, reason: 'noRecord' }

  try {
    await ports.serverLeave()
  } catch {
    // Nothing irreversible has happened. Say so and let them try again.
    return { ok: false, reason: 'serverRefused' }
  }

  forgetKeyRecord(relRef, ports.storage)

  // The verification. forgetKeyRecord swallows a refused write by design; this is where that
  // becomes visible instead of becoming a false reassurance.
  if (findKeyRecord(relRef, ports.storage)) return { ok: false, reason: 'recordRemains' }

  let signedOut = true
  try {
    await ports.logout()
  } catch {
    signedOut = false
  }
  return { ok: true, signedOut }
}

/* ── The words ────────────────────────────────────────────────────────────────────────────── */

/** The plain hairline control at the foot of the Allowed tab. Not beside Log out; see the panel. */
export const LEAVE_ACTION = 'Leave this relationship'

/**
 * The confirm's heading. A question, because that is what pressing the control opened.
 *
 * It names no name, and that is not an omission to fix later. This console does not hold the
 * person's — there is no route that would carry it and deliberately so — and a heading that read
 * "Leave your relationship with Dr X?" on the clinician's screen would mean this product had grown
 * a way to put a patient's name in front of somebody. The rest of the confirm says "them" for the
 * same reason.
 */
export const LEAVE_TITLE = 'Leave this relationship?'

/**
 * FIRST IN THE CONFIRM: the irreversibility, which is the load-bearing sentence rather than the
 * reassuring one.
 *
 * It leads rather than "nothing of theirs changes", and that ordering is a decision. No clinician
 * expects leaving to delete a patient's journal, so leading with the reassurance answers a question
 * they were not asking at the cost of the one they need.
 */
export const LEAVE_IS_NOT_UNDONE =
  'Your sign-in for this person closes as soon as you confirm, on every device, and it cannot be ' +
  'reopened. There is no way back in without a fresh invitation from them.'

/** SECOND: nothing on the owner's side moves. Reassurance, so it goes after the warning. */
export const LEAVE_TOUCHES_NOTHING_OF_THEIRS =
  'Nothing of theirs changes. Their entries, what they shared, and their record of sharing it all ' +
  'stay exactly as they are.'

/**
 * THIRD: the mirror of REVOKE_CAVEAT, and deliberately NOT that constant.
 *
 * "Revoking does not un-send what was already read" is the owner's sentence: it is about a reader
 * who already has something. From this side there is no such reader — the clinician is not
 * un-sending anything — and reusing it would be the same words describing a different act. This
 * surface is exempt from the rule that the caveat appears wherever access is turned off, and
 * asserts this constant in its place; the exemption and its reason are written beside the check in
 * companion/web/src/lib/components/owner/revokeCaveat.test.ts.
 *
 * WHAT IT SAYS, AND WHY IT NO LONGER SAYS "ONLY THIS BROWSER". This constant was
 * LEAVE_REACHES_ONLY_THIS_BROWSER while leaving was a per-browser act. The server route that closes
 * the credential made that first clause false — leaving now reaches every device — and a sentence
 * that is false at the point of the click is the one thing this product must not print. So the name
 * and the first clause both moved. The clause that was always the load-bearing one is unchanged:
 * the acceptance screen offers the key record as text to keep somewhere, so copies are the thing a
 * clinician is most likely to assume away.
 */
export const LEAVE_DOES_NOT_REACH_COPIES =
  'It does not reach copies. Anything you exported, printed or wrote down — including a saved copy ' +
  'of your key record, and anything you already opened — is still wherever you put it.'

/**
 * FOURTH: the nudge, which is professional conduct rather than mechanics.
 *
 * The person is not messaged about this. They will meet it in their own console — a line in this
 * relationship's access log, and a refusal the next time they try to share here — but nothing
 * reaches out to them, and a clinician should not leave that to a screen they may not open for
 * weeks. Said as one flat sentence: it is a reminder, not an instruction and not a reprimand.
 */
export const TELL_THEM_YOURSELF =
  'If they do not know yet, tell them yourself: nothing here will send them a message.'

/** Not "Cancel". Cancel names the dialog; this names what keeping the dialog shut actually does. */
export const KEEP_ACCESS = 'Keep access'

/** The one clay element on this surface. */
export const LEAVE_CONFIRM = 'Leave'

/**
 * The server would not take it, and nothing local has happened.
 *
 * Names the consequence — your keys are here, you are signed in, nothing was sent — and no cause,
 * because from this browser an unreachable server, a refusal and an answer that never arrived are
 * indistinguishable, and naming one of them would be naming something this screen cannot know.
 */
export const LEAVE_SERVER_REFUSED =
  'Nothing has changed. Your keys are still in this browser, you are still signed in, and nothing ' +
  'was sent to the person who invited you. You can try again.'

/** The write was refused, so nothing is claimed. */
export const LEAVE_RECORD_REMAINS =
  'Your sign-in is closed, but this browser would not let go of the keys, so they are still on ' +
  'this device. Clearing this site’s data for this browser removes them.'

/** Both halves landed. A statement of what happened, not a confirmation and not a congratulation. */
export const LEAVE_DONE =
  'Your sign-in for this person is closed and your keys for them are gone from this browser. ' +
  'Nothing of theirs changed.'

/**
 * The keys are gone, the relationship is ended, and this browser could not confirm its own
 * sign-out. Said rather than glossed, and without alarm: the door is already shut.
 */
export const LEAVE_SESSION_MAY_REMAIN =
  'Your sign-in for this person is closed and your keys for them are gone from this browser. This ' +
  'browser could not confirm its own sign-out, so close the tab to finish.'

/**
 * What the sign-in screen says to somebody with no record, later.
 *
 * It cannot say "you left". From that screen a clinician who left and one whose cache was cleared
 * are indistinguishable, and naming a cause the system cannot know is the one thing a refusal here
 * must not do. So it says what is absent and what would change it.
 */
export const NO_KEYS_IN_THIS_BROWSER =
  'This browser holds no keys. Keys are made here when an invitation is accepted, so the way in is ' +
  'a fresh invitation from the person who would share with you.'
