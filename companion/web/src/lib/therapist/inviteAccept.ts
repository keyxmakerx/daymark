/*
 * ACCEPTING AN INVITATION — the ceremony a clinician goes through once, in order, and the reasons
 * the order is the order.
 *
 * WHAT THIS REPLACES. `LoginGate` asks a clinician for nine values: a server URL, an inbox token, a
 * relationship id, a credential id, two of the owner's public keys, a wrapped-key blob, a TOTP code
 * and a reading passphrase. Seven of those are base64 or JSON, and — this is the part that made it
 * unusable rather than merely unfriendly — nothing in the product produced any of them. They could
 * only be assembled by hand, out of the database and the source, by somebody who had written it.
 * Meanwhile `redeemInvite` and `enrollTotp` had been sitting in session.ts fully written with no
 * caller at all, `wrap()` had no production caller either, and the link the server emails pointed at
 * a path nothing routed. This module is the missing middle: it consumes the link, generates what
 * can be generated, exchanges what has to be exchanged, and leaves exactly two things for a person
 * to supply — a passphrase they choose, and the code their authenticator shows.
 *
 * THE ORDER, AND WHY EACH STEP IS WHERE IT IS.
 *
 *   1. REDEEM the invite. First because everything downstream needs the relRef and the single-use
 *      enrolment ticket, and because a wrong or dead link should cost the person nothing but a
 *      message. A wrong secret never burns the invitation (AuthStore.redeemInvite) — it is metered,
 *      not consumed — so a mistyped paste is recoverable.
 *
 *   2. REFUSE if this browser already holds keys for the relationship. Insert-only, locally, for the
 *      same reason the server is insert-only remotely: the second set of keys silently replaces the
 *      first, and the first is the one the owner has already sealed a person's journal to.
 *
 *   3. GENERATE the two keypairs. In the browser, from libsodium, and never anywhere else — the
 *      secret halves must not exist on the server even for an instant.
 *
 *   4. WRAP them under the passphrase, PROVE THE WRAP OPENS, and store the blob. The proof is the
 *      expensive part of this module and the one it would be most tempting to drop; see
 *      `beginAcceptance` for why it stays.
 *
 *   5. ENROL the authenticator, with a secret this page generates and the person copies into their
 *      authenticator app.
 *
 *   6. SIGN IN with the first code that authenticator produces. This is what turns the enrolment
 *      into a session, and it is also the only honest confirmation that their authenticator is
 *      actually set up: a page that said "enrolled" without ever seeing a code from the device
 *      would be telling somebody they can get back in when nobody has checked.
 *
 *   7. REGISTER the two PUBLIC keys, which needs that session (cookie + anti-CSRF token). So the
 *      ordering guarantee the tests pin — public keys are never registered before enrolment
 *      succeeds — is structural here rather than a convention: without a successful enrolment there
 *      is no session, and without a session there is no route to register anything.
 *
 * WHAT HAPPENS WHEN AN ANSWER GOES MISSING, WHICH IS THE PART THIS MODULE GOT WRONG FIRST TIME. Two
 * of those steps commit something on the server that nothing in this product can undo — the
 * enrolment (step 5) and, in a different way, the sign-in and registration pair (6 and 7) — and on
 * a real network the answer to a committed request goes astray often. The rules that follow from
 * that are stated once here and enforced below:
 *
 *   - A LOST ANSWER IS NOT A REFUSAL. `beginAcceptance` rolls its stored record back only when the
 *     server said no in its own words. Anything else keeps the record and the authenticator secret
 *     and says out loud that it does not know, because the alternative — deleting a clinician's
 *     only secret keys because a proxy timed out — is the one failure in this file that cannot be
 *     recovered from at all.
 *
 *   - AN UNFINISHED CEREMONY MUST HAVE A WAY BACK IN. A browser that died between step 6 and step 7
 *     leaves a clinician enrolled with no public keys on file, an owner whose console will say
 *     "nothing published yet" forever, and an invitation that is spent and cannot be reissued into
 *     a second enrolment. `resumeAcceptance` finishes it from the wrapped record this browser
 *     already holds, with the passphrase and one authenticator code and nothing else.
 *
 * WHAT IS NEVER STORED, ANYWHERE. The passphrase and the unwrapped secret keys. The passphrase is
 * used to derive the wrapping key and is then the caller's to drop; the keys live in memory for the
 * length of the ceremony and are zeroized by the screen when it goes away. What persists in this
 * browser is the WRAPPED blob and two opaque identifiers — see KeyRecord for the honest accounting
 * of what that record still discloses about the person holding it.
 *
 * NO SVELTE, NO DOM, NO CLOCK OF ITS OWN. `now` is a port, `storage` is a port, and every network
 * call is a port, so the whole ceremony — including its ordering — is testable in the node
 * environment the suite runs in. See inviteAccept.test.ts.
 */
import _sodium from 'libsodium-wrappers-sumo'
import { fingerprint, initAssignmentCrypto, newBoxKeyPair, newSignKeyPair } from '../assignments/crypto'
import { unwrap, wrap, zeroize, type TherapistKeys, type WrappedKeyBlob } from './keyStore'
import type { EnrolOutcome, KeyRegistration, LoginResult, PortalClient, SessionInfo } from './session'

const URLSAFE = () => _sodium.base64_variants.URLSAFE_NO_PADDING

