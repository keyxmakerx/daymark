/*
 * A CLINICIAN PUTTING DOWN THEIR OWN ACCESS (issue #91, Slice E).
 *
 * An ordinary professional act — leaving a practice, retiring, no longer needing this — and the
 * copy and the colours treat it as one. What it must never be is a route to touching anything of
 * the owner's.
 *
 * ─── WHAT IT ACTUALLY DOES, WHICH IS LESS THAN THE WORD "LEAVE" SUGGESTS ────────────────────────
 *
 * Exactly two operations, and both are named here so the claim "this cannot destroy anything
 * belonging to the owner" is checkable rather than asserted:
 *
 *   1. Remove this relationship's KeyRecord from THIS BROWSER's localStorage.
 *   2. End the current session on the server (DELETE of one row from `sessions`).
 *
 * Nothing else is called. No share is revoked, no blob is written or deleted, no grant is touched,
 * no owner-side table is reached. The owner's entries, the material they shared, and their record
 * of having shared it are all exactly as they were.
 *
 * ─── AND WHAT IT THEREFORE IS NOT ───────────────────────────────────────────────────────────────
 *
 * IT IS NOT AN OFF SWITCH, and the copy has to be exact about that rather than comforting. The
 * server's credential table is insert-only by design: the clinician's TOTP row survives, and only
 * their sessions die. So a saved copy of the key record, plus the passphrase, plus the
 * authenticator, still opens the door from somewhere else — and the KeyRecord header in
 * inviteAccept.ts says the screen deliberately offers that record as text to keep. A clinician who
 * needs to be CERTAIN they are out cannot get there from here; that needs a server route that
 * closes the credential, which is not built.
 *
 * ─── WHY THE WRITE IS VERIFIED RATHER THAN TRUSTED ──────────────────────────────────────────────
 *
 * forgetKeyRecord() is best-effort by construction: its one existing caller is the rollback after a
 * refused enrolment, a path that is already failing, where turning a failed enrolment into a second
 * different error would bury the message the person needs. Its header says so, and says it must
 * never run on a maybe.
 *
 * This is its second caller and the opposite case. Here the clinician has ASKED for exactly that
 * outcome, and a swallowed write means telling somebody their keys are gone while the keys are
 * still in the browser they are looking at. So the record is re-read afterwards and the result says
 * which actually happened. That is the whole reason this is a module rather than two calls in a
 * click handler.
 */
import { findKeyRecord, forgetKeyRecord, type KeyRecordStorage } from './inviteAccept'

/** Everything this needs, as ports, so the order and the failure paths are node tests. */
export interface LeavePorts {
  /** localStorage, or whatever stands in for it. */
  readonly storage: KeyRecordStorage | null
  /** Ends the session server-side. Failure here is reported, never fatal — see below. */
  readonly logout: () => Promise<void>
}

export type LeaveOutcome =
  /** The record is gone from this browser. Whether the session ended is reported separately. */
  | { ok: true; signedOut: boolean }
  /** The record is still here. Nothing was claimed to the person. */
  | { ok: false; reason: 'recordRemains' | 'noRecord' }

/**
 * relRef -> the record forgotten, verified, and the session ended.
 *
 * ORDER: forget first, then log out. The other way round would leave a clinician looking at a
 * signed-out screen with their keys still on the device, which is the state most likely to be read
 * as "done".
 *
 * A FAILED LOGOUT IS NOT A FAILED LEAVE. Once the record is gone the irreversible half has
 * happened, and reporting the whole thing as failed would invite a retry of something that cannot
 * be retried. The session is reported separately so the screen can say the one true thing: the keys
 * are gone, and this session may still be open elsewhere until it expires.
 */
export async function leaveRelationship(relRef: string, ports: LeavePorts): Promise<LeaveOutcome> {
  /*
   * NO STORAGE IS `noRecord`, AND IT IS CHECKED HERE RATHER THAN LEFT TO findKeyRecord, WHICH
   * THROWS. That throw is right where it is — a browser refusing storage mid-enrolment means there
   * would be nothing to unwrap next time, which the person must be stopped and told about. Here it
   * would be an unhandled exception on a click, which is the worst of both: no keys were forgotten
   * and no sentence was shown. A browser with no storage holds no record, so there is nothing to
   * forget, and that is the honest answer.
   */
  if (!ports.storage) return { ok: false, reason: 'noRecord' }
  if (!findKeyRecord(relRef, ports.storage)) return { ok: false, reason: 'noRecord' }

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

/* ── The words that do not depend on the open question ────────────────────────────────────── */

/**
 * The mirror of REVOKE_CAVEAT, and deliberately NOT that constant.
 *
 * "Revoking does not un-send what was already read" is the owner's sentence: it is about a reader
 * who already has something. From this side there is no such reader — the clinician is not
 * un-sending anything — and reusing it would be the same words describing a different act.
 *
 * What IS true here is the thing a clinician is most likely to assume away: leaving reaches one
 * browser's storage and nothing else. The key-record clause is the honest part, because the
 * acceptance screen offers that record as text to keep somewhere, so "cannot be opened again" holds
 * only if no such copy exists.
 */
export const LEAVE_REACHES_ONLY_THIS_BROWSER =
  'Leaving reaches only this browser. Anything you exported, printed or wrote down — including a ' +
  'saved copy of your key record — is still wherever you put it.'

/** The irreversibility, which is the load-bearing sentence rather than the reassuring one. */
export const LEAVE_IS_NOT_UNDONE =
  'This browser forgets your keys for it. What was shared with you cannot be opened again from ' +
  'here, and there is no way back in without a fresh invitation from the person who invited you.'

/** Nothing on the owner's side moves. Second, because it is reassurance rather than the warning. */
export const LEAVE_TOUCHES_NOTHING_OF_THEIRS =
  'Nothing of theirs changes. Their entries, what they shared, and their record of sharing it all ' +
  'stay exactly as they are.'

/** The write was refused, so nothing is claimed. */
export const LEAVE_RECORD_REMAINS =
  'This browser would not let go of the keys, so nothing has changed. You are still signed in.'

/**
 * The keys are gone and the session may not be.
 *
 * Said rather than glossed: the irreversible half happened, and a retry would be a retry of
 * something that cannot be retried.
 */
export const LEAVE_SESSION_MAY_REMAIN =
  'Your keys for it are gone from this browser. This sign-in could not be ended on the server, so ' +
  'it may stay open until it expires on its own.'

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
