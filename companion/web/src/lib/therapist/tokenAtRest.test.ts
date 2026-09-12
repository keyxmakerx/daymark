/*
 * THE INBOX TOKEN, AND WHETHER IT SURVIVES THE TAB (issue #120).
 *
 * Every route serving relationship content requires the RAW inbox token in an `X-Rel-Token` header,
 * and the server stores only BLAKE2b-256 of it. So the token is one of the two things a reader of a
 * journal needs — the other being a role, an owner bearer token or a therapist session bound to the
 * same relationship, which RelationRoutes.resolve() checks separately and which RelationRoutesTest
 * now pins. Necessary, not sufficient. Still worth not leaving lying about.
 *
 * ─── WHAT THIS FILE GUARDS, AND WHY IT IS SHAPED ODDLY ──────────────────────────────────────────
 *
 * `KeyRecord` declares an optional `inboxToken` field, added so a clinician types it once rather
 * than every visit, and `LoginGate` tries to fill it in after a successful sign-in. That write
 * CANNOT LAND: saveKeyRecord is insert-only, the record being updated is by construction already in
 * storage, so the call throws every time and LoginGate's catch swallows it. The feature is dead and
 * the file header two hundred lines up still says only "the WRAPPED blob and two opaque
 * identifiers" persist.
 *
 * That leaves a security property that is TRUE BY ACCIDENT: nothing persists a bare inbox token.
 * The obvious repair — forget the record, then save it with the token attached — would put the raw
 * token in localStorage, in the clear, beside the Argon2id-wrapped keys, permanently, and no test
 * in the tree would have gone red.
 *
 * So this file asserts the property rather than the bug. Fix the dead write and (a) fails, which is
 * the point: whoever fixes it is then made to decide, deliberately, whether a bare journal
 * credential belongs in localStorage — rather than discovering it there later. (b) records why the
 * write is currently dead so the next reader does not think (a) is asserting a typo.
 *
 * Nothing here changes behaviour. #120 is a read-and-report pass; the dead write and what to do
 * about it are their own issue.
 */
import { describe, it, expect } from 'vitest'
import {
  KEY_RECORD_STORAGE_KEY,
  loadKeyRecords,
  saveKeyRecord,
  type KeyRecord,
  type KeyRecordStorage,
} from './inviteAccept'

const INBOX_TOKEN = 'inbox-token-256-bit-example-xyz'

const record = (relRef: string, extra: Partial<KeyRecord> = {}): KeyRecord => ({
  v: 1,
  relRef,
  credentialId: `cred-${relRef}`,
  wrapped: { v: 1, kdf: { alg: 'argon2id', memMiB: 256, ops: 3 }, saltB64: 's', nonceB64: 'n', ctB64: 'c' },
  createdAt: 1,
  ...extra,
})

/** A storage stub whose raw serialized contents can be inspected, which is the whole point. */
function storage(): KeyRecordStorage & { raw: () => string } {
  let box = ''
  return {
    getItem: (k: string) => (k === KEY_RECORD_STORAGE_KEY ? box || null : null),
    setItem: (k: string, v: string) => {
      if (k === KEY_RECORD_STORAGE_KEY) box = v
    },
    raw: () => box,
  }
}

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   (a) Nothing persisted carries a bare inbox token.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('(a) the inbox token does not survive the tab', () => {
  it('the detector sees a token nested inside a stringified record', () => {
    /*
     * Guard the guard, and specifically against the blindness that matters here. A token stored as
     * a FIELD of an object that is then JSON.stringify-ed will not be found by grepping source for
     * `setItem` near a variable name — it is only visible in the serialized bytes. So the check
     * below reads the stored string, and this proves that check is not blind.
     */
    const s = storage()
    saveKeyRecord(record('rel-planted', { inboxToken: INBOX_TOKEN }), s)
    expect(s.raw()).toContain(INBOX_TOKEN)
  })

  it('a record written by the acceptance ceremony carries no token', () => {
    const s = storage()
    saveKeyRecord(record('rel-a'), s)
    expect(s.raw()).not.toContain(INBOX_TOKEN)
    expect(loadKeyRecords(s)[0].inboxToken).toBeUndefined()
  })

  it('signing in cannot add one afterwards, which is what keeps the store clean today', () => {
    /*
     * LoginGate's post-sign-in write, reproduced exactly: take the loaded record, spread the token
     * on, save. It throws, every time, because the relRef is already present and saveKeyRecord is
     * insert-only. LoginGate catches and moves on, so a clinician is asked for the token again on
     * the next visit — forever.
     */
    const s = storage()
    saveKeyRecord(record('rel-a'), s)
    const loaded = loadKeyRecords(s)[0]

    expect(() => saveKeyRecord({ ...loaded, inboxToken: INBOX_TOKEN }, s)).toThrow(
      /already holds keys for that relationship/,
    )
    expect(s.raw(), 'nothing was written by the throwing call').not.toContain(INBOX_TOKEN)
  })
})

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   (b) Why (a) holds, recorded so it is not mistaken for a typo.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('(b) the field exists and the write is dead', () => {
  it('KeyRecord still declares the field, so this is dormant rather than absent', () => {
    // Typed as optional, so a record carrying one is valid and would round-trip. The type is not
    // what stops the token landing; the insert-only rule is.
    const withToken = record('rel-b', { inboxToken: INBOX_TOKEN })
    expect(withToken.inboxToken).toBe(INBOX_TOKEN)
    const s = storage()
    saveKeyRecord(withToken, s)
    expect(loadKeyRecords(s)[0].inboxToken).toBe(INBOX_TOKEN)
  })

  it('the repair somebody would reach for lands the token in the clear', () => {
    /*
     * Forget-then-save, which is what a person fixing the dead write would write. It works, and it
     * puts the raw token in storage NEXT TO the wrapped keys — not inside the wrapped blob, which
     * is the only thing on this record that a passphrase protects.
     *
     * This is here as the demonstration, not as an endorsement. If this ever becomes the real
     * behaviour, test (a) fails first and someone has to decide it on purpose.
     */
    const s = storage()
    saveKeyRecord(record('rel-c'), s)
    const loaded = loadKeyRecords(s)[0]

    s.setItem(KEY_RECORD_STORAGE_KEY, JSON.stringify([]))
    saveKeyRecord({ ...loaded, inboxToken: INBOX_TOKEN }, s)

    expect(s.raw()).toContain(INBOX_TOKEN)
    // And plainly: bare, not inside the wrapped blob.
    expect(loadKeyRecords(s)[0].wrapped.ctB64).not.toContain(INBOX_TOKEN)
    expect(loadKeyRecords(s)[0].inboxToken).toBe(INBOX_TOKEN)
  })
})
