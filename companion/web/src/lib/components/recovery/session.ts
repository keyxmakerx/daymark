/*
 * WHERE THE WRAPPED KEY GOES WHEN THERE IS NOWHERE FOR IT TO GO.
 *
 * ─── THE HOLE THIS FILLS, AND THE HOLE IT DOES NOT ──────────────────────────────────────────────
 *
 * dataKey.ts produces two locked boxes and hands them back as plain data, deliberately: "it does
 * not talk to the server: no fetch, no storage, no transport", because the same blob has to be
 * storable by a browser, by the phone, and by a file on a stick. migration.ts then names what is
 * missing on the other side of that seam — a key document, a route that reads and writes it, and
 * the client calls either side — and says none of it is implemented.
 *
 * So there is a finished producer, a finished consumer, and nothing in between. Which leaves the
 * interface with a choice, and only one honest answer:
 *
 *   PRETEND. Show "your recovery code has been saved" and let the person file the paper. This is
 *   the worst thing this surface could do, and it is worth being explicit about why: the lie is
 *   undetectable at the moment it is told and catastrophic at the moment it is discovered, which is
 *   years later, by somebody who has just lost their passphrase and is now finding out that the
 *   piece of paper they kept for exactly this was never attached to anything.
 *
 *   OMIT. Build only the code generator and stop before the wrapping. Then nothing about the flow
 *   can be clicked through, nobody can tell whether the parts work, and the missing piece is
 *   indistinguishable from a piece that was never designed.
 *
 *   STAND IN, AND SAY SO. This module. It keeps the wrapped key in this page's memory, for as long
 *   as this page is open, so that the second flow has a real wrapped key to open with a real code.
 *   Both halves are genuine — real Argon2id, real XChaCha20-Poly1305, real check character — and
 *   the join between them is a variable in a browser tab, which the interface says in plain words
 *   everywhere it matters (see STORAGE_IS_NOT_BUILT and HANDOFF_IS_A_STAND_IN in copy.ts).
 *
 * ─── WHAT THIS MODULE WILL NOT HOLD ─────────────────────────────────────────────────────────────
 *
 * The wrapped key and nothing else. Not the recovery code, not the passphrase, not the unwrapped
 * data key. Those exist in one component's local state, for the seconds it takes to show or use
 * them, and are dropped when it unmounts.
 *
 * The distinction is exactly the one the whole design rests on: the blob here is two locked boxes
 * and a pair of public salts, inert without one of the two secrets, which is why it can sit on a
 * server without the server learning anything (zeroKnowledge.test.ts asserts that as a property
 * rather than as prose). A module that also held a secret would have quietly become the escrow this
 * product does not have.
 *
 * ─── AND WHAT IT WILL NOT TOUCH ─────────────────────────────────────────────────────────────────
 *
 * No localStorage, no sessionStorage, no IndexedDB, no cookie, no clipboard, no fetch, no beacon.
 * Not one of them, and not for the blob either. Two reasons, and the second is the load-bearing one:
 *
 *   1. Anything that survives a reload would make this look like storage, and the thing it must
 *      never look like is storage. A wrapped key that is still there tomorrow morning invites
 *      exactly the belief this whole surface is written to prevent.
 *   2. Browser storage on a page served by the server it talks to is a poor place for key material
 *      of any kind, and a recovery flow is not the place to establish the habit.
 *
 * A test in this directory asserts the absence by reading the source, because an absence that
 * nothing checks is an absence that comes back.
 */
import type { RecoverableDataKey, WrappedSlot } from '../../recovery/dataKey'

/*
 * `import type`, not a value import. It is erased at build time, so this module — which the panel
 * needs the moment it renders — does not pull dataKey.ts and its libsodium dependency into the
 * chunk. The crypto is loaded on demand by the flows that actually derive something.
 */

/**
 * The wrapped key this page is holding, or null.
 *
 * Module-level rather than a Svelte store because there is nothing to subscribe to: it changes when
 * one of two buttons is pressed, and both callers read it immediately afterwards. A store would add
 * a reactive graph to a variable with two writers.
 */
let held: RecoverableDataKey | null = null

/** The wrapped key in this page's memory, or null when there is none. */
export function heldWrappedKey(): RecoverableDataKey | null {
  return held
}

/** Put a wrapped key in this page's memory. Replaces whatever was there. */
export function holdWrappedKey(blob: RecoverableDataKey): void {
  held = blob
}

/**
 * Forget it.
 *
 * Not a zeroize: the blob is ciphertext and public salts, so there is nothing in it to wipe, and
 * saying "zeroize" about it would blur a distinction the rest of this feature depends on. What this
 * does is drop the reference, which is all that is meaningful for a value that was never a secret.
 */
