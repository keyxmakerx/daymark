/*
 * THE RECOVERY CODE — the second secret that opens a person's own data key, built to be written on
 * paper by a frightened person and read back, correctly, months later by the same person.
 *
 * WHY THIS EXISTS AT ALL. docs/COMPANION_SECURITY.md §4 states the current position without
 * softening it: "No escrow, anywhere (O6). Lose the passphrase → snapshots unrecoverable." That is
 * an honest posture and it is also a cliff, and
 * docs/COMPANION_ACCESS_CONTROL.md § Key recovery is the decision to keep the first property while
 * removing the second: "We keep no-escrow and add user-held recovery... The server never holds a
 * recovery secret. The user can recover; the server still can't read." This file is the user-held
 * half of that sentence. The wrapping half is dataKey.ts.
 *
 * WHAT THIS FILE IS NOT. It is not a password reset, and nothing in it can be initiated by the
 * server, an org admin, a clinician, or support. A recovery code is generated on the user's device,
 * shown to them once, and never transmitted anywhere. If they lose both the passphrase and the
 * code, the data is still gone — that is not a defect to be engineered away later, it is the same
 * no-escrow property the product's central claim rests on, and the copy around this feature must
 * never imply otherwise.
 *
 * WHY A CODE AND NOT A PHRASE. share/wordlist.ts already contains 256 curated words, and a
 * BIP39-shaped phrase would have been the obvious reuse. It was rejected for one reason: that
 * wordlist is the DISPLAY vocabulary for the out-of-band SAS, and a word appearing in two places
 * with two completely different trust meanings — "compare this aloud with your therapist" versus
 * "never say this to anyone, ever" — is precisely the confusion that gets a secret read down a
 * phone line. A code that looks nothing like the pairing phrase cannot be mistaken for one.
 *
 * ─── THE ALPHABET ───────────────────────────────────────────────────────────────────────────────
 *
 * Thirty-one symbols: the digits 2-9 and the letters A-Z with I, L and O removed.
 *
 * WHY THOSE THREE LETTERS AND THOSE TWO DIGITS. The two classic handwriting collisions are 0/O and
 * 1/l/I, and the usual fix (Crockford's base32) keeps 0 and 1 in the printed alphabet and quietly
 * folds O→0 and I/L→1 when reading input back. We take the stricter branch and drop BOTH members
 * of each pair. It costs one symbol of alphabet size and it buys a diagnosis: because 0, O, 1, I
 * and L can never legitimately appear in a code, typing one is not an ambiguity to be resolved by
 * guessing — it is a definite error at a definite position, and parseRecoveryCode() says so in
 * those words instead of failing later as "wrong code".
 *
 * WHY 31 AND NOT A ROUND 32. Dropping only I and O from the letters would have left exactly 32
 * symbols, five clean bits each, and it is what most systems do. We deliberately did not, because
 * 31 is PRIME and 32 is not, and the primality is what makes the check symbol below provably catch
 * every single-symbol error rather than merely most of them (the proof is with checkValue()). We
 * do not need a bit-aligned alphabet: nothing here encodes bytes into symbols. The code is
 * generated AS symbols, drawn uniformly, so the awkward-looking 31 costs us nothing at all and
 * upgrades the error check from "usually" to "always".
 *
 * WHAT THE ALPHABET STILL DOES NOT FIX, STATED PLAINLY. S/5, Z/2, B/8 and G/6 remain in it and
 * remain confusable in bad handwriting. Shrinking the alphabet further would lengthen the code for
 * the same entropy, which trades one transcription risk for more transcription. The check symbol is
 * the answer to those pairs instead: a single misread character — of ANY kind, including all four
 * of those — is caught with certainty. That is a better deal than a smaller alphabet.
 *
 * WHY U IS KEPT. Crockford drops U so that a random string cannot spell an unfortunate word. We
 * keep it because dropping it would give 30 symbols, 30 is not prime, and the check symbol would
 * stop being exact. A thirty-symbol random string is not read as words, and the mild embarrassment
 * risk is worth strictly less than a provable error check on a key that cannot be re-issued.
 *
 * ─── ENTROPY ────────────────────────────────────────────────────────────────────────────────────
 *
 * 29 payload symbols x log2(31) = 29 x 4.9542 = 143.67 bits. Stated as a constant below and pinned
 * by a test, because a number in a comment drifts and a number in an assertion does not.
 *
 * WHY IT HAS TO BE THAT LARGE. This code opens exactly what the passphrase opens — the same data
 * key, the same years of somebody's journal — so it is the weaker of the two secrets that decides
 * the security of both. Three facts set the floor:
 *
 *   1. The wrapped blob is designed to sit on the server (that is the whole point: either secret
 *      opens it, so both wraps can be stored). An attacker who steals the disk therefore attacks
 *      OFFLINE, with no rate limit, no lockout and nobody to notice.
 *   2. Argon2id at the 256 MiB / 3-pass floor multiplies each guess by a constant. A constant is
 *      not a defence against a search space; it only buys a fixed number of bits (roughly 20-ish
 *      against commodity hardware). It is what makes a memorised passphrase survivable; it cannot
 *      make a short code survivable.
 *   3. So the code must be strong on entropy alone, with Argon2id as defence in depth rather than
 *      the thing standing between the attacker and the key. 128 bits is the conventional "never
 *      brute-forceable, no asterisks" line; 143.67 clears it with room to spare.
 *
 * WHY NOT MORE. Every additional symbol is another character a person has to copy by hand, under
 * stress, and later read back. Transcription errors scale with length while security gains scale
 * with the logarithm, so past the point where brute force is already impossible, more symbols make
 * the code strictly worse. Thirty symbols in six groups of five is about the length of a software
 * licence key — a shape people have demonstrably managed to transcribe for decades.
 *
 * A four-digit PIN, for the avoidance of doubt, would be 13.3 bits and would be opened in
 * microseconds by anyone holding the blob. There is no rate limiter in this design to save it,
 * because the attacker never has to ask our server anything.
 *
 * ─── WHAT THIS MODULE DOES NOT DO ───────────────────────────────────────────────────────────────
 *
 * NO CORRECTION, ONLY DETECTION. The syndrome of a mod-31 weighted checksum can be solved for a
 * correction at every position at once — each position yields some plausible delta — so "did you
 * mean" would be a guess dressed as help. A guess that happens to produce a valid-looking code
 * derives a DIFFERENT key and surfaces as an opaque decrypt failure, which is exactly the outcome
 * the checksum exists to prevent. Detect, name the position, let the person look again.
 *
 * NO LOGGING, NO ECHO. Nothing here writes to a console, and no fault string ever contains any part
 * of what was typed — the same rule inviteLink.ts states for the invite secret, and for the same
 * reason: a fault message is the thing most likely to be screenshotted into a support thread, and
 * half of what it would be quoting is a live credential. Positions are reported; characters are
 * not.
 *
 * NO DOM, NO CLOCK, NO NETWORK. Everything except newRecoveryCode() (which needs a CSPRNG) is a
 * pure function of a string, which is what makes every property above testable.
 */
