/*
 * WHAT THE RECOVERY SCREENS KEEP BETWEEN VISITS: NOTHING.
 *
 * The owner's key lives on their server, locked under the passphrase and again under the recovery
 * code (docs/SYNC_PROTOCOL.md §1.2), and these screens read it from there each time they need it
 * (recovery/serverKey.ts). The key file that once stood in for that storage is retired (#258): no
 * screen saves one and none reads one.
 *
 * ─── WHAT THESE SCREENS HOLD, AND FOR HOW LONG ──────────────────────────────────────────────────
 *
 * The locked key as the server handed it, for as long as one flow is on screen. A recovery code or
 * an open key only in one component's local state, for the seconds it takes to show or use them,
 * dropped when it unmounts. The locked key is two locked boxes and a pair of public salts, inert
 * without one of the two secrets, which is why a server can hold it without learning anything
 * (zeroKnowledge.test.ts asserts that as a property rather than as prose).
 *
 * ─── AND WHAT THEY WILL NOT TOUCH ───────────────────────────────────────────────────────────────
 *
 * No localStorage, no sessionStorage, no IndexedDB, no cookie, no clipboard, no beacon. Not one of
 * them, and not for the locked key either. Two reasons, and the second is the load-bearing one:
 *
 *   1. The server is where the key is kept. A second copy in the browser would be a second place to
 *      lose it from, and one that survives the passphrase change that retires the server's.
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
 * How many copies of the key are locked, by which secret.
 *
 * Rendered where a code is used, because it is the one honest thing that can be said about a locked
 * key without opening it, and because "one passphrase copy and one recovery copy" is the entire
 * design stated as a fact about the object in front of you. Counting rather than asserting: a key
 * with two recovery slots is a thing the format allows, and a screen that said "one" would be
 * describing the design instead of the data.
 */
export function slotSummary(blob: RecoverableDataKey): { passphrase: number; recovery: number } {
  const kind = (k: WrappedSlot['kind']) => blob.slots.filter((s) => s.kind === k).length
  return { passphrase: kind('passphrase'), recovery: kind('recovery') }
}
