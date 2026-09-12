/*
 * THE STANDING FACT THAT SOMEBODY IS READING YOUR ENTRIES (issue #105).
 *
 * A consented, ongoing share is a normal state of this product, and the strip that announces it is
 * on every owner screen and cannot be dismissed. Both halves of that are decisions:
 *
 * PERSISTENT, because a share is not an event that happened once. Someone has standing access to
 * real entries until the owner ends it, and a notice that scrolls away is a notice that stops being
 * true in the reader's memory long before it stops being true on the server.
 *
 * NOT AN ALARM. The strip is chrome with an indigo rule, not clay. Clay means one of four things
 * here — needs a human, overdue, refused, destructive — and an ongoing share consented to by the
 * person reading is none of them. Lighting the only alarm hue permanently for a normal state is how
 * an alarm hue stops meaning anything. Clay appears exactly once in this feature: on the confirm
 * button that actually ends the share.
 *
 * WHAT "SINCE" MEANS, SAID PLAINLY BECAUSE IT IS NOT WHAT THE ISSUE ASSUMED. The issue defines the
 * date as the day the share was accepted. Nothing in this product records that: a clinician opening
 * a share leaves a row in the relationship's audit log, which is a separate fetch per surface, is
 * not provably complete, and would make a permanent strip cost a request on every screen. What IS
 * known, from the versions already listed, is when the OWNER first published to the share lineage —
 * the day sharing began. That is what the sentence says and what this computes. If the acceptance
 * day is wanted instead, it needs the audit log and a different shape.
 */
import type { RelMeta } from '../sync/portal'

/** What a screen needs to know about the share lineage, and nothing more. */
export interface SharingState {
  /** True when at least one version has been published and not withdrawn. */
  readonly active: boolean
  /** Epoch ms of the earliest published version, or null when nothing has been published. */
  readonly since: number | null
}

/**
 * versions of the `share` lineage -> whether sharing is live, and since when.
 *
 * An empty list is `active: false`, which is the same answer as a lineage that was revoked: the
 * revoke route marks every version and removes the bytes, so a withdrawn share reads as nothing
 * published. The strip does not distinguish them, and should not — "not sharing" is one state to a
 * person, and a strip that said "previously sharing" would be a permanent reminder of an ended
 * relationship on every screen.
 */
export function sharingStateFrom(versions: readonly RelMeta[]): SharingState {
  if (versions.length === 0) return { active: false, since: null }
  let earliest = Number.POSITIVE_INFINITY
  for (const v of versions) {
    // A missing or nonsensical timestamp is skipped rather than min()-ed to zero, which would
    // otherwise print "since 1 January 1970" on the one strip that is always on screen.
    if (typeof v.createdAt === 'number' && Number.isFinite(v.createdAt) && v.createdAt > 0) {
      earliest = Math.min(earliest, v.createdAt)
    }
  }
  return { active: true, since: Number.isFinite(earliest) ? earliest : null }
}

/* ── The words ───────────────────────────────────────────────────────────────────────────── */

/** The micro-label. A noun for the state, not a warning and not a verb aimed at the reader. */
export const SHARING_LABEL = 'SHARING'

/** Stored display name, or this, so the strip never renders an empty gap where a name should be. */
export const UNNAMED_RECIPIENT = 'one person'

/**
 * The standing line.
 *
 * "Real entries" rather than "your data": the thing a person needs reminding of is that this is
 * not a summary or a score, it is what they wrote. `since` is omitted rather than guessed when no
 * usable timestamp came back.
 */
export function sharingLine(name: string, since: string | null): string {
  const who = name.trim() || UNNAMED_RECIPIENT
  return since ? `Sharing real entries with ${who} since ${since}.` : `Sharing real entries with ${who}.`
}

export const REVOKE_ACTION = 'Revoke'

/** The confirm, whose first body line is the standing caveat — that is the point of the click. */
export function revokeTitle(name: string): string {
  return `Revoke sharing with ${name.trim() || UNNAMED_RECIPIENT}?`
}

export function revokeConsequence(name: string): string {
  return `${name.trim() || UNNAMED_RECIPIENT} stops receiving new entries as soon as you confirm.`
}

/** Not "Cancel". Cancel names the dialog; this names what keeping the dialog shut actually does. */
export const KEEP_SHARING = 'Keep sharing'

/**
 * When the server marked the versions but could not remove every copy.
 *
 * Said rather than swallowed, because "withdrawn" and "withdrawn except for the copies still on
 * disk" are different facts and only one of them is true. Names the count, because a number is
 * something an operator can go and look for.
 */
export function copiesNotRemoved(n: number): string {
  const copies = n === 1 ? 'one copy' : `${n} copies`
  return (
    `Sharing is ended and nothing further will be delivered. ${copies} of what was already shared ` +
    `could not be removed from the server, so ${n === 1 ? 'it is' : 'they are'} still on its disk. ` +
    `Whoever runs the server can remove ${n === 1 ? 'it' : 'them'}.`
  )
}