import _sodium from 'libsodium-wrappers-sumo'
import { initCrypto } from '../sync/crypto'

/**
 * The 31 symbols, in the order that defines each symbol's numeric value (index 0..30).
 *
 * The order is the only thing that must never change once a single code has been written down: it
 * is the alphabet AND the check-symbol arithmetic. Reordering it silently invalidates every code
 * ever printed.
 */
export const RECOVERY_ALPHABET = '23456789ABCDEFGHJKMNPQRSTUVWXYZ'

/** 31 — prime, which is the property checkValue() depends on. */
export const ALPHABET_SIZE = RECOVERY_ALPHABET.length

/** Characters that can never appear in a code, so seeing one is a definite error, not a guess. */
const NEVER_IN_A_CODE = new Set(['0', 'O', '1', 'I', 'L'])

/** Symbols carrying entropy. */
export const PAYLOAD_SYMBOLS = 29
/** Symbols spent on the check value. One; see checkValue() for what one buys and what it does not. */
export const CHECK_SYMBOLS = 1
/** Total symbols in a canonical code. */
export const CODE_SYMBOLS = PAYLOAD_SYMBOLS + CHECK_SYMBOLS
/** Symbols per display group. Six groups of five, hyphen-separated. */
export const GROUP_SIZE = 5

/**
 * 143.67 bits. Computed rather than written so the constant cannot drift away from the alphabet and
 * length it describes; asserted against the 128-bit floor in recoveryCode.test.ts.
 */
export const RECOVERY_CODE_ENTROPY_BITS = PAYLOAD_SYMBOLS * Math.log2(ALPHABET_SIZE)

/** A generated code, in both the form the KDF eats and the form a person copies down. */
export interface RecoveryCode {
  /** 30 symbols, no separators, upper case. THIS is the KDF input — see dataKey.ts. */
  canonical: string
  /** The same 30 symbols as `XXXXX-XXXXX-XXXXX-XXXXX-XXXXX-XXXXX`, for showing and printing. */
  display: string
}