/* ── What this browser keeps ─────────────────────────────────────────────────────────────── */

/** Versioned so a later record format can be migrated rather than silently mis-read. */
export const KEY_RECORD_STORAGE_KEY = 'daymark.therapist.keys.v1'

/**
 * The durable half of a relationship, as this browser holds it.
 *
 * WHAT IS IN IT. The Argon2id-wrapped key blob — useless without the passphrase, which is not here
 * and is not anywhere else either — plus the relationship reference and the credential id, both of
 * which are opaque server-minted tokens that identify a relationship without naming anybody.
 *
 * WHAT IT STILL DISCLOSES, SAID PLAINLY. The same admission pinStore.ts makes about the owner's
 * side, and it is not smaller here. A device holding this record announces that this browser
 * profile belongs to a clinician using a Daymark portal, that they have N relationships, and when
 * each started. No names, no content, no key anybody can open — but on a shared, borrowed or seized
 * machine, "this person sees N clients through this service, starting on these dates" is
 * information about their practice and about the people in it.
 *
 * WHY IT IS KEPT ANYWAY. The alternative is that a clinician's private keys exist only for the
 * length of one browser session, and a returning clinician has nothing to unwrap — which does not
 * mean "log in again", it means every share ever sealed to those keys is unreadable forever. The
 * server cannot hold the blob for them in this build: reaching the relationship's blob channels
 * requires a session, and a session requires the credential this record is what remembers. So the
 * record is the only thing standing between a cleared cache and permanent key loss, which is why
 * the screen also offers it as text a person can keep somewhere of their own choosing.
 */
export interface KeyRecord {
  v: 1
  /** Opaque relationship reference, from the redeem. Identifies the relationship, nobody in it. */
  relRef: string
  /** The TOTP credential id this browser enrolled. Needed to sign in; secret-free by itself. */
  credentialId: string
  /** Argon2id-wrapped X25519 + Ed25519 secret keys. Opens only under the reading passphrase. */
  wrapped: WrappedKeyBlob
  createdAt: number
  /**
   * The relationship's inbox token, remembered after the first sign-in that supplied one.
   *
   * OPTIONAL, and it is the one value the acceptance ceremony cannot produce. The token is a second
   * factor on the relationship channels and its digest IS the relRef, so the server cannot hand it
   * back without giving away the thing it authenticates. It arrives with the invitation, out of
   * band, like the invitation secret itself.
   *
   * Kept here so a clinician types it once rather than every visit. Absent on records written
   * before this existed and on any that have never been signed in with — both read as "ask for it",
   * which is the harmless direction.
   */
  inboxToken?: string
  /**
   * When this browser last saw the server confirm it holds THESE public keys for the relationship,
   * in epoch milliseconds. ABSENT means the ceremony never got that far.
   *
   * WHY A HALF-FINISHED RECORD HAD TO BECOME VISIBLE. The ceremony's last two steps are a sign-in
   * and a key registration, and between them sits a window that used to be terminal. The enrolment
   * has committed on the server — credential inserted, ticket spent, invite CONSUMED, none of it
   * reversible and none of it repeatable, because enrolment is insert-only per relationship and no
   * route un-enrols one. If the tab closed there, the clinician was enrolled with no public keys on
   * file: the owner's console could read nothing, so it could pin nothing, so it could seal nothing,
   * and a fresh invitation could not repair it because the second enrolment answers ALREADY_ENROLLED
   * forever. The only thing missing was one authenticated POST that nothing in the product could
   * ever be made to send again. `resumeAcceptance` is that POST, and this field is how the screen
   * knows which relationships still need it — an unfinished set-up is offered, a finished one is
   * not, and neither has to be guessed at from an error the person half-remembers.
   *
   * OPTIONAL RATHER THAN A FORMAT BUMP. A v1 record written before this field existed is still a
   * perfectly readable v1 record; its absence reads as "unfinished", which costs one extra sign-in
   * and one POST the server answers 409 to. That is the harmless direction. Treating an unknown
   * record as finished would be the other one: it would hide the very relationships this exists to
   * rescue.
   */
  registeredAt?: number
}

/** The slice of the Storage API this needs. Lets tests pass a plain object; Node has no DOM. */
export interface KeyRecordStorage {
  getItem(key: string): string | null
  setItem(key: string, value: string): void
  removeItem?(key: string): void
}

/** localStorage, or null where it does not exist or the browser refuses to hand it over. */
export function defaultKeyStorage(): KeyRecordStorage | null {
  try {
    return globalThis.localStorage ?? null
  } catch {
    // Access itself throws in some blocked or partitioned contexts, not merely returns undefined.
    return null
  }
}

export type AcceptStep = 'redeem' | 'record' | 'wrap' | 'enrol' | 'login' | 'register' | 'code' | 'name' | 'pairing'

/**
 * A refusal, carrying WHICH step refused.
 *
 * The step is not decoration. What a person should do next differs completely between them — a dead
 * link is asked for again, a failed enrolment is retried, a browser that will not store is a
 * different browser — and a single "acceptance failed" string would leave the screen guessing.
 */
export class AcceptError extends Error {
  constructor(
    message: string,
    readonly step: AcceptStep,
  ) {
    super(message)
  }
}

