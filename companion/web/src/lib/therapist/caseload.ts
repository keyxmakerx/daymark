/*
 * ONE READING PASSPHRASE FOR THE WHOLE CASELOAD (#314).
 *
 * With one sign-in per person, a clinician signs in once and the server says which relationships
 * the account reaches. This browser holds a key record per relationship, each wrapped under a reading
 * passphrase; one passphrase typed once per session opens every one of them it can. Nobody can reset
 * it (#100), so nothing here offers to.
 *
 * WHAT IT OPENS. Only records for relationships the account reaches now: a record for a relationship
 * that ended, or that is attached to another sign-in, stays shut, so its keys are never in memory
 * for a session that cannot use them. A record wrapped under a different passphrase (one accepted
 * before the clinician used one passphrase for everything) is reported as not opened, never guessed
 * at again.
 *
 * Plain data in, plain data out; the unwrap is a port so a test needs no Argon2.
 */
import type { KeyRecord } from './inviteAccept'
import type { TherapistKeys, WrappedKeyBlob } from './keyStore'

export interface Caseload {
  /** Opened keys, by relationship. The caller zeroizes every one on sign-out and idle. */
  opened: Map<string, TherapistKeys>
  /** Reached by the account, held in this browser, and not opened by this passphrase. */
  otherPassphrase: string[]
  /** Reached by the account and with no key record in this browser at all. */
  notInThisBrowser: string[]
}

export async function unlockCaseload(
  reached: readonly string[],
  records: readonly KeyRecord[],
  passphrase: string,
  unwrapKeys: (blob: WrappedKeyBlob, passphrase: string) => Promise<TherapistKeys>,
): Promise<Caseload> {
  const byRel = new Map(records.map((r) => [r.relRef, r]))
  const opened = new Map<string, TherapistKeys>()
  const otherPassphrase: string[] = []
  const notInThisBrowser: string[] = []
  for (const relRef of reached) {
    const record = byRel.get(relRef)
    if (!record) {
      notInThisBrowser.push(relRef)
      continue
    }
    try {
      opened.set(relRef, await unwrapKeys(record.wrapped, passphrase))
    } catch {
      otherPassphrase.push(relRef)
    }
  }
  return { opened, otherPassphrase, notInThisBrowser }
}

/** The records in this browser for relationships the account does not reach: candidates for a key proof. */
export function attachable(reached: readonly string[], records: readonly KeyRecord[]): KeyRecord[] {
  const here = new Set(reached)
  return records.filter((r) => !here.has(r.relRef))
}