/** Every distinguishable way a typed code can fail to be a code. One value per diagnosis. */
export type RecoveryCodeFault =
  /** Nothing was typed, or only separators were. */
  | 'empty'
  /** A 0, O, 1, I or L — characters this alphabet excludes precisely so this is unambiguous. */
  | 'confusable'
  /** Some other character that is not a symbol: punctuation, a letter we do not use, a space run. */
  | 'notInAlphabet'
  | 'tooShort'
  | 'tooLong'
  /** Well-formed, right length, and the check symbol disagrees — a misread or mistyped character. */
  | 'checksum'

export type RecoveryCodeParse =
  | { ok: true; code: RecoveryCode }
  | { ok: false; fault: RecoveryCodeFault; at?: number }

/**
 * What to show a person for each fault.
 *
 * These are sentences, not codes, because the person reading them is mid-recovery and is not
 * having a good day. None of them echoes the input, none of them implies the data is gone, and none
 * of them congratulates anybody for anything. `at` (a 1-based symbol position) is carried on the
 * parse result rather than baked into these strings so a caller can point at the character without
 * this module having to compose the sentence.
 */
export const RECOVERY_FAULT_TEXT: Record<RecoveryCodeFault, string> = {
  empty: 'No recovery code was entered.',
  confusable: 'Recovery codes never contain the characters 0, O, 1, I or L. Check that character again.',
  notInAlphabet: 'That character is not one a recovery code uses.',
  tooShort: 'That is fewer characters than a recovery code has. Some may be missing.',
  tooLong: 'That is more characters than a recovery code has.',
  checksum: 'One character does not match the rest of the code. It is worth reading it back once more.',
}

/** Thrown by requireRecoveryCode(); carries the same diagnosis the parse result would have. */
export class RecoveryCodeError extends Error {
  constructor(
    readonly fault: RecoveryCodeFault,
    readonly at?: number,
  ) {
    super(RECOVERY_FAULT_TEXT[fault])
    this.name = 'RecoveryCodeError'
  }
}

/**
 * Strip everything a person might reasonably add and upper-case the rest.
 *
 * Spaces, tabs, newlines and hyphens go because we PUT the hyphens there — refusing to read back
 * the format we printed would be absurd. The two Unicode dashes are here because a code that has
 * been through a word processor, a chat client or a PDF comes back with en/em dashes in place of
 * the hyphens, and that is a formatting artefact rather than a mistake by the person.
 *
 * Nothing else is normalised. In particular O is NOT folded to 0 and I/L are NOT folded to 1 —
 * that is the Crockford behaviour this alphabet deliberately does not need, and doing it anyway
 * would turn a character we can diagnose exactly into a silent substitution.
 */
export function normalizeRecoveryInput(typed: string): string {
  return typed.replace(/[\s\-\u2010-\u2015]+/g, '').toUpperCase()
}

/**
 * The check symbol's numeric value: sum of (position x symbol value) mod 31.
 *
 * WHY THIS PARTICULAR CHECK, AND WHAT IT PROVABLY CATCHES. Positions are weighted 1..29, all
 * distinct and none of them zero mod 31. Because 31 is prime, the integers mod 31 form a field, so
 * a product of two non-zero values is never zero. Two consequences, and they are the whole reason
 * the alphabet is 31 symbols long:
 *
 *   - ANY single mistyped or misread symbol is caught, with certainty. Changing the symbol at
 *     position i shifts the sum by w_i x delta, with w_i non-zero and delta non-zero, so the sum
 *     always moves. Not "almost always" — always. This is the case the feature is for: one
 *     character read wrong off a piece of paper.
 *   - ANY transposition of two different payload symbols is caught, with certainty. Swapping
 *     positions i and j shifts the sum by (w_i - w_j)(d_j - d_i); the weights differ by at most 28
 *     so their difference is never zero mod 31, and the symbols differ by assumption.
 *
 * WHY THE PAYLOAD STOPS AT 29 SYMBOLS. The weights must stay distinct and non-zero mod 31, which
 * caps the payload at 30. We use 29, which leaves the property intact with a symbol of headroom.
 *
 * WHAT ONE CHECK SYMBOL DOES NOT BUY, SAID PLAINLY. Against input mangled in several places at
 * once — a whole group dropped, two characters swapped for two others — one symbol of check gives
 * a 1-in-31 chance of passing anyway, and then the failure surfaces one layer down as a decrypt
 * that does not work. That residual is accepted: a second check symbol would buy 1-in-961 against
 * the multi-error case while making every user copy another character, and the single-error case
 * (which is the realistic one, and which is already at certainty) would not improve at all.
 */