/**
 * Read the records this browser holds.
 *
 * Unreadable STORAGE and unreadable CONTENT both throw rather than returning an empty list, and the
 * reasoning is pinStore.ts's exactly: an empty list means "this browser holds no keys for anyone",
 * which is the answer that lets the ceremony proceed and overwrite. Falling back to it quietly, at
 * the one moment there is most reason to be suspicious, would turn a corrupt record into permission
 * to replace a clinician's keys.
 *
 * An ABSENT key is different and does return an empty list: a key that was never written and a key
 * somebody cleared are indistinguishable from here, and the browser will not say which happened.
 */
export function loadKeyRecords(storage: KeyRecordStorage | null = defaultKeyStorage()): KeyRecord[] {
  if (!storage) {
    throw new AcceptError(
      'This browser will not let the page keep your wrapped keys, so there would be nothing to unwrap the next time you signed in.',
      'record',
    )
  }
  const raw = storage.getItem(KEY_RECORD_STORAGE_KEY)
  if (raw === null) return []
  let parsed: unknown
  try {
    parsed = JSON.parse(raw)
  } catch {
    throw new AcceptError(
      'The keys this browser has stored are unreadable, so the page cannot tell whether it already holds keys for this relationship.',
      'record',
    )
  }
  if (!Array.isArray(parsed)) {
    throw new AcceptError('The keys this browser has stored are not in a shape this page can read.', 'record')
  }
  return parsed.filter((r): r is KeyRecord => {
    const rec = r as Partial<KeyRecord>
    return rec?.v === 1 && typeof rec.relRef === 'string' && typeof rec.credentialId === 'string' && !!rec.wrapped
  })
}

/** The record for one relationship, or null. What step 2 of the ceremony asks. */
export function findKeyRecord(relRef: string, storage: KeyRecordStorage | null = defaultKeyStorage()): KeyRecord | null {
  return loadKeyRecords(storage).find((r) => r.relRef === relRef) ?? null
}

/**
 * Add a record. INSERT-ONLY: a relationship that already has one is refused, never replaced.
 *
 * The same rule as the server's, for a sharper reason. On this side the thing being replaced is the
 * only copy of a secret key; there is no 409 to recover from and no re-issue. An overwrite here does
 * not fail — it succeeds, and every share the owner ever sealed to the old key becomes unreadable
 * with no error anywhere. So the caller is made to notice.
 */
export function saveKeyRecord(record: KeyRecord, storage: KeyRecordStorage | null = defaultKeyStorage()): void {
  if (!storage) throw new AcceptError('This browser will not let the page keep your wrapped keys.', 'record')
  const existing = loadKeyRecords(storage)
  if (existing.some((r) => r.relRef === record.relRef)) {
    throw new AcceptError('This browser already holds keys for that relationship.', 'record')
  }
  try {
    storage.setItem(KEY_RECORD_STORAGE_KEY, JSON.stringify([...existing, record]))
  } catch {
    // Quota, partitioned storage, private browsing. Fail closed, but not with a raw DOMException:
    // "The quota has been exceeded." tells a clinician mid-enrolment nothing about what to do.
    throw new AcceptError(
      'This browser refused to save your wrapped keys, so nothing was enrolled — there would have been no way back in.',
      'record',
    )
  }
}

/**
 * Remove one relationship's record.
 *
 * Exists for exactly one caller: the rollback when the server DEFINITELY refused the enrolment
 * after the record was written. See `beginAcceptance` for why the write comes first, why undoing it
 * is the lesser of the two available harms there, and — the correction that matters most in this
 * file — why "definitely" is doing all the work in that sentence. This function deletes the only
 * copy of a clinician's secret keys that will exist after the tab closes. It must never run on a
 * maybe.
 */
export function forgetKeyRecord(relRef: string, storage: KeyRecordStorage | null = defaultKeyStorage()): void {
  if (!storage) return
  const kept = loadKeyRecords(storage).filter((r) => r.relRef !== relRef)
  try {
    storage.setItem(KEY_RECORD_STORAGE_KEY, JSON.stringify(kept))
  } catch {
    // Best-effort by construction: this runs on a path that is already failing, and turning a
    // failed enrolment into a second, different error would bury the one the person needs.
  }
}

/**
 * Note that the server has confirmed it holds this record's public keys, so the screen stops
 * offering to finish a set-up that is finished.
 *
 * BEST-EFFORT, AND DELIBERATELY SO. It runs after the registration the ceremony was for has already
 * been answered. A browser that refuses the write at this point loses a flag, and losing the flag
 * costs one redundant sign-in later; throwing here would turn a completed acceptance into a red
 * message about a relationship that is, in fact, completely set up. Same reasoning as the audit
 * appends on the server, which are also written after the response they describe.
 *
 * `already-registered` counts as confirmation and that is not a slip. What this field records is
 * "the server holds keys for this relationship and this browser has been told so" — which is
 * exactly what a 409 says. Whether they are THIS record's keys is a different question, and the
 * only thing in the system that can answer it is the fingerprint the clinician reads aloud.
 */
export function markKeysRegistered(
  relRef: string,
  at: number,
  storage: KeyRecordStorage | null = defaultKeyStorage(),
): void {
  if (!storage) return
  try {
    const updated = loadKeyRecords(storage).map((r) => (r.relRef === relRef ? { ...r, registeredAt: at } : r))
    storage.setItem(KEY_RECORD_STORAGE_KEY, JSON.stringify(updated))
  } catch {
    // Including the throw from loadKeyRecords when the store is unreadable. Nothing downstream of
    // the ceremony depends on this flag; it only decides whether a screen offers a second chance.
  }
}

