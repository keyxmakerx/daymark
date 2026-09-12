/*
 * THE CLINICIAN'S INBOX TOKEN, REMEMBERED FOR ONE TAB.
 *
 * WHY IT HAS TO EXIST. The token is the one value the acceptance ceremony cannot produce: its
 * digest IS the relationship reference, so a server that could hand it back would be giving away
 * the thing it authenticates. It arrives out of band, from the person whose data it is, and the
 * sign-in screen has to get it from somewhere. Without this the answer is "from the clinician's
 * keyboard, forty-three characters, every single visit" — and friction of that shape does not
 * survive contact with a working day. It ends with the token in a text file on a desktop, which is
 * worse than anything this module does.
 *
 * WHAT WAS THERE BEFORE, AND WHY IT WAS NOT A BUG WORTH FIXING IN PLACE. KeyRecord carried an
 * optional `inboxToken` field, and LoginGate tried to fill it in after a successful sign-in with
 * `saveKeyRecord({ ...rec, inboxToken: token })`. That call threw every single time: saveKeyRecord
 * is insert-only and `rec` came from `loadKeyRecords()`, so its relationship reference was already
 * stored. The throw landed in a catch written for a different failure — a browser refusing storage
 * — and was swallowed. The feature was dead, silently, from the day it was written, and nothing
 * anywhere reported it (issue #125).
 *
 * WHERE, AND THE DECISION INSIDE IT. sessionStorage, not localStorage. The obvious repair was
 * forget-then-save, which works and puts a bare journal credential in localStorage, in the clear,
 * beside the Argon2id-wrapped keys, permanently. The wrapping passphrase would not protect it — it
 * is not inside the blob — so a copied browser profile would carry both halves of what the server
 * asks for, needing only a live session to be useful. Per tab is the middle ground the issue named:
 * a clinician who reloads, or opens a share in a second tab, is not asked again; nothing survives
 * the tab closing; and the record that DOES persist stays what inviteAccept.ts says it is.
 *
 * This is the same posture pairing/ownerRunStore.ts takes with the CPace scalar, for the same
 * reason, and the port interface below is deliberately identical to that one: a slice of the
 * Storage API small enough for a node test to pass a plain object, since these tests have no DOM.
 *
 * FAILS OPEN, ALWAYS. Every function here swallows what storage throws. A browser that refuses
 * costs one re-typed token; it must never cost a sign-in that has already succeeded, which is
 * precisely the mistake the code this replaces made in the other direction.
 */

/** Versioned so a later shape can be recognised rather than silently mis-read. */
export const INBOX_TOKEN_STORAGE_KEY = 'daymark.therapist.inbox-token.v1'

/** The slice of the Storage API this needs. Tests pass a plain object; node has no DOM. */
export interface InboxTokenStorage {
  getItem(key: string): string | null
  setItem(key: string, value: string): void
  removeItem?(key: string): void
}

/** sessionStorage, or null where it does not exist or the browser refuses to hand it over. */
export function defaultInboxTokenStorage(): InboxTokenStorage | null {
  try {
    // Access itself throws in some blocked or partitioned contexts, not merely returns undefined.
    return globalThis.sessionStorage ?? null
  } catch {
    return null
  }
}

/**
 * Every token this tab is holding, keyed by relationship reference.
 *
 * A MAP RATHER THAN A KEY PER RELATIONSHIP, because a clinician can hold several and the sign-in
 * screen lets them choose. One entry per relationship in one document keeps `forget` able to remove
 * exactly one without enumerating the whole store.
 */
type Held = Record<string, string>

function read(storage: InboxTokenStorage | null): Held {
  if (!storage) return {}
  let raw: string | null
  try {
    raw = storage.getItem(INBOX_TOKEN_STORAGE_KEY)
  } catch {
    return {}
  }
  if (!raw) return {}
  let parsed: unknown
  try {
    parsed = JSON.parse(raw)
  } catch {
    return {}
  }
  if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) return {}
  const out: Held = {}
  for (const [relRef, token] of Object.entries(parsed as Record<string, unknown>)) {
    // Anything that is not a non-empty string is treated as absent rather than repaired. The
    // harmless direction is "ask for it again".
    if (typeof token === 'string' && token.length > 0) out[relRef] = token
  }
  return out
}

function write(storage: InboxTokenStorage | null, held: Held): void {
  if (!storage) return
  try {
    storage.setItem(INBOX_TOKEN_STORAGE_KEY, JSON.stringify(held))
  } catch {
    // A refusing browser costs one re-typed token next visit, and nothing else.
  }
}

/**
 * Remember the token that made a sign-in work, for the life of this tab.
 *
 * Called AFTER the sign-in succeeded, never before: a token that has not been accepted by the
 * server is a token that would be silently offered back on the next attempt, which turns one typo
 * into a sign-in that cannot be completed without knowing to clear something invisible.
 */
export function rememberInboxToken(
  storage: InboxTokenStorage | null,
  relRef: string,
  token: string,
): void {
  if (!relRef || !token) return
  write(storage, { ...read(storage), [relRef]: token })
}

/** The token this tab holds for a relationship, or null. Null always means "ask for it". */
export function recallInboxToken(storage: InboxTokenStorage | null, relRef: string): string | null {
  if (!relRef) return null
  return read(storage)[relRef] ?? null
}

/*
 * THERE IS NO forget(). Deliberately, and it is worth saying why rather than leaving a gap someone
 * fills in later without the reasoning.
 *
 * Closing the tab is the forget, and it is the only one that cannot be got wrong. A token only ever
 * reaches this store after a sign-in the server completed — LoginGate writes it after the owner's
 * published keys have come back, which the relationship routes will not return without it — so a
 * held token is always one that worked, and there is no "wrong value stuck in a tab" to rescue
 * anybody from. therapist/leave.ts, which is where a clinician puts down their access, states in
 * its own header that it performs exactly two operations and that the claim is checkable; a third
 * one added quietly from here would make that header false.
 */