function checkValue(payload: string): number {
  let sum = 0
  for (let i = 0; i < payload.length; i++) {
    const value = RECOVERY_ALPHABET.indexOf(payload[i])
    // Callers reach here only through parseRecoveryCode (which validated every character first) or
    // through newRecoveryCode (which drew them from the alphabet). A symbol that is in neither
    // would index at -1 and quietly produce a plausible-looking wrong check value, so it stops
    // here instead: a check symbol computed over something that is not a payload is worse than no
    // check symbol at all.
    if (value < 0) throw new RecoveryCodeError('notInAlphabet', i + 1)
    sum = (sum + (i + 1) * value) % ALPHABET_SIZE
  }
  return sum
}

/** The check symbol for a 29-symbol payload. */
export function checkSymbol(payload: string): string {
  return RECOVERY_ALPHABET[checkValue(payload)]
}

/** 30 canonical symbols to `XXXXX-XXXXX-XXXXX-XXXXX-XXXXX-XXXXX`, which is the form a person sees. */
export function formatRecoveryCode(canonical: string): string {
  const groups: string[] = []
  for (let i = 0; i < canonical.length; i += GROUP_SIZE) groups.push(canonical.slice(i, i + GROUP_SIZE))
  return groups.join('-')
}

/**
 * Read a typed code, or say exactly why it is not one.
 *
 * ORDER OF DIAGNOSIS, AND WHY. Characters are checked before length, because a person who pasted a
 * sentence or an email signature is better served by "that character is not one a recovery code
 * uses" than by "that is more characters than a recovery code has" — the first tells them what
 * they pasted, the second tells them how much of it. Length is checked before the checksum because
 * a checksum over the wrong number of symbols is meaningless.
 */
export function parseRecoveryCode(typed: string): RecoveryCodeParse {
  const canonical = normalizeRecoveryInput(typed)
  if (canonical.length === 0) return { ok: false, fault: 'empty' }
  for (let i = 0; i < canonical.length; i++) {
    const ch = canonical[i]
    if (NEVER_IN_A_CODE.has(ch)) return { ok: false, fault: 'confusable', at: i + 1 }
    if (!RECOVERY_ALPHABET.includes(ch)) return { ok: false, fault: 'notInAlphabet', at: i + 1 }
  }
  if (canonical.length < CODE_SYMBOLS) return { ok: false, fault: 'tooShort' }
  if (canonical.length > CODE_SYMBOLS) return { ok: false, fault: 'tooLong' }
  const payload = canonical.slice(0, PAYLOAD_SYMBOLS)
  if (canonical.slice(PAYLOAD_SYMBOLS) !== checkSymbol(payload)) return { ok: false, fault: 'checksum' }
  return { ok: true, code: { canonical, display: formatRecoveryCode(canonical) } }
}

/**
 * parseRecoveryCode() for callers that would only have thrown on the failure branch anyway.
 *
 * dataKey.ts uses this so that a mistyped code is refused by the CHECKSUM, before a single byte of
 * Argon2id runs — the distinction the tests assert, and the distinction a person feels as the
 * difference between an instant "read that character again" and a three-second wait ending in
 * "wrong code".
 */
export function requireRecoveryCode(typed: string): RecoveryCode {
  const parsed = parseRecoveryCode(typed)
  if (!parsed.ok) throw new RecoveryCodeError(parsed.fault, parsed.at)
  return parsed.code
}

/**
 * A fresh code, drawn uniformly from the alphabet by the CSPRNG.
 *
 * WHY REJECTION SAMPLING RATHER THAN A MODULO. 256 is not a multiple of 31 (it is 8 x 31 + 8), so
 * `randomByte % 31` would hand out the first eight symbols nine times in 256 draws and the rest
 * eight — a small bias, but a bias in the ONE place where uniformity is the entire security
 * argument, and free to remove. Bytes at or above 248 are discarded and redrawn; every symbol then
 * has probability exactly 8/248.
 *
 * The draw is done in one buffer with a top-up loop rather than a byte at a time, because each
 * randombytes_buf call crosses into WASM.
 */
export async function newRecoveryCode(): Promise<RecoveryCode> {
  await initCrypto()
  const LIMIT = ALPHABET_SIZE * Math.floor(256 / ALPHABET_SIZE) // 248
  const symbols: string[] = []
  while (symbols.length < PAYLOAD_SYMBOLS) {
    const draw = _sodium.randombytes_buf(PAYLOAD_SYMBOLS)
    for (let i = 0; i < draw.length && symbols.length < PAYLOAD_SYMBOLS; i++) {
      if (draw[i] < LIMIT) symbols.push(RECOVERY_ALPHABET[draw[i] % ALPHABET_SIZE])
    }
    draw.fill(0)
  }
  const payload = symbols.join('')
  const canonical = payload + checkSymbol(payload)
  return { canonical, display: formatRecoveryCode(canonical) }
}