/**
 * The relationships this browser holds keys for whose public keys it has never seen registered.
 *
 * These are the rescuable ones. Each is a clinician who is enrolled — or may be; see
 * `beginAcceptance` on the unknown enrolment — and whose owner is looking at a console that says
 * "nothing published yet" and will go on saying it forever unless one authenticated POST is sent.
 * The screen offers exactly this list, and `resumeAcceptance` sends it.
 */
export function unfinishedKeyRecords(storage: KeyRecordStorage | null = defaultKeyStorage()): KeyRecord[] {
  return loadKeyRecords(storage).filter((r) => typeof r.registeredAt !== 'number')
}

/* ── The authenticator secret ────────────────────────────────────────────────────────────── */

/** RFC 4648 base32, which is the alphabet every authenticator app reads an `otpauth:` secret in. */
const BASE32_ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567'

/**
 * Base32-encode, unpadded.
 *
 * WHY A SECOND ENCODING OF THE SAME BYTES. The server takes the TOTP secret as base64url (see
 * `decodeSecret` in TherapistAuthRoutes.kt) and authenticator apps take it as base32 — RFC 4648
 * §6 in the `otpauth:` URI that every one of them scans or accepts pasted. These are two spellings
 * of ONE random value: the bytes are generated once, sent to the server in one encoding and shown to
 * the person in the other. Nothing re-derives, and nothing round-trips through a string.
 *
 * Unpadded because `=` in a URI query has to be escaped and several popular authenticators simply
 * reject it; the standard permits omitting it.
 */
export function base32(bytes: Uint8Array): string {
  let bits = 0
  let value = 0
  let out = ''
  for (const byte of bytes) {
    value = (value << 8) | byte
    bits += 8
    while (bits >= 5) {
      out += BASE32_ALPHABET[(value >>> (bits - 5)) & 31]
      bits -= 5
    }
  }
  if (bits > 0) out += BASE32_ALPHABET[(value << (5 - bits)) & 31]
  return out
}

/**
 * The `otpauth:` URI an authenticator app scans or takes pasted.
 *
 * WHAT GOES IN THE LABEL, AND WHAT DELIBERATELY DOES NOT. The label is what shows in the clinician's
 * authenticator app forever, and several popular authenticators back their entries up to a cloud
 * account. So it names the deployment host and a short prefix of the opaque relationship reference,
 * and nothing else. It does not and must not carry a client's name, initials, a case number or
 * anything else about the person receiving care: that would put an identifiable care relationship
 * into a third-party backup, which is the one place this product's threat model has no reach at all.
 * The host is a fact the clinician's mailbox already holds; the relRef prefix is opaque.
 *
 * The relRef is truncated here, and that is safe for the same reason it would be unsafe in
 * signIn.ts: this is a NAME, chosen so two relationships on one server do not look identical in a
 * list. Nothing compares it to anything, and no decision anywhere rests on it.
 *
 * The parameters are stated rather than left to defaults because the defaults are not universal:
 * the server implements SHA1 / 6 digits / 30s (Totp.kt), and an app that assumed SHA256 would show
 * six digits that are wrong for reasons neither party could see.
 */
export function otpauthUri(secretBase32: string, host: string, relRef: string): string {
  const account = `${host || 'daymark'} (${relRef.slice(0, 8)})`
  const params = new URLSearchParams({
    secret: secretBase32,
    issuer: 'Daymark',
    algorithm: 'SHA1',
    digits: '6',
    period: '30',
  })
  return `otpauth://totp/${encodeURIComponent('Daymark')}:${encodeURIComponent(account)}?${params.toString()}`
}

/* ── The passphrase ──────────────────────────────────────────────────────────────────────── */

/**
 * The shortest reading passphrase this flow will wrap keys under.
 *
 * A floor, not a score. There is no meter here, no rating, no colour and no adjective: a strength
 * score is a guess dressed as a measurement, it rewards the character-class theatre that produces
 * `Passw0rd!`, and this product does not put grades on things people do. Twelve characters is stated
 * as the requirement, the screen says a few words are the easiest way to reach it, and that is all
 * the software has any business claiming.
 */
export const MIN_PASSPHRASE_CHARS = 12

/**
 * What is wrong with this passphrase, in sentences, or an empty list.
 *
 * The confirmation field is not a formality here. This passphrase is not recoverable — no reset, no
 * hint, no route back through the server, which holds nothing that could open the blob. A typo in
 * the only entry of it would be discovered by a clinician the first time they came back, at which
 * point their keys would be gone. So it is typed twice before anything is wrapped under it.
 */
export function checkPassphrase(passphrase: string, confirmation: string): string[] {
  const problems: string[] = []
  if (passphrase.trim() === '') {
    problems.push('Choose a reading passphrase.')
  } else if (passphrase.length < MIN_PASSPHRASE_CHARS) {
    problems.push(`Use at least ${MIN_PASSPHRASE_CHARS} characters. A few unrelated words is the easiest way to get there.`)
  }
  if (confirmation !== passphrase) {
    problems.push('The two entries are not the same. Nothing can recover this passphrase later, so it is typed twice now.')
  }
  return problems
}