export function releaseWrappedKey(): void {
  held = null
}

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   The file form of the same stand-in.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

/**
 * The marker every saved file carries, in a field name nobody would mistake for a wire format.
 *
 * A JSON file with slots and salts in it looks exactly like something a server produced, and it
 * will outlive this build. If one turns up in a support thread in two years, the first line of it
 * has to say what it is.
 */
export const STAND_IN_MARKER = 'daymark-wrapped-key-stand-in'

/** What the saved file holds. Deliberately not called a document, a record or a manifest. */
export interface WrappedKeyFile {
  standIn: typeof STAND_IN_MARKER
  /** A note to whoever opens the file in a text editor rather than through this screen. */
  note: string
  wrapped: RecoverableDataKey
}

/**
 * The file's own explanation of itself. Read by a person, not by code.
 *
 * It says the two things that are not obvious from the JSON: that no secret is in it, and that no
 * server will accept it. The first stops somebody treating the file as dangerous when it is not;
 * the second stops somebody treating it as a backup when it is not.
 */
export const FILE_NOTE =
  'Two locked copies of one key, and no secret. Neither a passphrase nor a recovery code is in ' +
  'this file or derivable from it. This format is a stand-in used by the recovery screen while ' +
  'there is no storage for a wrapped key; no server accepts it.'

/** The saved file's text. Indented, because a person may well open it and look. */
export function encodeWrappedKeyFile(blob: RecoverableDataKey): string {
  const file: WrappedKeyFile = { standIn: STAND_IN_MARKER, note: FILE_NOTE, wrapped: blob }
  return JSON.stringify(file, null, 2)
}

/** Everything a loaded file can be other than a wrapped key. One value per diagnosis. */
export type WrappedKeyFileFault = 'notJson' | 'notThisFile' | 'noSlots'

export const WRAPPED_KEY_FILE_FAULT_TEXT: Record<WrappedKeyFileFault, string> = {
  notJson: 'That file is not the one this screen saves. It could not be read as JSON at all.',
  notThisFile: 'That is JSON, and it is not a wrapped key saved by this screen.',
  noSlots: 'That file says it is a wrapped key and carries no wrapped copies of one.',
}

export type WrappedKeyFileRead =
  | { ok: true; blob: RecoverableDataKey }
  | { ok: false; fault: WrappedKeyFileFault }

/**
 * Read a saved file back, structurally.
 *
 * Shape only. Whether the KDF parameters clear the security floor, whether a ciphertext is the size
 * of a wrapped key, and whether a slot decodes at all are dataKey.ts's questions, and it asks all
 * of them on every use of every slot precisely because a blob is untrusted input. Duplicating those
 * checks here would produce a second floor to keep in step with the first, and the failure mode of
 * two floors drifting apart is one of them silently becoming the lower one.
 *
 * So this function answers exactly one question: is this the file this screen saves. Nothing here
 * throws — a person picking the wrong file out of a folder is ordinary, not exceptional.
 */
export function decodeWrappedKeyFile(text: string): WrappedKeyFileRead {
  let parsed: unknown
  try {
    parsed = JSON.parse(text)
  } catch {
    return { ok: false, fault: 'notJson' }
  }
  const file = parsed as Partial<WrappedKeyFile> | null
  if (!file || typeof file !== 'object' || file.standIn !== STAND_IN_MARKER) {
    return { ok: false, fault: 'notThisFile' }
  }
  const wrapped = file.wrapped as RecoverableDataKey | undefined
  if (!wrapped || typeof wrapped !== 'object' || !Array.isArray(wrapped.slots)) {
    return { ok: false, fault: 'notThisFile' }
  }
  if (wrapped.slots.length === 0) return { ok: false, fault: 'noSlots' }
  return { ok: true, blob: wrapped }
}

/**
 * How many copies of the key are wrapped, by which secret.
 *
 * Rendered on both flows, because it is the one honest thing that can be said about a blob without
 * opening it, and because "one passphrase copy and one recovery copy" is the entire design stated
 * as a fact about the object in front of you. Counting rather than asserting: a blob with two
 * recovery slots is a thing the format allows, and a screen that said "one" would be describing the
 * design instead of the data.
 */
export function slotSummary(blob: RecoverableDataKey): { passphrase: number; recovery: number } {
  const kind = (k: WrappedSlot['kind']) => blob.slots.filter((s) => s.kind === k).length
  return { passphrase: kind('passphrase'), recovery: kind('recovery') }
}
