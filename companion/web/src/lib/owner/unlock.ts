/*
 * OPENING THE OWNER CONSOLE — the sequence, apart from the screen that runs it.
 *
 * The console used to open by pressing a button that minted a throwaway identity (issue #121). It
 * now opens by the owner producing the key they already have: the locked key their server keeps
 * (recovery/serverKey.ts reads it; docs/SYNC_PROTOCOL.md §1.2), and either the passphrase or the
 * recovery code that opens it. From that key the pairing identity is derived (owner/identity.ts),
 * and the key itself is wiped.
 *
 * ─── WHY THIS IS A MODULE AND NOT A FUNCTION INSIDE THE COMPONENT ───────────────────────────────
 *
 * Web tests run in node with no DOM, so anything asserted inside a `.svelte` file can only be
 * asserted structurally, over its source text. The interesting properties of this sequence are not
 * structural — they are about ORDER and about what is left behind:
 *
 *   - the master is wiped after the identity is derived, INCLUDING when deriving throws;
 *   - the master is never returned, so no caller can hold it by accident;
 *   - a mistyped recovery code is refused before Argon2id runs, not three seconds later;
 *   - every failure has exactly one diagnosis, and none of them guesses at a cause.
 *
 * Each of those is a real node test here, and would be a hopeful comment there. This follows
 * pairing/ownerCeremony.ts, which exists for the same reason.
 *
 * ─── ON THE COST OF GETTING IN ──────────────────────────────────────────────────────────────────
 *
 * The owner types their access token and a secret EVERY session, and this module is where that is
 * most visible, so it is worth stating plainly rather than leaving as an accident of the code. The
 * server keeps the locked key, and nothing on this side keeps anything: components/recovery/
 * session.ts argues at length against browser storage for key material on a page the server serves.
 * So the friction is not an oversight; it is the shape of a console that holds nothing between
 * visits.
 */
import {
  unwrapWithPassphrase,
  unwrapWithRecoveryCode,
  zeroizeDataKey,
  DataKeyError,
  type RecoverableDataKey,
} from '../recovery/dataKey'
import { RecoveryCodeError, RECOVERY_FAULT_TEXT, type RecoveryCodeFault } from '../recovery/recoveryCode'
import { ownerIdentityFromMaster } from './identity'
import type { Identity } from '../share/pairing'

/** Which of the two secrets the owner is offering. */
export type SecretKind = 'passphrase' | 'recovery'

/** Everything an unlock can be other than an identity. */
export type UnlockFault = 'noSecret' | 'noSlotOfThatKind' | 'didNotOpen' | RecoveryCodeFault

export type UnlockResult = { ok: true; identity: Identity } | { ok: false; fault: UnlockFault; at?: number }

/**
 * What to show for each fault.
 *
 * `didNotOpen` is the one worth reading twice. Argon2id ran and the ciphertext did not authenticate,
 * and from here that is ALL that is known: the wrong passphrase and a key edited by whoever keeps it
 * are one outcome, not two. Saying "wrong passphrase" would assert a cause this code cannot
 * distinguish, and would send someone to re-type a passphrase that was right all along. So it names
 * the consequence and points at the one input.
 */
export const UNLOCK_FAULT_TEXT: Record<UnlockFault, string> = {
  ...RECOVERY_FAULT_TEXT,
  noSecret: 'Nothing was entered.',
  noSlotOfThatKind: 'The key this server holds has no copy locked that way.',
  didNotOpen: 'That did not open the key this server holds. It is worth checking what you typed.',
}

/**
 * The server's locked key + one secret -> the owner's pairing identity, or one diagnosis.
 *
 * Nothing throws. Mistyping a code is ordinary rather than exceptional, and a screen that has to
 * distinguish exceptions from results in a catch block ends up with a branch nobody tests. The key
 * arrives as the caller holds it — the server's copy today, a phone-side handoff or a WebAuthn-PRF
 * slot one day — and the wipe is in one place whichever way it came.
 */
export async function unlockFromBlob(
  blob: RecoverableDataKey,
  secret: string,
  kind: SecretKind,
): Promise<UnlockResult> {
  if (!secret.trim()) return { ok: false, fault: 'noSecret' }
  let master: Uint8Array | null = null
  try {
    master =
      kind === 'passphrase'
        ? await unwrapWithPassphrase(blob, secret)
        : await unwrapWithRecoveryCode(blob, secret)
  } catch (err) {
    /*
     * A RecoveryCodeError means the checksum refused what was typed BEFORE any derivation — a
     * single mistyped character, diagnosed in milliseconds and positioned. Reporting that as
     * "did not open" would spend three seconds of Argon2id to say something less useful.
     */
    if (err instanceof RecoveryCodeError) return { ok: false, fault: err.fault, at: err.at }
    if (err instanceof DataKeyError && /no (passphrase|recovery) slot/.test(err.message)) {
      return { ok: false, fault: 'noSlotOfThatKind' }
    }
    return { ok: false, fault: 'didNotOpen' }
  }

  try {
    return { ok: true, identity: ownerIdentityFromMaster(master) }
  } catch {
    // A master that opened but is the wrong length is a corrupt blob, not a wrong secret.
    return { ok: false, fault: 'didNotOpen' }
  } finally {
    /*
     * In `finally`, so the master is wiped on the way out of both branches. The identity is what
     * the caller gets; the master is not returned by any path out of this module, because a value
     * that opens someone's entire journal should not be sitting in a component's state waiting for
     * a later refactor to persist it.
     */
    zeroizeDataKey(master)
  }
}