/* ── The fingerprint a clinician reads out loud ──────────────────────────────────────────── */

/**
 * A value in reading groups, for the two things on this screen that leave it through a human: the
 * fingerprint that gets read aloud, and the authenticator key that gets copied into another app by
 * eye. LOSSLESS by construction — `groupForReading(v).join('') === v` — so what is spoken or typed
 * is the whole value and never a summary of it. Grouping only: the owner's console shows the same
 * fingerprint ungrouped, and the characters are identical either way.
 */
export function groupForReading(value: string, size = 4): string[] {
  if (!Number.isInteger(size) || size < 1) throw new RangeError('group size must be a positive integer')
  const out: string[] = []
  for (let i = 0; i < value.length; i += size) out.push(value.slice(i, i + size))
  return out
}

/**
 * The fixed copy for the key-checking step, held here rather than in the markup so the one sentence
 * that carries the security argument can be asserted by a test.
 *
 * WHY THE WORDING IS THE CONTROL. Comparing a fingerprint on screen against a fingerprint on
 * another screen catches a typo and nothing else. Both screens are drawn by the same server, so a
 * server that substituted a key would simply draw its substitute in both places and the comparison
 * would agree, enthusiastically, about the attacker's key. The check only means something when the
 * value crosses a channel the server does not control — a voice, a phone call, a room. That is why
 * the copy says READ IT TO THEM and never "check it matches on screen", and why this is the one
 * moment in the flow that asks a clinician to do something away from the computer.
 *
 * WHY IT NAMES TWO FINGERPRINTS AND NOT ONE, WHICH IS THE CORRECTION THIS COPY EXISTS AFTER. This
 * screen used to show the signing fingerprint alone, and the encryption key — the one the owner's
 * shares are actually sealed to — was computed nowhere on the clinician's side of the exchange at
 * all. The owner's console, correctly, refuses to pin unless it has heard BOTH (see
 * owner/therapistKeys.ts `confirmationMatches` for why one is not enough: the two keys arrive in
 * one JSON body from one machine, so checking either alone leaves the other free to be swapped).
 * Put those two facts together and the ceremony was unfinishable in the good case and a tautology
 * in the bad one: the only place on earth an owner could find an encryption fingerprint to type was
 * the console's own screen, drawn from the very record a substituting server had just supplied, so
 * typing it back would have been the server confirming itself. A check nobody can complete honestly
 * is not a check that fails safe; it is a check people route around. Both values are read aloud
 * here because both are pinned there.
 */
export const KEY_CHECK_COPY = {
  title: 'Your key fingerprints',
  lede:
    'These are the fingerprints of the two keys this browser just made for you — the encryption ' +
    'key, which is what they seal everything they send you to, and the signing key, which is what ' +
    'proves an assignment or a game plan came from you. The person who invited you has a copy of ' +
    'what the server says your keys are, and they will ask you to read both of these out — on the ' +
    'phone, or in the room with them.',
  why:
    'You do not need to read these out. The short code you typed is what proved these keys are ' +
    'yours: it never reached the server, so only the person who gave it to you could open what ' +
    'this browser sealed with it, and their console recorded these two the moment they did. They ' +
    'are here so either of you can compare them with what their screen shows, if you ever want to.',
  both:
    'Both of them, not just the interesting one. They travelled together, sealed under the same ' +
    'code, and their console recorded the pair — an encryption key without its signing key is not ' +
    'a therapist, it is half a record.',
  mismatch:
    'If what they have does not match what you read, stop and say so. Do not send anything, and do ' +
    'not accept a new invitation until you have worked out why, on a channel that is not this ' +
    'server.',
} as const

/* ── The ports ───────────────────────────────────────────────────────────────────────────── */

/**
 * Everything the ceremony reaches outside itself for: four network calls, the crypto, the clock and
 * the browser's storage.
 *
 * WHY PORTS RATHER THAN IMPORTS. The property this module has to prove is an ORDERING — that public
 * keys never reach the server before enrolment has succeeded — and an ordering can only be observed
 * by something that watches the calls happen. A test that stubs `fetch` proves the ordering of HTTP
 * requests; a test that stubs these proves the ordering of the ceremony, which is the thing that
 * matters and the thing a refactor could break while leaving every request shape intact.
 *
 * The crypto is a port for a duller reason: Argon2id at the 256 MiB floor takes about a second per
 * derivation, and the ceremony does two. The round-trip proof is exercised for real in one test and
 * stubbed in the ones that are about something else.
 */
export interface AcceptancePorts {
  /**
   * Three-valued, and it is the most important type in this interface. See [EnrolOutcome]: this
   * call commits something no route in the product can undo, so "I did not hear a yes" and "I heard
   * a no" have to reach `beginAcceptance` as different words.
   */
  enrol(enrollTicket: string, credentialId: string, totpSecretB64: string): Promise<EnrolOutcome>
  login(credentialId: string, code: string): Promise<LoginResult>
  register(session: SessionInfo, boxPubB64: string, signPubB64: string): Promise<KeyRegistration>
  logout(csrf: string): Promise<void>
  newKeys(): TherapistKeys
  wrapKeys(keys: TherapistKeys, passphrase: string): Promise<WrappedKeyBlob>
  unwrapKeys(blob: WrappedKeyBlob, passphrase: string): Promise<TherapistKeys>
  /** Random bytes, in both spellings the ceremony needs them in. */
  randomToken(bytes: number): { raw: Uint8Array; b64url: string }
  toBase64(bytes: Uint8Array): string
  storage: KeyRecordStorage | null
  now(): number
}

/** The real ports, over a live `PortalClient`. Awaits the sodium init the key generation needs. */
export async function portsFor(
  client: PortalClient,
  storage: KeyRecordStorage | null = defaultKeyStorage(),
): Promise<AcceptancePorts> {
  const so = await initAssignmentCrypto()
  return {
    enrol: (ticket, credentialId, secret) => client.enrollTotp(ticket, credentialId, secret),
    login: (credentialId, code) => client.loginTotp(credentialId, code),
    register: (session, boxPubB64, signPubB64) => client.registerTherapistKeys(session, boxPubB64, signPubB64),
    logout: (csrf) => client.logout(csrf),
    newKeys: () => ({ box: newBoxKeyPair(), sign: newSignKeyPair() }),
    wrapKeys: (keys, passphrase) => wrap(keys, passphrase),
    unwrapKeys: (blob, passphrase) => unwrap(blob, passphrase),
    randomToken: (bytes) => {
      const raw = so.randombytes_buf(bytes)
      return { raw, b64url: so.to_base64(raw, URLSAFE()) }
    },
    toBase64: (bytes) => so.to_base64(bytes, URLSAFE()),
    storage,
    now: () => Date.now(),
  }
}

/* ── The ceremony ────────────────────────────────────────────────────────────────────────── */

/**
 * The keypair this browser holds for a relationship, in every spelling the ceremony needs it in.
 *
 * BOTH FINGERPRINTS, ALWAYS, AND NEVER ONE. They are computed here rather than at the screen so
 * that the value a clinician reads out and the value the owner's console pins are demonstrably the
 * same function of the same bytes, and so that a test can hold that claim. The pairing between the
 * two sides is asserted in owner/keyCeremony.test.ts, which is the test that would have caught the
 * version of this interface that carried only the signing fingerprint — the owner's gate demands
 * both, and for as long as the clinician's side computed one, the second field on the owner's
 * screen had no honest source anywhere in the product.
 */
export interface KeyIdentity {
  /** In memory for the length of the ceremony; the screen zeroizes these when it goes away. */
  keys: TherapistKeys
  boxPubB64: string
  signPubB64: string
  /** BLAKE2b-16 of the X25519 public key — the key the owner's shares are sealed TO. */
  boxFingerprint: string
  /** BLAKE2b-16 of the Ed25519 public key — the key their assignments are checked AGAINST. */
  signFingerprint: string
}

/** Both fingerprints of a live keypair, in the order the two screens list them. */
function identityOf(ports: AcceptancePorts, keys: TherapistKeys): KeyIdentity {
  return {
    keys,
    boxPubB64: ports.toBase64(keys.box.publicKey),
    signPubB64: ports.toBase64(keys.sign.publicKey),
    boxFingerprint: fingerprint(keys.box.publicKey),
    signFingerprint: fingerprint(keys.sign.publicKey),
  }
}

/** What the first half of the ceremony produced. Held in memory by the screen, never persisted. */
export interface Enrolment extends KeyIdentity {
  relRef: string
  scope: string[]
  credentialId: string
  /** The authenticator secret, in the spelling an authenticator app takes. Shown once, never kept. */
  totpSecretBase32: string
  otpauthUri: string
  /**
   * Whether the server SAID it enrolled this credential, as opposed to this page not knowing.
   *
   * False is not a failure and is not a success; it is the honest state after an enrolment whose
   * answer never arrived. The screen has to say so — the authenticator secret below may or may not
   * be the secret of a credential that exists — and the code the clinician types next is what
   * settles it, because a sign-in cannot succeed against a credential that was never inserted.
   * The ceremony's ordering guarantee survives that uncertainty untouched for exactly that reason:
   * public keys are registered with a session, a session comes only from a successful sign-in, and
   * a successful sign-in is proof the enrolment committed after all.
   */
  serverConfirmedEnrolment: boolean
}

export interface AcceptInput {
  inviteId: string
  secret: string
  passphrase: string
  /** Deployment host, for the authenticator entry's label only. */
  host?: string
}

/** How long the authenticator secret is. 20 bytes is the RFC 6238 recommendation for HMAC-SHA1. */
const TOTP_SECRET_BYTES = 20

/**
 * How long the credential id is. Opaque, unguessable, and never displayed as anything meaningful.
 * Exported because therapist/pairingAccept.ts mints one at the same width, and two independent
 * sixteens are a number waiting to disagree.
 */
export const CREDENTIAL_ID_BYTES = 16

export function sameKeys(a: TherapistKeys, b: TherapistKeys): boolean {
  const eq = (x: Uint8Array, y: Uint8Array) => x.length === y.length && x.every((v, i) => v === y[i])
  // Not constant-time, and it does not need to be: both sides of this comparison are values this
  // page generated moments ago and already holds in full. There is no secret here to leak to a
  // clock that the caller does not already have in a variable.
  return eq(a.box.privateKey, b.box.privateKey) && eq(a.sign.privateKey, b.sign.privateKey)
}

/*
 * `beginAcceptance` USED TO LIVE HERE, and its removal is the cut-over (plan §3.7, 2026-09-04).
 *
 * It began by redeeming the invitation secret — which the emailed link carries — so the ceremony
 * it opened was reachable by whoever read that email, and the short pairing code in the design
 * document secured nothing. The route it called is gone from the server; this is the client half
 * of the same removal.
 *
 * What replaced it is therapist/pairingAccept.ts: the same generate, wrap, prove and store steps,
 * in the same order and with the same refusals — including the insert-only rule, which now asks
 * its question at the first moment the relationship is known rather than after a redeem — but
 * reached only once the therapist has typed the code the owner spoke, and finishing only once the
 * owner has approved what that code sealed.
 *
 * Everything from a TICKET onwards is unchanged and still lives below, shared by both ceremonies:
 * they differ in how a ticket comes to exist and in nothing after it.
 */

/**
 * Spend an enrolment ticket: mint the authenticator secret, enrol it, and hand back everything the
 * screen needs to show the person their authenticator entry.
 *
 * EXTRACTED SO THAT TWO CEREMONIES CAN SHARE IT. The invite-secret path above reaches this after a
 * redeem; the pairing path (therapist/pairingAccept.ts) reaches it after the owner has approved a
 * matched code and the ticket has come back through the encrypted channel. Everything from here on
 * is identical in both — the same insert-only enrolment, the same three-valued outcome, the same
 * rollback rule — and it is identical because the server cannot tell the two apart by this point:
 * a ticket is a ticket.
 *
 * WHAT IT ASSUMES OF ITS CALLER, and cannot check: that the KeyRecord for [relRef] has already been
 * written, and that [keys] are the keys inside it. Both callers write the record immediately before
 * calling, for the reason argued above `beginAcceptance` — the losable window is the recoverable
 * one. The rollback here removes that record, so a caller that had not written one would be
 * deleting somebody else's; that is why this is not exported for general use but for those two.
 *
 * THE THREE-VALUED OUTCOME IS THE POINT. 'refused' is the only value that rolls back, because it is
 * the only one that says the server wrote nothing. 'unknown' — a request whose answer never arrived
 * — keeps the record and the secret and says so, because the enrolment may well have committed and
 * no route in this product un-enrols a relationship.
 */
export async function enrolWithTicket(
  ports: AcceptancePorts,
  args: {
    relRef: string
    scope: string[]
    enrollTicket: string
    credentialId: string
    keys: TherapistKeys
    host?: string
  },
): Promise<Enrolment> {
  const totp = ports.randomToken(TOTP_SECRET_BYTES)
  let outcome: EnrolOutcome
  try {
    outcome = await ports.enrol(args.enrollTicket, args.credentialId, totp.b64url)
  } catch {
    // A rejected fetch says nothing about the server's state. A request that was answered and whose
    // answer was lost on the way back looks exactly like a request that never arrived, and only one
    // of those is safe to undo — so neither is undone.
    outcome = 'unknown'
  }
  if (outcome === 'refused') {
    forgetKeyRecord(args.relRef, ports.storage)
    throw new AcceptError(
      'The server would not enrol an authenticator for this invitation, so nothing was set up. Ask the person who invited you for a fresh link.',
      'enrol',
    )
  }

  const totpSecretBase32 = base32(totp.raw)
  return {
    relRef: args.relRef,
    scope: args.scope,
    credentialId: args.credentialId,
    totpSecretBase32,
    otpauthUri: otpauthUri(totpSecretBase32, args.host ?? '', args.relRef),
    serverConfirmedEnrolment: outcome === 'enrolled',
    ...identityOf(ports, args.keys),
  }
}

export interface Acceptance {
  session: SessionInfo
  registration: KeyRegistration
}

/**
 * Steps 6 and 7: the first code from the authenticator, then the public keys.
 *
 * THE ORDERING GUARANTEE IS STRUCTURAL, NOT A CONVENTION, AND IT DOES NOT REST ON THE TYPE ALONE.
 * It used to be stated as "this takes an `Enrolment`, which only exists after the server confirmed
 * the enrolment" — and that sentence stopped being true the day `beginAcceptance` learned to return
 * an enrolment whose answer never arrived. The guarantee survives intact because it never actually
 * depended on that: registering keys needs a session, a session comes only from `POST
 * /v1/totp/verify` accepting a code, and no code is accepted for a credential the server did not
 * insert. So a public key cannot reach the server ahead of a real enrolment even when this client
 * has no idea whether the enrolment happened — the server decides, and it decides in the right
 * order. The type still stops a caller assembling the arguments by hand; it is the second lock.
 *
 * WHY IT LOGS OUT AT THE END. The session this created exists to carry one authenticated write. The
 * ceremony does not open the portal — it cannot, because it has none of the owner's keys yet — so
 * leaving a live session behind would leave an authenticated cookie sitting on what is quite often a
 * shared clinic machine, in a tab whose work is finished. Best-effort: a logout that fails must not
 * turn a completed acceptance into an error.
 */
export async function completeAcceptance(
  ports: AcceptancePorts,
  enrolment: Enrolment,
  code: string,
): Promise<Acceptance> {
  return signInAndRegister(ports, enrolment.relRef, enrolment.credentialId, enrolment, code)
}

/** What a rescued ceremony hands back: the keypair, in both spellings, and the two answers. */
export interface Resumption extends KeyIdentity {
  acceptance: Acceptance
}

/**
 * What the acceptance page can still do for a relationship whose ceremony was cut in half.
 *
 * THE WINDOW THIS CLOSES, AND WHY NOTHING ELSE COULD CLOSE IT. Between the sign-in and the key
 * registration the server has already committed everything irreversible: the credential is
 * inserted, the enrolment ticket is spent, the invitation is CONSUMED. A tab closed there — a
 * laptop asleep, a proxy reset, a phone switching networks — left the clinician enrolled with no
 * public keys on file, and every route out was shut. Reloading re-redeems a consumed invite and
 * gets 410. A fresh invitation redeems perfectly and then dies at the enrolment, because enrolment
 * is insert-only per relationship (AuthStore.enrollTotp: `SELECT 1 FROM totp WHERE credential_id=?
 * OR rel_ref=?`) and nothing in the server deletes a totp row. Meanwhile the owner's console reads
 * 404 from the key route for the life of the relationship, so it can pin nothing and seal nothing.
 * The relationship was over, and the only thing missing was one POST that this browser was already
 * holding every input for.
 *
 * WHAT IT ASKS FOR AND WHY THAT IS THE RIGHT PRICE. The reading passphrase, because the public keys
 * have to come out of the wrapped blob — this browser stores no public copy, and inventing one
 * would mean trusting a value that had never been proven to belong to the secret key. And a code
 * from the authenticator, because registration needs a session and a session is what a code buys.
 * Two things the clinician already has; nothing typed that only the server could supply.
 *
 * IT IS NOT A SECOND ACCEPTANCE AND CANNOT BECOME ONE. No invite is redeemed, no keypair is
 * generated, no record is written and none is replaced — an unfinished record and a finished one
 * are equally untouchable here. The only writes it performs are the server's own key registration
 * and the local note that the registration was answered.
 *
 * A 409 IS A NORMAL ANSWER ON THIS PATH, and the caller has to say so honestly rather than reading
 * it as the alarm it is during a first acceptance. It means the server holds keys for the
 * relationship — very often the ones this very browser registered a moment before the tab died,
 * which is the whole scenario. It could also mean another device's, or somebody else's, and nothing
 * on this side can tell those apart. Only the fingerprints, read aloud, can.
 */
export async function resumeAcceptance(
  ports: AcceptancePorts,
  record: KeyRecord,
  passphrase: string,
  code: string,
): Promise<Resumption> {
  let keys: TherapistKeys
  try {
    keys = await ports.unwrapKeys(record.wrapped, passphrase)
  } catch {
    throw new AcceptError(
      'That reading passphrase does not open the keys this browser is holding for that relationship. Nothing was sent and nothing was changed.',
      'wrap',
    )
  }
  const identity = identityOf(ports, keys)
  try {
    const acceptance = await signInAndRegister(ports, record.relRef, record.credentialId, identity, code)
    return { ...identity, acceptance }
  } catch (e) {
    // The keys came out of the blob for this call and go no further than it. The caller never
    // received them, so nothing else can be holding the reference, and leaving a clinician's
    // signing key in the heap after a refused code would be a copy nobody asked for.
    zeroize(keys)
    throw e
  }
}

/** Steps 6 and 7 themselves, shared by the first acceptance and by the rescue above. */
async function signInAndRegister(
  ports: AcceptancePorts,
  relRef: string,
  credentialId: string,
  keys: Pick<KeyIdentity, 'boxPubB64' | 'signPubB64'>,
  code: string,
): Promise<Acceptance> {
  const login = await ports.login(credentialId, code.trim())
  if (!login.ok || !login.session) {
    throw new AcceptError(login.error ?? 'That code was not accepted.', 'login')
  }
  // `POST /v1/totp/verify` does not echo the relRef, so the session it returns carries an empty one.
  // It is bound here from the redeem — the server derives its own from the cookie and answers 403
  // if the path disagrees, so this is a value the client has to get right rather than one it gets
  // to choose.
  const session: SessionInfo = { ...login.session, relRef }

  let registration: KeyRegistration
  try {
    registration = await ports.register(session, keys.boxPubB64, keys.signPubB64)
  } catch (e) {
    /*
     * THE MESSAGE CARRIES THE WAY BACK, because this is the exact step whose failure used to be the
     * end of the relationship. It said "key registration failed" — the PortalError's own words,
     * accurate and useless — and a clinician reading that had no reason to think anything could be
     * done about it, when in fact everything needed to try again was sitting in the browser they
     * were reading it in. The underlying reason is kept, in brackets, because an operator reading
     * over somebody's shoulder wants the status; the sentence in front of it is for the person.
     */
    const because = e instanceof Error && e.message ? ` (${e.message})` : ''
    throw new AcceptError(
      `Your public keys could not be registered${because}. Nothing has been lost: this browser is holding your keys, and this page can send them whenever you come back to it — you will need your reading passphrase and a code from your authenticator.`,
      'register',
    )
  }

  // Written BEFORE the logout, and before the caller gets its answer, because this is the only
  // moment the fact is known and the next line is another network call that can hang. It is the
  // difference between a screen that offers to finish a set-up that is finished and one that does
  // not; nothing about the relationship's safety turns on it, which is why it never throws.
  markKeysRegistered(relRef, ports.now(), ports.storage)

  await ports.logout(session.csrf).catch(() => {})
  return { session, registration }
}
